package com.gigasoft.runtime

import com.gigasoft.api.PluginChannelSpec
import com.gigasoft.api.PluginChannelStats
import com.gigasoft.api.PluginMessage
import com.gigasoft.api.PluginMessageResult
import com.gigasoft.api.PluginMessageStatus
import com.gigasoft.api.PluginNetwork
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

internal class RuntimePluginNetworkHub {
    private val channels = ConcurrentHashMap<String, ChannelState>()
    private val subscriptionsByPlugin = ConcurrentHashMap<String, MutableList<Subscription>>()
    private val pluginRateWindows = ConcurrentHashMap<String, RateWindow>()

    fun viewFor(pluginId: String): PluginNetwork {
        return RuntimePluginNetwork(pluginId, this)
    }

    fun removePlugin(pluginId: String) {
        val subscriptions = subscriptionsByPlugin.remove(pluginId).orEmpty()
        subscriptions.forEach { sub ->
            channels[sub.channel]?.listeners?.remove(sub.entry)
        }
    }

    fun registerChannel(pluginId: String, spec: PluginChannelSpec): Boolean {
        val id = normalizeChannel(spec.id)
        require(spec.schemaVersion > 0) { "schemaVersion must be > 0" }
        require(spec.maxInFlight > 0) { "maxInFlight must be > 0" }
        require(spec.maxMessagesPerMinute > 0) { "maxMessagesPerMinute must be > 0" }
        require(spec.maxPayloadEntries > 0) { "maxPayloadEntries must be > 0" }
        require(spec.maxPayloadTotalChars > 0) { "maxPayloadTotalChars must be > 0" }
        val state = ChannelState(
            ownerPluginId = pluginId,
            spec = spec.copy(id = id)
        )
        val existing = channels.putIfAbsent(id, state)
        return existing == null
    }

    fun listChannels(): List<PluginChannelSpec> = channels.values.map { it.spec }.sortedBy { it.id }

    fun subscribe(pluginId: String, channel: String, listener: (PluginMessage) -> Unit) {
        val id = normalizeChannel(channel)
        val state = channels[id] ?: return
        val entry = ListenerEntry(pluginId = pluginId, listener = listener)
        state.listeners += entry
        subscriptionsByPlugin.computeIfAbsent(pluginId) { mutableListOf() }.add(Subscription(id, entry))
    }

    fun unsubscribe(pluginId: String, channel: String, listener: (PluginMessage) -> Unit): Boolean {
        val id = normalizeChannel(channel)
        val state = channels[id] ?: return false
        val removed = state.listeners.removeIf { it.pluginId == pluginId && (it.listener === listener || it.listener == listener) }
        if (removed) {
            subscriptionsByPlugin[pluginId]?.removeIf { it.channel == id && (it.entry.listener === listener || it.entry.listener == listener) }
        }
        return removed
    }

    fun send(pluginId: String, channel: String, message: PluginMessage): PluginMessageResult {
        val id = normalizeChannel(channel)
        val state = channels[id] ?: return PluginMessageResult(PluginMessageStatus.CHANNEL_NOT_FOUND, reason = "Unknown channel '$channel'")
        val spec = state.spec

        if (message.schemaVersion != spec.schemaVersion) {
            state.rejected.incrementAndGet()
            return PluginMessageResult(
                status = PluginMessageStatus.SCHEMA_MISMATCH,
                reason = "schemaVersion ${message.schemaVersion} does not match ${spec.schemaVersion}"
            )
        }
        if (message.payload.size > spec.maxPayloadEntries) {
            state.rejected.incrementAndGet()
            return PluginMessageResult(
                status = PluginMessageStatus.PAYLOAD_INVALID,
                reason = "payload entries exceed ${spec.maxPayloadEntries}"
            )
        }
        var totalChars = 0
        message.payload.forEach { (k, v) ->
            totalChars += k.length + v.length
        }
        if (totalChars > spec.maxPayloadTotalChars) {
            state.rejected.incrementAndGet()
            return PluginMessageResult(
                status = PluginMessageStatus.PAYLOAD_INVALID,
                reason = "payload chars exceed ${spec.maxPayloadTotalChars}"
            )
        }
        if (!acquirePluginRateSlot(pluginId, spec.maxMessagesPerMinute)) {
            state.rejected.incrementAndGet()
            state.droppedQuota.incrementAndGet()
            return PluginMessageResult(
                status = PluginMessageStatus.QUOTA_EXCEEDED,
                reason = "plugin message quota exceeded (${spec.maxMessagesPerMinute}/min)"
            )
        }
        if (!acquireInFlight(state)) {
            state.rejected.incrementAndGet()
            state.droppedBackpressure.incrementAndGet()
            return PluginMessageResult(
                status = PluginMessageStatus.BACKPRESSURE,
                reason = "channel in-flight limit reached (${spec.maxInFlight})"
            )
        }
        return try {
            val outbound = message.copy(
                channel = id,
                sourcePluginId = message.sourcePluginId ?: pluginId
            )
            var delivered = 0
            state.listeners.forEach { listener ->
                try {
                    listener.listener(outbound)
                    delivered++
                } catch (_: Throwable) {
                    // Listener failure does not break channel.
                }
            }
            state.accepted.incrementAndGet()
            PluginMessageResult(
                status = PluginMessageStatus.ACCEPTED,
                deliveredSubscribers = delivered
            )
        } finally {
            state.inFlight.decrementAndGet()
        }
    }

