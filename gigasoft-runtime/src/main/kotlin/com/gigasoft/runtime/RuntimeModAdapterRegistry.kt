package com.gigasoft.runtime

import com.gigasoft.api.AdapterInvocation
import com.gigasoft.api.AdapterResponse
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

class RuntimeModAdapterRegistry(
    private val pluginId: String,
    private val logger: GigaLogger,
    private val securityConfig: AdapterSecurityConfig = AdapterSecurityConfig(),
    private val invocationObserver: (adapterId: String, outcome: AdapterInvocationOutcome) -> Unit = { _, _ -> }
) : ModAdapterRegistry {
    private val adapters = ConcurrentHashMap<String, ModAdapter>()
    private val callWindows = ConcurrentHashMap<String, RateWindow>()
    private val adapterIdRegex = Regex("^[a-zA-Z0-9._:-]{1,64}$")
    private val actionRegex = Regex("^[a-zA-Z0-9._:-]{1,64}$")
    private val maxPayloadEntries = securityConfig.maxPayloadEntries
    private val maxPayloadKeyChars = securityConfig.maxPayloadKeyChars
    private val maxPayloadValueChars = securityConfig.maxPayloadValueChars
    private val maxPayloadTotalChars = securityConfig.maxPayloadTotalChars
    private val maxCallsPerMinute = securityConfig.maxCallsPerMinute
    private val invocationTimeoutMillis = securityConfig.invocationTimeoutMillis
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

    override fun register(adapter: ModAdapter) {
        require(adapter.id.matches(adapterIdRegex)) { "Adapter id '${adapter.id}' is invalid for plugin '$pluginId'" }
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
        val adapter = adapters[adapterId]
            ?: return denied(
                adapterId,
                "Unknown adapter '$adapterId' in plugin '$pluginId'"
            )

        val validationError = validateInvocation(adapter, invocation)
        if (validationError != null) {
            return denied(adapterId, validationError)
        }

        if (!rateLimit(adapterId)) {
            return denied(
                adapterId,
                "Adapter '$adapterId' rate limit exceeded in plugin '$pluginId'"
            )
        }

        if (fastMode || invocationTimeoutMillis <= 0L) {
            return try {
                val response = adapter.invoke(invocation)
                invocationObserver(adapterId, AdapterInvocationOutcome.ACCEPTED)
                response
            } catch (t: Throwable) {
                invocationObserver(adapterId, AdapterInvocationOutcome.FAILED)
                AdapterResponse(
                    success = false,
                    message = "Adapter '$adapterId' failed: ${t.message}"
                )
            }
        }

        val future = CompletableFuture.supplyAsync({ adapter.invoke(invocation) }, invokeExecutor ?: directExecutor)
        return try {
            val response = future.get(invocationTimeoutMillis, TimeUnit.MILLISECONDS)
            invocationObserver(adapterId, AdapterInvocationOutcome.ACCEPTED)
            response
        } catch (_: TimeoutException) {
            // Best effort interruption of slow adapter work.
            future.cancel(true)
            invocationObserver(adapterId, AdapterInvocationOutcome.TIMEOUT)
            AdapterResponse(
                success = false,
                message = "Adapter '$adapterId' timed out after ${invocationTimeoutMillis}ms"
            )
        } catch (t: Throwable) {
            invocationObserver(adapterId, AdapterInvocationOutcome.FAILED)
            AdapterResponse(
                success = false,
                message = "Adapter '$adapterId' failed: ${t.message}"
            )
        }
    }

    private fun validateInvocation(adapter: ModAdapter, invocation: AdapterInvocation): String? {
        if (!invocation.action.matches(actionRegex)) {
            return "Invalid adapter action '${invocation.action}'"
        }
        val payload = invocation.payload
        if (fastMode) {
            val requiredCapability = payload["required_capability"]?.trim().orEmpty()
            if (requiredCapability.isNotEmpty() && requiredCapability !in adapter.capabilities) {
                return "Adapter '${adapter.id}' does not provide capability '$requiredCapability'"
            }
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

    private fun denied(adapterId: String, message: String): AdapterResponse {
        invocationObserver(adapterId, AdapterInvocationOutcome.DENIED)
        return AdapterResponse(success = false, message = message)
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
            return true
        }
    }

    fun shutdown() {
        invokeExecutor?.shutdownNow()
    }

    private data class RateWindow(
        val calls: ArrayDeque<Long> = ArrayDeque(),
        var size: Int = 0
    )
}
