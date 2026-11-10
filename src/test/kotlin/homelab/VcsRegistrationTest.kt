package homelab

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginDescriptor
import com.intellij.openapi.extensions.PluginId
import com.intellij.testFramework.junit5.TestApplication
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Capability vcs-registration: the plugin as the IDE loaded it.
 */
@TestApplication
class VcsRegistrationTest {

    @Test
    @DisplayName("Plugin is loaded with its identity")
    fun pluginIsLoadedWithItsIdentity() {
        val plugin = loadedPlugin()

        assertEquals("intellijcvs", plugin.pluginId.idString)
        assertEquals("IntelliJCVS", plugin.name)
        assertEquals("Home Laboratory", plugin.vendor)
    }

    @Test
    @DisplayName("Compatibility starts at build 262 and has no upper bound")
    fun compatibilityStartsAtBuild262AndHasNoUpperBound() {
        val plugin = loadedPlugin()

        assertEquals("262", plugin.sinceBuild)
        assertNull(plugin.untilBuild)
    }

    private fun loadedPlugin(): PluginDescriptor {
        val id = PluginId.getId("intellijcvs")
        // getPlugin also returns installed plugins that are disabled; isLoaded proves the IDE loaded it.
        check(PluginManagerCore.isLoaded(id)) { "the IDE did not load plugin intellijcvs" }
        return checkNotNull(PluginManagerCore.getPlugin(id))
    }
}
