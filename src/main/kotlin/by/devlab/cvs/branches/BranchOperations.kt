package by.devlab.cvs.branches

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The one branch operation (switch, Back to Trunk, New Branch…) a project runs at a time (design decision 21): two
 * `cvs update` runs over one working copy would write `CVS/Entries` and `CVS/Tag` at the same time.
 */
@Service(Service.Level.PROJECT)
class BranchOperations(private val project: Project) {

    private val running = AtomicBoolean()

    /** Whether a branch operation is running. */
    val isRunning: Boolean get() = running.get()

    /** Marks an operation as running; false when one already runs. */
    fun tryStart(): Boolean = running.compareAndSet(false, true)

    /** Marks the running operation as finished. */
    fun finish() {
        running.set(false)
    }

    companion object {
        const val IN_PROGRESS = "CVS branch operation in progress…"

        fun getInstance(project: Project): BranchOperations = project.service()
    }
}
