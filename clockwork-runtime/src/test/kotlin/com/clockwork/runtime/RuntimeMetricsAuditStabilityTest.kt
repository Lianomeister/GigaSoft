package com.clockwork.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RuntimeMetricsAuditStabilityTest {
    @Test
    fun `adapter audit retention is bounded under sustained invoke load`() {
        val metrics = RuntimeMetrics(
            adapterAuditRetention = AdapterAuditRetentionPolicy(
                maxEntriesPerPlugin = 40,
                maxEntriesPerAdapter = 12,
                maxAgeMillis = 60_000L
            )
        )

        repeat(1_000) { idx ->
            val adapterId = if (idx % 2 == 0) "bridge.host.server" else "bridge.host.player"
            val outcome = if (idx % 7 == 0) AdapterInvocationOutcome.TIMEOUT else AdapterInvocationOutcome.ACCEPTED
            metrics.recordAdapterInvocation(
                pluginId = "demo",
                adapterId = adapterId,
                outcome = outcome,
                action = "ping",
                detail = "load-$idx",
                durationNanos = 1000L + idx,
                payloadEntries = idx % 5
            )
        }

        val profile = metrics.snapshot(pluginId = "demo", activeTaskIds = emptyList())
        assertEquals(1_000L, profile.adapterCounters.total)
        assertTrue(profile.adapterAudit.retainedEntries <= 40)
        assertTrue(profile.adapterAudit.recent.count { it.adapterId == "bridge.host.server" } <= 12)
        assertTrue(profile.adapterAudit.recent.count { it.adapterId == "bridge.host.player" } <= 12)
    }

    @Test
    fun `clearPlugin removes retained adapter audit and counters`() {
        val metrics = RuntimeMetrics()
        metrics.recordAdapterInvocation("demo", "bridge.host.server", AdapterInvocationOutcome.ACCEPTED)
        metrics.recordAdapterInvocation("demo", "bridge.host.server", AdapterInvocationOutcome.FAILED)

        val before = metrics.snapshot("demo", activeTaskIds = emptyList())
        assertTrue(before.adapterCounters.total >= 2L)
        assertTrue(before.adapterAudit.totalRecorded >= 2L)

        metrics.clearPlugin("demo")
        val after = metrics.snapshot("demo", activeTaskIds = emptyList())
        assertEquals(0L, after.adapterCounters.total)
        assertEquals(0L, after.adapterAudit.totalRecorded)
        assertEquals(0, after.adapterAudit.retainedEntries)
    }
}
