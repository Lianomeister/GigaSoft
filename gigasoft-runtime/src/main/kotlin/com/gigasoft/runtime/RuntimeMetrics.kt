package com.gigasoft.runtime

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
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
    val slowSystems: List<SlowSystemSnapshot>,
    val adapterHotspots: List<AdapterHotspotSnapshot>,
    val isolatedSystems: List<SystemIsolationSnapshot>,
    val diagnosticsThresholds: ProfileDiagnosticsThresholds
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

class RuntimeMetrics {
    private val thresholds = ProfileDiagnosticsThresholds()
    private val systemMetrics = ConcurrentHashMap<String, ConcurrentHashMap<String, MutableMetric>>()
    private val taskMetrics = ConcurrentHashMap<String, ConcurrentHashMap<String, MutableMetric>>()
    private val adapterMetrics = ConcurrentHashMap<String, ConcurrentHashMap<String, MutableAdapterMetric>>()

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
        val metric = adapterMetrics
            .computeIfAbsent(pluginId) { ConcurrentHashMap() }
            .computeIfAbsent(adapterId) { MutableAdapterMetric() }
        metric.record(outcome)
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
        val sortedTaskIds = activeTaskIds.sorted()
        val slowSystems = detectSlowSystems(systems, thresholds)
        val adapterHotspots = detectAdapterHotspots(adapters, thresholds)
        return PluginRuntimeProfile(
            pluginId = pluginId,
            activeTasks = sortedTaskIds.size,
            activeTaskIds = sortedTaskIds,
            systems = systems,
            tasks = tasks,
            adapters = adapters,
            slowSystems = slowSystems,
            adapterHotspots = adapterHotspots,
            isolatedSystems = isolatedSystems.sortedByDescending { it.remainingTicks },
            diagnosticsThresholds = thresholds
        )
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
}
