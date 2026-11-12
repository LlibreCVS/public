package by.devlab.cvs.branches

import com.intellij.cvsSupport2.config.CvsApplicationLevelConfiguration
import com.intellij.openapi.application.ApplicationManager
import java.io.IOException
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/** What a CVS working-copy root is on, as its root directory's `CVS/Tag` records it (design decision 6). */
enum class BranchKind { TRUNK, BRANCH, TAG, DATE }

/**
 * The state of one CVS working-copy root: the trunk, a branch, a plain tag or a date. [name] is the branch or tag
 * name, or the date as the indicator shows it; it is empty on the trunk.
 */
data class BranchState(val kind: BranchKind, val name: String) {

    /** The indicator's text: `trunk`, the branch name, `<tag> (tag)` or `<date> (date)`. */
    val text: String
        get() = when (kind) {
            BranchKind.TRUNK -> "trunk"
            BranchKind.BRANCH -> name
            BranchKind.TAG -> "$name (tag)"
            BranchKind.DATE -> "$name (date)"
        }

    /** The tooltip line for this state; on a tag or a date it says that a commit is not possible there. */
    val tooltip: String
        get() = when (kind) {
            BranchKind.TRUNK -> "On the trunk"
            BranchKind.BRANCH -> "On branch $name"
            BranchKind.TAG -> "On tag $name: a commit is not possible on a tag"
            BranchKind.DATE -> "On date $name: a commit is not possible on a date"
        }

    companion object {
        val TRUNK = BranchState(BranchKind.TRUNK, "")

        /** Reads the state of the working-copy root [root] from its `CVS/Tag`. Reads only that file. */
        fun read(root: Path): BranchState = parse(StickyTag.readDirectoryTag(root))

        /** The state a `CVS/Tag` line records (`T<branch>`, `N<tag>`, `D<date>`); null or empty is the trunk. */
        fun parse(tagLine: String?): BranchState {
            val line = tagLine?.trim().orEmpty()
            if (line.length < 2) return TRUNK
            val value = line.substring(1)
            return when (line[0]) {
                'T' -> BranchState(BranchKind.BRANCH, value)
                'N' -> BranchState(BranchKind.TAG, value)
                'D' -> BranchState(BranchKind.DATE, formatDate(value))
                else -> TRUNK
            }
        }

        /** `2026.11.01.00.00.00` as `2026-11-01 00:00:00` (design decision 14); anything else as it is. */
        private fun formatDate(date: String): String {
            val parts = date.split('.')
            if (parts.size != 6 || parts.any { part -> part.isEmpty() || part.any { !it.isDigit() } }) return date
            return "${parts[0]}-${parts[1]}-${parts[2]} ${parts[3]}:${parts[4]}:${parts[5]}"
        }
    }
}

/**
 * A root's [state] with the number of its files on another branch ([mixedFiles], design decision 7); the ` *` mark
 * and the tooltip's count come from it.
 */
data class RootStatus(val state: BranchState, val mixedFiles: Int = 0) {

    /** The state's text, followed by ` *` when files are on another branch. */
    val text: String get() = if (mixedFiles > 0) "${state.text} *" else state.text

    /** The state's tooltip line, with the number of files on another branch when there are any. */
    val tooltip: String
        get() = when {
            mixedFiles == 1 -> "${state.tooltip}; 1 file is on another branch"
            mixedFiles > 1 -> "${state.tooltip}; $mixedFiles files are on another branch"
            else -> state.tooltip
        }
}

/**
 * Counts the files of a working copy whose sticky tag differs from its root's (design decisions 7, 15, 18). It reads
 * only `CVS/Entries`, `CVS/Entries.Log`, `CVS/Tag`, `CVS/Root` and `CVS/Repository` and walks only the
 * subdirectories the working copy tracks, so a nested checkout of its own never marks the outer root.
 */
object MixedScan {

