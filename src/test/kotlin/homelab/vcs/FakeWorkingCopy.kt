package homelab.vcs

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.io.path.isDirectory
import kotlin.io.path.readBytes
import kotlin.io.path.relativeTo

/**
 * Writes fake CVS working copies (only their administrative files) with java.nio, and snapshots trees so that
 * tests can prove nothing was changed. No CVS server or repository is involved.
 */
object FakeWorkingCopy {

    /**
     * Makes [dir] a CVS working directory of [root] (the exact content of the first line of CVS/Root, written
     * with [lineEnding]) and returns it.
     */
    fun write(dir: Path, root: String, lineEnding: String = "\n"): Path {
        val admin = Files.createDirectories(dir.resolve("CVS"))
        Files.writeString(admin.resolve("Root"), root + lineEnding)
        Files.writeString(admin.resolve("Repository"), "module/${dir.fileName}$lineEnding")
        Files.writeString(admin.resolve("Entries"), "D$lineEnding")
        return dir
    }

    /** Writes [content] as the whole CVS/Root file of [dir], creating CVS/ when needed. */
    fun writeRootFile(dir: Path, content: String): Path {
        val admin = Files.createDirectories(dir.resolve("CVS"))
        Files.writeString(admin.resolve("Root"), content)
        return dir
    }

    /** One file or directory of a snapshot. */
    data class Entry(val directory: Boolean, val content: String?, val modified: FileTime)

    /** Every file and directory below [top] with its content and modification time, keyed by relative path. */
    fun snapshot(top: Path): Map<String, Entry> =
        Files.walk(top).use { paths ->
            paths.toList().associate { path ->
                val directory = path.isDirectory()
                path.relativeTo(top).toString() to Entry(
                    directory = directory,
                    content = if (directory) null else path.readBytes().decodeToString(),
                    modified = Files.getLastModifiedTime(path),
                )
            }
        }
}
