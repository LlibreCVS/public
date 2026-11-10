package homelab.vcs

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * Reads the CVS administrative files of a working directory with java.nio, so that the answer reflects the
 * current disk state and nothing is loaded into the VFS. An I/O problem counts as "no usable CVS/Root"; nothing
 * here throws.
 */
object CvsWorkingCopy {

    /** Whether [dir] has a CVS/Root regular file. One stat, no read. */
    fun hasRootFile(dir: Path): Boolean = Files.isRegularFile(rootFile(dir))

    /**
     * The repository that CVS/Root of [dir] names: its first line without surrounding whitespace, or null when
     * the file is missing, unreadable, or its first line is blank.
     */
    fun repository(dir: Path): String? {
        val file = rootFile(dir)
        if (!Files.isRegularFile(file)) return null
        val firstLine = try {
            // ISO-8859-1 maps every byte, so no content is "malformed"; only equality of lines matters.
            Files.newBufferedReader(file, StandardCharsets.ISO_8859_1).use { it.readLine() }
        } catch (_: IOException) {
            return null
        }
        return firstLine?.trim()?.takeIf { it.isNotEmpty() }
    }

    /**
     * Whether [dir] is the top directory of a working copy: it names a repository, and its parent names none or
     * a different one.
     */
    fun isRoot(dir: Path): Boolean {
        val repository = repository(dir) ?: return false
        val parent = dir.parent ?: return true
        return repository(parent) != repository
    }

    private fun rootFile(dir: Path): Path = dir.resolve("CVS").resolve("Root")
}
