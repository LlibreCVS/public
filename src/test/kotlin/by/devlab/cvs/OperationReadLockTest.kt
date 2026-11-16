package by.devlab.cvs

import com.intellij.cvsSupport2.connections.CvsEnvironment
import com.intellij.cvsSupport2.connections.CvsRootProvider
import com.intellij.cvsSupport2.cvshandlers.CommandCvsHandler
import com.intellij.cvsSupport2.cvsoperations.common.CvsExecutionEnvironment
import com.intellij.cvsSupport2.cvsoperations.common.LocalPathIndifferentOperation
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.testFramework.junit5.TestApplication
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.netbeans.lib.cvsclient.IClientEnvironment
import org.netbeans.lib.cvsclient.IRequestProcessor
import org.netbeans.lib.cvsclient.command.AbstractCommand
import org.netbeans.lib.cvsclient.command.Command
import org.netbeans.lib.cvsclient.event.ICvsListenerRegistry
import org.netbeans.lib.cvsclient.event.IEventSender
import org.netbeans.lib.cvsclient.progress.IProgressViewer
import org.netbeans.lib.cvsclient.progress.sending.DummyRequestsProgressHandler
import org.netbeans.lib.cvsclient.request.CommandRequest
import org.netbeans.lib.cvsclient.request.Requests
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Capability cvs-operations, requirement "Operations do not hold the read lock while talking to the server": an
 * operation with OpenCVS's default execution settings runs an `rlog` against the throwaway server and, from inside
 * its command, records its read access and whether a write action requested meanwhile can complete.
 */
@TestApplication
class OperationReadLockTest {

    companion object {
        @JvmField
        @RegisterExtension
        val session = CvsTestSession()
    }

    @Test
    @DisplayName("Server exchange runs without read access")
    fun serverExchangeRunsWithoutReadAccess() {
        val probe = probe("read-access")

        assertFalse(probe.readAccess!!, "the command ran with read access to the IDE's model")
    }

    @Test
    @DisplayName("A write action runs while a command waits for the server")
    fun writeActionRunsWhileCommandWaitsForTheServer() {
        val probe = probe("write-action")

        assertTrue(probe.writeActionRan!!, "a write action requested during the command did not complete within 10 s")
    }

    private fun probe(module: String): ProbeOperation {
        val seed = session.newDirectory("$module-seed")
        TestData.write(seed.resolve("readme.txt"), "module\n".toByteArray())
        session.importModule("alice", seed, module)
        val operation = ProbeOperation(session.environment("alice"), module)
        session.run(CommandCvsHandler("probe", operation, true))
        return operation
    }

    /** A repository-path operation that keeps OpenCVS's defaults (it overrides nothing about how it runs). */
    private class ProbeOperation(environment: CvsEnvironment, private val module: String) :
        LocalPathIndifferentOperation(environment) {
        @Volatile
        var readAccess: Boolean? = null

        @Volatile
        var writeActionRan: Boolean? = null

        override fun createCommand(root: CvsRootProvider, environment: CvsExecutionEnvironment): Command =
            object : AbstractCommand() {
                override fun execute(
                    requestProcessor: IRequestProcessor,
                    eventSender: IEventSender,
                    listenerRegistry: ICvsListenerRegistry,
                    clientEnvironment: IClientEnvironment,
                    progressViewer: IProgressViewer,
                ): Boolean {
                    val application = ApplicationManager.getApplication()
                    readAccess = application.isReadAccessAllowed
                    val written = CountDownLatch(1)
                    application.invokeLater({ application.runWriteAction { written.countDown() } }, ModalityState.any())
                    writeActionRan = written.await(10, TimeUnit.SECONDS)
                    val requests = Requests(CommandRequest.RLOG, clientEnvironment)
                    requests.addArgumentRequest(module)
                    return requestProcessor.processRequests(requests, DummyRequestsProgressHandler())
                }

                override fun getCvsCommandLine(): String = "rlog $module"
            }

        override fun getOperationName(): String = "rlog"
    }
}