    /** The number of files of the working copy [root] on another branch than the root directory. */
    fun count(root: Path): Int {
        val rootSticky = StickyTag.ofDirectoryTag(StickyTag.readDirectoryTag(root))
        var count = 0
        val pending = ArrayDeque(listOf(root))
        while (pending.isNotEmpty()) {
            val dir = pending.removeFirst()
            count += StickyTag.fileStickyFields(dir).count { StickyTag.ofEntry(it) != rootSticky }
            pending.addAll(TrackedDirectories.of(dir))
        }
        return count
    }
}

/** Reading the sticky information CVS keeps in a directory's administrative files. */
internal object StickyTag {

    /** A sticky tag or date without its one-letter prefix; null stands for the trunk (no sticky information). */
    data class Sticky(val date: Boolean, val value: String)

    /** The sticky information of a directory's `CVS/Tag` line: `T`/`N` name or `D` date (decision 18). */
    fun ofDirectoryTag(line: String?): Sticky? {
        val tag = line?.trim().orEmpty()
        if (tag.length < 2) return null
        return when (tag[0]) {
            'T', 'N' -> Sticky(false, tag.substring(1))
            'D' -> Sticky(true, tag.substring(1))
            else -> null
        }
    }

    /** The sticky information of a `CVS/Entries` sticky field: `T` name (branch, tag or revision) or `D` date. */
    fun ofEntry(field: String): Sticky? {
        if (field.length < 2) return null
        return when (field[0]) {
            'T' -> Sticky(false, field.substring(1))
            'D' -> Sticky(true, field.substring(1))
            else -> null
        }
    }

    /**
     * The sticky field of every file [dir]'s `CVS/Entries` lists, with the additions and removals of its
     * `CVS/Entries.Log` applied. The files are decoded as ISO-8859-1: only the ASCII sticky field is used, and file
     * names written in any encoding keep their bytes (windows-1251 entries, design risks).
     */
    fun fileStickyFields(dir: Path): Collection<String> {
        val files = LinkedHashMap<String, String>()
        for (line in adminLines(dir, "Entries")) {
            if (line.startsWith("/")) entryName(line)?.let { files[it] = stickyField(line) }
        }
        for (line in adminLines(dir, "Entries.Log")) {
            val entry = line.substring(2.coerceAtMost(line.length))
            if (!entry.startsWith("/")) continue
            val name = entryName(entry) ?: continue
            when {
                line.startsWith("A ") -> files[name] = stickyField(entry)
                line.startsWith("R ") -> files.remove(name)
            }
        }
        return files.values
    }

    private fun entryName(line: String): String? = line.split('/').getOrNull(1)?.takeIf { it.isNotEmpty() }

    private fun stickyField(line: String): String = line.split('/').getOrElse(5) { "" }.trim()

    private fun adminLines(dir: Path, name: String): List<String> {
        val file = dir.resolve("CVS").resolve(name)
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return emptyList()
        return try {
            Files.readString(file, Charsets.ISO_8859_1).split('\n').map { it.removeSuffix("\r") }
        } catch (_: IOException) {
            emptyList()
        }
    }

    /** The first line of [dir]'s `CVS/Tag`, or null when there is none or it cannot be read. */
    fun readDirectoryTag(dir: Path): String? {
        val file = dir.resolve("CVS").resolve("Tag")
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return null
        return try {
            // Tag names and dates are ASCII; ISO-8859-1 never fails to decode.
            Files.readString(file, Charsets.ISO_8859_1).lineSequence().firstOrNull()
        } catch (_: IOException) {
            null
        }
    }
}

/**
 * The subdirectories a working-copy directory tracks, found the way working-copy detection finds them
 * (`FindAllRootsHelper.isTrackedChild`): listed as `D/<name>/` in `CVS/Entries` or added in `CVS/Entries.Log`
 * (the last log line wins), a working-copy directory itself, on the same `CVS/Root`, and with a repository path that
 * continues the parent's. Names are matched in each usual CVS encoding (the CVS settings' encoding, UTF-8, the OS
 * encoding).
 */
