package by.devlab.cvs

import com.intellij.cvsSupport2.javacvsImpl.FileReadOnlyHandler
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.tempPathFixture
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Files

/** Capability cvs-operations, requirement "Received files get their read-only state cheaply". */
@TestApplication
class FileReadOnlyHandlerTest {

    private val directory = tempPathFixture()

    @Test
    @DisplayName("A writable file is made read-only")
    fun aWritableFileIsMadeReadOnly() {
        val file = Files.writeString(directory.get().resolve("writable.txt"), "text").toFile()
        assertTrue(file.canWrite())

        FileReadOnlyHandler().setFileReadOnly(file, true)

        assertFalse(file.canWrite())
        file.setWritable(true)
    }

    @Test
    @DisplayName("A read-only file is made writable")
    fun aReadOnlyFileIsMadeWritable() {
        val file = Files.writeString(directory.get().resolve("read-only.txt"), "text").toFile()
        file.setWritable(false)
        assertFalse(file.canWrite())

        FileReadOnlyHandler().setFileReadOnly(file, false)

        assertTrue(file.canWrite())
    }

    @Test
    @DisplayName("A file already in the requested state is left alone")
    fun aFileAlreadyInTheRequestedStateIsLeftAlone() {
        val writable = Files.writeString(directory.get().resolve("stays-writable.txt"), "text").toFile()
        val readOnly = Files.writeString(directory.get().resolve("stays-read-only.txt"), "text").toFile()
        readOnly.setWritable(false)

        FileReadOnlyHandler().setFileReadOnly(writable, false)
        FileReadOnlyHandler().setFileReadOnly(readOnly, true)

        assertTrue(writable.canWrite())
        assertFalse(readOnly.canWrite())
        readOnly.setWritable(true)
    }
}
