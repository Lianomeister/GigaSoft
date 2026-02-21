package com.clockwork.runtime

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RuntimeSchedulerTest {
    @Test
    fun `task budget is enforced`() {
        val scheduler = RuntimeScheduler("demo", maxTasks = 1)
        scheduler.once("first", 1) {}

        assertFailsWith<IllegalArgumentException> {
            scheduler.once("second", 1) {}
        }

        scheduler.shutdown()
    }

    @Test
    fun `active task ids are sorted deterministically`() {
        val scheduler = RuntimeScheduler("demo", maxTasks = 10)
        scheduler.once("z-task", 10) {}
        scheduler.once("a-task", 10) {}
        scheduler.once("m-task", 10) {}

        assertEquals(listOf("a-task", "m-task", "z-task"), scheduler.activeTaskIds())
        scheduler.shutdown()
    }

    @Test
    fun `worker threads allow parallel one-shot tasks`() {
        val scheduler = RuntimeScheduler("demo", maxTasks = 10, workerThreads = 2)
        val started = CountDownLatch(2)
        val release = CountDownLatch(1)
        val concurrent = AtomicInteger(0)
        val peak = AtomicInteger(0)
        repeat(2) { idx ->
            scheduler.once("p$idx", 1) {
                val now = concurrent.incrementAndGet()
                peak.updateAndGet { current -> maxOf(current, now) }
                started.countDown()
                release.await(500, TimeUnit.MILLISECONDS)
                concurrent.decrementAndGet()
            }
        }
        assertTrue(started.await(1, TimeUnit.SECONDS))
        release.countDown()
        assertTrue(peak.get() >= 2, "Expected at least two concurrent tasks, got peak=${peak.get()}")
        scheduler.shutdown()
    }

    @Test
    fun `repeating task continues after exception`() {
        val scheduler = RuntimeScheduler("demo", maxTasks = 10, workerThreads = 1, tickMillis = 5L)
        val runs = AtomicInteger(0)
        val done = CountDownLatch(1)
        scheduler.repeating("flaky", 1) {
            val next = runs.incrementAndGet()
            if (next == 1) {
                throw IllegalStateException("boom")
            }
            if (next >= 3) {
                done.countDown()
            }
        }

        assertTrue(done.await(600, TimeUnit.MILLISECONDS), "Repeating task did not continue after exception")
        scheduler.shutdown()
    }
}
