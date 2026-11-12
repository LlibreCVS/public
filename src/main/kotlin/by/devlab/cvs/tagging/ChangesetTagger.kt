package by.devlab.cvs.tagging

import com.intellij.cvsSupport2.changeBrowser.CvsChangeList
import com.intellij.cvsSupport2.connections.CvsEnvironment
import com.intellij.cvsSupport2.connections.CvsRootProvider
import com.intellij.cvsSupport2.cvsExecution.CvsOperationExecutor
import com.intellij.cvsSupport2.cvsExecution.CvsOperationExecutorCallback
import com.intellij.cvsSupport2.cvshandlers.CommandCvsHandler
import com.intellij.cvsSupport2.cvsoperations.common.CvsExecutionEnvironment
import com.intellij.cvsSupport2.cvsoperations.common.LocalPathIndifferentOperation
import com.intellij.cvsSupport2.cvsoperations.common.LocalPathIndifferentOperationHelper
import com.intellij.cvsSupport2.cvsoperations.cvsMessages.CvsMessagesAdapter
import com.intellij.cvsSupport2.cvsoperations.cvsMessages.MessageEvent
import com.intellij.cvsSupport2.cvsoperations.cvsTagOrBranch.RtagCommand
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import org.netbeans.lib.cvsclient.IClientEnvironment
import org.netbeans.lib.cvsclient.IRequestProcessor
import org.netbeans.lib.cvsclient.command.AbstractCommand
import org.netbeans.lib.cvsclient.command.Command
import org.netbeans.lib.cvsclient.command.log.LogInformation
import org.netbeans.lib.cvsclient.command.log.LogMessageParser
import org.netbeans.lib.cvsclient.event.ICvsListenerRegistry
import org.netbeans.lib.cvsclient.event.IEventSender
import org.netbeans.lib.cvsclient.file.ICvsFileSystem
import org.netbeans.lib.cvsclient.progress.IProgressViewer
import org.netbeans.lib.cvsclient.progress.sending.DummyRequestsProgressHandler
import org.netbeans.lib.cvsclient.request.CommandRequest
import org.netbeans.lib.cvsclient.request.Requests
import java.io.File

/**
 * The outcome of tagging a changeset: the tag, how many files got it, the files the server left as they were
 * (repository paths), the server's messages about files it left unchanged, and the server's error, if the
 * command failed.
 */
data class TagResult(
    val tag: String,
    val taggedCount: Int,
    val notMoved: List<String>,
    val unchangedMessages: List<String>,
    val error: String?,
)

/**
 * Which of [paths] (repository-relative, e.g. `module/file.txt`) the RCS file [rcsFile] (e.g.
 * `/repository/module/Attic/file.txt,v`) of the repository [repository] is, or null if none.
 */
internal fun changesetPath(repository: String, rcsFile: String, paths: List<String>): String? {
    val name = rcsFile.replace('\\', '/').removeSuffix(",v").replace("/Attic/", "/")
    return paths.firstOrNull { name.endsWith("/$it") }
}

/**
 * Tags the files of a committed changeset in the repository with one `rtag`, on their latest trunk revisions,
 * without a working copy (design decisions 3, 6, 7, 16, 19, 20).
 */
class ChangesetTagger(private val project: Project?) {

    fun tag(changeset: CvsChangeList, tag: String, move: Boolean): TagResult {
        val paths = changeset.fileRevisions.map { it.path() }.distinct()

        // rtag would put the tag on the dead head of a file removed on the trunk; such files keep their tags
        // (design decisions 6, 23).
        val heads = HeadStateOperation(changeset.environment, paths)
        val lookup = run(heads)
        lookup.failure(emptyList())?.let { return TagResult(tag, 0, emptyList(), emptyList(), it) }
        val alive = paths.filterNot { it in heads.removed }
        if (alive.isEmpty()) return TagResult(tag, 0, emptyList(), emptyList(), null)

        val tagging = run(RtagOperation(changeset.environment, alive, tag, move))
        val unchanged = tagging.messages.filter { unchangedFile(it) != null }
        val notMoved = unchanged.mapNotNull { unchangedFile(it) }.distinct()
        return TagResult(tag, alive.size - notMoved.size, notMoved, unchanged, tagging.failure(unchanged))
    }

    /** What one CVS operation produced: every server message, its error messages, and its failure, if any. */
    private class Outcome(val messages: List<String>, val errors: List<String>, val error: String?) {
        /**
         * The text of the operation's failure, or null if it did its work. A path the server cannot find is a
         * failure, although cvs reports it only as "ignored" (the module may no longer exist, design decision 20);
         * [expected] messages (files left unchanged) are not.
         */
        fun failure(expected: List<String>): String? {
            if (error != null) return error
            val missing = messages.filter { CANNOT_FIND.matches(it.trim()) }
            return (errors.filterNot { it in expected } + missing).distinct().takeIf { it.isNotEmpty() }?.joinToString("\n")
        }
    }

