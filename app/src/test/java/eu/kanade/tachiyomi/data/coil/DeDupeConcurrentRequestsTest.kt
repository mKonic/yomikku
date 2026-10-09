package eu.kanade.tachiyomi.data.coil

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class DeDupeConcurrentRequestsTest {

    @Test
    fun `waiting callers start only after the leader succeeds`() = runTest {
        val dedupe = DeDupeConcurrentRequests<Int>()
        val release = CompletableDeferred<Unit>()
        val leaderDone = AtomicInteger()
        val startedEarly = AtomicInteger()
        val calls = AtomicInteger()

        val results = List(4) {
            async(start = CoroutineStart.UNDISPATCHED) {
                dedupe.apply("cover") {
                    if (calls.incrementAndGet() == 1) {
                        release.await()
                        yield()
                        leaderDone.set(1)
                    } else if (leaderDone.get() == 0) {
                        startedEarly.incrementAndGet()
                    }
                    1
                }
            }
        }
        // Only the leader has entered the block so far
        assertEquals(1, calls.get())
        release.complete(Unit)

        assertEquals(listOf(1, 1, 1, 1), results.awaitAll())
        assertEquals(0, startedEarly.get())
        assertEquals(4, calls.get())
    }

    @Test
    fun `a failed leader hands the request to a waiting caller`() = runTest {
        val dedupe = DeDupeConcurrentRequests<Int> { it > 0 }
        val release = CompletableDeferred<Unit>()
        val attempts = AtomicInteger()

        val results = List(2) {
            async(start = CoroutineStart.UNDISPATCHED) {
                dedupe.apply("cover") {
                    release.await()
                    // The first attempt reports failure without throwing
                    if (attempts.incrementAndGet() == 1) 0 else 1
                }
            }
        }
        release.complete(Unit)

        assertEquals(listOf(0, 1), results.awaitAll())
        assertEquals(2, attempts.get())
    }

    @Test
    fun `different keys do not wait for each other`() = runTest {
        val dedupe = DeDupeConcurrentRequests<String>()
        val blockA = CompletableDeferred<Unit>()

        val a = async(start = CoroutineStart.UNDISPATCHED) {
            dedupe.apply("a") {
                blockA.await()
                "a"
            }
        }
        val b = dedupe.apply("b") { "b" }

        assertEquals("b", b)
        blockA.complete(Unit)
        assertEquals("a", a.await())
    }
}
