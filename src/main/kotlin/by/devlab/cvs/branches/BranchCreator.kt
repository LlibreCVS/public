package by.devlab.cvs.branches

import by.devlab.cvs.tagging.validateTagName
import com.intellij.cvsSupport2.config.CvsConfiguration
import com.intellij.cvsSupport2.cvshandlers.CommandCvsHandler
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.vcsUtil.VcsUtil
import java.nio.file.Path

/**
 * Creates a branch in roots (design decisions 5, 10, 11, 14): asks the name and the switch option, creates the branch
 * in each root with OpenCVS's branch handler (never overriding an existing branch), adds it to the root's remembered
 * list without asking the server, switches the root when asked, and reports in one notification. A switch to the new
 * branch is not confirmed: the branch starts at the revisions the working copy holds (decision 11).
 */
class BranchCreator(private val project: Project, private val prompts: BranchPrompts = BranchPrompts.UI) {

    /** Asks for the branch and creates it in [roots] on the calling thread (never the UI thread); null when cancelled. */
    fun create(roots: List<Path>): BranchReport? {
        val request = prompts.askNewBranch(project, roots.map(BranchSwitcher::rootName)) ?: return null
        val name = request.name.trim()
        val invalid = validateTagName(name)
        if (invalid != null) {
            val report = BranchReport("New branch $name", emptyList(), emptyList(), emptyList(), roots.map { BranchSwitcher.rootName(it) to invalid })
            CvsRunner.notify(project, report)
            return report
        }
        val report = create(roots, NewBranchRequest(name, request.switch))
        CvsRunner.notify(project, report)
        return report
    }

    /** Creates [request]'s branch in each of [roots], one after another; a failure in one root does not stop the others. */
    private fun create(roots: List<Path>, request: NewBranchRequest): BranchReport {
        val lists = BranchListService.getInstance(project)
        val switcher = BranchSwitcher(project, prompts)
        val readOnly = CvsConfiguration.getInstance(project).MAKE_NEW_FILES_READONLY
        val created = ArrayList<String>()
        val conflicts = ArrayList<String>()
        val failed = ArrayList<Pair<String, String>>()
        for (root in roots) {
            val name = BranchSwitcher.rootName(root)
            val files = arrayOf(VcsUtil.getFilePath(root.toFile(), true))
            // The tag and the switch run as two handlers, so that a branch made before a failed switch still enters the
            // list and the report says which step failed (design.md, Risks).
            val error = try {
                CvsRunner.run(project, CommandCvsHandler.createBranchHandler(files, request.name, false, false, readOnly, project))
            } catch (e: Exception) {
                e.message ?: e.toString()
            }
            if (error != null) {
                CvsRunner.refresh(project, root)
                failed += name to error
                continue
            }
            lists.add(root, request.name)
            if (request.switch) {
                val result = switcher.update(root, SwitchTarget.Branch(request.name))
                if (result.error != null) {
                    failed += name to "the branch was created, the switch failed: ${result.error}"
                    continue
                }
                conflicts += result.conflicts
            } else {
                CvsRunner.refresh(project, root)
            }
            created += name
        }
        return BranchReport(
            "New branch ${request.name}", created, emptyList(), conflicts, failed,
            switchedLabel = if (request.switch) "Created and switched" else "Created",
        )
    }

    /** Creates a branch in [roots] in a background task (called from the popup on the UI thread). */
    fun start(roots: List<Path>) {
        object : Task.Backgroundable(project, "Creating a CVS branch", false) {
            override fun run(indicator: ProgressIndicator) {
                create(roots)
            }
        }.queue()
    }
}
