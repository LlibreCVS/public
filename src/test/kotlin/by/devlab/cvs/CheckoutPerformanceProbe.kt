package by.devlab.cvs

import com.intellij.testFramework.junit5.TestApplication
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.extension.RegisterExtension
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * Manual probe of checkout speed (not part of the suite): runs only with `LIBRECVS_PERF=true`. Builds a module of
 * [DIRS] × [FILES_PER_DIR] text files on the throwaway server with the server's own `cvs import`, checks it out
 * through the plugin, and samples every thread's stack every [SAMPLE_MS] ms; prints the elapsed time and the frames
 * that are on top of the stacks most often, and checks the scenario "Setting the read-only state is a small share of
 * checkout" (capability cvs-operations).
 */
@TestApplication
@EnabledIfEnvironmentVariable(named = "LIBRECVS_PERF", matches = "true")
class CheckoutPerformanceProbe {

    companion object {
        @JvmField
        @RegisterExtension
        val session = CvsTestSession()

        val DIRS = System.getenv("LIBRECVS_PERF_DIRS")?.toInt() ?: 40
        val FILES_PER_DIR = System.getenv("LIBRECVS_PERF_FILES")?.toInt() ?: 100
        const val FILE_BYTES = 15_000
        const val SAMPLE_MS = 50L
    }

    @Test
    fun checkoutSpeed() {
        session.useEncoding(Charsets.UTF_8)
        val seed = session.newDirectory("perf-seed")
        val random = Random(1)
        repeat(DIRS) { d ->
            val dir = Files.createDirectories(seed.resolve("dir$d"))
            repeat(FILES_PER_DIR) { f ->
                val line = "line of dir $d file $f ${random.nextLong()}\n"
                Files.writeString(dir.resolve("file$f.txt"), line.repeat(FILE_BYTES / line.length))
            }
        }
        val importStart = System.nanoTime()
        session.importModule("alice", seed, "perf")
        println("PERF import: ${(System.nanoTime() - importStart) / 1_000_000} ms")

        val samples = ConcurrentHashMap<String, AtomicInteger>()
        val checkoutThreadSamples = AtomicInteger()
        val readOnlySamples = AtomicInteger()
        val checkoutThread = Thread.currentThread()
        val threads = ConcurrentHashMap<String, AtomicInteger>()
        val running = AtomicBoolean(true)
        val sampler = Thread {
            while (running.get()) {
                for ((thread, stack) in Thread.getAllStackTraces()) {
                    if (stack.isEmpty() || thread == Thread.currentThread()) continue
                    if (thread.state == Thread.State.WAITING || thread.state == Thread.State.TIMED_WAITING) continue
                    val interesting = stack.take(12).joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}" }
                    if (!stack.any { it.className.contains("cvs", ignoreCase = true) }) continue
                    if (thread == checkoutThread) {
                        checkoutThreadSamples.incrementAndGet()
                        if (stack.any { it.methodName == "setFileReadOnly" }) readOnlySamples.incrementAndGet()
                    }
                    samples.computeIfAbsent(interesting) { AtomicInteger() }.incrementAndGet()
                    threads.computeIfAbsent(thread.name) { AtomicInteger() }.incrementAndGet()
                }
                Thread.sleep(SAMPLE_MS)
            }
        }.apply { isDaemon = true; start() }

        val start = System.nanoTime()
        val copy = session.checkout("bob", "perf", session.newDirectory("perf-co"))
        val elapsed = (System.nanoTime() - start) / 1_000_000
        running.set(false)
        sampler.join()

        val files = Files.walk(copy).use { s -> s.filter { Files.isRegularFile(it) && !it.toString().contains("\\CVS\\") }.count() }
        println("PERF checkout: $files files in $elapsed ms (${files * 1000 / elapsed.coerceAtLeast(1)} files/s)")
        println("PERF threads: " + threads.entries.sortedByDescending { it.value.get() }.take(5).joinToString { "${it.key}=${it.value}" })
        samples.entries.sortedByDescending { it.value.get() }.take(25).forEach { println("PERF ${it.value.get()}  ${it.key}") }
        val share = readOnlySamples.get() * 100 / checkoutThreadSamples.get().coerceAtLeast(1)
        println("PERF read-only state: ${readOnlySamples.get()} of ${checkoutThreadSamples.get()} checkout-thread samples ($share%)")
        // Scenario "Setting the read-only state is a small share of checkout".
        assertTrue(share < 10, "setting the read-only state takes $share% of the checkout thread's samples")
    }
}
