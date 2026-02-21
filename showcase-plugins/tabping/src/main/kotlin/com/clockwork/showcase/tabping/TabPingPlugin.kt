package com.clockwork.showcase.tabping

import com.clockwork.api.*

data class TabPingState(
    val pingByPlayer: Map<String, Int> = emptyMap()
)

class TabPingPlugin : GigaPlugin {
    private val stateKey = "tabping-state"
    private val pingByPlayer = linkedMapOf<String, Int>()

    private val delegate = gigaPlugin(id = "showcase-tabping", name = "Showcase TabPing", version = "1.0.0") {
        commands {
            spec(
                command = "tabping-set",
                argsSchema = listOf(CommandArgSpec("player", CommandArgType.STRING), CommandArgSpec("pingMs", CommandArgType.INT)),
                usage = "tabping-set <player> <pingMs>"
            ) { inv ->
                val player = inv.parsedArgs.requiredString("player").trim().lowercase()
                val ping = inv.parsedArgs.requiredInt("pingMs").coerceIn(0, 9999)
                pingByPlayer[player] = ping
                inv.pluginContext.saveState(stateKey, TabPingState(pingByPlayer.toMap()))
                CommandResult.ok("TabPing $player => ${ping}ms")
            }

            spec(
                command = "tabping-show",
                argsSchema = listOf(CommandArgSpec("player", CommandArgType.STRING, required = false)),
                usage = "tabping-show [player]"
            ) { inv ->
                val player = (inv.parsedArgs.string("player") ?: inv.sender.id).trim().lowercase()
                val ping = pingByPlayer[player] ?: return@spec CommandResult.ok("No ping sample for $player")
                CommandResult.ok("$player ping=${ping}ms")
            }

            spec(command = "tabping-board", usage = "tabping-board") { _ ->
                if (pingByPlayer.isEmpty()) return@spec CommandResult.ok("No ping samples")
                val board = pingByPlayer.entries
                    .sortedBy { it.value }
                    .joinToString(" | ") { "${it.key}:${it.value}ms" }
                CommandResult.ok(board)
            }
        }
    }

    override fun onEnable(ctx: PluginContext) {
        val state = ctx.loadOrDefault(stateKey) { TabPingState() }
        pingByPlayer.clear(); pingByPlayer.putAll(state.pingByPlayer)
        delegate.onEnable(ctx)
    }

    override fun onDisable(ctx: PluginContext) {
        ctx.saveState(stateKey, TabPingState(pingByPlayer.toMap()))
        delegate.onDisable(ctx)
    }
}
