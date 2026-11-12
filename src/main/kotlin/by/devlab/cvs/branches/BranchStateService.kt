package by.devlab.cvs.branches

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.messages.Topic
import java.nio.file.Path

/** Told when the cached branch state of a project's CVS roots changed. */
fun interface BranchStateListener {
    fun stateChanged()
}

/**
 * The branch state of every CVS working-copy root of a project, cached and refreshed in the background (design
 * decisions 5, 8, 11, 16, 17, 19). The widgets and the commit window only read it.
 */
@Service(Service.Level.PROJECT)
class BranchStateService(private val project: Project) : Disposable {

    /** One working-copy root: its directory, the name shown for it (the directory's name) and its status. */
    data class Root(val path: Path, val name: String, val status: RootStatus)

    /** What the indicator shows: [text], [tooltip] (lines separated by `\n`) and the [state] of the shown root. */
    data class Indicator(val text: String, val tooltip: String, val state: BranchState)

    /** The cached roots, sorted by path; empty in a project without CVS roots. */
    fun roots(): List<Root> = TODO()

    /** The cached root that holds [file], the innermost one when roots are nested, or null. */
    fun rootFor(file: VirtualFile): Root? = TODO()

    /** The indicator for the cached roots with [activeFile] in the active editor, or null without CVS roots. */
    fun indicator(activeFile: VirtualFile? = selectedFile()): Indicator? = TODO()

    /** Schedules finding the roots again and recomputing all of them. */
    fun refresh(): Unit = TODO()

    /** Waits until no recomputation is pending or running (test hook, design decision 16). */
    fun awaitRefresh(): Unit = TODO()

    private fun selectedFile(): VirtualFile? = null

    override fun dispose() {}

    companion object {
        @Topic.ProjectLevel
        val TOPIC: Topic<BranchStateListener> = Topic(BranchStateListener::class.java, Topic.BroadcastDirection.NONE)

        fun getInstance(project: Project): BranchStateService = project.service()
    }
}
