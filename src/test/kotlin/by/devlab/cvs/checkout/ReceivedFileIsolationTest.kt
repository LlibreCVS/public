package by.devlab.cvs.checkout

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import com.intellij.openapi.progress.ProcessCanceledException
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.netbeans.lib.cvsclient.IClientEnvironment
import org.netbeans.lib.cvsclient.IConnectionStreams
import org.netbeans.lib.cvsclient.admin.Entry
import org.netbeans.lib.cvsclient.admin.IAdminReader
import org.netbeans.lib.cvsclient.admin.IAdminWriter
import org.netbeans.lib.cvsclient.connection.IConnection
import org.netbeans.lib.cvsclient.event.IEventSender
import org.netbeans.lib.cvsclient.file.AbstractFileObject
import org.netbeans.lib.cvsclient.file.CvsFileSystem
import org.netbeans.lib.cvsclient.file.DirectoryObject
import org.netbeans.lib.cvsclient.file.FileObject
import org.netbeans.lib.cvsclient.file.ICvsFileSystem
import org.netbeans.lib.cvsclient.file.IFileReadOnlyHandler
import org.netbeans.lib.cvsclient.file.IFileSystem
import org.netbeans.lib.cvsclient.file.ILocalFileReader
import org.netbeans.lib.cvsclient.file.ILocalFileWriter
import org.netbeans.lib.cvsclient.file.IReaderFactory
import org.netbeans.lib.cvsclient.file.IWriterFactory
import org.netbeans.lib.cvsclient.response.DefaultResponseHandler
import org.netbeans.lib.cvsclient.response.IResponseServices
import org.netbeans.lib.cvsclient.util.IIgnoreFileFilter
import org.netbeans.lib.cvsclient.CvsRoot
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.Reader
import java.io.Writer
import java.nio.charset.Charset
import java.util.Date

/**
 * Capability cvs-operations, requirement "Checkout continues past files it cannot receive": one received file's
 * failure is isolated in javacvs's response handler, and the protocol stream stays in step (design decisions 6, 7).
 * The handler is driven directly on one byte stream that holds the bodies of several `Updated` responses.
 */
class ReceivedFileIsolationTest {

    @TempDir
    lateinit var root: File

    /** What a fake writer does with one file: read it all, or read [readBytes] bytes and then throw. */
    private sealed interface Behavior {
        data object Store : Behavior
        data class FailAfter(val readBytes: Int) : Behavior
        data class FailTextAfter(val readChars: Int) : Behavior
    }

    /** The content each file's writer call received, by file name. */
    private val received = linkedMapOf<String, ByteArray>()
    private val entries = mutableListOf<String>()
    private val errors = mutableListOf<String>()
    private val behaviors = mutableMapOf<String, Behavior>()
    private var failingEntry: String? = null
    private var entryFailure: () -> Throwable = { IllegalStateException("entries file is broken") }
    private var failingListener: String? = null

    @Test
    @DisplayName("Cancellation while receiving a file is not reported as a failed file")
    fun cancellationWhileReceivingAFileIsNotReportedAsAFailedFile() {
        failingEntry = "a.bin"
        entryFailure = { ProcessCanceledException() }
        val stream = stream("a.bin" to "first".toByteArray())

        assertThrows(ProcessCanceledException::class.java) { receiveBinary(stream, "a.bin", "first".length) }
        assertEquals(emptyList<String>(), errors)
    }

    @Test
    @DisplayName("A broken connection keeps the file's original failure")
    fun aBrokenConnectionKeepsTheFilesOriginalFailure() {
        behaviors["a.bin"] = Behavior.FailAfter(3)
        // The connection ends after 5 of the file's 15 bytes.
        val stream = ByteArrayInputStream("first".toByteArray())

        val thrown = assertThrows(IOException::class.java) { receiveBinary(stream, "a.bin", 15) }
        assertTrue(thrown.suppressed.any { it.message == "disk full" } || thrown.message == "disk full", "$thrown")
    }