    fun channelStats(channel: String): PluginChannelStats? {
        val id = normalizeChannel(channel)
        val state = channels[id] ?: return null
        return PluginChannelStats(
            channelId = state.spec.id,
            schemaVersion = state.spec.schemaVersion,
            inFlight = state.inFlight.get(),
            accepted = state.accepted.get(),
            rejected = state.rejected.get(),
            droppedBackpressure = state.droppedBackpressure.get(),
            droppedQuota = state.droppedQuota.get()
        )
    }

    private fun acquirePluginRateSlot(pluginId: String, maxPerMinute: Int): Boolean {
        val now = System.currentTimeMillis()
        val start = now - 60_000L
        val window = pluginRateWindows.computeIfAbsent(pluginId) { RateWindow() }
        synchronized(window) {
            while (true) {
                val head = window.calls.peekFirst() ?: break
                if (head >= start) break
                window.calls.removeFirst()
                window.size--
            }
            if (window.size >= maxPerMinute) return false
            window.calls.addLast(now)
            window.size++
            return true
        }
    }

    private fun acquireInFlight(state: ChannelState): Boolean {
        while (true) {
            val current = state.inFlight.get()
            if (current >= state.spec.maxInFlight) return false
            if (state.inFlight.compareAndSet(current, current + 1)) return true
        }
    }

    private fun normalizeChannel(raw: String): String {
        val id = raw.trim().lowercase()
        require(id.isNotBlank()) { "channel must not be blank" }
        return id
    }

    private data class ListenerEntry(
        val pluginId: String,
        val listener: (PluginMessage) -> Unit
    )

    private data class Subscription(
        val channel: String,
        val entry: ListenerEntry
    )

    private data class ChannelState(
        val ownerPluginId: String,
        val spec: PluginChannelSpec,
        val listeners: CopyOnWriteArrayList<ListenerEntry> = CopyOnWriteArrayList(),
        val inFlight: AtomicInteger = AtomicInteger(0),
        val accepted: AtomicLong = AtomicLong(0L),
        val rejected: AtomicLong = AtomicLong(0L),
        val droppedBackpressure: AtomicLong = AtomicLong(0L),
        val droppedQuota: AtomicLong = AtomicLong(0L)
    )

    private data class RateWindow(
        val calls: ArrayDeque<Long> = ArrayDeque(),
        var size: Int = 0
    )
}

private class RuntimePluginNetwork(
    private val pluginId: String,
    private val hub: RuntimePluginNetworkHub
) : PluginNetwork {
    override fun registerChannel(spec: PluginChannelSpec): Boolean = hub.registerChannel(pluginId, spec)
    override fun listChannels(): List<PluginChannelSpec> = hub.listChannels()
    override fun subscribe(channel: String, listener: (PluginMessage) -> Unit) = hub.subscribe(pluginId, channel, listener)
    override fun unsubscribe(channel: String, listener: (PluginMessage) -> Unit): Boolean = hub.unsubscribe(pluginId, channel, listener)
    override fun send(channel: String, message: PluginMessage): PluginMessageResult = hub.send(pluginId, channel, message)
    override fun channelStats(channel: String): PluginChannelStats? = hub.channelStats(channel)
}

