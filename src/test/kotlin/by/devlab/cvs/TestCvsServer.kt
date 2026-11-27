package by.devlab.cvs

import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.netbeans.lib.cvsclient.connection.PServerPasswordScrambler
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.absolutePathString
import kotlin.io.path.writeText

/**
 * Finds the `cvs` program that runs the throwaway server: first the system property [PROPERTY], then `PATH`.
 * A missing program fails the tests instead of skipping them (design decision 11).
 */
object CvsLocator {
    const val PROPERTY = "librecvs.cvs"

    fun locate(property: String? = System.getProperty(PROPERTY), path: String? = System.getenv("PATH")): Path {
        if (!property.isNullOrBlank()) {
            pathOrNull(property)?.takeIf { Files.isRegularFile(it) }?.let { return it }
        }
        val names = if (File.separatorChar == '\\') listOf("cvs.exe") else listOf("cvs")
        path.orEmpty().split(File.pathSeparator).filter { it.isNotBlank() }.forEach { dir ->
            names.forEach { name ->
                pathOrNull(dir)?.resolve(name)?.takeIf { Files.isRegularFile(it) }?.let { return it }
            }
        }
        throw IllegalStateException(
            "cvs was not found. Looked in the system property $PROPERTY (${property ?: "not set"}) and on PATH " +
                "(${if (path.isNullOrBlank()) "empty" else path}). Install cvs-msys2 or point $PROPERTY to cvs.exe."
        )
    }

    private fun pathOrNull(text: String): Path? = runCatching { Path.of(text) }.getOrNull()
}

/**
 * A throwaway CVS pserver for the tests (design decisions 7, 8, 13, 16). [start] creates a fresh repository under
 * `build/cvs-test/` with `cvs init`, lets the users [USERS] in with any password, and listens on 127.0.0.1 on a free
 * port; every accepted connection gets its own `cvs pserver` process (inetd emulation). [close] stops the listener,
 * the connections and every process the server started, with their descendants.
 *
 * Use it per test class: `@JvmField @RegisterExtension val server = TestCvsServer()` in a companion object.
 */
class TestCvsServer : AutoCloseable, BeforeAllCallback, AfterAllCallback {

    private lateinit var work: Path
    private lateinit var repository: Path
    private lateinit var temp: Path
    private lateinit var listener: ServerSocket
    private val connections = CopyOnWriteArrayList<Socket>()
    private val processes = CopyOnWriteArrayList<ProcessHandle>()
    private val descendants = CopyOnWriteArrayList<ProcessHandle>()
    private val connectionCount = AtomicInteger()
    private val requestBytes = java.io.ByteArrayOutputStream()

    /**
     * The encoding the plugin talks in; connections opened while it is not UTF-8 are transcoded to UTF-8 for the
     * msys server, which cannot store other file-name bytes (design decision 18).
     */
    @Volatile
    var clientCharset: Charset = Charsets.UTF_8

    /** The directory of this server under [WORK_ROOT]: repository, temp files, logs. */
    val workDirectory: Path get() = work

    /** The port the server listens on. */
    val port: Int get() = listener.localPort

    /** The repository path as clients name it: the msys form the server was allowed (design decision 8). */
    val repositoryPath: String get() = msysPath(repository)

    /**
     * The lines every client has sent to this server so far, in the client's encoding as received (requests such as
     * `co`, their arguments, and file contents of commits), for tests that check which requests were made.
     */
    fun requestLines(): List<String> = synchronized(requestBytes) { requestBytes.toByteArray() }.decodeToString().lines()

    /** A file of the repository, e.g. an RCS file `module/file.txt,v`, for inspection by the tests. */
    fun repositoryFile(relative: String): Path = repository.resolve(relative)

