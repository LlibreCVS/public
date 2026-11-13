package by.devlab.cvs.branches

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic
import com.intellij.util.xmlb.annotations.Attribute
import com.intellij.util.xmlb.annotations.Tag
import com.intellij.util.xmlb.annotations.XCollection
import java.nio.file.Path

/** Told when a remembered branch list was loaded, changed or failed to load. */
fun interface BranchListListener {
    fun listsChanged()
}

/** The remembered branch list of every CVS root of a project (design decisions 4, 5, 13, 18). */
@Service(Service.Level.PROJECT)
@State(name = "IntelliJCVS.BranchLists", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class BranchListService(private val project: Project) : PersistentStateComponent<BranchListService.ListsState> {

    /** A root's remembered [branches], sorted, and when they were loaded from the server ([loadedAt], epoch millis). */
    data class BranchList(val branches: List<String>, val loadedAt: Long)

    /** The persistent state: one entry per root. */
    class ListsState {
        @get:XCollection(style = XCollection.Style.v2)
        var roots: MutableList<RootList> = ArrayList()
    }

    /** One root's list in the workspace file. */
    @Tag("root")
    class RootList {
        @get:Attribute
        var path: String = ""

        @get:Attribute
        var loadedAt: Long = 0

        @get:XCollection(style = XCollection.Style.v2, elementName = "branch", valueAttributeName = "name")
        var branches: MutableList<String> = ArrayList()
    }

    fun list(root: Path): BranchList? = throw NotImplementedError()

    fun isLoading(root: Path): Boolean = throw NotImplementedError()

    fun error(root: Path): String? = throw NotImplementedError()

    fun load(roots: Collection<Path>): Unit = throw NotImplementedError()

    fun add(root: Path, branch: String): Unit = throw NotImplementedError()

    fun awaitLoad(): Unit = throw NotImplementedError()

    internal fun set(root: Path, branches: Collection<String>, loadedAt: Long): Unit = throw NotImplementedError()

    override fun getState(): ListsState = throw NotImplementedError()

    override fun loadState(state: ListsState): Unit = throw NotImplementedError()

    companion object {
        @Topic.ProjectLevel
        val TOPIC: Topic<BranchListListener> = Topic(BranchListListener::class.java, Topic.BroadcastDirection.NONE)

        fun getInstance(project: Project): BranchListService = project.service()
    }
}
