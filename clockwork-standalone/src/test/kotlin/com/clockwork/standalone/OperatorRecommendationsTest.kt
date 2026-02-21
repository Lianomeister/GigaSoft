package com.clockwork.standalone

import com.clockwork.runtime.AdapterAuditRetentionPolicy
import com.clockwork.runtime.AdapterAuditSnapshot
import com.clockwork.runtime.AdapterCountersSnapshot
import com.clockwork.runtime.AdapterHotspotSnapshot
import com.clockwork.runtime.PluginFaultBudgetSnapshot
import com.clockwork.runtime.PluginPerformanceDiagnostics
import com.clockwork.runtime.FaultBudgetPolicy
import com.clockwork.runtime.SlowSystemSnapshot
import com.clockwork.runtime.SystemIsolationSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OperatorRecommendationsTest {
    @Test
    fun `recommendations include stable automation codes`() {
        val perf = PluginPerformanceDiagnostics(
            adapterCounters = AdapterCountersSnapshot(accepted = 10, denied = 2, timeouts = 1, failures = 1),
            adapterAudit = AdapterAuditSnapshot(
                retention = AdapterAuditRetentionPolicy(),
                totalRecorded = 14,
                retainedEntries = 14,
                outcomeCounts = mapOf("ACCEPTED" to 10),
                recent = emptyList()
            ),
            slowSystems = listOf(
                SlowSystemSnapshot(
                    systemId = "tick.main",
                    runs = 100,
                    failures = 5,
                    averageNanos = 3_000_000,
                    maxNanos = 12_000_000,
                    failureRate = 0.05,
                    reasons = listOf("average_nanos>=2000000")
                )
            ),
            adapterHotspots = listOf(
                AdapterHotspotSnapshot(
                    adapterId = "bridge.host.server",
                    total = 100,
                    accepted = 80,
                    denied = 10,
                    timeouts = 5,
                    failures = 5,
                    deniedRate = 0.1,
                    timeoutRate = 0.05,
                    failureRate = 0.05,
                    reasons = listOf("timeout_rate>=0.02")
                )
            ),
            isolatedSystems = listOf(
                SystemIsolationSnapshot(
                    systemId = "tick.main",
                    isolated = true,
                    remainingTicks = 20,
                    consecutiveFailures = 6,
                    isolationLevel = 1,
                    isolationCount = 2,
                    skippedTicks = 30,
                    isolateUntilTick = 999,
                    lastFailureTick = 100,
                    lastError = "boom"
                )
            ),
            faultBudget = PluginFaultBudgetSnapshot(
                policy = FaultBudgetPolicy(maxFaultsPerWindow = 10, windowMillis = 60_000),
                used = 10,
                remaining = 0,
                tripped = true,
                recentSources = mapOf("adapter:bridge.host.server" to 10)
            )
        )

        val recommendations = buildPluginPerformanceRecommendations("demo", perf)
        val codes = recommendations.map { it.code }.toSet()

        assertTrue("SYS_SLOW" in codes)
        assertTrue("ADAPTER_HOTSPOT" in codes)
        assertTrue("SYSTEM_ISOLATED" in codes)
        assertTrue("FAULT_BUDGET_PRESSURE" in codes)
        assertEquals("critical", recommendations.first { it.code == "FAULT_BUDGET_PRESSURE" }.severity)
    }
}