    private fun run(operation: LocalPathIndifferentOperation): Outcome {
        val handler = CommandCvsHandler("Put on Tag", operation, false)
        val messages = mutableListOf<String>()
        val errors = mutableListOf<String>()
        handler.addCvsListener(object : CvsMessagesAdapter() {
            // cvs 1.11 sends its "NOT MOVING" warnings as ordinary (M) lines, not on the error stream.
            override fun addMessage(event: MessageEvent) {
                if (event.message.isNotBlank()) messages += event.message
            }

            override fun addError(message: String, path: String?, fs: ICvsFileSystem?, root: String?, warning: Boolean) {
                if (!warning) errors += message
            }
        })
        val executor = CvsOperationExecutor(false, project, ModalityState.nonModal())
        executor.setShowErrors(false)
        executor.performActionSync(handler, CvsOperationExecutorCallback.EMPTY)
        return Outcome(messages, errors, if (executor.hasNoErrors()) null else executor.firstError.message)
    }

    /** The repository path of the file a server message says was left unchanged, or null for any other message. */
    private fun unchangedFile(message: String): String? =
        UNCHANGED.firstNotNullOfOrNull { it.matchEntire(message.trim()) }?.groupValues?.get(1)

    /**
     * `rtag` of repository paths: upstream's `RTagOperation` maps local files through a working copy's
     * `CVS/Repository`, which the changeset may not have (design decision 16).
     */
    private class RtagOperation(
        environment: CvsEnvironment,
        paths: List<String>,
        private val tag: String,
        private val move: Boolean,
    ) : LocalPathIndifferentOperation(environment) {
        private val helper = LocalPathIndifferentOperationHelper().apply { paths.forEach { addFile(File(it)) } }

        override fun createCommand(root: CvsRootProvider, environment: CvsExecutionEnvironment): Command =
            RtagCommand(tag).apply {
                helper.addFilesTo(this)
                setOverrideExistings(move)
            }

        override fun getOperationName(): String = "rtag"
    }

    /**
     * `rlog -r` of repository paths: the latest revision of each file on its default branch with its state, so
     * that files whose trunk head is dead are known before tagging (design decision 23).
     */
    private class HeadStateOperation(environment: CvsEnvironment, private val paths: List<String>) :
        LocalPathIndifferentOperation(environment) {
        /** The [paths] whose latest revision is a removal. */
        val removed = mutableSetOf<String>()

        override fun createCommand(root: CvsRootProvider, environment: CvsExecutionEnvironment): Command =
            HeadStateCommand(paths)

        override fun getOperationName(): String = "rlog"

        override fun fileInfoGenerated(info: Any?) {
            super.fileInfoGenerated(info)
            if (info !is LogInformation) return
            if (info.revisionList.firstOrNull()?.state != CvsChangeList.DEAD_STATE) return
            changesetPath(myEnvironment.repository, info.rcsFileName, paths)?.let { removed += it }
        }
    }

    private class HeadStateCommand(private val paths: List<String>) : AbstractCommand() {
        override fun execute(
            requestProcessor: IRequestProcessor,
            eventSender: IEventSender,
            listenerRegistry: ICvsListenerRegistry,
            clientEnvironment: IClientEnvironment,
            progressViewer: IProgressViewer,
        ): Boolean {
            val requests = Requests(CommandRequest.RLOG, clientEnvironment)
            requests.addArgumentRequest("-r")
            paths.forEach { requests.addArgumentRequest(it) }
            val parser = LogMessageParser(eventSender, clientEnvironment.cvsFileSystem)
            parser.registerListeners(listenerRegistry)
            try {
                return requestProcessor.processRequests(requests, DummyRequestsProgressHandler())
            } finally {
                parser.unregisterListeners(listenerRegistry)
            }
        }

        override fun getCvsCommandLine(): String = "rlog -r ${paths.joinToString(" ")}"
    }

    private companion object {
        /** "cvs rtag: cannot find module `module/file.txt' - ignored" (also from rlog and from `cvs server`). */
        val CANNOT_FIND = Regex("""cvs [^:]+: cannot find module `.+' - ignored""")

        /** cvs 1.11 messages about a file left unchanged; group 1 is the file's repository path. */
        val UNCHANGED = listOf(
            // An existing tag without -F: `W module/file.txt : TEST already exists on version 1.2 : NOT MOVING tag to version 1.3`.
            Regex("""W (.+) : \S+ already exists on (?:version|branch) \S+ : NOT MOVING tag to (?:version|branch) \S+"""),
            // A branch tag with -F: "cvs rtag: module/file.txt: Not moving branch tag `BR' from 1.3 to 1.3."
            Regex("""cvs [^:]+: (.+): Not moving branch tag `\S+' from \S+ to \S+"""),
        )
    }
}
