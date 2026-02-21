package com.clockwork.showcase.craftvisualizer

import com.clockwork.api.*
import java.util.concurrent.ConcurrentHashMap

data class CraftVizState(
    val autoPlayers: Set<String> = emptySet()
)

class CraftVisualizerPlugin : GigaPlugin {
    private val stateKey = "craftviz-state"
    private val autoEnabled = ConcurrentHashMap.newKeySet<String>()

    private val delegate = gigaPlugin(id = "showcase-craftvisualizer", name = "Showcase CraftVisualizer", version = "1.0.0") {
        events {
            subscribe(GigaInventoryChangeEvent::class.java) { event ->
                val key = event.owner.lowercase()
                if (key !in autoEnabled) return@subscribe
                val ctx = pluginContextRef ?: return@subscribe
                val msg = "[CraftViz] Slot ${event.slot} -> ${event.itemId}"
                ctx.host.sendPlayerMessage(event.owner, msg)
                ctx.actionBar(event.owner, msg, durationTicks = 40)
            }
        }

        commands {
            spec(
                command = "craftviz",
                argsSchema = listOf(CommandArgSpec("recipe", CommandArgType.STRING)),
                usage = "craftviz <recipeName>"
            ) { inv ->
                val ctx = inv.pluginContext
                val recipe = inv.parsedArgs.requiredString("recipe").trim()
                val msg = "[CraftViz] Recipe preview: $recipe"
                ctx.host.sendPlayerMessage(inv.sender.id, msg)
                ctx.actionBar(inv.sender.id, msg, durationTicks = 60)
                CommandResult.ok("Visualized recipe '$recipe'")
            }

            spec(
                command = "craftviz-toggle",
                argsSchema = listOf(CommandArgSpec("player", CommandArgType.STRING, required = false)),
                usage = "craftviz-toggle [player]"
            ) { inv ->
                val ctx = inv.pluginContext
                val player = (inv.parsedArgs.string("player") ?: inv.sender.id).trim().lowercase()
                val enabled = if (player in autoEnabled) {
                    autoEnabled.remove(player)
                    false
                } else {
                    autoEnabled.add(player)
                    true
                }
                saveState(ctx)
                CommandResult.ok("CraftViz auto ${if (enabled) "enabled" else "disabled"} for $player")
            }

            spec(command = "craftviz-status", usage = "craftviz-status") { _ ->
                if (autoEnabled.isEmpty()) return@spec CommandResult.ok("CraftViz auto disabled for all")
                CommandResult.ok("CraftViz auto: ${autoEnabled.sorted().joinToString(", ")}")
            }
        }
    }

    @Volatile
    private var pluginContextRef: PluginContext? = null

    override fun onEnable(ctx: PluginContext) {
        pluginContextRef = ctx
        val state = ctx.storage.store(stateKey, CraftVizState::class.java, version = 1).load() ?: CraftVizState()
        autoEnabled.clear(); autoEnabled.addAll(state.autoPlayers)
        delegate.onEnable(ctx)
    }

    override fun onDisable(ctx: PluginContext) {
        saveState(ctx)
        pluginContextRef = null
        delegate.onDisable(ctx)
    }

    private fun saveState(ctx: PluginContext) {
        ctx.storage.store(stateKey, CraftVizState::class.java, version = 1).save(CraftVizState(autoPlayers = autoEnabled.toSet()))
    }
}
