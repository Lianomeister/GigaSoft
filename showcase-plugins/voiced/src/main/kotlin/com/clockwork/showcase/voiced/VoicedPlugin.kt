package com.clockwork.showcase.voiced

import com.clockwork.api.*

data class VoicedState(
    val playerModes: Map<String, String> = emptyMap()
)

class VoicedPlugin : GigaPlugin {
    private val stateKey = "voiced-state"
    private val playerModes = linkedMapOf<String, String>()
    private val allowedModes = linkedSetOf("normal", "robot", "deep", "chipmunk", "radio")

    private val delegate = gigaPlugin(id = "showcase-voiced", name = "Showcase Voiced", version = "1.0.0") {
        commands {
            spec(
                command = "voice-mode",
                argsSchema = listOf(
                    CommandArgSpec("mode", CommandArgType.STRING),
                    CommandArgSpec("player", CommandArgType.STRING, required = false)
                ),
                usage = "voice-mode <normal|robot|deep|chipmunk|radio> [player]"
            ) { inv ->
                val mode = inv.parsedArgs.requiredString("mode").trim().lowercase()
                if (mode !in allowedModes) {
                    return@spec CommandResult.error("Unknown mode '$mode'", code = "E_ARGS")
                }
                val player = (inv.parsedArgs.string("player") ?: inv.sender.id).trim()
                val key = player.lowercase()
                playerModes[key] = mode
                inv.pluginContext.saveState(stateKey, VoicedState(playerModes.toMap()))
                inv.pluginContext.host.sendPlayerMessage(player, "[Voiced] Mode set to $mode")
                inv.pluginContext.actionBar(player, "Voice: $mode", durationTicks = 50)
                CommandResult.ok("Voice mode for $player => $mode")
            }

            spec(
                command = "voice-status",
                argsSchema = listOf(CommandArgSpec("player", CommandArgType.STRING, required = false)),
                usage = "voice-status [player]"
            ) { inv ->
                val player = (inv.parsedArgs.string("player") ?: inv.sender.id).trim().lowercase()
                val mode = playerModes[player] ?: "normal"
                CommandResult.ok("$player => $mode")
            }

            spec(
                command = "voice-clear",
                argsSchema = listOf(CommandArgSpec("player", CommandArgType.STRING, required = false)),
                usage = "voice-clear [player]"
            ) { inv ->
                val player = (inv.parsedArgs.string("player") ?: inv.sender.id).trim().lowercase()
                playerModes.remove(player)
                inv.pluginContext.saveState(stateKey, VoicedState(playerModes.toMap()))
                CommandResult.ok("Voice mode cleared for $player")
            }

            spec(command = "voice-list", usage = "voice-list") { _ ->
                CommandResult.ok("Modes: ${allowedModes.joinToString(", ")}")
            }
        }
    }

    override fun onEnable(ctx: PluginContext) {
        val state = ctx.loadOrDefault(stateKey) { VoicedState() }
        playerModes.clear()
        playerModes.putAll(state.playerModes)
        delegate.onEnable(ctx)
    }

    override fun onDisable(ctx: PluginContext) {
        ctx.saveState(stateKey, VoicedState(playerModes.toMap()))
        delegate.onDisable(ctx)
    }
}
