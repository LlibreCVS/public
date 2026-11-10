package by.devlab.vcs

import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.AbstractVcs
import com.intellij.openapi.vcs.VcsKey

/**
 * The CVS version control system. It offers no CVS operations yet; later changes add its providers.
 */
class CvsVcs(project: Project) : AbstractVcs(project, NAME) {

    override fun getDisplayName(): String = NAME

    companion object {
        const val NAME: String = "CVS"

        /** The only way to obtain the key: the VcsKey constructor is internal API. */
        @JvmField
        val KEY: VcsKey = createKey(NAME)
    }
}
