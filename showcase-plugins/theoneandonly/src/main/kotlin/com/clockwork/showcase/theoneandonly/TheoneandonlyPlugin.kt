package com.clockwork.showcase.theoneandonly

import com.clockwork.api.*

data class OneOnlyState(
    val limits: Map<String, Int> = emptyMap(),
    val knownPlayers: Set<String> = emptySet()
)

class TheoneandonlyPlugin : GigaPlugin {
    private val stateKey = "oneonly-state"
    private val limits = linkedMapOf<String, Int>()
    private val knownPlayers = linkedSetOf<String>()

    private val delegate = gigaPlugin(id = "showcase-theoneandonly", name = "Showcase Theoneandonly", version = "1.0.0") {
        systems {
            system("oneonly-enforce") { ctx ->
                knownPlayers.toList().forEach { player -> enforceForPlayer(ctx, player) }
            }
        }

        events {
            subscribe(GigaPlayerJoinEvent::class.java) { event ->
                knownPlayers.add(event.player.name.lowercase())
            }
        }

        commands {
            spec(
                command = "oneonly-limit",
                argsSchema = listOf(CommandArgSpec("item", CommandArgType.STRING), CommandArgSpec("count", CommandArgType.INT)),
                usage = "oneonly-limit <item> <count>"
            ) { inv ->
                val item = inv.parsedArgs.requiredString("item").trim().lowercase()
                val count = inv.parsedArgs.requiredInt("count").coerceIn(1, 64)
                limits[item] = count
                saveState(inv.pluginContext)
                CommandResult.ok("Limit set: $item -> $count")
            }

            spec(
                command = "oneonly-remove",
                argsSchema = listOf(CommandArgSpec("item", CommandArgType.STRING)),
                usage = "oneonly-remove <item>"
            ) { inv ->
                val item = inv.parsedArgs.requiredString("item").trim().lowercase()
                val removed = limits.remove(item) ?: return@spec CommandResult.error("No limit for $item", code = "E_NOT_FOUND")
                saveState(inv.pluginContext)
                CommandResult.ok("Removed limit for $item (was $removed)")
            }

            spec(command = "oneonly-list", usage = "oneonly-list") { _ ->
                if (limits.isEmpty()) return@spec CommandResult.ok("No one-only limits")
                CommandResult.ok(limits.entries.joinToString(" | ") { "${it.key}=${it.value}" })
            }

            spec(
                command = "oneonly-audit",
                argsSchema = listOf(CommandArgSpec("player", CommandArgType.STRING), CommandArgSpec("item", CommandArgType.STRING)),
                usage = "oneonly-audit <player> <item>"
            ) { inv ->
                val player = inv.parsedArgs.requiredString("player").trim()
                val item = inv.parsedArgs.requiredString("item").trim().lowercase()
                val total = countPlayerItem(inv.pluginContext, player, item)
                CommandResult.ok("$player has $item x$total")
            }
        }
    }

    override fun onEnable(ctx: PluginContext) {
        val state = ctx.storage.store(stateKey, OneOnlyState::class.java, version = 1).load() ?: OneOnlyState()
        limits.clear(); limits.putAll(state.limits)
        knownPlayers.clear(); knownPlayers.addAll(state.knownPlayers)
        delegate.onEnable(ctx)
    }

    override fun onDisable(ctx: PluginContext) {
        saveState(ctx)
        delegate.onDisable(ctx)
    }

    private fun enforceForPlayer(ctx: PluginContext, playerLower: String) {
        val playerName = playerLower
        limits.forEach { (item, limit) ->
            var remaining = limit
            for (slot in 0..53) {
                val slotItem = ctx.host.inventoryItem(playerName, slot)?.trim()?.lowercase() ?: continue
                if (slotItem != item) continue
                if (remaining > 0) {
                    remaining--
                } else {
                    ctx.host.setPlayerInventoryItem(playerName, slot, "minecraft:air")
                    ctx.host.sendPlayerMessage(playerName, "[Theoneandonly] Extra $item removed")
                }
            }
        }
    }

    private fun countPlayerItem(ctx: PluginContext, player: String, item: String): Int {
        var total = 0
        for (slot in 0..53) {
            if (ctx.host.inventoryItem(player, slot)?.trim()?.lowercase() == item) total++
        }
        return total
    }

    private fun saveState(ctx: PluginContext) {
        ctx.storage.store(stateKey, OneOnlyState::class.java, version = 1).save(
            OneOnlyState(limits = limits.toMap(), knownPlayers = knownPlayers.toSet())
        )
    }
}
