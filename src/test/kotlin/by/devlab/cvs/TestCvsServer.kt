package by.devlab.cvs

/** Finds the `cvs` program that runs the throwaway server (design decision 11). */
object CvsLocator {
    const val PROPERTY = "intellijcvs.cvs"

    fun locate(property: String? = System.getProperty(PROPERTY), path: String? = System.getenv("PATH")): java.nio.file.Path =
        throw NotImplementedError()
}

/** A throwaway CVS pserver for the tests (design decisions 7, 8, 13, 16). */
class TestCvsServer : AutoCloseable {
    val port: Int get() = throw NotImplementedError()
    val repositoryPath: String get() = throw NotImplementedError()

    fun start(): Unit = throw NotImplementedError()

    fun startedProcesses(): List<ProcessHandle> = throw NotImplementedError()

    override fun close(): Unit = throw NotImplementedError()

    companion object {
        const val PASSWORD = "secret"
    }
}
