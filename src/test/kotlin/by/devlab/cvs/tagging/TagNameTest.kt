package by.devlab.cvs.tagging

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.runInEdtAndGet
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
        for (name in listOf("", "   ", "1TEST", "TEST 2", "TEST.2", "ТЕСТ")) {
            val reason = validateTagName(name)
            assertNotNull(reason, "'$name' accepted")
            assertTrue(reason!!.isNotBlank(), "no reason for '$name'")
        }
    }
}
