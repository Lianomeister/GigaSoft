package com.gigasoft.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RuntimeMetricsDiagnosticsTest {
    @Test
    fun `profile marks slow systems when average threshold is exceeded`() {
        val metrics = RuntimeMetrics()
        repeat(25) {
            metrics.recordSystemTick(
                pluginId = "demo",
                systemId = "slow-system",
                durationNanos = 3_000_000L,
                success = true
            )
        }

        val profile = metrics.snapshot("demo", activeTaskIds = emptyList())
        assertEquals(1, profile.slowSystems.size)
        assertEquals("slow-system", profile.slowSystems.first().systemId)
        assertTrue(profile.slowSystems.first().reasons.any { it.startsWith("average_nanos>=") })
    }

    @Test
    fun `profile marks adapter hotspots when timeout rate is high`() {
        val metrics = RuntimeMetrics()
        repeat(24) {
            metrics.recordAdapterInvocation(
                pluginId = "demo",
                adapterId = "bridge.world",
                outcome = AdapterInvocationOutcome.ACCEPTED
            )
        }
        metrics.recordAdapterInvocation(
            pluginId = "demo",
            adapterId = "bridge.world",
            outcome = AdapterInvocationOutcome.TIMEOUT
        )

        val profile = metrics.snapshot("demo", activeTaskIds = emptyList())
        assertEquals(1, profile.adapterHotspots.size)
        assertEquals("bridge.world", profile.adapterHotspots.first().adapterId)
        assertTrue(profile.adapterHotspots.first().reasons.any { it.startsWith("timeout_rate>=") })
    }

    @Test
    fun `profile includes isolated systems from runtime snapshots`() {
        val metrics = RuntimeMetrics()
        val profile = metrics.snapshot(
            pluginId = "demo",
            activeTaskIds = emptyList(),
            isolatedSystems = listOf(
                SystemIsolationSnapshot(
                    systemId = "logic.fast",
                    isolated = false,
                    remainingTicks = 0L,
                    consecutiveFailures = 0,
                    isolationLevel = 0,
                    isolationCount = 1L,
                    skippedTicks = 2L,
                    isolateUntilTick = 12L,
                    lastFailureTick = 10L,
                    lastError = "boom"
                ),
                SystemIsolationSnapshot(
                    systemId = "logic.slow",
                    isolated = true,
                    remainingTicks = 15L,
                    consecutiveFailures = 0,
                    isolationLevel = 2,
                    isolationCount = 3L,
                    skippedTicks = 20L,
                    isolateUntilTick = 30L,
                    lastFailureTick = 15L,
                    lastError = "timeout"
                )
            )
        )

        assertEquals(2, profile.isolatedSystems.size)
        assertEquals("logic.slow", profile.isolatedSystems.first().systemId)
        assertTrue(profile.isolatedSystems.first().isolated)
    }
}
