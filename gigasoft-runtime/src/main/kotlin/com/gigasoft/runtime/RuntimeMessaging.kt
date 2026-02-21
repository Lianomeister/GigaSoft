package com.gigasoft.runtime

import com.gigasoft.api.CommandRegistry
import com.gigasoft.api.EventBus
import com.gigasoft.api.GigaCommandPostExecuteEvent
import com.gigasoft.api.GigaCommandPreExecuteEvent
import com.gigasoft.api.PluginContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class RuntimeCommandRegistry(
    private val pluginId: String = "unknown"
) : CommandRegistry {
    private val handlers = ConcurrentHashMap<String, Pair<String, (PluginContext, String, List<String>) -> String>>()
    private val aliases = ConcurrentHashMap<String, String>()

    override fun register(
        command: String,
        description: String,
        action: (ctx: PluginContext, sender: String, args: List<String>) -> String
    ) {
        val key = normalizeCommand(command)
        require(!aliases.containsKey(key)) { "Command '$key' collides with existing alias" }
        val previous = handlers.putIfAbsent(key, description to action)
        require(previous == null) { "Duplicate command '$key'" }
    }

    override fun registerOrReplace(
        command: String,
        description: String,
        action: (ctx: PluginContext, sender: String, args: List<String>) -> String
    ) {
        val key = normalizeCommand(command)
        require(!aliases.containsKey(key)) { "Command '$key' collides with existing alias" }
        handlers[key] = description to action
    }

    override fun unregister(command: String): Boolean {
        val key = normalizeCommand(command)
        val removed = handlers.remove(key) != null
        if (removed) {
            aliases.entries.removeIf { (_, target) -> target == key }
        }
        return removed
    }

    override fun registerAlias(alias: String, command: String): Boolean {
        val aliasKey = normalizeCommand(alias)
        val commandKey = normalizeCommand(command)
        require(aliasKey != commandKey) { "Alias '$aliasKey' must not equal command id" }
        require(handlers.containsKey(commandKey)) { "Cannot alias unknown command '$commandKey'" }
        require(!handlers.containsKey(aliasKey)) { "Alias '$aliasKey' collides with command id" }
        val previous = aliases.putIfAbsent(aliasKey, commandKey)
        if (previous == null) return true
        require(previous == commandKey) { "Alias '$aliasKey' already points to '$previous'" }
        return false
    }

    override fun unregisterAlias(alias: String): Boolean {
        val aliasKey = normalizeCommand(alias)
        return aliases.remove(aliasKey) != null
    }

    override fun resolve(commandOrAlias: String): String? {
        val key = normalizeCommand(commandOrAlias)
        return when {
            handlers.containsKey(key) -> key
            else -> aliases[key]
        }
    }

    override fun registeredCommands(): Map<String, String> = commands()

    fun aliases(): Map<String, String> = aliases.toMap()

    fun unregisterAll(): Int {
        val count = handlers.size
        handlers.clear()
        aliases.clear()
        return count
    }

    fun execute(ctx: PluginContext, sender: String, commandLine: String): String {
        val tokens = tokenizeCommand(commandLine)
        if (tokens.isEmpty()) return ""
        val requested = tokens[0].lowercase()
        val key = resolve(requested) ?: return "Unknown command: $requested"
        val args = if (tokens.size > 1) tokens.subList(1, tokens.size) else emptyList()
        val handler = handlers[key] ?: return "Unknown command: $requested"
        val started = System.nanoTime()
        val pre = GigaCommandPreExecuteEvent(
            pluginId = pluginId,
            command = key,
            sender = sender,
            args = args,
            rawCommandLine = commandLine
        )
        ctx.events.publish(pre)
        if (pre.cancelled) {
            val response = pre.overrideResponse ?: pre.cancelReason ?: "Command '$key' cancelled"
            ctx.events.publish(
                GigaCommandPostExecuteEvent(
                    pluginId = pluginId,
                    command = key,
                    sender = sender,
                    args = args,
                    rawCommandLine = commandLine,
                    response = response,
                    success = false,
                    durationNanos = System.nanoTime() - started,
                    error = pre.cancelReason ?: "cancelled"
                )
            )
            return response
        }
        pre.overrideResponse?.let { response ->
            ctx.events.publish(
                GigaCommandPostExecuteEvent(
                    pluginId = pluginId,
                    command = key,
                    sender = sender,
                    args = args,
                    rawCommandLine = commandLine,
                    response = response,
                    success = true,
                    durationNanos = System.nanoTime() - started
                )
            )
            return response
        }
        return try {
            val response = handler.second(ctx, sender, args)
            ctx.events.publish(
                GigaCommandPostExecuteEvent(
                    pluginId = pluginId,
                    command = key,
                    sender = sender,
                    args = args,
                    rawCommandLine = commandLine,
                    response = response,
                    success = true,
                    durationNanos = System.nanoTime() - started
                )
            )
            response
        } catch (t: Throwable) {
            val response = "Command '$key' failed: ${t.message ?: t.javaClass.simpleName}"
            ctx.events.publish(
                GigaCommandPostExecuteEvent(
                    pluginId = pluginId,
                    command = key,
                    sender = sender,
                    args = args,
                    rawCommandLine = commandLine,
                    response = response,
                    success = false,
                    durationNanos = System.nanoTime() - started,
                    error = t.message ?: t.javaClass.simpleName
                )
            )
            response
        }
    }

    fun commands(): Map<String, String> = handlers.mapValues { it.value.first }

    private fun normalizeCommand(command: String): String {
        val key = command.trim().lowercase()
        require(key.isNotEmpty()) { "Command name must not be blank" }
        return key
    }

    private fun tokenizeCommand(input: String): List<String> {
        val out = ArrayList<String>(8)
        val len = input.length
        var i = 0
        while (i < len) {
            while (i < len && input[i].isWhitespace()) i++
            if (i >= len) break
            val start = i
            while (i < len && !input[i].isWhitespace()) i++
            out.add(input.substring(start, i))
        }
        return out
    }
}

