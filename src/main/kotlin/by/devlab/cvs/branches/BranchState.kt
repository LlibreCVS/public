package by.devlab.cvs.branches

import java.io.IOException
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

/** Reading the sticky information CVS keeps in a directory's administrative files. */
internal object StickyTag {

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
