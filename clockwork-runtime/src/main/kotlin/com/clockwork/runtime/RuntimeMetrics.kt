package com.clockwork.runtime

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.ArrayDeque
import kotlin.math.max

data class MetricSnapshot(
    val runs: Long,
    val failures: Long,
    val totalNanos: Long,
    val maxNanos: Long
) {
    val averageNanos: Long
        get() = if (runs == 0L) 0L else totalNanos / runs
}

data class PluginRuntimeProfile(
    val pluginId: String,
    val activeTasks: Int,
    val activeTaskIds: List<String>,
    val systems: Map<String, MetricSnapshot>,
    val tasks: Map<String, MetricSnapshot>,
    val adapters: Map<String, AdapterMetricSnapshot>,
    val adapterCounters: AdapterCountersSnapshot,
    val adapterAudit: AdapterAuditSnapshot,
    val slowSystems: List<SlowSystemSnapshot>,
    val adapterHotspots: List<AdapterHotspotSnapshot>,
    val isolatedSystems: List<SystemIsolationSnapshot>,
    val faultBudget: PluginFaultBudgetSnapshot,
    val diagnosticsThresholds: ProfileDiagnosticsThresholds
)

data class FaultBudgetPolicy(
    val maxFaultsPerWindow: Int = 100,
    val windowMillis: Long = 60_000L
)

enum class FaultBudgetStage {
    NORMAL,
    WARN,
    THROTTLE,
    ISOLATE
}

data class FaultBudgetEscalationPolicy(
    val warnUsageRatio: Double = 0.60,
    val throttleUsageRatio: Double = 0.80,
    val isolateUsageRatio: Double = 1.00,
    val throttleBudgetMultiplier: Double = 0.50
)

data class PluginFaultBudgetSnapshot(
    val policy: FaultBudgetPolicy,
    val used: Int,
    val remaining: Int,
    val tripped: Boolean,
    val recentSources: Map<String, Int>,
    val usageRatio: Double = 0.0,
    val stage: FaultBudgetStage = FaultBudgetStage.NORMAL,
    val escalationPolicy: FaultBudgetEscalationPolicy = FaultBudgetEscalationPolicy()
)

data class SystemIsolationSnapshot(
    val systemId: String,
    val isolated: Boolean,
    val remainingTicks: Long,
    val consecutiveFailures: Int,
    val isolationLevel: Int,
    val isolationCount: Long,
    val skippedTicks: Long,
    val isolateUntilTick: Long,
    val lastFailureTick: Long,
    val lastError: String?
)

data class ProfileDiagnosticsThresholds(
    val minSystemRuns: Long = 20,
    val slowSystemAverageNanos: Long = 2_000_000L,
    val slowSystemMaxNanos: Long = 10_000_000L,
    val systemFailureRateThreshold: Double = 0.05,
    val minAdapterInvocations: Long = 20,
    val adapterTimeoutRateThreshold: Double = 0.02,
    val adapterFailureRateThreshold: Double = 0.05,
    val adapterDeniedRateThreshold: Double = 0.25
)

data class SlowSystemSnapshot(
    val systemId: String,
    val runs: Long,
    val failures: Long,
    val averageNanos: Long,
    val maxNanos: Long,
    val failureRate: Double,
    val reasons: List<String>
)

data class AdapterHotspotSnapshot(
    val adapterId: String,
    val total: Long,
    val accepted: Long,
    val denied: Long,
    val timeouts: Long,
    val failures: Long,
    val deniedRate: Double,
    val timeoutRate: Double,
    val failureRate: Double,
    val reasons: List<String>
)

enum class AdapterInvocationOutcome {
    ACCEPTED,
    DENIED,
    TIMEOUT,
    FAILED
}

data class AdapterMetricSnapshot(
    val accepted: Long,
    val denied: Long,
    val timeouts: Long,
    val failures: Long
) {
    val total: Long
        get() = accepted + denied + timeouts + failures
}

data class AdapterCountersSnapshot(
    val accepted: Long,
    val denied: Long,
    val timeouts: Long,
    val failures: Long
) {
    val total: Long
        get() = accepted + denied + timeouts + failures
}

data class AdapterAuditRetentionPolicy(
    val maxEntriesPerPlugin: Int = 512,
    val maxEntriesPerAdapter: Int = 128,
    val maxAgeMillis: Long = 300_000L,
    val maxMemoryBytes: Long = 512L * 1024L
)

data class AdapterAuditEntrySnapshot(
    val timestampMillis: Long,
    val adapterId: String,
    val action: String,
    val outcome: String,
    val detail: String,
    val durationNanos: Long,
    val payloadEntries: Int
)

