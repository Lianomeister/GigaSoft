package com.clockwork.showcase.invisframes

import com.clockwork.api.*

data class InvisFramesState(
    val enabledPlayers: Set<String> = emptySet()
)

class InvisFramesPlugin : GigaPlugin {
    private val stateKey = "invisframes-state"
    private val enabledPlayers = linkedSetOf<String>()
    private val invisFrameItemId = "minecraft:item_frame[invisible=true]"

    private val delegate = gigaPlugin(id = "showcase-invisframes", name = "Showcase InvisFrames", version = "1.0.0") {
        commands {
            spec(
                command = "invisframe-craft",
                argsSchema = listOf(
                    CommandArgSpec("player", CommandArgType.STRING, required = false),
                    CommandArgSpec("count", CommandArgType.INT, required = false)
                ),
                usage = "invisframe-craft [player] [count]"
            ) { inv ->
                val player = (inv.parsedArgs.string("player") ?: inv.sender.id).trim()
                val count = (inv.parsedArgs.int("count") ?: 1).coerceIn(1, 64)
                val added = inv.pluginContext.host.givePlayerItem(player, invisFrameItemId, count)
                if (added <= 0) return@spec CommandResult.error("Could not give item", code = "E_HOST")
                CommandResult.ok("Gave $player ${added}x invisible item frame")
            }

            spec(command = "invisframe-toggle", usage = "invisframe-toggle") { inv ->
                val key = inv.sender.id.lowercase()
                val enabled = if (key in enabledPlayers) {
                    enabledPlayers.remove(key)
                    false
                } else {
                    enabledPlayers.add(key)
                    true
                }
                inv.pluginContext.saveState(stateKey, InvisFramesState(enabledPlayers.toSet()))
                CommandResult.ok("InvisFrames ${if (enabled) "enabled" else "disabled"} for ${inv.sender.id}")
            }

            spec(
                command = "invisframe-status",
                argsSchema = listOf(CommandArgSpec("player", CommandArgType.STRING, required = false)),
                usage = "invisframe-status [player]"
            ) { inv ->
                val key = (inv.parsedArgs.string("player") ?: inv.sender.id).trim().lowercase()
                CommandResult.ok("$key enabled=${key in enabledPlayers}")
            }
        }
    }

    override fun onEnable(ctx: PluginContext) {
        val state = ctx.loadOrDefault(stateKey) { InvisFramesState() }
        enabledPlayers.clear(); enabledPlayers.addAll(state.enabledPlayers)
        delegate.onEnable(ctx)
    }

    override fun onDisable(ctx: PluginContext) {
        ctx.saveState(stateKey, InvisFramesState(enabledPlayers.toSet()))
        delegate.onDisable(ctx)
    }
}
