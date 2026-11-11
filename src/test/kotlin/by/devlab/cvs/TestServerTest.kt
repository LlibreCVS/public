package by.devlab.cvs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.netbeans.lib.cvsclient.connection.PServerPasswordScrambler
import java.io.BufferedReader
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
