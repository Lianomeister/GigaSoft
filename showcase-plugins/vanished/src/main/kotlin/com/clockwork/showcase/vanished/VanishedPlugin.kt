package com.clockwork.showcase.vanished

import com.clockwork.api.*
import java.util.concurrent.ConcurrentHashMap

data class VanishState(
    val vanishedPlayers: Set<String> = emptySet()
)

class VanishedPlugin : GigaPlugin {
    private val stateKey = "vanished-state"
    private val vanished = ConcurrentHashMap.newKeySet<String>()

    private val delegate = gigaPlugin(id = "showcase-vanished", name = "Showcase Vanished", version = "1.0.0") {
        commands {
            spec(
                command = "vanish",
                argsSchema = listOf(CommandArgSpec("player", CommandArgType.STRING, required = false)),
                usage = "vanish [player]"
            ) { inv ->
                val ctx = inv.pluginContext
                val player = (inv.parsedArgs.string("player") ?: inv.sender.id).trim()
                val key = player.lowercase()
                val enabled = if (vanished.contains(key)) {
                    vanished.remove(key)
                    ctx.host.removePlayerEffect(player, "invisibility")
                    false
                } else {
                    vanished.add(key)
                    ctx.host.addPlayerEffect(player, "invisibility", durationTicks = 20 * 60 * 60, amplifier = 0)
                    true
                }
                saveState(ctx)
                ctx.host.sendPlayerMessage(player, "[Vanished] ${if (enabled) "enabled" else "disabled"}")
                CommandResult.ok("Vanish ${if (enabled) "enabled" else "disabled"} for $player")
            }

            spec(command = "vanish-list", usage = "vanish-list") { _ ->
                if (vanished.isEmpty()) return@spec CommandResult.ok("No vanished players")
                CommandResult.ok("Vanished: ${vanished.sorted().joinToString(", ")}")
            }
        }

        events {
            subscribe(GigaPlayerJoinEvent::class.java) { event ->
                val key = event.player.name.lowercase()
                if (key in vanished) {
                    pluginContextRef?.host?.sendPlayerMessage(event.player.name, "[Vanished] Join message suppressed in showcase mode")
                }
            }
            subscribe(GigaPlayerLeaveEvent::class.java) { event ->
                val key = event.player.name.lowercase()
                if (key in vanished) {
                    pluginContextRef?.host?.sendPlayerMessage(event.player.name, "[Vanished] Leave message suppressed in showcase mode")
                }
            }
        }
    }

    @Volatile
    private var pluginContextRef: PluginContext? = null

    override fun onEnable(ctx: PluginContext) {
        pluginContextRef = ctx
        val state = ctx.storage.store(stateKey, VanishState::class.java, version = 1).load() ?: VanishState()
        vanished.clear()
        vanished.addAll(state.vanishedPlayers)
        delegate.onEnable(ctx)
    }

    override fun onDisable(ctx: PluginContext) {
        saveState(ctx)
        pluginContextRef = null
        delegate.onDisable(ctx)
    }

    private fun saveState(ctx: PluginContext) {
        ctx.storage.store(stateKey, VanishState::class.java, version = 1).save(VanishState(vanishedPlayers = vanished.toSet()))
    }
}
