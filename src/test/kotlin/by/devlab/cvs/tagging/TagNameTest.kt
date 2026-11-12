package by.devlab.cvs.tagging

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.runInEdtAndGet
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** Capability changeset-tagging, requirement "The tag dialog accepts only valid tag names" (design decisions 5, 10). */
@TestApplication
class TagNameTest {

    @Test
    @DisplayName("Valid tag name accepted")
    fun validTagNameAccepted() {
        assertNull(validateTagName("TEST_2-1"))
        // Surrounding spaces are trimmed, not rejected (review focus).
        assertNull(validateTagName(" TEST "))

        val move = runInEdtAndGet {
            val dialog = PutOnTagDialog(null, listOf("module/one.txt"))
            try {
                dialog.moveExisting
            } finally {
                Disposer.dispose(dialog.disposable)
            }
        }
        assertTrue(move, "Move tag if it already exists is checked by default")
    }

    @Test
    @DisplayName("Invalid tag name rejected")
    fun invalidTagNameRejected() {
        for (name in listOf("", "   ", "1TEST", "TEST 2", "TEST.2", "ТЕСТ", "HEAD", "BASE")) {
            val reason = validateTagName(name)
            assertNotNull(reason, "'$name' accepted")
            assertTrue(reason!!.isNotBlank(), "no reason for '$name'")
        }
    }

    @Test
    @DisplayName("RCS file names map to the exact changeset path")
    fun rcsFileNamesMapToTheExactChangesetPath() {
        val paths = listOf("m/src/a.txt", "m/x/src/a.txt", "m/Attic/b.txt", "n/a.txt", "x/n/a.txt")

        // One changeset path may be a suffix of another.
        assertEquals("x/n/a.txt", changesetPath("/repo", "/repo/x/n/a.txt,v", paths))

        assertEquals("m/x/src/a.txt", changesetPath("/repo", "/repo/m/x/src/Attic/a.txt,v", paths))
        assertEquals("m/src/a.txt", changesetPath("/repo", "/repo/m/src/a.txt,v", paths))
        // Only the last directory named Attic is CVS's; a module directory may be called that too.
        assertEquals("m/Attic/b.txt", changesetPath("/repo", "/repo/m/Attic/b.txt,v", paths))
        assertNull(changesetPath("/repo", "/repo/other/a.txt,v", paths))
    }
}
