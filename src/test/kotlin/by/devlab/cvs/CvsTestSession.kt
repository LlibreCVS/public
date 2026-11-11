package by.devlab.cvs

import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.ExtensionContext
import java.nio.charset.Charset
import java.nio.file.Path

/** Stub; see the implementation commit. */
class CvsTestSession : BeforeAllCallback, AfterAllCallback, AfterEachCallback {
    val server = TestCvsServer()

    override fun beforeAll(context: ExtensionContext) {}
    override fun afterAll(context: ExtensionContext) {}
    override fun afterEach(context: ExtensionContext) {}

    fun useEncoding(charset: Charset): Unit = throw NotImplementedError()
    fun newDirectory(name: String): Path = throw NotImplementedError()
    fun importModule(user: String, source: Path, module: String, binaryExtensions: List<String> = emptyList()): Unit =
        throw NotImplementedError()
    fun checkout(user: String, module: String, target: Path): Path = throw NotImplementedError()
    fun realPasswordFileFingerprint(): String = throw NotImplementedError()
}