internal object TrackedDirectories {

    /** The tracked subdirectories of the working-copy directory [dir], sorted by path. */
    fun of(dir: Path): List<Path> {
        if (!isWorkingCopyDirectory(dir)) return emptyList()
        val children = try {
            Files.newDirectoryStream(dir).use { stream ->
                stream.filter { it.fileName.toString() != "CVS" && Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }
            }
        } catch (_: IOException) {
            return emptyList()
        }
        if (children.isEmpty()) return emptyList()
        val listed = listedNames(dir)
        return children.filter { it.fileName.toString() in listed && continuesParent(dir, it) }.sortedBy { it.toString() }
    }

    /** Whether [dir] has the administrative files of a CVS working-copy directory. */
    fun isWorkingCopyDirectory(dir: Path): Boolean {
        val admin = dir.resolve("CVS")
        return listOf("Entries", "Root", "Repository").all { Files.isRegularFile(admin.resolve(it), LinkOption.NOFOLLOW_LINKS) }
    }

    /** Whether [dir] is a working-copy directory that its parent does not track: a root of its own. */
    fun isRoot(dir: Path): Boolean {
        if (!isWorkingCopyDirectory(dir)) return false
        val parent = dir.parent ?: return true
        return dir !in of(parent)
    }

    private fun listedNames(dir: Path): Set<String> {
        val entries = bytes(dir.resolve("CVS/Entries"))
        val log = bytes(dir.resolve("CVS/Entries.Log"))
        val names = HashSet<String>()
        for (charset in adminFileCharsets()) {
            val listed = LinkedHashSet<String>()
            for (line in lines(entries, charset)) directoryName(line)?.let { listed += it }
            for (line in lines(log, charset)) {
                val name = directoryName(line.substring(2.coerceAtMost(line.length))) ?: continue
                if (line.startsWith("A ")) listed += name
                if (line.startsWith("R ")) listed -= name
            }
            names += listed
        }
        return names
    }

    private fun continuesParent(parent: Path, child: Path): Boolean {
        if (!isWorkingCopyDirectory(child)) return false
        return try {
            val parentRoot = Files.readString(parent.resolve("CVS/Root"), Charsets.ISO_8859_1).trim()
            val childRoot = Files.readString(child.resolve("CVS/Root"), Charsets.ISO_8859_1).trim()
            if (parentRoot != childRoot) return false
            val parentRepository = bytes(parent.resolve("CVS/Repository"))
            val childRepository = bytes(child.resolve("CVS/Repository"))
            val name = child.fileName.toString()
            adminFileCharsets().any { charset ->
                String(childRepository, charset).trim() ==
                    String(parentRepository, charset).trim().trimEnd('/') + "/" + name
            }
        } catch (_: IOException) {
            false
        }
    }

    private fun directoryName(line: String): String? {
        if (!line.startsWith("D/")) return null
        return line.substring(2).substringBefore('/').takeIf { it.isNotEmpty() }
    }

    private fun lines(bytes: ByteArray, charset: Charset): List<String> = String(bytes, charset).split(Regex("\r?\n"))

    private fun bytes(file: Path): ByteArray =
        if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) Files.readAllBytes(file) else ByteArray(0)

    private fun adminFileCharsets(): Set<Charset> {
        val charsets = LinkedHashSet<Charset>()
        if (ApplicationManager.getApplication() != null) {
            val configured = CvsApplicationLevelConfiguration.getInstance().ENCODING
            if (configured != CvsApplicationLevelConfiguration.DEFAULT && Charset.isSupported(configured)) {
                charsets += Charset.forName(configured)
            }
        }
        charsets += Charsets.UTF_8
        System.getProperty("native.encoding")?.takeIf { Charset.isSupported(it) }?.let { charsets += Charset.forName(it) }
        return charsets
    }
}
