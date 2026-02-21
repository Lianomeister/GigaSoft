package com.gigasoft.runtime

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
}