    /**
     * Runs the fixture's `cvs` directly on the repository (no server, no plugin) with [arguments], e.g.
     * `rlog -h module/file.txt`, to inspect or prepare the repository (in [workingDirectory], e.g. for `import`);
     * returns its output, decoded as UTF-8. Dates without a zone are read in the Windows system zone: this cvs
     * ignores `TZ` (changeset-update design decision 19).
     */
    fun cvs(vararg arguments: String, workingDirectory: Path? = null): String {
        val process = ProcessBuilder(listOf(CvsLocator.locate().toString(), "-f", "-d", repositoryPath) + arguments)
            .redirectErrorStream(true)
            .apply { configure(environment(), work.resolve("home")) }
            .apply { if (workingDirectory != null) directory(workingDirectory.toFile()) }
            .start()
        val output = process.inputStream.readBytes().decodeToString()
        check(process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0) {
            "cvs ${arguments.joinToString(" ")} failed: $output"
        }
        return output
    }

    /** The CVSROOT through which [user] reaches this server. */
    fun cvsRoot(user: String): String = ":pserver:$user@127.0.0.1:$port$repositoryPath"

    fun start() {
        val cvs = CvsLocator.locate()
        work = Files.createTempDirectory(Files.createDirectories(WORK_ROOT), "server-")
        repository = work.resolve("repository")
        temp = Files.createTempDirectory(TEMP_ROOT, "cvs-test-tmp-")
        val home = Files.createDirectories(work.resolve("home"))

        val init = ProcessBuilder(cvs.toString(), "-f", "-d", repositoryPath, "init")
            .redirectErrorStream(true)
            .apply { configure(environment(), home) }
            .start()
        val output = init.inputStream.readBytes().decodeToString()
        check(init.waitFor(60, TimeUnit.SECONDS) && init.exitValue() == 0) { "cvs init failed: $output" }
        // An empty password field lets any password in; the server runs as the mapped (current) Windows user.
        val systemUser = System.getProperty("user.name")
        repository.resolve("CVSROOT/passwd").writeText(USERS.joinToString("") { "$it::$systemUser\n" })

        listener = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val command = listOf(cvs.toString(), "-f", "-T", msysPath(temp), "--allow-root=$repositoryPath", "pserver")
        Thread({ acceptLoop(command, home) }, "cvs-test-server-$port").apply { isDaemon = true }.start()
    }

    private fun acceptLoop(command: List<String>, home: Path) {
        while (!listener.isClosed) {
            val socket = try {
                listener.accept()
            } catch (_: IOException) {
                return
            }
            connections.add(socket)
            try {
                serve(socket, command, home)
            } catch (e: IOException) {
                socket.close()
            }
        }
    }

    private fun serve(socket: Socket, command: List<String>, home: Path) {
        val log = work.resolve("pserver-${connectionCount.incrementAndGet()}.log").toFile()
        val process = ProcessBuilder(command)
            .redirectError(ProcessBuilder.Redirect.appendTo(log))
            .apply { configure(environment(), home) }
            .start()
        processes.add(process.toHandle())
        // The client's encoding when the connection opens decides how its lines are converted (decision 18).
        val charset = clientCharset
        val transcode = charset != Charsets.UTF_8
        val requests = if (transcode) CvsProtocolTranscoder.requests(charset, Charsets.UTF_8) else null
        val responses = if (transcode) CvsProtocolTranscoder.responses(Charsets.UTF_8, charset) else null
        pump("in", RecordingInputStream(socket.getInputStream()), process.outputStream, requests) { process.outputStream.close() }
        pump("out", process.inputStream, socket.getOutputStream(), responses) { socket.close() }
    }

    /** Passes the client's bytes through and keeps a copy of them for [requestLines]. */
    private inner class RecordingInputStream(private val from: InputStream) : java.io.FilterInputStream(from) {
        override fun read(): Int = super.read().also { if (it >= 0) synchronized(requestBytes) { requestBytes.write(it) } }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            super.read(buffer, offset, length).also { if (it > 0) synchronized(requestBytes) { requestBytes.write(buffer, offset, it) } }
    }

    /**
     * Copies [from] to [to] on a daemon thread until either side ends, then runs [done]; with a [transcoder] the
     * text lines are re-encoded on the way.
     */
    private fun pump(name: String, from: InputStream, to: OutputStream, transcoder: CvsProtocolTranscoder?, done: () -> Unit) {
        Thread({
            try {
                if (transcoder != null) {
                    transcoder.copy(BufferedInputStream(from), to)
                } else {
                    val buffer = ByteArray(8192)
                    while (true) {
                        val read = from.read(buffer)
                        if (read < 0) break
                        to.write(buffer, 0, read)
                        to.flush()
                    }
                }
            } catch (_: IOException) {
                // The other side went away; closing below ends the connection.
            } finally {
                runCatching(done)
            }
        }, "cvs-test-server-$port-$name").apply { isDaemon = true }.start()
    }