    @Test
    @DisplayName("A failing listener does not report a received file as failed")
    fun aFailingListenerDoesNotReportAReceivedFileAsFailed() {
        failingListener = "a.bin"
        val stream = stream("a.bin" to "first".toByteArray())

        assertThrows(IllegalStateException::class.java) { receiveBinary(stream, "a.bin", "first".length) }
        assertEquals(listOf("a.bin"), entries)
        assertEquals(emptyList<String>(), errors)
    }

    @Test
    @DisplayName("A file that fails part-way does not disturb the next file")
    fun aFileThatFailsPartWayDoesNotDisturbTheNextFile() {
        behaviors["a.bin"] = Behavior.FailAfter(3)
        val stream = stream("a.bin" to "first file body".toByteArray(), "b.bin" to "second".toByteArray())

        receiveBinary(stream, "a.bin", "first file body".length)
        receiveBinary(stream, "b.bin", "second".length)

        assertArrayEquals("second".toByteArray(), received["b.bin"])
        assertEquals(listOf("b.bin"), entries)
        assertFailureReported("module/a.bin")
        assertEquals("ok\n", stream.readAllBytes().decodeToString())
    }

    @Test
    @DisplayName("A failure after a file was written does not stop the checkout")
    fun aFailureAfterAFileWasWrittenDoesNotStopTheCheckout() {
        failingEntry = "a.bin"
        val stream = stream("a.bin" to "first".toByteArray(), "b.bin" to "second".toByteArray())

        receiveBinary(stream, "a.bin", "first".length)
        receiveBinary(stream, "b.bin", "second".length)

        assertArrayEquals("second".toByteArray(), received["b.bin"])
        assertEquals(listOf("b.bin"), entries)
        assertFailureReported("module/a.bin")
        assertEquals("ok\n", stream.readAllBytes().decodeToString())
    }

    @Test
    @DisplayName("A failure on the last file leaves the command result readable")
    fun aFailureOnTheLastFileLeavesTheCommandResultReadable() {
        behaviors["b.bin"] = Behavior.FailAfter(2)
        val stream = stream("a.bin" to "first".toByteArray(), "b.bin" to "second".toByteArray())

        receiveBinary(stream, "a.bin", "first".length)
        receiveBinary(stream, "b.bin", "second".length)

        assertArrayEquals("first".toByteArray(), received["a.bin"])
        assertFailureReported("module/b.bin")
        assertEquals("ok\n", stream.readAllBytes().decodeToString())
    }

    @Test
    @DisplayName("A failing zero-length file does not disturb the next file")
    fun aFailingZeroLengthFileDoesNotDisturbTheNextFile() {
        behaviors["empty.bin"] = Behavior.FailAfter(0)
        val stream = stream("empty.bin" to ByteArray(0), "b.bin" to "second".toByteArray())

        receiveBinary(stream, "empty.bin", 0)
        receiveBinary(stream, "b.bin", "second".length)

        assertArrayEquals("second".toByteArray(), received["b.bin"])
        assertFailureReported("module/empty.bin")
        assertEquals("ok\n", stream.readAllBytes().decodeToString())
    }

    @Test
    @DisplayName("A failing text file read through a reader does not disturb the next file")
    fun aFailingTextFileReadThroughAReaderDoesNotDisturbTheNextFile() {
        // An InputStreamReader fills its buffer from the stream; unbounded, it would swallow the next file's bytes.
        behaviors["a.txt"] = Behavior.FailTextAfter(2)
        val first = "line one\nline two\n".toByteArray()
        val stream = stream("a.txt" to first, "b.txt" to "next\n".toByteArray())

        receiveText(stream, "a.txt", first.size)
        receiveText(stream, "b.txt", "next\n".length)

        assertArrayEquals("next\n".toByteArray(), received["b.txt"])
        assertEquals(listOf("b.txt"), entries)
        assertFailureReported("module/a.txt")
        assertEquals("ok\n", stream.readAllBytes().decodeToString())
    }

