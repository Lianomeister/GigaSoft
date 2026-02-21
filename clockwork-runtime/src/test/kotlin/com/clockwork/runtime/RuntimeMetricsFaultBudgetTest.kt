package com.clockwork.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RuntimeMetricsFaultBudgetTest {
    @Test
    fun `fault budget tracks adapter and system faults`() {
        val metrics = RuntimeMetrics()
        metrics.recordPluginFault("demo", "system:tick")
        metrics.recordAdapterInvocation("demo", "bridge", AdapterInvocationOutcome.FAILED)
        metrics.recordAdapterInvocation("demo", "bridge", AdapterInvocationOutcome.TIMEOUT)
        val profile = metrics.snapshot("demo", activeTaskIds = emptyList())
        assertEquals(3, profile.faultBudget.used)
        assertTrue(profile.faultBudget.remaining >= 0)
        assertTrue((profile.faultBudget.recentSources["system:tick"] ?: 0) >= 1)
        assertTrue((profile.faultBudget.recentSources["adapter:bridge"] ?: 0) >= 2)
    }

    @Test
    fun `fault budget policy is configurable`() {
        val metrics = RuntimeMetrics(
            faultBudgetPolicy = FaultBudgetPolicy(
                maxFaultsPerWindow = 2,
                windowMillis = 120_000L
            )
        )
        metrics.recordPluginFault("demo", "system:a")
        metrics.recordPluginFault("demo", "system:b")
        val profile = metrics.snapshot("demo", activeTaskIds = emptyList())
        assertEquals(2, profile.faultBudget.used)
        assertEquals(0, profile.faultBudget.remaining)
        assertTrue(profile.faultBudget.tripped)
    }
}