    /**
     * Every process this server started, with every descendant seen so far: descendants are collected on each
     * call, so a child that has exited, or whose parent has exited, stays on the list.
     */
    fun startedProcesses(): List<ProcessHandle> {
        processes.toList().forEach { process -> descendantsOf(process).forEach { descendants.addIfAbsent(it) } }
        return (processes + descendants).distinct()
    }

    /**
     * The descendants of [process]. On Windows the process snapshot behind [ProcessHandle.descendants] fails now and
     * then with "snapshot not available" while processes start or exit; the snapshot is taken again then.
     */
    private fun descendantsOf(process: ProcessHandle): List<ProcessHandle> {
        repeat(SNAPSHOT_ATTEMPTS - 1) {
            try {
                return process.descendants().toList()
            } catch (e: RuntimeException) {
                if (e.message != "snapshot not available") throw e
                Thread.sleep(100)
            }
        }
        return process.descendants().toList()
    }

    /**
     * Stops the listener, then the connections, waits for the processes and destroys whatever still runs, with the
     * descendants collected again after the wait (a child forked while the connections closed is caught too).
     */
    override fun close() {
        if (!::listener.isInitialized) return
        listener.close()
        startedProcesses()
        connections.forEach { runCatching { it.close() } }
        startedProcesses().forEach { process -> runCatching { process.onExit().get(5, TimeUnit.SECONDS) } }
        val remaining = startedProcesses().filter { it.isAlive }
        remaining.forEach { it.destroyForcibly() }
        remaining.forEach { process -> runCatching { process.onExit().get(5, TimeUnit.SECONDS) } }
        runCatching { temp.toFile().deleteRecursively() }
    }

    override fun beforeAll(context: ExtensionContext) = start()

    override fun afterAll(context: ExtensionContext) = close()

    companion object {
        /** Any password is accepted; this one is what the tests send. */
        const val PASSWORD = "secret"

        private const val SNAPSHOT_ATTEMPTS = 5

        /** The CVS users of the repository (design decision 13). */
        val USERS = listOf("alice", "bob")

        /** The repositories, working copies and logs lie below this directory. */
        val WORK_ROOT: Path = Path.of("build", "cvs-test").toAbsolutePath()

        /**
         * The server's own temp files (`-T`) go to the system temp directory, not under [WORK_ROOT]: in the user
         * profile on this workstation (an FSLogix container), creating the per-command temp directories failed
         * with "Permission denied" now and then (design decision 19). Each server deletes its directory on close.
         */
        val TEMP_ROOT: Path = Path.of(System.getenv("TEMP") ?: System.getProperty("java.io.tmpdir"))

        /** `C:\Users\x` becomes `/c/Users/x`, the form msys cvs accepts in `--allow-root` and CVSROOTs. */
        fun msysPath(path: Path): String {
            val text = path.absolutePathString().replace('\\', '/')
            return if (text.length > 1 && text[1] == ':') "/${text[0].lowercaseChar()}${text.substring(2)}" else text
        }

        private fun configure(environment: MutableMap<String, String>, home: Path) {
            // The server process never sees the user's home, CVSROOT or CVS settings.
            environment.keys.removeIf { it.startsWith("CVS") }
            environment["HOME"] = home.absolutePathString()
        }

        /** The responses a client announces in `Valid-responses`, as the cvs 1.11 server expects them. */
        const val VALID_RESPONSES = "ok error Valid-requests Checked-in New-entry Checksum Copy-file Updated Created " +
            "Update-existing Merged Patched Rcs-diff Mode Mod-time Removed Remove-entry Set-static-directory " +
            "Clear-static-directory Set-sticky Clear-sticky Template Notified Module-expansion Wrapper-rcsOption M " +
            "Mbinary E F MT"
    }
}