class RuntimeEventBus(
    private val mode: EventDispatchMode = EventDispatchMode.EXACT
) : EventBus {
    private val listeners = ConcurrentHashMap<Class<*>, CopyOnWriteArrayList<ListenerEntry>>()
    private val dispatchCache = ConcurrentHashMap<Class<*>, Array<(Any) -> Unit>>()

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> subscribe(eventType: Class<T>, listener: (T) -> Unit) {
        listeners.computeIfAbsent(eventType) { CopyOnWriteArrayList() }
            .add(ListenerEntry(rawListener = listener as (Any) -> Unit, callback = { event -> listener(event as T) }))
        if (mode != EventDispatchMode.EXACT) {
            dispatchCache.clear()
        } else {
            dispatchCache.remove(eventType)
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> unsubscribe(eventType: Class<T>, listener: (T) -> Unit): Boolean {
        val bucket = listeners[eventType] ?: return false
        val removed = bucket.removeIf { entry ->
            entry.rawListener === listener || entry.rawListener == listener
        }
        if (removed) {
            if (mode != EventDispatchMode.EXACT) {
                dispatchCache.clear()
            } else {
                dispatchCache.remove(eventType)
            }
        }
        return removed
    }

    override fun publish(event: Any) {
        val eventType = event::class.java
        val callbacks = dispatchCache.computeIfAbsent(eventType) {
            when (mode) {
                EventDispatchMode.EXACT -> listeners[eventType]?.map { it.callback }?.toTypedArray() ?: emptyArray()
                EventDispatchMode.POLYMORPHIC -> resolveHierarchical(eventType, exactFirst = false)
                EventDispatchMode.HYBRID -> resolveHierarchical(eventType, exactFirst = true)
            }
        }
        for (callback in callbacks) {
            callback(event)
        }
    }

    private fun resolveHierarchical(eventType: Class<*>, exactFirst: Boolean): Array<(Any) -> Unit> {
        val resolved = ArrayList<(Any) -> Unit>()
        val ordered = listeners.entries
            .asSequence()
            .filter { (listenerType, _) -> listenerType.isAssignableFrom(eventType) }
            .sortedWith(
                compareBy<Map.Entry<Class<*>, CopyOnWriteArrayList<ListenerEntry>>> { entry ->
                    if (exactFirst && entry.key == eventType) 0 else 1
                }
                    .thenBy { entry -> inheritanceDistance(eventType, entry.key) }
                    .thenBy { entry -> entry.key.name }
            )
            .toList()
        ordered.forEach { bucket -> bucket.value.forEach { resolved.add(it.callback) } }
        return resolved.toTypedArray()
    }

    private fun inheritanceDistance(eventType: Class<*>, listenerType: Class<*>): Int {
        if (eventType == listenerType) return 0
        var distance = 1
        var current = eventType.superclass
        while (current != null) {
            if (current == listenerType) return distance
            distance++
            current = current.superclass
        }
        return if (listenerType.isInterface && listenerType.isAssignableFrom(eventType)) {
            distance + 64
        } else {
            Int.MAX_VALUE
        }
    }

    private data class ListenerEntry(
        val rawListener: (Any) -> Unit,
        val callback: (Any) -> Unit
    )
}
