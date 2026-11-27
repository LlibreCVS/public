package by.devlab.cvs.update

import by.devlab.cvs.branches.BranchKind
import by.devlab.cvs.branches.BranchPrompts
import by.devlab.cvs.branches.BranchStateService
import by.devlab.cvs.branches.BranchSwitcher
import by.devlab.cvs.branches.SwitchTarget
import by.devlab.cvs.tagging.PutOnTagAction
import com.intellij.cvsSupport2.CvsVcs2
import com.intellij.cvsSupport2.changeBrowser.CvsChangeList
import com.intellij.cvsSupport2.connections.CvsRootParser
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.VcsDataKeys
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Asks what to update to [cutoff] (design decision 13): the scope, or null when the user cancels. [roots] are the
 * project's CVS working-copy roots a folder must lie in.
 */
fun interface UpdateScopeSource {
    fun ask(project: Project, cutoff: ChangesetCutoff, roots: List<Path>): UpdateScope?
}

/** The real source: the scope dialog. */
private val DIALOG = UpdateScopeSource { project, cutoff, roots ->
    val dialog = UpdateToChangesetDialog(project, cutoff, roots)
    if (dialog.showAndGet()) dialog.scope else null
}

/**
 * "Update to This Changeset..." in the Repository view (design decisions 2, 4, 6, 9, 12, 16): updates the project's
 * roots on the changeset's repository, or one folder, to the trunk as of the changeset's last file revision.
 */
class UpdateToChangesetAction(
    private val scopes: UpdateScopeSource = DIALOG,
    private val prompts: BranchPrompts = BranchPrompts.UI,
) : DumbAwareAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    /** Enabled for one trunk changeset while no root is on a branch or a tag by the cached states (decision 12). */
    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isVisible = project != null
        e.presentation.isEnabled = project != null && selected(e) != null && BranchStateService.getInstance(project).roots()
            .none { it.status.state.kind == BranchKind.BRANCH || it.status.state.kind == BranchKind.TAG }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val changeset = selected(e) ?: return
        val cutoff = ChangesetCutoff.of(changeset)
        val roots = cvsRoots(project)
        val paths = when (val scope = scopes.ask(project, cutoff, roots) ?: return) {
            UpdateScope.WholeProject -> roots.filter { sameRepository(it, changeset) }
            is UpdateScope.Folder -> listOf(scope.path)
        }
        BranchSwitcher(project, prompts).start(SwitchTarget.Date(cutoff), paths)
    }

    /** The one selected CVS changeset, if every file revision of it is on the trunk (as for Put on Tag). */
    private fun selected(e: AnActionEvent): CvsChangeList? {
        val changeset = e.getData(VcsDataKeys.CHANGE_LISTS)?.singleOrNull() as? CvsChangeList ?: return null
        return changeset.takeIf { it.fileRevisions.isNotEmpty() && it.fileRevisions.all { revision -> PutOnTagAction.isTrunk(revision.number()) } }
    }

    private fun cvsRoots(project: Project): List<Path> =
        ProjectLevelVcsManager.getInstance(project).getRootsUnderVcs(CvsVcs2.getInstance(project)).map { it.toNioPath() }

    companion object {
        /**
         * Whether the working-copy [root] uses the changeset's repository (decision 16): the same method, host, port
         * and repository path in its `CVS/Root`; the user may differ.
         */
        fun sameRepository(root: Path, changeset: CvsChangeList): Boolean {
            val own = try {
                Files.readString(root.resolve("CVS").resolve("Root")).trim()
            } catch (_: IOException) {
                return false
            }
            return key(own) == key(changeset.environment.cvsRootAsString)
        }

        private fun key(cvsRoot: String): List<String?> = runCatching {
            val parsed = CvsRootParser.valueOf(cvsRoot, false)
            listOf(parsed.METHOD?.name, parsed.HOST, parsed.PORT, parsed.REPOSITORY?.trimEnd('/'))
        }.getOrElse { listOf(cvsRoot) }
    }
}
