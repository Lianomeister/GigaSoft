package com.clockwork.showcase.sit

import com.clockwork.api.*
import java.util.concurrent.ConcurrentHashMap

data class SitState(
    val sittingPlayers: Set<String> = emptySet()
)

class SitPlugin : GigaPlugin {
    private val stateKey = "sit-state"
    private val sitting = ConcurrentHashMap.newKeySet<String>()

    private val delegate = gigaPlugin(id = "showcase-sit", name = "Showcase Sit", version = "1.0.0") {
        commands {
            spec(command = "sit", usage = "sit") { inv ->
                val ctx = inv.pluginContext
                val player = inv.sender.id
                val snapshot = ctx.host.findPlayer(player) ?: return@spec CommandResult.error("Player not found", code = "E_NOT_FOUND")
                val x = snapshot.location.x.toInt()
                val y = snapshot.location.y.toInt()
                val z = snapshot.location.z.toInt()
                val below = ctx.host.blockAt(snapshot.location.world, x, y - 1, z)?.blockId.orEmpty().lowercase()
                val above = ctx.host.blockAt(snapshot.location.world, x, y + 1, z)?.blockId.orEmpty().lowercase()
                val seatOk = below.contains("slab") || below.contains("stairs") || below.contains("chair")
                val airAbove = above.isBlank() || above.contains("air")
                if (!seatOk || !airAbove) {
                    return@spec CommandResult.error("Need slab/stairs/chair with air above", code = "E_ARGS")
                }
                val moved = ctx.host.movePlayer(
                    player,
                    HostLocationRef(snapshot.location.world, snapshot.location.x, snapshot.location.y - 0.45, snapshot.location.z)
                )
                if (moved == null) return@spec CommandResult.error("Could not sit now", code = "E_HOST")
                sitting.add(player.lowercase())
                saveState(ctx)
                CommandResult.ok("You are now sitting")
            }

            spec(command = "stand", usage = "stand") { inv ->
                val ctx = inv.pluginContext
                val player = inv.sender.id
                val key = player.lowercase()
                if (!sitting.remove(key)) return@spec CommandResult.ok("You are not sitting")
                val snapshot = ctx.host.findPlayer(player)
                if (snapshot != null) {
                    ctx.host.movePlayer(
                        player,
                        HostLocationRef(snapshot.location.world, snapshot.location.x, snapshot.location.y + 0.45, snapshot.location.z)
                    )
                }
                saveState(ctx)
                CommandResult.ok("You are now standing")
            }

            spec(command = "sit-status", usage = "sit-status") { _ ->
                if (sitting.isEmpty()) return@spec CommandResult.ok("Nobody is sitting")
                CommandResult.ok("Sitting: ${sitting.sorted().joinToString(", ")}")
            }
        }
    }

    override fun onEnable(ctx: PluginContext) {
        val state = ctx.storage.store(stateKey, SitState::class.java, version = 1).load() ?: SitState()
        sitting.clear()
        sitting.addAll(state.sittingPlayers)
        delegate.onEnable(ctx)
    }

    override fun onDisable(ctx: PluginContext) {
        saveState(ctx)
        delegate.onDisable(ctx)
    }

    private fun saveState(ctx: PluginContext) {
        ctx.storage.store(stateKey, SitState::class.java, version = 1).save(SitState(sittingPlayers = sitting.toSet()))
    }
}
