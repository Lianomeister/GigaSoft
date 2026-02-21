package com.gigasoft.runtime

import com.gigasoft.api.CommandRegistry
import com.gigasoft.api.EventBus
import com.gigasoft.api.PluginContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class RuntimeCommandRegistry : CommandRegistry {
    private val handlers = ConcurrentHashMap<String, Pair<String, (PluginContext, String, List<String>) -> String>>()

    override fun register(
        command: String,
        description: String,
        action: (ctx: PluginContext, sender: String, args: List<String>) -> String
    ) {
        val key = normalizeCommand(command)
        val previous = handlers.putIfAbsent(key, description to action)
        require(previous == null) { "Duplicate command '$key'" }
    }

    override fun registerOrReplace(
        command: String,
        description: String,
        action: (ctx: PluginContext, sender: String, args: List<String>) -> String
    ) {
        val key = normalizeCommand(command)
        handlers[key] = description to action
    }

    override fun unregister(command: String): Boolean {
        val key = normalizeCommand(command)
        return handlers.remove(key) != null
    }

    fun execute(ctx: PluginContext, sender: String, commandLine: String): String {
        val tokens = tokenizeCommand(commandLine)
        if (tokens.isEmpty()) return ""
        val key = tokens[0].lowercase()
        val args = if (tokens.size > 1) tokens.subList(1, tokens.size) else emptyList()
        val handler = handlers[key] ?: return "Unknown command: $key"
        return handler.second(ctx, sender, args)
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
    private val listeners = ConcurrentHashMap<Class<*>, CopyOnWriteArrayList<(Any) -> Unit>>()
    private val dispatchCache = ConcurrentHashMap<Class<*>, Array<(Any) -> Unit>>()

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> subscribe(eventType: Class<T>, listener: (T) -> Unit) {
        listeners.computeIfAbsent(eventType) { CopyOnWriteArrayList() }.add { event -> listener(event as T) }
        if (mode == EventDispatchMode.POLYMORPHIC) {
            dispatchCache.clear()
        } else {
            dispatchCache.remove(eventType)
        }
    }

    override fun publish(event: Any) {
        val eventType = event::class.java
        val callbacks = dispatchCache.computeIfAbsent(eventType) {
            if (mode == EventDispatchMode.POLYMORPHIC) {
                val resolved = ArrayList<(Any) -> Unit>()
                for ((listenerType, bucket) in listeners) {
                    if (!listenerType.isAssignableFrom(eventType)) continue
                    resolved.addAll(bucket)
                }
                resolved.toTypedArray()
            } else {
                listeners[eventType]?.toTypedArray() ?: emptyArray()
            }
        }
        for (callback in callbacks) {
            callback(event)
        }
    }
}
