package by.devlab.vcs

import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE
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
     * Makes [dir] a CVS working directory and returns it: CVS/Root holds [root], CVS/Repository holds
     * [repository] (encoded in [charset]), each followed by [lineEnding], and CVS/Entries is empty.
     */
    fun write(
        dir: Path,
        root: String,
        repository: String = "module/${dir.fileName}",
        lineEnding: String = "\n",
        charset: Charset = Charsets.UTF_8,
    ): Path {
        val admin = Files.createDirectories(dir.resolve("CVS"))
        Files.writeString(admin.resolve("Root"), root + lineEnding)
        Files.write(admin.resolve("Repository"), (repository + lineEnding).toByteArray(charset))
        Files.write(admin.resolve("Entries"), ByteArray(0))
        return dir
    }

    /** Lists [child] as a directory in the CVS/Entries of [parent], with its name encoded in [charset]. */
    fun track(parent: Path, child: Path, charset: Charset = Charsets.UTF_8): Path {
        append(parent.resolve("CVS/Entries"), "D/${child.fileName}////\n", charset)
        return child
    }

    /** Writes [content] (e.g. `TBR_RELEASE`, `NRELEASE_1`, `D2026.11.01.00.00.00`) as the CVS/Tag of [dir]. */
    fun tag(dir: Path, content: String): Path {
        Files.writeString(dir.resolve("CVS/Tag"), content + "\n")
        return dir
    }

    /**
     * Lists file [name] in the CVS/Entries of [dir] at revision [revision] with the sticky field [sticky] (`T<tag>`,
     * `D<date>` or empty), the line encoded in [charset]. The file itself is not written.
     */
    fun fileEntry(dir: Path, name: String, sticky: String = "", revision: String = "1.1", charset: Charset = Charsets.UTF_8) {
        append(dir.resolve("CVS/Entries"), "/$name/$revision/Thu Nov 12 10:00:00 2026//$sticky\n", charset)
    }

    /** Appends [line] (without its line ending) to the CVS/Entries.Log of [parent]. */
    fun logEntry(parent: Path, line: String, charset: Charset = Charsets.UTF_8) {
        append(parent.resolve("CVS/Entries.Log"), "$line\n", charset)
    }

    private fun append(file: Path, text: String, charset: Charset) {
        Files.write(file, text.toByteArray(charset), CREATE, APPEND)
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
