package by.devlab.cvs.branches

import by.devlab.cvs.TestCvsServer
import by.devlab.vcs.FakeWorkingCopy
import com.intellij.cvsSupport2.CvsVcs2
import com.intellij.cvsSupport2.config.CvsApplicationLevelConfiguration
import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.VcsDirectoryMapping
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import org.junit.jupiter.api.Assertions.assertTrue
import org.netbeans.lib.cvsclient.connection.PServerPasswordScrambler
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories

/** Shared setup of the branch-indicator tests: fake working copies mapped to CVS in a test project. */
object BranchFixtures {

    const val REPOSITORY = ":pserver:anonymous@cvs.example.invalid:/cvsroot"

    /** A fake working copy [name] under [parent] of module [name], on [tag] (a `CVS/Tag` line) or the trunk. */
    fun workingCopy(parent: Path, name: String, tag: String? = null): Path {
        val root = FakeWorkingCopy.write(parent.resolve(name).createDirectories(), REPOSITORY, name)
        if (tag != null) FakeWorkingCopy.tag(root, tag)
        return root
    }

    /** Replaces the CVS mappings of [project] with [dirs]. */
    fun mapToCvs(project: Project, vararg dirs: Path) {
        ProjectLevelVcsManager.getInstance(project).directoryMappings =
            dirs.map { VcsDirectoryMapping(virtualFile(it).path, CvsVcs2.getKey().name) }
    }

    /** The VFS file for [path], refreshed from disk first. */
    fun virtualFile(path: Path): VirtualFile =
        checkNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)) { "no VFS file for $path" }
            .also { it.refresh(false, it.isDirectory) }

    /** The project's branch state service after a full refresh. */
    fun refreshed(project: Project): BranchStateService =
        BranchStateService.getInstance(project).apply {
            refresh()
            awaitRefresh()
        }

    /** Points every `CVS/Root` of [root] at a closed local port, with a password for it so that no login is asked. */
    fun unreachable(root: Path) {
        val port = ServerSocket(0, 50, InetAddress.getLoopbackAddress()).use { it.localPort }
        val cvsRoot = ":pserver:alice@127.0.0.1:$port/cvsroot"
        Files.walk(root).use { paths ->
            paths.filter { it.fileName.toString() == "Root" && it.parent.fileName.toString() == "CVS" }
                .forEach { Files.writeString(it, "$cvsRoot\n") }
        }
        // OpenCVS reads CVS/Root through the VFS, so the IDE must see the new files.
        virtualFile(root).refresh(false, true)
        val scrambled = PServerPasswordScrambler.getInstance().scramble(TestCvsServer.PASSWORD)
        val passwordFile = Path.of(CvsApplicationLevelConfiguration.getInstance().PATH_TO_PASSWORD_FILE)
        Files.writeString(passwordFile, "$cvsRoot $scrambled\n", StandardOpenOption.APPEND)
    }

    /** Runs [action] and returns its result with the one IntelliJCVS notification it shows in [project]. */
    fun <T> notified(project: Project, action: () -> T): Pair<T, Notification> {
        val shown = LinkedBlockingQueue<Notification>()
        val disposable = Disposer.newDisposable()
        try {
            project.messageBus.connect(disposable).subscribe(Notifications.TOPIC, object : Notifications {
                override fun notify(notification: Notification) {
                    if (notification.groupId == "IntelliJCVS") shown += notification
                }
            })
            val result = action()
            val notification = checkNotNull(shown.poll(60, TimeUnit.SECONDS)) { "no IntelliJCVS notification" }
            assertTrue(shown.poll(1, TimeUnit.SECONDS) == null, "more than one notification")
            return result to notification
        } finally {
            Disposer.dispose(disposable)
        }
    }
}
