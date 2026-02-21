package com.gigasoft.runtime

import com.gigasoft.api.AdapterInvocation
import com.gigasoft.api.AdapterResponse
import com.gigasoft.api.EventBus
import com.gigasoft.api.GigaAdapterPostInvokeEvent
import com.gigasoft.api.GigaAdapterPreInvokeEvent
import com.gigasoft.api.GigaLogger
import com.gigasoft.api.ModAdapter
import com.gigasoft.api.ModAdapterRegistry
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

class RuntimeModAdapterRegistry(
    private val pluginId: String,
    private val logger: GigaLogger,
    private val securityConfig: AdapterSecurityConfig = AdapterSecurityConfig(),
    private val eventBus: EventBus? = null,
    private val invocationObserver: (adapterId: String, outcome: AdapterInvocationOutcome) -> Unit = { _, _ -> }
) : ModAdapterRegistry {
    private val adapters = ConcurrentHashMap<String, ModAdapter>()
    private val callWindows = ConcurrentHashMap<String, RateWindow>()
    private val maxPayloadEntries = securityConfig.maxPayloadEntries
    private val maxPayloadKeyChars = securityConfig.maxPayloadKeyChars
    private val maxPayloadValueChars = securityConfig.maxPayloadValueChars
    private val maxPayloadTotalChars = securityConfig.maxPayloadTotalChars
    private val maxCallsPerMinute = securityConfig.maxCallsPerMinute
    private val maxCallsPerMinutePerPlugin = securityConfig.maxCallsPerMinutePerPlugin
    private val maxConcurrentInvocationsPerAdapter = securityConfig.maxConcurrentInvocationsPerAdapter
    private val invocationTimeoutMillis = securityConfig.invocationTimeoutMillis
    private val auditLogEnabled = securityConfig.auditLogEnabled
    private val auditLogSuccesses = securityConfig.auditLogSuccesses
    private val fastMode = securityConfig.executionMode == AdapterExecutionMode.FAST
    private val threadCounter = AtomicInteger(0)
    private val directExecutor = java.util.concurrent.Executor { it.run() }
    private val invokeExecutor = if (fastMode) {
        null
    } else {
        Executors.newFixedThreadPool(
            Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
        ) { runnable ->
            Thread(runnable, "gigasoft-adapter-$pluginId-${threadCounter.incrementAndGet()}").apply { isDaemon = true }
        }
    }
    @Volatile
    private var listCache: List<ModAdapter> = emptyList()
    @Volatile
    private var listDirty = true
    private val validActionCache = ConcurrentHashMap.newKeySet<String>()
    private val pluginWindow = RateWindow()
    private val concurrentInvocations = ConcurrentHashMap<String, AtomicInteger>()

    override fun register(adapter: ModAdapter) {
        require(isValidToken(adapter.id)) { "Adapter id '${adapter.id}' is invalid for plugin '$pluginId'" }
        val previous = adapters.putIfAbsent(adapter.id, adapter)
        require(previous == null) { "Duplicate adapter id '${adapter.id}' in plugin '$pluginId'" }
        listDirty = true
        logger.info("Registered adapter ${adapter.id}@${adapter.version}")
    }

    override fun list(): List<ModAdapter> {
        if (!listDirty) return listCache
        synchronized(this) {
            if (!listDirty) return listCache
            listCache = adapters.values.sortedBy { it.id }
            listDirty = false
            return listCache
        }
    }

    override fun find(id: String): ModAdapter? = adapters[id]

    override fun invoke(adapterId: String, invocation: AdapterInvocation): AdapterResponse {
        val started = System.nanoTime()
        val pre = GigaAdapterPreInvokeEvent(
            pluginId = pluginId,
            adapterId = adapterId,
            action = invocation.action,
            payload = invocation.payload
        )
        eventBus?.publish(pre)
        if (pre.cancelled) {
            val response = pre.overrideResponse
                ?: AdapterResponse(success = false, message = pre.cancelReason ?: "Adapter invocation cancelled")
            invocationObserver(adapterId, AdapterInvocationOutcome.DENIED)
            audit(adapterId, invocation, "DENIED", response.message ?: "Adapter invocation cancelled")
            publishPost(adapterId, invocation, response, "DENIED", started)
            return response
        }
        pre.overrideResponse?.let { response ->
            invocationObserver(adapterId, AdapterInvocationOutcome.ACCEPTED)
            audit(adapterId, invocation, "ACCEPTED", "Adapter invocation overridden by pre event")
            publishPost(adapterId, invocation, response, "ACCEPTED", started)
            return response
        }

        val adapter = adapters[adapterId]
            ?: return denied(
                adapterId,
                invocation,
                "Unknown adapter '$adapterId' in plugin '$pluginId'",
                started
            )

        val validationError = validateInvocation(adapter, invocation)
        if (validationError != null) {
            return denied(adapterId, invocation, validationError, started)
        }

        if (!rateLimit(adapterId)) {
            return denied(
                adapterId,
                invocation,
                "Adapter '$adapterId' rate limit exceeded in plugin '$pluginId'",
                started
            )
        }

        if (!tryAcquireConcurrentSlot(adapterId)) {
            return denied(
                adapterId,
                invocation,
                "Adapter '$adapterId' concurrency limit exceeded in plugin '$pluginId'",
                started
            )
        }

        val released = AtomicBoolean(false)
        fun releaseOnce() {
            if (!released.compareAndSet(false, true)) return
            releaseConcurrentSlot(adapterId)
        }

        if (fastMode || invocationTimeoutMillis <= 0L) {
            return try {
                val response = adapter.invoke(invocation)
                invocationObserver(adapterId, AdapterInvocationOutcome.ACCEPTED)
                audit(adapterId, invocation, "ACCEPTED", "Adapter invocation accepted")
                publishPost(adapterId, invocation, response, "ACCEPTED", started)
                response
            } catch (t: Throwable) {
                invocationObserver(adapterId, AdapterInvocationOutcome.FAILED)
                val response = AdapterResponse(
                    success = false,
                    message = "Adapter '$adapterId' failed: ${t.message}"
                )
                audit(adapterId, invocation, "FAILED", response.message.orEmpty())
                publishPost(adapterId, invocation, response, "FAILED", started)
                response
            } finally {
                releaseOnce()
            }
        }

        val future = CompletableFuture.supplyAsync({ adapter.invoke(invocation) }, invokeExecutor ?: directExecutor)
        return try {
            val response = future.get(invocationTimeoutMillis, TimeUnit.MILLISECONDS)
            invocationObserver(adapterId, AdapterInvocationOutcome.ACCEPTED)
            audit(adapterId, invocation, "ACCEPTED", "Adapter invocation accepted")
            publishPost(adapterId, invocation, response, "ACCEPTED", started)
            response
        } catch (_: TimeoutException) {
            // Best effort interruption of slow adapter work.
            future.cancel(true)
            invocationObserver(adapterId, AdapterInvocationOutcome.TIMEOUT)
            val response = AdapterResponse(
                success = false,
                message = "Adapter '$adapterId' timed out after ${invocationTimeoutMillis}ms"
            )
            audit(adapterId, invocation, "TIMEOUT", response.message.orEmpty())
            publishPost(adapterId, invocation, response, "TIMEOUT", started)
            response
        } catch (t: Throwable) {
            invocationObserver(adapterId, AdapterInvocationOutcome.FAILED)
            val response = AdapterResponse(
                success = false,
                message = "Adapter '$adapterId' failed: ${t.message}"
            )
            audit(adapterId, invocation, "FAILED", response.message.orEmpty())
            publishPost(adapterId, invocation, response, "FAILED", started)
            response
        } finally {
            releaseOnce()
        }
    }

    private fun validateInvocation(adapter: ModAdapter, invocation: AdapterInvocation): String? {
        val action = invocation.action
        if (!isValidAction(action)) {
            return "Invalid adapter action '${invocation.action}'"
        }
        val payload = invocation.payload
        if (payload.isEmpty()) {
            return null
        }
        if (payload.size > maxPayloadEntries) {
            return "Adapter payload exceeds $maxPayloadEntries entries"
        }
        var totalChars = 0
        for ((key, value) in payload) {
            if (key.isBlank() || key.length > maxPayloadKeyChars) {
                return "Adapter payload contains invalid key"
            }
            if (value.length > maxPayloadValueChars) {
                return "Adapter payload contains oversized value"
            }
            totalChars += key.length + value.length
            if (totalChars > maxPayloadTotalChars) {
                return "Adapter payload exceeds ${maxPayloadTotalChars} chars"
            }
        }
        val requiredCapability = payload["required_capability"]?.trim().orEmpty()
        if (requiredCapability.isNotEmpty() && requiredCapability !in adapter.capabilities) {
            return "Adapter '${adapter.id}' does not provide capability '$requiredCapability'"
        }
        return null
    }

    private fun denied(adapterId: String, invocation: AdapterInvocation, message: String, started: Long): AdapterResponse {
        invocationObserver(adapterId, AdapterInvocationOutcome.DENIED)
        audit(adapterId, invocation, "DENIED", message)
        val response = AdapterResponse(success = false, message = message)
        publishPost(adapterId, invocation, response, "DENIED", started)
        return response
    }

    private fun publishPost(
        adapterId: String,
        invocation: AdapterInvocation,
        response: AdapterResponse,
        outcome: String,
        started: Long
    ) {
        eventBus?.publish(
            GigaAdapterPostInvokeEvent(
                pluginId = pluginId,
                adapterId = adapterId,
                action = invocation.action,
                payload = invocation.payload,
                response = response,
                outcome = outcome,
                durationNanos = System.nanoTime() - started
            )
        )
    }

    private fun rateLimit(adapterId: String): Boolean {
        if (fastMode || maxCallsPerMinute <= 0) return true
        val now = System.currentTimeMillis()
        val windowStart = now - 60_000L
        val window = callWindows.computeIfAbsent(adapterId) { RateWindow() }
        synchronized(window) {
            while (true) {
                val head = window.calls.peekFirst() ?: break
                if (head >= windowStart) break
                window.calls.removeFirst()
                window.size--
            }
            if (window.size >= maxCallsPerMinute) {
                return false
            }
            window.calls.addLast(now)
            window.size++
            if (maxCallsPerMinutePerPlugin > 0 && !pluginRateLimit(now, maxCallsPerMinutePerPlugin)) {
                window.calls.removeLast()
                window.size--
                return false
            }
            return true
        }
    }

    private fun pluginRateLimit(now: Long, limit: Int): Boolean {
        val windowStart = now - 60_000L
        synchronized(pluginWindow) {
            while (true) {
                val head = pluginWindow.calls.peekFirst() ?: break
                if (head >= windowStart) break
                pluginWindow.calls.removeFirst()
                pluginWindow.size--
            }
            if (pluginWindow.size >= limit) return false
            pluginWindow.calls.addLast(now)
            pluginWindow.size++
            return true
        }
    }

    private fun tryAcquireConcurrentSlot(adapterId: String): Boolean {
        if (fastMode || maxConcurrentInvocationsPerAdapter <= 0) return true
        val counter = concurrentInvocations.computeIfAbsent(adapterId) { AtomicInteger(0) }
        while (true) {
            val current = counter.get()
            if (current >= maxConcurrentInvocationsPerAdapter) return false
            if (counter.compareAndSet(current, current + 1)) return true
        }
    }

    private fun releaseConcurrentSlot(adapterId: String) {
        if (fastMode || maxConcurrentInvocationsPerAdapter <= 0) return
        val counter = concurrentInvocations[adapterId] ?: return
        while (true) {
            val current = counter.get()
            if (current <= 0) return
            if (counter.compareAndSet(current, current - 1)) return
        }
    }

    private fun audit(adapterId: String, invocation: AdapterInvocation, outcome: String, detail: String) {
        if (!auditLogEnabled) return
        if (!auditLogSuccesses && outcome == "ACCEPTED") return
        val payload = summarizePayload(invocation.payload)
        logger.info(
            "[adapter-audit] plugin=$pluginId adapter=$adapterId action=${invocation.action} " +
                "outcome=$outcome detail=\"$detail\" payload=$payload mode=${securityConfig.executionMode}"
        )
    }

    private fun summarizePayload(payload: Map<String, String>): String {
        if (payload.isEmpty()) return "entries=0"
        val keys = payload.keys
            .sorted()
            .take(8)
            .joinToString(",")
        return "entries=${payload.size} keys=[$keys]"
    }

    fun shutdown() {
        invokeExecutor?.shutdownNow()
    }

    private data class RateWindow(
        val calls: ArrayDeque<Long> = ArrayDeque(),
        var size: Int = 0
    )

    private fun isValidAction(action: String): Boolean {
        if (validActionCache.contains(action)) return true
        if (!isValidToken(action)) return false
        validActionCache += action
        return true
    }

    private fun isValidToken(value: String): Boolean {
        val len = value.length
        if (len == 0 || len > 64) return false
        for (ch in value) {
            val ok = ch in 'a'..'z' ||
                ch in 'A'..'Z' ||
                ch in '0'..'9' ||
                ch == '.' || ch == '_' || ch == ':' || ch == '-'
            if (!ok) return false
        }
        return true
    }
}
