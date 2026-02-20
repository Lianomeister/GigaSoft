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
        handlers[command.lowercase()] = description to action
    }

    fun execute(ctx: PluginContext, sender: String, commandLine: String): String {
        val tokens = commandLine.trim().split(" ").filter { it.isNotBlank() }
        if (tokens.isEmpty()) return ""
        val key = tokens.first().lowercase()
        val args = tokens.drop(1)
        val handler = handlers[key] ?: return "Unknown command: $key"
        return handler.second(ctx, sender, args)
    }

    fun commands(): Map<String, String> = handlers.mapValues { it.value.first }
}

class RuntimeEventBus : EventBus {
    private val listeners = ConcurrentHashMap<Class<*>, CopyOnWriteArrayList<(Any) -> Unit>>()

    override fun <T : Any> subscribe(eventType: Class<T>, listener: (T) -> Unit) {
        listeners.computeIfAbsent(eventType) { CopyOnWriteArrayList() }.add { event -> listener(event as T) }
    }

    override fun publish(event: Any) {
        listeners[event::class.java]?.forEach { it(event) }
    }
}
