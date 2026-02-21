package com.clockwork.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SystemFaultIsolationControllerTest {
    @Test
    fun `isolates system after repeated failures and tracks skipped ticks`() {
        val controller = SystemFaultIsolationController(
            SystemIsolationPolicy(
                failureThreshold = 2,
                baseCooldownTicks = 3L,
                maxCooldownTicks = 10L
            )
        )

        assertTrue(controller.shouldRun("demo", "logic", tick = 1L))
        assertEquals(0L, controller.onFailure("demo", "logic", tick = 1L, error = "boom-1"))
        assertTrue(controller.shouldRun("demo", "logic", tick = 2L))
        assertEquals(3L, controller.onFailure("demo", "logic", tick = 2L, error = "boom-2"))

        assertFalse(controller.shouldRun("demo", "logic", tick = 3L))
        assertFalse(controller.shouldRun("demo", "logic", tick = 4L))
        assertTrue(controller.shouldRun("demo", "logic", tick = 5L))

        val snapshot = controller.snapshot("demo", "logic", tick = 5L)
        assertEquals(1L, snapshot.isolationCount)
        assertEquals(2L, snapshot.skippedTicks)
        assertEquals("boom-2", snapshot.lastError)
    }

    @Test
    fun `successful runs reduce isolation level after previous isolation`() {
        val controller = SystemFaultIsolationController(
            SystemIsolationPolicy(
                failureThreshold = 1,
                baseCooldownTicks = 2L,
                maxCooldownTicks = 8L
            )
        )

        assertTrue(controller.shouldRun("demo", "logic", tick = 1L))
        assertEquals(2L, controller.onFailure("demo", "logic", tick = 1L, error = "boom"))
        var snapshot = controller.snapshot("demo", "logic", tick = 2L)
        assertEquals(1, snapshot.isolationLevel)

        assertTrue(controller.shouldRun("demo", "logic", tick = 3L))
        controller.onSuccess("demo", "logic")
        snapshot = controller.snapshot("demo", "logic", tick = 3L)
        assertEquals(0, snapshot.isolationLevel)
    }
}
