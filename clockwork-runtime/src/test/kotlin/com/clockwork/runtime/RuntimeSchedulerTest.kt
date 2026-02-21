package com.clockwork.runtime

import kotlin.test.Test
import kotlin.test.assertFailsWith

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
}