data class AdapterAuditSnapshot(
    val retention: AdapterAuditRetentionPolicy,
    val totalRecorded: Long,
    val retainedEntries: Int,
    val retainedEstimatedBytes: Long,
    val outcomeCounts: Map<String, Long>,
    val recent: List<AdapterAuditEntrySnapshot>
)

class RuntimeMetrics(
    private val thresholds: ProfileDiagnosticsThresholds = ProfileDiagnosticsThresholds(),
    private val faultBudgetPolicy: FaultBudgetPolicy = FaultBudgetPolicy(),
    private val faultBudgetEscalationPolicy: FaultBudgetEscalationPolicy = FaultBudgetEscalationPolicy(),
    private val adapterAuditRetention: AdapterAuditRetentionPolicy = AdapterAuditRetentionPolicy()
) {
    private val systemMetrics = ConcurrentHashMap<String, ConcurrentHashMap<String, MutableMetric>>()
    private val taskMetrics = ConcurrentHashMap<String, ConcurrentHashMap<String, MutableMetric>>()
    private val adapterMetrics = ConcurrentHashMap<String, ConcurrentHashMap<String, MutableAdapterMetric>>()
    private val pluginFaults = ConcurrentHashMap<String, MutableFaultWindow>()
    private val pluginAdapterAudit = ConcurrentHashMap<String, MutableAdapterAuditWindow>()

    fun recordSystemTick(pluginId: String, systemId: String, durationNanos: Long, success: Boolean) {
        val metric = systemMetrics
            .computeIfAbsent(pluginId) { ConcurrentHashMap() }
            .computeIfAbsent(systemId) { MutableMetric() }
        metric.record(durationNanos, success)
    }

    fun recordTaskRun(pluginId: String, taskId: String, durationNanos: Long, success: Boolean) {
        val metric = taskMetrics
            .computeIfAbsent(pluginId) { ConcurrentHashMap() }
            .computeIfAbsent(taskId) { MutableMetric() }
        metric.record(durationNanos, success)
    }

    fun recordAdapterInvocation(pluginId: String, adapterId: String, outcome: AdapterInvocationOutcome) {
        recordAdapterInvocation(
            pluginId = pluginId,
            adapterId = adapterId,
            outcome = outcome,
            action = "",
            detail = "",
            durationNanos = 0L,
            payloadEntries = 0
        )
    }

    fun recordAdapterInvocation(
        pluginId: String,
        adapterId: String,
        outcome: AdapterInvocationOutcome,
        action: String,
        detail: String,
        durationNanos: Long,
        payloadEntries: Int
    ) {
        val metric = adapterMetrics
            .computeIfAbsent(pluginId) { ConcurrentHashMap() }
            .computeIfAbsent(adapterId) { MutableAdapterMetric() }
        metric.record(outcome)
        recordAdapterAudit(
            pluginId = pluginId,
            adapterId = adapterId,
            action = action,
            outcome = outcome,
            detail = detail,
            durationNanos = durationNanos,
            payloadEntries = payloadEntries
        )
        if (outcome == AdapterInvocationOutcome.FAILED || outcome == AdapterInvocationOutcome.TIMEOUT) {
            recordPluginFault(pluginId, "adapter:$adapterId")
        }
    }

    fun clearPlugin(pluginId: String) {
        systemMetrics.remove(pluginId)
        taskMetrics.remove(pluginId)
        adapterMetrics.remove(pluginId)
        pluginFaults.remove(pluginId)
        pluginAdapterAudit.remove(pluginId)
    }

    fun recordPluginFault(pluginId: String, source: String) {
        val now = System.currentTimeMillis()
        val windowStart = now - faultBudgetPolicy.windowMillis
        val window = pluginFaults.computeIfAbsent(pluginId) { MutableFaultWindow() }
        synchronized(window) {
            while (true) {
                val head = window.faults.peekFirst() ?: break
                if (head.atMillis >= windowStart) break
                window.faults.removeFirst()
                val count = (window.sourceCounts[head.source] ?: 0) - 1
                if (count <= 0) window.sourceCounts.remove(head.source) else window.sourceCounts[head.source] = count
            }
            window.faults.addLast(FaultStamp(now, source))
            window.sourceCounts[source] = (window.sourceCounts[source] ?: 0) + 1
        }
    }

    fun snapshot(
        pluginId: String,
        activeTaskIds: List<String>,
        isolatedSystems: List<SystemIsolationSnapshot> = emptyList()
    ): PluginRuntimeProfile {
        val systems = systemMetrics[pluginId].orEmpty()
            .mapValues { it.value.snapshot() }
            .toSortedMap()
        val tasks = taskMetrics[pluginId].orEmpty()
            .mapValues { it.value.snapshot() }
            .toSortedMap()
        val adapters = adapterMetrics[pluginId].orEmpty()
            .mapValues { it.value.snapshot() }
            .toSortedMap()
        val adapterCounters = aggregateAdapterCounters(adapters)
        val adapterAudit = snapshotAdapterAudit(pluginId)
        val sortedTaskIds = activeTaskIds.sorted()
        val slowSystems = detectSlowSystems(systems, thresholds)
        val adapterHotspots = detectAdapterHotspots(adapters, thresholds)
        val faultBudget = snapshotFaultBudget(pluginId)
        return PluginRuntimeProfile(
            pluginId = pluginId,
            activeTasks = sortedTaskIds.size,
            activeTaskIds = sortedTaskIds,
            systems = systems,
            tasks = tasks,
            adapters = adapters,
            adapterCounters = adapterCounters,
            adapterAudit = adapterAudit,
            slowSystems = slowSystems,
            adapterHotspots = adapterHotspots,
            isolatedSystems = isolatedSystems.sortedByDescending { it.remainingTicks },
            faultBudget = faultBudget,
            diagnosticsThresholds = thresholds
        )
    }

    private fun snapshotFaultBudget(pluginId: String): PluginFaultBudgetSnapshot {
        val now = System.currentTimeMillis()
        val windowStart = now - faultBudgetPolicy.windowMillis
        val window = pluginFaults.computeIfAbsent(pluginId) { MutableFaultWindow() }
        synchronized(window) {
            while (true) {
                val head = window.faults.peekFirst() ?: break
                if (head.atMillis >= windowStart) break
                window.faults.removeFirst()
                val count = (window.sourceCounts[head.source] ?: 0) - 1
                if (count <= 0) window.sourceCounts.remove(head.source) else window.sourceCounts[head.source] = count
            }
            val used = window.faults.size
            val remaining = (faultBudgetPolicy.maxFaultsPerWindow - used).coerceAtLeast(0)
            val usageRatio = if (faultBudgetPolicy.maxFaultsPerWindow <= 0) 0.0
            else used.toDouble() / faultBudgetPolicy.maxFaultsPerWindow.toDouble()
            val stage = when {
                usageRatio >= faultBudgetEscalationPolicy.isolateUsageRatio -> FaultBudgetStage.ISOLATE
                usageRatio >= faultBudgetEscalationPolicy.throttleUsageRatio -> FaultBudgetStage.THROTTLE
                usageRatio >= faultBudgetEscalationPolicy.warnUsageRatio -> FaultBudgetStage.WARN
                else -> FaultBudgetStage.NORMAL
            }
            return PluginFaultBudgetSnapshot(
                policy = faultBudgetPolicy,
                used = used,
                remaining = remaining,
                tripped = used >= faultBudgetPolicy.maxFaultsPerWindow,
                recentSources = window.sourceCounts.toSortedMap(compareByDescending<String> { window.sourceCounts[it] ?: 0 }.thenBy { it }),
                usageRatio = usageRatio,
                stage = stage,
                escalationPolicy = faultBudgetEscalationPolicy
            )
        }
    }

    private fun detectSlowSystems(
        systems: Map<String, MetricSnapshot>,
        thresholds: ProfileDiagnosticsThresholds
    ): List<SlowSystemSnapshot> {
        return systems.entries
            .mapNotNull { (systemId, metric) ->
                if (metric.runs < thresholds.minSystemRuns) return@mapNotNull null
                val failureRate = if (metric.runs <= 0L) 0.0 else metric.failures.toDouble() / metric.runs.toDouble()
                val reasons = mutableListOf<String>()
                if (metric.averageNanos >= thresholds.slowSystemAverageNanos) {
                    reasons += "average_nanos>=${thresholds.slowSystemAverageNanos}"
                }
                if (metric.maxNanos >= thresholds.slowSystemMaxNanos) {
                    reasons += "max_nanos>=${thresholds.slowSystemMaxNanos}"
                }
                if (failureRate >= thresholds.systemFailureRateThreshold) {
                    reasons += "failure_rate>=${thresholds.systemFailureRateThreshold}"
                }
                if (reasons.isEmpty()) return@mapNotNull null
                SlowSystemSnapshot(
                    systemId = systemId,
                    runs = metric.runs,
                    failures = metric.failures,
                    averageNanos = metric.averageNanos,
                    maxNanos = metric.maxNanos,
                    failureRate = failureRate,
                    reasons = reasons
                )
            }
            .sortedWith(
                compareByDescending<SlowSystemSnapshot> { it.averageNanos }
                    .thenByDescending { it.maxNanos }
                    .thenByDescending { it.failureRate }
            )
    }

    private fun detectAdapterHotspots(
        adapters: Map<String, AdapterMetricSnapshot>,
        thresholds: ProfileDiagnosticsThresholds
    ): List<AdapterHotspotSnapshot> {
        return adapters.entries
            .mapNotNull { (adapterId, metric) ->
                if (metric.total < thresholds.minAdapterInvocations) return@mapNotNull null
                val deniedRate = if (metric.total <= 0L) 0.0 else metric.denied.toDouble() / metric.total.toDouble()
                val timeoutRate = if (metric.total <= 0L) 0.0 else metric.timeouts.toDouble() / metric.total.toDouble()
                val failureRate = if (metric.total <= 0L) 0.0 else metric.failures.toDouble() / metric.total.toDouble()
                val reasons = mutableListOf<String>()
                if (timeoutRate >= thresholds.adapterTimeoutRateThreshold) {
                    reasons += "timeout_rate>=${thresholds.adapterTimeoutRateThreshold}"
                }
                if (failureRate >= thresholds.adapterFailureRateThreshold) {
                    reasons += "failure_rate>=${thresholds.adapterFailureRateThreshold}"
                }
                if (deniedRate >= thresholds.adapterDeniedRateThreshold) {
                    reasons += "denied_rate>=${thresholds.adapterDeniedRateThreshold}"
                }
                if (reasons.isEmpty()) return@mapNotNull null
                AdapterHotspotSnapshot(
                    adapterId = adapterId,
                    total = metric.total,
                    accepted = metric.accepted,
                    denied = metric.denied,
                    timeouts = metric.timeouts,
                    failures = metric.failures,
                    deniedRate = deniedRate,
                    timeoutRate = timeoutRate,
                    failureRate = failureRate,
                    reasons = reasons
                )
            }
            .sortedWith(
                compareByDescending<AdapterHotspotSnapshot> { maxOf(it.timeoutRate, it.failureRate, it.deniedRate) }
                    .thenByDescending { it.total }
            )
    }

    private fun aggregateAdapterCounters(adapters: Map<String, AdapterMetricSnapshot>): AdapterCountersSnapshot {
        var accepted = 0L
        var denied = 0L
        var timeouts = 0L
        var failures = 0L
        adapters.values.forEach { metric ->
            accepted += metric.accepted
            denied += metric.denied
            timeouts += metric.timeouts
            failures += metric.failures
        }
        return AdapterCountersSnapshot(
            accepted = accepted,
            denied = denied,
            timeouts = timeouts,
            failures = failures
        )
    }

    private fun recordAdapterAudit(
        pluginId: String,
        adapterId: String,
        action: String,
        outcome: AdapterInvocationOutcome,
        detail: String,
        durationNanos: Long,
        payloadEntries: Int
    ) {
        val window = pluginAdapterAudit.computeIfAbsent(pluginId) { MutableAdapterAuditWindow() }
        val now = System.currentTimeMillis()
        val normalizedAction = action.trim()
        val normalizedDetail = detail.trim().take(160)
        synchronized(window) {
            pruneAdapterAudit(window, now)
            val entry = AdapterAuditEntrySnapshot(
                timestampMillis = now,
                adapterId = adapterId,
                action = normalizedAction,
                outcome = outcome.name,
                detail = normalizedDetail,
                durationNanos = durationNanos.coerceAtLeast(0L),
                payloadEntries = payloadEntries.coerceAtLeast(0)
            )
            window.entries.addLast(entry)
            window.totalRecorded++
            window.perAdapterRetained[adapterId] = (window.perAdapterRetained[adapterId] ?: 0) + 1
            window.retainedEstimatedBytes += estimateAuditEntryBytes(entry)
            window.outcomeCounts[outcome.name] = (window.outcomeCounts[outcome.name] ?: 0L) + 1L
            trimAdapterAuditToBounds(window)
        }
    }

    private fun snapshotAdapterAudit(pluginId: String): AdapterAuditSnapshot {
        val window = pluginAdapterAudit.computeIfAbsent(pluginId) { MutableAdapterAuditWindow() }
        synchronized(window) {
            pruneAdapterAudit(window, System.currentTimeMillis())
            return AdapterAuditSnapshot(
                retention = adapterAuditRetention,
                totalRecorded = window.totalRecorded,
                retainedEntries = window.entries.size,
                retainedEstimatedBytes = window.retainedEstimatedBytes,
                outcomeCounts = window.outcomeCounts.toSortedMap(),
                recent = window.entries.toList()
            )
        }
    }

    private fun pruneAdapterAudit(window: MutableAdapterAuditWindow, nowMillis: Long) {
        val minTimestamp = nowMillis - adapterAuditRetention.maxAgeMillis
        while (true) {
            val head = window.entries.peekFirst() ?: break
            if (head.timestampMillis >= minTimestamp) break
            removeOldestAuditEntry(window)
        }
    }

    private fun trimAdapterAuditToBounds(window: MutableAdapterAuditWindow) {
        while (window.entries.size > adapterAuditRetention.maxEntriesPerPlugin) {
            removeOldestAuditEntry(window)
        }
        if (adapterAuditRetention.maxEntriesPerAdapter <= 0) return
        val adapterCap = adapterAuditRetention.maxEntriesPerAdapter
        while (true) {
            val overflowingAdapter = window.perAdapterRetained.entries
                .firstOrNull { it.value > adapterCap }
                ?.key
                ?: break
            val iterator = window.entries.iterator()
            var removed = false
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.adapterId != overflowingAdapter) continue
                iterator.remove()
                val nextCount = (window.perAdapterRetained[entry.adapterId] ?: 0) - 1
                if (nextCount <= 0) window.perAdapterRetained.remove(entry.adapterId) else window.perAdapterRetained[entry.adapterId] = nextCount
                removed = true
                break
            }
            if (!removed) {
                window.perAdapterRetained.remove(overflowingAdapter)
                break
            }
        }
        while (window.retainedEstimatedBytes > adapterAuditRetention.maxMemoryBytes) {
            removeOldestAuditEntry(window)
        }
    }

    private fun removeOldestAuditEntry(window: MutableAdapterAuditWindow) {
        val removed = window.entries.pollFirst() ?: return
        window.retainedEstimatedBytes = (window.retainedEstimatedBytes - estimateAuditEntryBytes(removed)).coerceAtLeast(0L)
        val nextCount = (window.perAdapterRetained[removed.adapterId] ?: 0) - 1
        if (nextCount <= 0) window.perAdapterRetained.remove(removed.adapterId) else window.perAdapterRetained[removed.adapterId] = nextCount
    }

    private fun estimateAuditEntryBytes(entry: AdapterAuditEntrySnapshot): Long {
        // Approximation used only for bounded in-memory retention control.
        val textBytes = (entry.adapterId.length + entry.action.length + entry.outcome.length + entry.detail.length) * 2L
        return 96L + textBytes
    }

    private class MutableMetric {
        private val runs = AtomicLong(0)
        private val failures = AtomicLong(0)
        private val totalNanos = AtomicLong(0)
        private val maxNanos = AtomicLong(0)

        fun record(durationNanos: Long, success: Boolean) {
            runs.incrementAndGet()
            if (!success) failures.incrementAndGet()
            totalNanos.addAndGet(durationNanos)
            maxNanos.updateAndGet { current -> max(current, durationNanos) }
        }

        fun snapshot(): MetricSnapshot {
            return MetricSnapshot(
                runs = runs.get(),
                failures = failures.get(),
                totalNanos = totalNanos.get(),
                maxNanos = maxNanos.get()
            )
        }
    }

    private class MutableAdapterMetric {
        private val accepted = AtomicLong(0)
        private val denied = AtomicLong(0)
        private val timeouts = AtomicLong(0)
        private val failures = AtomicLong(0)

        fun record(outcome: AdapterInvocationOutcome) {
            when (outcome) {
                AdapterInvocationOutcome.ACCEPTED -> accepted.incrementAndGet()
                AdapterInvocationOutcome.DENIED -> denied.incrementAndGet()
                AdapterInvocationOutcome.TIMEOUT -> timeouts.incrementAndGet()
                AdapterInvocationOutcome.FAILED -> failures.incrementAndGet()
            }
        }

        fun snapshot(): AdapterMetricSnapshot {
            return AdapterMetricSnapshot(
                accepted = accepted.get(),
                denied = denied.get(),
                timeouts = timeouts.get(),
                failures = failures.get()
            )
        }
    }

    private data class FaultStamp(
        val atMillis: Long,
        val source: String
    )

    private class MutableFaultWindow {
        val faults = ArrayDeque<FaultStamp>()
        val sourceCounts = linkedMapOf<String, Int>()
    }

    private class MutableAdapterAuditWindow {
        val entries = ArrayDeque<AdapterAuditEntrySnapshot>()
        var totalRecorded: Long = 0L
        var retainedEstimatedBytes: Long = 0L
        val perAdapterRetained = linkedMapOf<String, Int>()
        val outcomeCounts = linkedMapOf<String, Long>()
    }
}
