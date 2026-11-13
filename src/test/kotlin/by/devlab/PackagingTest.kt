package by.devlab

import com.intellij.CvsBundle
import com.intellij.testFramework.junit5.TestApplication
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The packaged plugin jar: OpenCVS's resources are in it, its sources are not, and the licenses travel with it
 * (design decision 16).
 */
@TestApplication
class PackagingTest {

    @Test
    @DisplayName("Resource bundles resolve at runtime")
    fun resourceBundlesResolveAtRuntime() {
        assertEquals("Commit Transaction", CvsBundle.message("operation.name.commit.changes"))
    }

    @Test
    @DisplayName("Bundles from the source trees are packaged")
    fun bundlesFromTheSourceTreesArePackaged() {
        val entries = jarEntries()

        assertTrue("messages/JavaCvsSrcBundle.properties" in entries)
        assertTrue("messages/SmartCvsSrcBundle.properties" in entries)
    }

    @Test
    @DisplayName("Jar contains no Java sources")
    fun jarContainsNoJavaSources() {
        assertEquals(emptyList<String>(), jarEntries().filter { it.endsWith(".java") })
    }

    @Test
    @DisplayName("Jar carries the notice and the licenses")
    fun jarCarriesTheNoticeAndTheLicenses() {
        val entries = jarEntries()

        for (name in listOf(
            "META-INF/NOTICE",
            "META-INF/LICENSE",
            "META-INF/sun-public-license.txt",
            "META-INF/trilead-ssh2-LICENSE.txt",
        )) {
            assertTrue(name in entries, "$name missing")
        }
    }

    @Test
    @DisplayName("Action sits in the Repository toolbar and menu")
    fun actionSitsInTheRepositoryToolbarAndMenu() {
        val root = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(InputSource(StringReader(jarEntry("META-INF/plugin.xml")))).documentElement
        assertEquals("idea-plugin", root.tagName)
        // Only children of the root count: the platform ignores anything nested deeper.
        val actions = root.children("actions").flatMap { it.children("action") }
        val action = actions.singleOrNull { it.getAttribute("id") == "IntelliJCVS.PutOnTag" }
        checkNotNull(action) { "no action IntelliJCVS.PutOnTag under <actions> of the packaged descriptor" }
        assertEquals("by.devlab.cvs.tagging.PutOnTagAction", action.getAttribute("class"))
        assertEquals("Put on Tag...", action.getAttribute("text"))
        assertEquals(
            setOf("CommittedChangesToolbar", "ChangesViewPopupMenu"),
            action.children("add-to-group").map { it.getAttribute("group-id") }.toSet(),
        )
        val groups = root.children("extensions").flatMap { it.children("notificationGroup") }
        assertTrue(groups.any { it.getAttribute("id") == "IntelliJCVS" }, "no notification group IntelliJCVS")
    }

    @Test
    @DisplayName("Branch widget sits in the main toolbar")
    fun branchWidgetSitsInTheMainToolbar() {
        val root = descriptor()
        val action = root.children("actions").flatMap { it.children("action") }
            .singleOrNull { it.getAttribute("id") == "IntelliJCVS.BranchWidget" }
        checkNotNull(action) { "no action IntelliJCVS.BranchWidget under <actions> of the packaged descriptor" }
        assertEquals("by.devlab.cvs.branches.BranchToolbarAction", action.getAttribute("class"))
        val group = action.children("add-to-group").single()
        assertEquals("MainToolbarLeft", group.getAttribute("group-id"))
        assertEquals("before", group.getAttribute("anchor"))
        assertEquals("MainToolbarGeneralActionsGroup", group.getAttribute("relative-to-action"))
        val factories = root.children("extensions").flatMap { it.children("statusBarWidgetFactory") }
        val factory = factories.singleOrNull { it.getAttribute("id") == "IntelliJCVS.BranchWidget" }
        checkNotNull(factory) { "no statusBarWidgetFactory IntelliJCVS.BranchWidget" }
        assertEquals("by.devlab.cvs.branches.BranchStatusBarWidgetFactory", factory.getAttribute("implementation"))
    }

    /** The root element of the packaged plugin descriptor. */
    private fun descriptor(): Element =
        DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(InputSource(StringReader(jarEntry("META-INF/plugin.xml")))).documentElement

    private fun Element.children(tag: String): List<Element> =
        (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>().filter { it.tagName == tag }

    /** The text of [name] in the plugin jar. */
    private fun jarEntry(name: String): String =
        ZipFile(pluginJar().toFile()).use { zip ->
            zip.getInputStream(checkNotNull(zip.getEntry(name)) { "no $name in the jar" }).readBytes().toString(Charsets.UTF_8)
        }

    /** Entry names of the plugin jar that the build passes in system property `intellijcvs.jar`. */
    private fun jarEntries(): Set<String> =
        ZipFile(pluginJar().toFile()).use { zip -> zip.entries().asSequence().map { it.name }.toSet() }

    /** The plugin jar that the build passes in system property `intellijcvs.jar`. */
    private fun pluginJar(): Path {
        val jar = Path.of(checkNotNull(System.getProperty("intellijcvs.jar")) { "intellijcvs.jar is not set" })
        check(Files.isRegularFile(jar)) { "no plugin jar at $jar" }
        return jar
    }
}
