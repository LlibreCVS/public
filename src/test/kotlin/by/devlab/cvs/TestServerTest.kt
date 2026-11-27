package by.devlab.cvs

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.netbeans.lib.cvsclient.connection.PServerPasswordScrambler
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.io.InputStreamReader
import java.net.ConnectException
import java.net.InetAddress
import java.net.Socket

/**
 * Capability cvs-operations, the throwaway server itself (design decisions 7, 8, 11, 16). These tests drive the
 * fixture directly; they need no IntelliJ application.
 */
class TestServerTest {

    @Test
    @DisplayName("Missing cvs fails the tests clearly")
    fun missingCvsFailsTheTestsClearly() {
        val error = assertThrows<IllegalStateException> {
            CvsLocator.locate(property = "C:\\no\\such\\dir\\cvs.exe", path = "")
        }

        val message = error.message!!
        assertTrue(message.contains("cvs was not found"), message)
        assertTrue(message.contains(CvsLocator.PROPERTY) && message.contains("C:\\no\\such\\dir\\cvs.exe"), message)
        assertTrue(message.contains("PATH"), message)
    }

    @Test
    @DisplayName("Server processes end with the tests")
    fun serverProcessesEndWithTheTests() {
        val server = TestCvsServer()
        server.start()
        val processes = try {
            Socket(InetAddress.getLoopbackAddress(), server.port).use { socket ->
                assertEquals("I LOVE YOU", authenticate(socket, server, "alice"))
            }
            server.startedProcesses()
        } finally {
            server.close()
        }

        assertTrue(processes.isNotEmpty(), "the connection started no server process")
        assertEquals(emptyList<ProcessHandle>(), processes.filter { it.isAlive })
        assertThrows<ConnectException> { Socket(InetAddress.getLoopbackAddress(), server.port).close() }
    }

    // The scenario's "whether they passed or failed": a failing test leaves its connection open.
    @Test
    @DisplayName("Server processes end with the tests (connection still open)")
    fun serverProcessesEndWithTheTestsWhileAConnectionIsOpen() {
        val server = TestCvsServer()
        server.start()
        val socket = Socket(InetAddress.getLoopbackAddress(), server.port)
        try {
            assertEquals("I LOVE YOU", authenticate(socket, server, "alice"))
            val session = "Root ${server.repositoryPath}\nValid-responses ${TestCvsServer.VALID_RESPONSES}\nvalid-requests\n"
            socket.getOutputStream().apply { write(session.toByteArray(Charsets.US_ASCII)); flush() }

            server.close()

            val processes = server.startedProcesses()
            assertTrue(processes.isNotEmpty(), "the connection started no server process")
            assertEquals(emptyList<ProcessHandle>(), processes.filter { it.isAlive })
        } finally {
            socket.close()
        }
    }

    @Test
    @DisplayName("Two connections are served at once")
    fun twoConnectionsAreServedAtOnce() {
        TestCvsServer().use { server ->
            server.start()
            Socket(InetAddress.getLoopbackAddress(), server.port).use { first ->
                Socket(InetAddress.getLoopbackAddress(), server.port).use { second ->
                    // Both requests go out before either answer is read: a server that serves one
                    // connection at a time would never answer the second while the first stays open.
                    sendAuthRequest(first, server, "alice")
                    sendAuthRequest(second, server, "bob")
                    second.soTimeout = 30_000
                    first.soTimeout = 30_000

                    assertEquals("I LOVE YOU", readLine(second))
                    assertEquals("I LOVE YOU", readLine(first))
                }
            }
        }
    }