    private fun assertFailureReported(path: String) {
        assertEquals(1, errors.size, "$errors")
        assertTrue(errors[0].startsWith("cvs checkout: could not receive $path: "), errors[0])
    }

    /** The bodies of [files] one after the other, then the command result `ok`. */
    private fun stream(vararg files: Pair<String, ByteArray>): InputStream =
        ByteArrayInputStream(files.fold(ByteArray(0)) { all, file -> all + file.second } + "ok\n".toByteArray())

    private fun receiveBinary(stream: InputStream, name: String, length: Int) =
        receive(stream, name, "/$name/1.1//-kb/", length)

    private fun receiveText(stream: InputStream, name: String, length: Int) =
        receive(stream, name, "/$name/1.1///", length)

    private fun receive(stream: InputStream, name: String, entryLine: String, length: Int) {
        DefaultResponseHandler().processUpdatedResponse(
            "module/", "/repo/module/$name", entryLine, "u=rw,g=r,o=r", length,
            environment(), responseServices(), connectionStreams(stream),
        )
    }

    private fun environment(): IClientEnvironment {
        val fileSystem = CvsFileSystem(root, "/repo")
        return object : IClientEnvironment {
            override fun getConnection(): IConnection = unused()
            override fun getCvsFileSystem(): ICvsFileSystem = fileSystem
            override fun getCvsRoot(): CvsRoot = CvsRoot("alice", "/repo", ":pserver:alice@localhost:/repo")
            override fun getAdminReader(): IAdminReader = unused()
            override fun getAdminWriter(): IAdminWriter = this@ReceivedFileIsolationTest.adminWriter
            override fun getLocalFileReader(): ILocalFileReader = unused()
            override fun getLocalFileWriter(): ILocalFileWriter = this@ReceivedFileIsolationTest.localFileWriter
            override fun getIgnoreFileFilter(): IIgnoreFileFilter = unused()
            override fun getFileReadOnlyHandler(): IFileReadOnlyHandler = IFileReadOnlyHandler { _, _ -> }
            override fun getCharset(): String = "UTF-8"
        }
    }

    private val localFileWriter = object : ILocalFileWriter {
        override fun writeTextFile(
            fileObject: FileObject, length: Int, inputStream: InputStream, readOnly: Boolean, readerFactory: IReaderFactory,
            fileReadOnlyHandler: IFileReadOnlyHandler, fileSystem: IFileSystem, charSet: Charset?,
        ) {
            when (val behavior = behaviors[fileObject.name] ?: Behavior.Store) {
                is Behavior.FailTextAfter -> {
                    val reader = readerFactory.createReader(inputStream)
                    repeat(behavior.readChars) { reader.read() }
                    throw IOException("text write failed")
                }
                else -> write(fileObject, length, inputStream, behavior)
            }
        }

        override fun writeBinaryFile(
            fileObject: FileObject, length: Int, inputStream: InputStream, readOnly: Boolean,
            fileReadOnlyHandler: IFileReadOnlyHandler, cvsFileSystem: ICvsFileSystem,
        ) = write(fileObject, length, inputStream, behaviors[fileObject.name] ?: Behavior.Store)

        private fun write(fileObject: FileObject, length: Int, inputStream: InputStream, behavior: Behavior) {
            if (behavior is Behavior.FailAfter) {
                inputStream.readNBytes(behavior.readBytes)
                throw IOException("disk full")
            }
            val bytes = inputStream.readNBytes(length)
            check(bytes.size == length) { "short read" }
            received[fileObject.name] = bytes
        }

        override fun removeLocalFile(fileObject: FileObject, cvsFileSystem: ICvsFileSystem, fileReadOnlyHandler: IFileReadOnlyHandler) = unused()
        override fun renameLocalFile(fileObject: FileObject, cvsFileSystem: ICvsFileSystem, newFileName: String) = unused()
        override fun setNextFileDate(modifiedDate: Date?) {}
        override fun setNextFileMode(nextFileMode: String?) {}
    }

