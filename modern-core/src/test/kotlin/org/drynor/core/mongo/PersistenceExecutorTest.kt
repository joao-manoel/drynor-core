package org.drynor.core.mongo

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class PersistenceExecutorTest {
    @Test fun `player writes and save barrier stay ordered while another player progresses`() {
        val running = CountDownLatch(1)
        val release = CountDownLatch(1)
        val other = CountDownLatch(1)
        val barrier = java.util.concurrent.CompletableFuture<Int>()
        val stored = AtomicInteger()
        val executor = PersistenceExecutor(4)
        try {
            assertTrue(executor.submitOrdered("alice") { running.countDown(); release.await(); stored.set(1) })
            assertTrue(running.await(5, TimeUnit.SECONDS))
            assertTrue(executor.submitOrdered("alice") { stored.set(2) })
            assertTrue(executor.submitOrdered("alice") { barrier.complete(stored.get()) })
            assertTrue(executor.submitOrdered("bob") { other.countDown() })
            assertTrue(other.await(5, TimeUnit.SECONDS))
            assertFalse(barrier.isDone)
            release.countDown()
            assertEquals(2, barrier.get(5, TimeUnit.SECONDS))
            executor.close()
            assertEquals(2, stored.get())
            assertFalse(executor.submitOrdered("alice") { fail("closed lane accepted work") })
        } finally { release.countDown(); executor.close() }
    }

    @Test fun `bounded executor rejects excess work drains accepted writes and refuses work after close`() {
        val running = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completed = AtomicInteger()
        val executor = PersistenceExecutor(1)
        try {
            assertTrue(executor.submit { running.countDown(); release.await(); completed.incrementAndGet() })
            assertTrue(running.await(5, TimeUnit.SECONDS))
            assertFalse(executor.submit { fail("excess write was executed") })
            release.countDown()
            executor.close()
            assertEquals(1, completed.get())
            assertFalse(executor.submit { fail("closed executor accepted a write") })
        } finally { release.countDown(); executor.close() }
    }
}
