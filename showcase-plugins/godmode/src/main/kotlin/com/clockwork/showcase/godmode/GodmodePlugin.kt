package com.clockwork.showcase.godmode

import com.clockwork.api.*
import java.util.concurrent.ConcurrentHashMap

class GodmodePlugin : GigaPlugin {
    private val godPlayers = ConcurrentHashMap.newKeySet<String>()

    private val delegate = gigaPlugin(id = "showcase-godmode", name = "Showcase Godmode", version = "1.0.0") {
        systems {
            system("godmode-heal-loop") { ctx ->
                godPlayers.forEach { playerName ->
                    val status = ctx.host.playerStatus(playerName) ?: return@forEach
                    if (status.health < status.maxHealth) {
                        ctx.host.setPlayerStatus(playerName, status.copy(health = status.maxHealth))
                    }
                }
            }
        }

        commands {
            spec(
                command = "godmode",
                description = "Toggle godmode for a player (admin-only by server policy)",
                argsSchema = listOf(CommandArgSpec("player", CommandArgType.STRING, required = false)),
                usage = "godmode [player]"
            ) { inv ->
                val ctx = inv.pluginContext
                val player = inv.parsedArgs.string("player") ?: inv.sender.id
                if (player.isBlank()) {
                    return@spec CommandResult.error("Player is required", code = "E_ARGS")
                }
                val enabled = if (godPlayers.contains(player.lowercase())) {
                    godPlayers.remove(player.lowercase())
                    false
                } else {
                    godPlayers.add(player.lowercase())
                    true
                }
                ctx.host.sendPlayerMessage(player, "[Godmode] ${if (enabled) "ENABLED" else "DISABLED"}")
                CommandResult.ok("Godmode ${if (enabled) "enabled" else "disabled"} for $player")
            }
        }
    }

    override fun onEnable(ctx: PluginContext) = delegate.onEnable(ctx)
    override fun onDisable(ctx: PluginContext) = delegate.onDisable(ctx)
}
