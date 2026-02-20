package com.gigasoft.runtime

import com.gigasoft.api.AdapterInvocation
import com.gigasoft.api.AdapterResponse
import com.gigasoft.api.GigaLogger
import com.gigasoft.api.ModAdapter
import com.gigasoft.api.ModAdapterRegistry
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class RuntimeModAdapterRegistry(
    private val pluginId: String,
    private val logger: GigaLogger,
    private val invocationObserver: (adapterId: String, outcome: AdapterInvocationOutcome) -> Unit = { _, _ -> }
) : ModAdapterRegistry {
    private val adapters = ConcurrentHashMap<String, ModAdapter>()
    private val callWindows = ConcurrentHashMap<String, ConcurrentLinkedDeque<Long>>()
    private val adapterIdRegex = Regex("^[a-zA-Z0-9._:-]{1,64}$")
    private val actionRegex = Regex("^[a-zA-Z0-9._:-]{1,64}$")
    private val maxPayloadEntries = 32
    private val maxPayloadKeyChars = 64
    private val maxPayloadValueChars = 512
    private val maxPayloadTotalChars = 4096
    private val maxCallsPerMinute = 180
    private val invocationTimeoutMillis = 250L

    override fun register(adapter: ModAdapter) {
        require(adapter.id.matches(adapterIdRegex)) { "Adapter id '${adapter.id}' is invalid for plugin '$pluginId'" }
        val previous = adapters.putIfAbsent(adapter.id, adapter)
        require(previous == null) { "Duplicate adapter id '${adapter.id}' in plugin '$pluginId'" }
        logger.info("Registered adapter ${adapter.id}@${adapter.version}")
    }

    override fun list(): List<ModAdapter> = adapters.values.sortedBy { it.id }

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

        return try {
            val response = CompletableFuture
                .supplyAsync { adapter.invoke(invocation) }
                .get(invocationTimeoutMillis, TimeUnit.MILLISECONDS)
            invocationObserver(adapterId, AdapterInvocationOutcome.ACCEPTED)
            response
        } catch (_: TimeoutException) {
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
        if (invocation.payload.size > maxPayloadEntries) {
            return "Adapter payload exceeds $maxPayloadEntries entries"
        }
        val totalChars = invocation.payload.entries.sumOf { it.key.length + it.value.length }
        if (totalChars > maxPayloadTotalChars) {
            return "Adapter payload exceeds ${maxPayloadTotalChars} chars"
        }
        if (invocation.payload.keys.any { it.isBlank() || it.length > maxPayloadKeyChars }) {
            return "Adapter payload contains invalid key"
        }
        if (invocation.payload.values.any { it.length > maxPayloadValueChars }) {
            return "Adapter payload contains oversized value"
        }
        val requiredCapability = invocation.payload["required_capability"]?.trim().orEmpty()
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
        val now = System.currentTimeMillis()
        val windowStart = now - 60_000L
        val deque = callWindows.computeIfAbsent(adapterId) { ConcurrentLinkedDeque() }
        while (true) {
            val head = deque.peekFirst() ?: break
            if (head >= windowStart) break
            deque.pollFirst()
        }
        if (deque.size >= maxCallsPerMinute) {
            return false
        }
        deque.addLast(now)
        return true
    }
}
