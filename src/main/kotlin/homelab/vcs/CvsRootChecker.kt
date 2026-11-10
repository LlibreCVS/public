package homelab.vcs

import com.intellij.openapi.vcs.VcsKey
import com.intellij.openapi.vcs.VcsRootChecker
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Path

/**
 * Tells the IDE's root detection which directories are CVS working-copy roots.
 *
 * The CVS admin files are read with java.nio, never through the VFS: the scanner passes transient files on
 * purpose, and VFS reads would load every CVS/ directory into the persistent VFS or return stale content.
 */
class CvsRootChecker : VcsRootChecker() {

    override fun getSupportedVcs(): VcsKey = CvsVcs.KEY

    // Never override the deprecated isRoot(String): with neither overridden, the defaults call each other.
    override fun isRoot(file: VirtualFile): Boolean = file.localPath()?.let(CvsWorkingCopy::isRoot) ?: false

    private fun VirtualFile.localPath(): Path? =
        try {
            toNioPath()
        } catch (_: UnsupportedOperationException) {
            null
        }
}
