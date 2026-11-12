package by.devlab.cvs.tagging

import com.intellij.cvsSupport2.changeBrowser.CvsChangeList
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vcs.VcsDataKeys

/** What the user asked for in the tag dialog. */
data class TagRequest(val tag: String, val move: Boolean)

/** Asks the user which tag to put on a changeset; null when the user cancels (design decision 22). */
fun interface TagRequestSource {
    fun ask(project: Project, changeset: CvsChangeList): TagRequest?
}

/** The real source: the Put on Tag dialog. */
private val DIALOG = TagRequestSource { project, changeset ->
    val dialog = PutOnTagDialog(project, changeset.fileRevisions.map { it.path() }.distinct())
    if (dialog.showAndGet()) TagRequest(dialog.tagName, dialog.moveExisting) else null
}

/**
 * "Put on Tag..." in the Repository view: tags, in the repository, the latest trunk revisions of the files of
 * one committed CVS trunk changeset and reports the result (design decisions 4, 11, 12, 18, 20).
 */
class PutOnTagAction(private val requests: TagRequestSource = DIALOG) : DumbAwareAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && selected(e) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val changeset = selected(e) ?: return
        val request = requests.ask(project, changeset) ?: return
        object : Task.Backgroundable(project, "Putting tag ${request.tag}", false) {
            override fun run(indicator: ProgressIndicator) {
                val result = try {
                    ChangesetTagger(project).tag(changeset, request.tag, request.move)
                } catch (e: ProcessCanceledException) {
                    throw e
                } catch (e: Exception) {
                    TagResult(request.tag, 0, emptyList(), emptyList(), e.message ?: e.toString())
                }
                notify(project, result)
            }
        }.queue()
    }

    /** The one selected CVS changeset, if it was committed on the trunk. */
    private fun selected(e: AnActionEvent): CvsChangeList? {
        val changeset = e.getData(VcsDataKeys.CHANGE_LISTS)?.singleOrNull() as? CvsChangeList ?: return null
        return changeset.takeIf { it.fileRevisions.isNotEmpty() && it.fileRevisions.all { revision -> isTrunk(revision.number()) } }
    }

    private fun notify(project: Project, result: TagResult) {
        val kept = if (result.keptRemoved > 0) "\n${files(result.keptRemoved)} removed on the trunk kept their tags" else ""
        val (type, title, content) = when {
            result.error != null ->
                Triple(NotificationType.ERROR, "Tag ${result.tag} was not set", result.error)
            result.unchangedMessages.isNotEmpty() ->
                Triple(
                    NotificationType.WARNING,
                    "Tag ${result.tag}: the server left files unchanged",
                    // The count is of files that did get the tag; the warning never says the tag is set.
                    "${files(result.taggedCount)} tagged.$kept\n" + result.unchangedMessages.joinToString("\n"),
                )
            else ->
                Triple(NotificationType.INFORMATION, "Put on Tag", "Tag ${result.tag} set on ${files(result.taggedCount)}$kept")
        }
        NotificationGroupManager.getInstance().getNotificationGroup(GROUP)
            .createNotification(title, StringUtil.escapeXmlEntities(content).replace("\n", "<br>"), type)
            .notify(project)
    }

    private fun files(count: Int) = "$count ${if (count == 1) "file" else "files"}"

    companion object {
        const val GROUP = "IntelliJCVS"

        /**
         * A trunk revision has a two-part number; an import on the vendor branch 1.1.1 (`1.1.1.x`) also counts as
         * the trunk. Upstream's `getBranch()` is not used: it also names a branch for a trunk revision that later
         * became a branch point (design decision 18).
         */
        fun isTrunk(number: String): Boolean {
            val parts = number.split('.')
            return parts.size == 2 || (parts.size == 4 && parts.take(3) == listOf("1", "1", "1"))
        }
    }
}
