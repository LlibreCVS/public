package by.devlab.cvs.tagging

import com.intellij.cvsSupport2.changeBrowser.CvsChangeList
import com.intellij.openapi.project.Project

/**
 * The outcome of tagging a changeset: the tag, how many files got it, the files the server left as they were
 * (repository paths), the server's messages about files it left unchanged, and the server's error, if the
 * command failed.
 */
data class TagResult(
    val tag: String,
    val taggedCount: Int,
    val notMoved: List<String>,
    val unchangedMessages: List<String>,
    val error: String?,
)

/**
 * Tags the files of a committed changeset in the repository with one `rtag`, on their latest trunk revisions,
 * without a working copy (design decisions 3, 6, 7, 16).
 */
class ChangesetTagger(private val project: Project?) {

    fun tag(changeset: CvsChangeList, tag: String, move: Boolean): TagResult = TODO()
}