    private val adminWriter = object : IAdminWriter {
        override fun ensureCvsDirectory(directoryObject: DirectoryObject, repositoryPath: String, cvsRoot: CvsRoot, cvsFileSystem: ICvsFileSystem) {}
        override fun setEntry(directoryObject: DirectoryObject, entry: Entry, cvsFileSystem: ICvsFileSystem) {
            if (entry.fileName == failingEntry) throw entryFailure()
            entries += entry.fileName
        }
        override fun removeEntryForFile(fileObject: AbstractFileObject, cvsFileSystem: ICvsFileSystem) = unused()
        override fun pruneDirectory(directoryObject: DirectoryObject, cvsFileSystem: ICvsFileSystem) = unused()
        override fun editFile(fileObject: FileObject, entry: Entry, cvsFileSystem: ICvsFileSystem, fileReadOnlyHandler: IFileReadOnlyHandler) = unused()
        override fun uneditFile(fileObject: FileObject, cvsFileSystem: ICvsFileSystem, fileReadOnlyHandler: IFileReadOnlyHandler) = unused()
        override fun setStickyTagForDirectory(directoryObject: DirectoryObject, tag: String?, cvsFileSystem: ICvsFileSystem) = unused()
        override fun setEntriesDotStatic(directoryObject: DirectoryObject, set: Boolean, cvsFileSystem: ICvsFileSystem) = unused()
        override fun writeTemplateFile(
            directoryObject: DirectoryObject, fileLength: Int, inputStream: InputStream, readerFactory: IReaderFactory,
            clientEnvironment: IClientEnvironment,
        ) = unused()
        override fun directoryAdded(directory: DirectoryObject, cvsFileSystem: ICvsFileSystem) = unused()
    }

    private fun responseServices(): IResponseServices {
        val sender = object : IEventSender {
            override fun notifyTerminationListeners(error: Boolean) {}
            override fun notifyMessageListeners(message: ByteArray, error: Boolean, tagged: Boolean) {
                if (error) errors += message.toString(Charsets.UTF_8)
            }
            override fun notifyFileInfoListeners(fileInfoContainer: Any) {}
            override fun notifyFileInfoListeners(fileInfoContainer: ByteArray) {}
            override fun notifyModuleExpansionListeners(module: String) {}
            override fun notifyEntryListeners(fileObject: FileObject, entry: Entry?) {
                check(fileObject.name != failingListener) { "listener failed" }
            }
            override fun notifyDirectoryListeners(directoryObject: DirectoryObject, setStatic: Boolean) {}
        }
        return object : IResponseServices {
            override fun setNextFileDate(modifiedDate: Date?) {}
            override fun getNextFileDate(): Date? = null
            override fun getEventSender(): IEventSender = sender
            override fun setValidRequests(validRequests: String) {}
            override fun getNextFileMode(): String? = null
            override fun setNextFileMode(nextFileMode: String?) {}
        }
    }

    private fun connectionStreams(stream: InputStream) = object : IConnectionStreams {
        override fun getLoggedInputStream(): InputStream = stream
        override fun getLoggedOutputStream(): OutputStream = unused()
        override fun getLoggedReader(): Reader = unused()
        override fun getLoggedWriter(): Writer = unused()
        override fun getInputStream(): InputStream = stream
        override fun getOutputStream(): OutputStream = unused()
        override fun getReaderFactory(): IReaderFactory = IReaderFactory { InputStreamReader(it, Charsets.UTF_8) }
        override fun getWriterFactory(): IWriterFactory = unused()
        override fun flushForReading() {}
        override fun close() {}
    }

    private fun unused(): Nothing = throw UnsupportedOperationException("not used by the response handler")
}
