package com.clockwork.showcase.portableec

import com.clockwork.api.*

data class PortableEcState(
    val chests: Map<String, Map<Int, String>> = emptyMap()
)

class PortableEcPlugin : GigaPlugin {
    private val stateKey = "portable-ec-state"
    private val chests = linkedMapOf<String, MutableMap<Int, String>>()

    private val delegate = gigaPlugin(id = "showcase-portableec", name = "Showcase Portable-EC", version = "1.0.0") {
        commands {
            spec(command = "ec", aliases = listOf("enderchest"), usage = "ec") { inv ->
                val owner = inv.sender.id.lowercase()
                val slots = chests[owner].orEmpty().toSortedMap()
                if (slots.isEmpty()) return@spec CommandResult.ok("Portable EC empty")
                val preview = slots.entries.joinToString(", ") { "${it.key}:${it.value}" }
                CommandResult.ok("Portable EC: $preview")
            }

            spec(
                command = "ec-put",
                argsSchema = listOf(CommandArgSpec("slot", CommandArgType.INT), CommandArgSpec("item", CommandArgType.STRING)),
                usage = "ec-put <slot> <item>"
            ) { inv ->
                val owner = inv.sender.id.lowercase()
                val slot = inv.parsedArgs.requiredInt("slot").coerceIn(0, 26)
                val item = inv.parsedArgs.requiredString("item").trim()
                val map = chests.computeIfAbsent(owner) { linkedMapOf() }
                map[slot] = item
                saveState(inv.pluginContext)
                CommandResult.ok("Stored $item in EC slot $slot")
            }

            spec(
                command = "ec-take",
                argsSchema = listOf(
                    CommandArgSpec("slot", CommandArgType.INT),
                    CommandArgSpec("count", CommandArgType.INT, required = false)
                ),
                usage = "ec-take <slot> [count]"
            ) { inv ->
                val owner = inv.sender.id.lowercase()
                val slot = inv.parsedArgs.requiredInt("slot").coerceIn(0, 26)
                val count = (inv.parsedArgs.int("count") ?: 1).coerceIn(1, 64)
                val map = chests[owner] ?: return@spec CommandResult.error("Portable EC empty", code = "E_NOT_FOUND")
                val item = map.remove(slot) ?: return@spec CommandResult.error("Slot empty", code = "E_NOT_FOUND")
                inv.pluginContext.host.givePlayerItem(inv.sender.id, item, count)
                if (map.isEmpty()) chests.remove(owner)
                saveState(inv.pluginContext)
                CommandResult.ok("Took $item x$count from slot $slot")
            }
        }
    }

    override fun onEnable(ctx: PluginContext) {
        val state = ctx.storage.store(stateKey, PortableEcState::class.java, version = 1).load() ?: PortableEcState()
        chests.clear()
        state.chests.forEach { (owner, slots) -> chests[owner] = slots.toMutableMap() }
        delegate.onEnable(ctx)
    }

    override fun onDisable(ctx: PluginContext) {
        saveState(ctx)
        delegate.onDisable(ctx)
    }

    private fun saveState(ctx: PluginContext) {
        ctx.storage.store(stateKey, PortableEcState::class.java, version = 1).save(
            PortableEcState(chests = chests.mapValues { it.value.toMap() })
        )
    }
}
