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
    val adapters: Map<String, AdapterMetricSnapshot>
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

    fun snapshot(pluginId: String, activeTaskIds: List<String>): PluginRuntimeProfile {
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
        return PluginRuntimeProfile(
            pluginId = pluginId,
            activeTasks = sortedTaskIds.size,
            activeTaskIds = sortedTaskIds,
            systems = systems,
            tasks = tasks,
            adapters = adapters
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