    // changeset-update: the time-zone scenario needs a server that really reads a zone-less date in its own zone.
    @Test
    @DisplayName("Server runs in the time zone it was given")
    fun serverRunsInTheTimeZoneItWasGiven() {
        TestCvsServer(timeZone = "JST-9").use { server ->
            server.start()
            val seed = java.nio.file.Files.createTempDirectory(server.workDirectory, "zone-seed-")
            seed.resolve("file.txt").toFile().writeText("one\n")
            server.cvs("import", "-m", "zone", "zone", "vendor", "start", workingDirectory = seed)
            val log = server.cvs("rlog", "zone/file.txt")
            val date = Regex("""date: (\d{4}/\d{2}/\d{2} \d{2}:\d{2}:\d{2});""").find(log)!!.groupValues[1]
            val format = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            val later = java.time.LocalDateTime.parse(date, java.time.format.DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"))
                .plusHours(1).format(format)

            // One hour after the commit in UTC wall time; read as JST it is eight hours before the commit.
            val zoneLess = server.cvs("rlog", "-d", later, "zone/file.txt")
            val utc = server.cvs("rlog", "-d", "$later UTC", "zone/file.txt")

            assertTrue(zoneLess.contains("selected revisions: 0"), zoneLess)
            assertTrue(utc.contains("selected revisions: 1"), utc)
        }
    }

    // Found while proving checkout: with the server's temp directory inside the user profile, commands that create
    // directories there failed with "Permission denied", most often the first one (design decision 19).
    @Test
    @DisplayName("First command on a fresh server succeeds")
    fun firstCommandOnAFreshServerSucceeds() {
        TestCvsServer().use { server ->
            server.start()
            Socket(InetAddress.getLoopbackAddress(), server.port).use { socket ->
                sendAuthRequest(socket, server, "alice")
                val repository = server.repositoryPath
                val command = "Root $repository\nValid-responses ${TestCvsServer.VALID_RESPONSES}\n" +
                    "Directory .\n$repository\nDirectory first\n$repository/first\nnoop\n"
                socket.getOutputStream().apply { write(command.toByteArray(Charsets.US_ASCII)); flush() }
                socket.shutdownOutput()
                socket.soTimeout = 30_000

                val answer = socket.getInputStream().readAllBytes().decodeToString()

                assertEquals(listOf("I LOVE YOU", "ok"), answer.lines().filter { it.isNotEmpty() }, answer)
            }
        }
    }

    // Design decision 18: msys stores windows-1251 names mangled, so the pump converts protocol lines.
    @Test
    @DisplayName("Transcoder converts names and keeps payloads")
    fun transcoderConvertsNamesAndKeepsPayloads() {
        val cp1251 = Charset.forName("windows-1251")
        val payload = byteArrayOf(0x0D, 0x0A, 0x00, 0x1A, 0xC4.toByte(), 0xEE.toByte(), 0x0A, 'E'.code.toByte(), 0x0A)
        fun request(charset: Charset) = bytes(
            "Directory Документы\n/repo/m/Документы\nModified данные.bin\nu=rw,g=r,o=r\n${payload.size}\n".toByteArray(charset),
            payload,
            "Argument Сообщение коммита\nci\n".toByteArray(charset),
        )
        fun response(charset: Charset) = bytes(
            "M U m/Документы/данные.bin\nUpdated m/Документы/\n/repo/m/Документы/данные.bin\n/данные.bin/1.1///-kb\nu=rw,g=r,o=r\n${payload.size}\n".toByteArray(charset),
            payload,
            "Mbinary\n${payload.size}\n".toByteArray(charset),
            payload,
            "ok\n".toByteArray(charset),
        )

        assertArrayEquals(request(Charsets.UTF_8), transcode(CvsProtocolTranscoder.requests(cp1251, Charsets.UTF_8), request(cp1251)))
        assertArrayEquals(response(cp1251), transcode(CvsProtocolTranscoder.responses(Charsets.UTF_8, cp1251), response(Charsets.UTF_8)))
    }

    // Found in 3.4: annotate sends file lines inside M responses, in the encoding the file was stored in; the
    // server stores contents as the client's bytes, so a line that is not UTF-8 is already in the client's.
    @Test
    @DisplayName("Transcoder passes file text in responses unchanged")
    fun transcoderPassesFileTextInResponsesUnchanged() {
        val cp1251 = Charset.forName("windows-1251")
        val annotateLine = "M 1.2          (bob      11-Nov-26): Вторая, изменена bob: ёЁ щЩ ъЪ\n".toByteArray(cp1251)

        assertArrayEquals(annotateLine, transcode(CvsProtocolTranscoder.responses(Charsets.UTF_8, cp1251), annotateLine))
    }

    private fun bytes(vararg parts: ByteArray): ByteArray = parts.fold(ByteArray(0)) { all, part -> all + part }

    private fun transcode(transcoder: CvsProtocolTranscoder, input: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        transcoder.copy(ByteArrayInputStream(input), output)
        return output.toByteArray()
    }

    private fun authenticate(socket: Socket, server: TestCvsServer, user: String): String {
        sendAuthRequest(socket, server, user)
        socket.soTimeout = 30_000
        return readLine(socket)
    }

    private fun sendAuthRequest(socket: Socket, server: TestCvsServer, user: String) {
        val password = PServerPasswordScrambler.getInstance().scramble(TestCvsServer.PASSWORD)
        val request = "BEGIN AUTH REQUEST\n${server.repositoryPath}\n$user\n$password\nEND AUTH REQUEST\n"
        socket.getOutputStream().apply { write(request.toByteArray(Charsets.US_ASCII)); flush() }
    }

    private fun readLine(socket: Socket): String =
        BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.US_ASCII)).readLine()
}
