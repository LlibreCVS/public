package by.devlab.cvs

import com.intellij.cvsSupport2.config.ImportConfiguration
import com.intellij.cvsSupport2.connections.ssh.SSHPasswordProviderImpl
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.testFramework.junit5.TestApplication
import org.jdom.Element
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.netbeans.lib.cvsclient.connection.PServerPasswordScrambler

/**
 * Capability cvs-operations, requirement "Settings are saved without deprecated components".
 *
 * The IDE reports a component as deprecated (ComponentStoreImpl.initJdom) only when it is not a
 * PersistentStateComponent and the store loads state, which a test application does not do. So the tests prove the
 * platform's condition instead of waiting for the report: the service is a PersistentStateComponent whose @State
 * keeps the name and file that earlier versions saved the state under.
 */
@TestApplication
class SettingsPersistenceTest {

    @Test
    @DisplayName("Import settings load without a deprecation error")
    fun importSettingsLoadWithoutADeprecationError() {
        assertStoredLikeBefore(ImportConfiguration.getInstance(), "ImportConfiguration")
    }

    @Test
    @DisplayName("SSH password store loads without a deprecation error")
    fun sshPasswordStoreLoadsWithoutADeprecationError() {
        assertStoredLikeBefore(SSHPasswordProviderImpl.getInstance(), "SSHPasswordProvider")
    }

    @Test
    @DisplayName("Import settings saved earlier are kept")
    fun importSettingsSavedEarlierAreKept() {
        // The form DefaultJDOMExternalizer wrote under <component name="ImportConfiguration"> in other.xml.
        val saved = Element("component").setAttribute("name", "ImportConfiguration")
            .addContent(option("VENDOR", "vendor-1"))
            .addContent(option("RELEASE_TAG", "start-1"))
            .addContent(option("LOG_MESSAGE", "Импорт модуля"))
            .addContent(option("CHECKOUT_AFTER_IMPORT", "false"))
            .addContent(option("KEYWORD_SUBSTITUTION_WRAPPERS", "jar -kb;"))
            .addContent(option("MAKE_NEW_FILES_READ_ONLY", "true"))

        val loaded = persistent(ImportConfiguration())
        loaded.loadState(saved)
        val config = loaded as ImportConfiguration

        assertEquals("vendor-1", config.VENDOR)
        assertEquals("start-1", config.RELEASE_TAG)
        assertEquals("Импорт модуля", config.LOG_MESSAGE)
        assertEquals(false, config.CHECKOUT_AFTER_IMPORT)
        assertEquals("jar -kb;", config.KEYWORD_SUBSTITUTION_WRAPPERS)
        assertEquals(true, config.MAKE_NEW_FILES_READ_ONLY)

        val reloaded = persistent(ImportConfiguration())
        reloaded.loadState(checkNotNull(loaded.state))
        val again = reloaded as ImportConfiguration
        assertEquals("vendor-1", again.VENDOR)
        assertEquals("start-1", again.RELEASE_TAG)
        assertEquals("Импорт модуля", again.LOG_MESSAGE)
        assertEquals(false, again.CHECKOUT_AFTER_IMPORT)
        assertEquals("jar -kb;", again.KEYWORD_SUBSTITUTION_WRAPPERS)
        assertEquals(true, again.MAKE_NEW_FILES_READ_ONLY)
    }

    @Test
    @DisplayName("SSH passwords saved earlier are kept")
    fun sshPasswordsSavedEarlierAreKept() {
        val root = ":ssh:alice@cvs.example.test:/cvs"
        val scrambler = PServerPasswordScrambler.getInstance()
        val saved = Element("component").setAttribute("name", "SSHPasswordProvider")
            .addContent(Element("passwords").addContent(password(root, scrambler.scramble("secret"))))
            .addContent(Element("ppkpasswords").addContent(password(root, scrambler.scramble("key-phrase"))))

        val loaded = persistent(SSHPasswordProviderImpl())
        loaded.loadState(saved)
        val provider = loaded as SSHPasswordProviderImpl

        assertEquals("secret", provider.getPasswordForCvsRoot(root))
        assertEquals("key-phrase", provider.getPPKPasswordForCvsRoot(root))

        val state = checkNotNull(loaded.state)
        assertEquals(scrambler.scramble("secret"), state.getChild("passwords")?.getChild("password")?.getAttributeValue("PASSWORD"))
        assertEquals(root, state.getChild("passwords")?.getChild("password")?.getAttributeValue("CVSROOT"))
        assertEquals(scrambler.scramble("key-phrase"), state.getChild("ppkpasswords")?.getChild("password")?.getAttributeValue("PASSWORD"))
        assertEquals(root, state.getChild("ppkpasswords")?.getChild("password")?.getAttributeValue("CVSROOT"))
    }

    @Test
    @DisplayName("Passwords kept only for the session are not saved")
    fun passwordsKeptOnlyForTheSessionAreNotSaved() {
        val root = ":ext:bob@cvs.example.test:/cvs"
        val provider = SSHPasswordProviderImpl()
        provider.storePasswordForCvsRoot(root, "session-only", false)

        assertEquals("session-only", provider.getPasswordForCvsRoot(root))
        val state = persistent(provider).state
        assertNull(state?.getChild("passwords"), "saved state: ${state?.let { com.intellij.openapi.util.JDOMUtil.write(it) }}")
    }

    private fun option(name: String, value: String) = Element("option").setAttribute("name", name).setAttribute("value", value)

    private fun password(root: String, scrambled: String) = Element("password").setAttribute("CVSROOT", root).setAttribute("PASSWORD", scrambled)

    @Suppress("UNCHECKED_CAST")
    private fun persistent(component: Any): PersistentStateComponent<Element> {
        assertTrue(component is PersistentStateComponent<*>, "${component.javaClass.name} is not a PersistentStateComponent")
        return component as PersistentStateComponent<Element>
    }

    /** The component keeps its state where earlier versions saved it: under [name] in other.xml. */
    private fun assertStoredLikeBefore(component: Any, name: String) {
        persistent(component)
        val state = component.javaClass.getAnnotation(State::class.java)
        assertEquals(name, state?.name, "@State name")
        assertEquals(listOf("other.xml"), state?.storages?.map { it.value }, "@State storages")
    }
}
