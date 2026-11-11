package by.devlab

import com.intellij.cvsSupport2.config.CvsApplicationLevelConfiguration
import com.intellij.cvsSupport2.config.CvsRootConfiguration
import com.intellij.cvsSupport2.config.ui.CvsRootFieldByFieldConfigurationPanel
import com.intellij.cvsSupport2.connections.CvsMethod
import com.intellij.cvsSupport2.connections.CvsRootData
import com.intellij.cvsSupport2.connections.CvsRootException
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.TestApplication
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.netbeans.lib.cvsclient.connection.IConnection
import com.intellij.cvsSupport2.javacvsImpl.io.ReadWriteStatistics
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JComboBox

/**
 * Capability cvs-connections. Connections are only created, never opened: opening is where a process could
 * start or the network could be used. The pserver password file is an empty temporary file, never the
 * user's ~/.cvspass (design decision 15).
 */
@TestApplication
@RunInEdt
class CvsConnectionsTest {

    @TempDir
    lateinit var temp: Path

    private var previousPasswordFile: String? = null

    @BeforeEach
    fun useEmptyPasswordFile() {
        val configuration = CvsApplicationLevelConfiguration.getInstance()
        previousPasswordFile = configuration.PATH_TO_PASSWORD_FILE
        configuration.PATH_TO_PASSWORD_FILE = Files.createFile(temp.resolve(".cvspass")).toString()
    }

    @AfterEach
    fun restorePasswordFile() {
        CvsApplicationLevelConfiguration.getInstance().PATH_TO_PASSWORD_FILE = previousPasswordFile
    }

    @Test
    @DisplayName("Method choice excludes local")
    fun methodChoiceExcludesLocal() {
        val panel = CvsRootFieldByFieldConfigurationPanel()
        panel.updateFrom(CvsRootData(":pserver:anonymous@cvs.example.invalid:/cvsroot").apply {
            METHOD = CvsMethod.PSERVER_METHOD
        })
        val field = CvsRootFieldByFieldConfigurationPanel::class.java.getDeclaredField("myMethods")
        field.isAccessible = true
        val methods = field.get(panel) as JComboBox<*>

        val offered = (0 until methods.itemCount).map { (methods.getItemAt(it) as CvsMethod).name }

        assertEquals(listOf("pserver", "ext", "ssh"), offered)
    }

    // The parser and the settings still need the local method to recognize a local CVSROOT and reject it.
    @Test
    @DisplayName("Local method is still known to the parser")
    fun localMethodIsStillKnownToTheParser() {
        assertSame(CvsMethod.LOCAL_METHOD, CvsMethod.getValue("local"))
    }

    @Test
    @DisplayName("Local method is rejected")
    fun localMethodIsRejected() {
        assertRejected(":local:/var/cvs/repo")
    }

    @Test
    @DisplayName("Bare repository path is rejected")
    fun bareRepositoryPathIsRejected() {
        // Review focus: Windows paths have no method either and must not be read as a host named C.
        for (root in listOf("/var/cvs/repo", "C:/cvs/repo", "C:\\cvs\\repo")) {
            assertRejected(root)
        }
    }

    @Test
    @DisplayName("Remote method is still accepted")
    fun remoteMethodIsStillAccepted() {
        val connection = connectionFor(":pserver:anonymous@cvs.example.invalid:/cvsroot")

        assertNotNull(connection)
    }

    private fun assertRejected(root: String) {
        val error = assertThrows<CvsRootException>("CVSROOT $root") { connectionFor(root) }
        assertEquals("Local CVS repositories are not supported; use pserver, ext or ssh", error.message, root)
    }

    /** Turns [root] into a connection the way the plugin's operations do, without opening it. */
    private fun connectionFor(root: String): IConnection =
        CvsRootConfiguration().apply { CVS_ROOT = root }.createConnection(ReadWriteStatistics())
}
