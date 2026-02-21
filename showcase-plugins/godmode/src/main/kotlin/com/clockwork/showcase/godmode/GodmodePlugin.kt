package com.clockwork.showcase.godmode

import com.clockwork.api.*
import java.util.concurrent.ConcurrentHashMap

data class GodmodeState(
    val enabledPlayers: Set<String> = emptySet()
)

class GodmodePlugin : GigaPlugin {
    private val stateKey = "godmode-state"
    private val godPlayers = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var pluginContextRef: PluginContext? = null

    private val delegate = gigaPlugin(id = "showcase-godmode", name = "Showcase Godmode", version = "1.1.0") {
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
                val player = normalizePlayer(inv.parsedArgs.string("player") ?: inv.sender.id)
                if (player.isBlank()) {
                    return@spec CommandResult.error("Player is required", code = "E_ARGS")
                }
                val enabled = if (godPlayers.contains(player)) {
                    godPlayers.remove(player)
                    false
                } else {
                    godPlayers.add(player)
                    true
                }
                saveState(ctx)
                ctx.host.sendPlayerMessage(player, "[Godmode] ${if (enabled) "ENABLED" else "DISABLED"}")
                CommandResult.ok("Godmode ${if (enabled) "enabled" else "disabled"} for $player")
            }

            spec(
                command = "godmode-list",
                description = "List players with godmode enabled",
                usage = "godmode-list"
            ) { _ ->
                if (godPlayers.isEmpty()) {
                    return@spec CommandResult.ok("No players currently in godmode")
                }
                val players = godPlayers.toList().sorted().joinToString(", ")
                CommandResult.ok("Godmode enabled: $players")
            }

            spec(
                command = "godmode-clear",
                description = "Disable godmode for all players",
                usage = "godmode-clear"
            ) { inv ->
                val count = godPlayers.size
                godPlayers.clear()
                saveState(inv.pluginContext)
                CommandResult.ok("Cleared godmode for $count player(s)")
            }
        }
    }

    override fun onEnable(ctx: PluginContext) {
        pluginContextRef = ctx
        loadState(ctx)
        delegate.onEnable(ctx)
    }

    override fun onDisable(ctx: PluginContext) {
        saveState(ctx)
        pluginContextRef = null
        delegate.onDisable(ctx)
    }

    private fun normalizePlayer(raw: String?): String {
        return String(raw ?: "").trim().lowercase()
    }

    private fun loadState(ctx: PluginContext) {
        val state = ctx.storage.store(stateKey, GodmodeState::class.java, version = 1).load() ?: GodmodeState()
        godPlayers.clear()
        godPlayers.addAll(state.enabledPlayers.map { normalizePlayer(it) }.filter { it.isNotBlank() })
    }

    private fun saveState(ctx: PluginContext? = pluginContextRef) {
        val local = ctx ?: return
        local.storage.store(stateKey, GodmodeState::class.java, version = 1).save(
            GodmodeState(enabledPlayers = godPlayers.toSet())
        )
    }
}
