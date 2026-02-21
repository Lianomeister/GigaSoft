package com.clockwork.core

import com.clockwork.runtime.SystemIsolationSnapshot
import java.util.concurrent.ConcurrentHashMap

internal data class SystemIsolationPolicy(
    val failureThreshold: Int = 5,
    val baseCooldownTicks: Long = 40L,
    val maxCooldownTicks: Long = 800L,
    val maxIsolationLevel: Int = 10
) {
    val enabled: Boolean
        get() = failureThreshold > 0 && baseCooldownTicks > 0 && maxCooldownTicks > 0
}

internal data class SystemKey(
    val pluginId: String,
    val systemId: String
)

internal class SystemFaultIsolationController(
    private val policy: SystemIsolationPolicy
) {
    private val states = ConcurrentHashMap<SystemKey, MutableSystemState>()

    fun clear() {
        states.clear()
    }

    fun shouldRun(pluginId: String, systemId: String, tick: Long): Boolean {
        val key = SystemKey(pluginId = pluginId, systemId = systemId)
        val state = states.computeIfAbsent(key) { MutableSystemState() }
        if (!policy.enabled) return true
        if (tick >= state.isolateUntilTick) return true
        state.skippedTicks++
        return false
    }

    fun onSuccess(pluginId: String, systemId: String) {
        val key = SystemKey(pluginId = pluginId, systemId = systemId)
        val state = states.computeIfAbsent(key) { MutableSystemState() }
        state.consecutiveFailures = 0
        if (state.isolationLevel > 0) {
            state.isolationLevel--
        }
    }

    fun onFailure(pluginId: String, systemId: String, tick: Long, error: String?): Long {
        val key = SystemKey(pluginId = pluginId, systemId = systemId)
        val state = states.computeIfAbsent(key) { MutableSystemState() }
        state.lastFailureTick = tick
        state.lastError = error
        state.consecutiveFailures++
        if (!policy.enabled || state.consecutiveFailures < policy.failureThreshold) {
            return 0L
        }
        state.consecutiveFailures = 0
        state.isolationLevel = (state.isolationLevel + 1).coerceAtMost(policy.maxIsolationLevel)
        val shift = (state.isolationLevel - 1).coerceIn(0, 20)
        val multiplier = 1L shl shift
        val cooldown = (policy.baseCooldownTicks * multiplier).coerceAtMost(policy.maxCooldownTicks)
        state.isolateUntilTick = tick + cooldown
        state.isolationCount++
        return cooldown
    }

    fun snapshot(pluginId: String, systemId: String, tick: Long): SystemIsolationSnapshot {
        val key = SystemKey(pluginId = pluginId, systemId = systemId)
        val state = states.computeIfAbsent(key) { MutableSystemState() }
        val remainingTicks = (state.isolateUntilTick - tick).coerceAtLeast(0L)
        return SystemIsolationSnapshot(
            systemId = systemId,
            isolated = remainingTicks > 0L,
            remainingTicks = remainingTicks,
            consecutiveFailures = state.consecutiveFailures,
            isolationLevel = state.isolationLevel,
            isolationCount = state.isolationCount,
            skippedTicks = state.skippedTicks,
            isolateUntilTick = state.isolateUntilTick,
            lastFailureTick = state.lastFailureTick,
            lastError = state.lastError
        )
    }

    fun pruneTo(activeSystems: Set<SystemKey>): Set<SystemKey> {
        val removed = mutableSetOf<SystemKey>()
        val iterator = states.keys.iterator()
        while (iterator.hasNext()) {
            val key = iterator.next()
            if (key !in activeSystems) {
                iterator.remove()
                removed += key
            }
        }
        return removed
    }

    private data class MutableSystemState(
        var consecutiveFailures: Int = 0,
        var isolationLevel: Int = 0,
        var isolationCount: Long = 0L,
        var skippedTicks: Long = 0L,
        var isolateUntilTick: Long = 0L,
        var lastFailureTick: Long = 0L,
        var lastError: String? = null
    )
}
