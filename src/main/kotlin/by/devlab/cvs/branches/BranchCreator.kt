package by.devlab.cvs.branches

import by.devlab.cvs.tagging.validateTagName
import com.intellij.cvsSupport2.config.CvsConfiguration
import com.intellij.cvsSupport2.cvshandlers.CommandCvsHandler
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.project.Project
import com.intellij.vcsUtil.VcsUtil
import java.nio.file.Path

/**
 * Creates a branch in roots (design decisions 5, 10, 11, 14): asks the name and the switch option, creates the branch
 * in each root with OpenCVS's branch handler, adds it to the root's remembered list without asking the server, switches
 * the root when asked, and reports in one notification. A switch to the new branch is not confirmed: the branch starts
 * at the revisions the working copy holds (decision 11). A name already in some roots' lists is decided by the user
 * before any change: use it, recreate it (after a second confirmation) or cancel (decision 10).
 */
class BranchCreator(private val project: Project, private val prompts: BranchPrompts = BranchPrompts.UI) {

    /** Asks for the branch and creates it in [roots] on the calling thread (never the UI thread); null when cancelled. */
    fun create(roots: List<Path>): BranchReport? {
        val request = prompts.askNewBranch(project, roots.map(BranchSwitcher::rootName)) ?: return null
        val name = request.name.trim()
        val invalid = validateTagName(name)
        val report = if (invalid != null) {
            BranchReport("New branch $name", emptyList(), emptyList(), emptyList(), roots.map { BranchSwitcher.rootName(it) to invalid })
        } else {
            create(roots, NewBranchRequest(name, request.switch)) ?: return null
        }
        CvsRunner.notify(project, report)
        return report
    }

    /**
     * Creates [request]'s branch in each of [roots], one after another; a failure in one root does not stop the others.
     * Null when the user cancelled at a question, before any change.
     */
    private fun create(roots: List<Path>, request: NewBranchRequest): BranchReport? {
        val branch = request.name
        val lists = BranchListService.getInstance(project)
        val switcher = BranchSwitcher(project, prompts)
        val existing = roots.filter { lists.list(it)?.branches?.contains(branch) == true }
        var recreate = false
        var partialSkipped = emptySet<Path>()
        if (existing.isNotEmpty()) {
            when (prompts.askExistingBranch(project, branch, existing.map(BranchSwitcher::rootName))) {
                ExistingBranchChoice.CANCEL -> return null
                ExistingBranchChoice.RECREATE -> {
                    if (!prompts.confirmRecreate(project, branch, recreateWarning(branch, existing))) return null
                    recreate = true
                }
                ExistingBranchChoice.USE -> if (request.switch) {
                    // These roots switch to a branch that already has its own revisions: a partial branch is confirmed
                    // first, then the uncommitted changes (decisions 8, 11, 20).
                    val steps = switcher.confirmPartial(branch, existing.map { BranchSwitcher.Step(it, BranchSwitcher.rootName(it), null) })
                        ?: return null
                    partialSkipped = steps.filter { it.skip != null }.mapTo(HashSet()) { it.root }
                    val changes = switcher.uncommittedChanges(steps.filter { it.skip == null })
                    if (changes.isNotEmpty() && !prompts.confirmSwitch(project, branch, changes)) return null
                }
            }
        }

        val readOnly = CvsConfiguration.getInstance(project).MAKE_NEW_FILES_READONLY
        val given = ArrayList<String>()
        val switchedExisting = ArrayList<String>()
        val skipped = ArrayList<Pair<String, String>>()
        val conflicts = ArrayList<String>()
        val failed = ArrayList<Pair<String, String>>()
        for (root in roots) {
            val name = BranchSwitcher.rootName(root)
            val use = root in existing && !recreate
            if (use && root in partialSkipped) {
                skipped += name to BranchSwitcher.PARTIAL
                continue
            }
            if (use && !request.switch) {
                skipped += name to "already has $branch"
                continue
            }
            if (!use) {
                // Override (`tag -b -F -B`) only for a branch the user chose to recreate; never automatically, even
                // when CVS answers that the branch exists because the list was stale (decision 10).
                val error = tag(root, branch, override = root in existing, readOnly)
                if (error != null) {
                    CvsRunner.refresh(project, root)
                    failed += name to error
                    continue
                }
                lists.add(root, branch)
                // A recreated branch is on every file of the root now: its old counts no longer hold (decision 24).
                if (root in existing) lists.forget(root, branch)
            }
            if (request.switch) {
                // The tag and the switch run as two handlers, so that a branch made before a failed switch still enters
                // the list and the report says which step failed (design.md, Risks).
                val result = switcher.update(root, SwitchTarget.Branch(branch))
                if (result.error != null) {
                    failed += name to if (use) result.error else "the branch was created, the switch failed: ${result.error}"
                    continue
                }
                conflicts += result.conflicts
            } else {
                CvsRunner.refresh(project, root)
            }
            if (use) switchedExisting += name else given += name
        }
        val label = when {
            recreate && request.switch -> "Created or recreated and switched"
            recreate -> "Created or recreated"
            request.switch -> "Created and switched"
            else -> "Created"
        }
        val notes = if (switchedExisting.isEmpty()) emptyList() else listOf("Switched to the existing branch: ${switchedExisting.joinToString(", ")}")
        return BranchReport("New branch $branch", given, skipped, conflicts, failed, switchedLabel = label, notes = notes)
    }

    /**
     * Runs `tag -b` (with [override], `tag -b -F -B`) over [root]; returns the CVS error, or the "NOT MOVING" warning cvs
     * 1.11 prints instead of an error when the branch already exists (design decision 10).
     */
    private fun tag(root: Path, branch: String, override: Boolean, readOnly: Boolean): String? {
        val files = arrayOf(VcsUtil.getFilePath(root.toFile(), true))
        val notMoved = ArrayList<String>()
        val error = try {
            CvsRunner.run(project, CommandCvsHandler.createBranchHandler(files, branch, false, override, readOnly, project)) { message ->
                if (NOT_MOVING.containsMatchIn(message)) notMoved += message.trim()
            }
        } catch (e: Exception) {
            // A cancel or another control-flow exception ends the operation; it is not a root's failure (decision 22).
            if (e is ControlFlowException) throw e
            e.message ?: e.toString()
        }
        return error ?: notMoved.firstOrNull()?.let { "$branch already exists in the repository (${it})" }
    }

    /**
     * Creates a branch in [roots] in a background task (called from the popup on the UI thread), unless another branch
     * operation runs (design decision 21).
     */
    fun start(roots: List<Path>) {
        BranchOperations.getInstance(project).queue("Creating a CVS branch") { create(roots) }
    }

    private companion object {
        /** cvs 1.11 refusing to move an existing tag: `W file : BR already exists on branch 1.1.0.2 : NOT MOVING tag ...`. */
        val NOT_MOVING = Regex("""already exists on (?:version|branch) \S+ : NOT MOVING tag|Not moving branch tag""")

        fun recreateWarning(branch: String, roots: List<Path>): String =
            "Recreating moves branch $branch in the repository, for the whole team, to the revisions these roots hold:\n" +
                roots.joinToString("\n") { BranchSwitcher.rootName(it) } +
                "\n\nCommits already made on $branch stay in the repository but lose the branch name."
    }
}
