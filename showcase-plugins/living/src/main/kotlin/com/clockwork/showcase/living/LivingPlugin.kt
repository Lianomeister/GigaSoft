package com.clockwork.showcase.living

import com.clockwork.api.*

data class HomeLocation(
    val world: String,
    val x: Double,
    val y: Double,
    val z: Double
)

data class LivingState(
    val homes: Map<String, Map<String, HomeLocation>> = emptyMap(),
    val teleportDelayTicks: Int = 60
)

class LivingPlugin : GigaPlugin {
    private val stateKey = "living-state"
    private val homes = linkedMapOf<String, MutableMap<String, HomeLocation>>()
    private var teleportDelayTicks: Int = 60

    private val delegate = gigaPlugin(id = "showcase-living", name = "Showcase Living", version = "1.0.0") {
        commands {
            spec(
                command = "sethome",
                argsSchema = listOf(CommandArgSpec("name", CommandArgType.STRING, required = false)),
                usage = "sethome [name]"
            ) { inv ->
                val ctx = inv.pluginContext
                val player = inv.sender.id
                val name = (inv.parsedArgs.string("name") ?: "default").trim().lowercase()
                val snapshot = ctx.host.findPlayer(player) ?: return@spec CommandResult.error("Player not found", code = "E_NOT_FOUND")
                val byPlayer = homes.computeIfAbsent(player.lowercase()) { linkedMapOf() }
                byPlayer[name] = HomeLocation(snapshot.location.world, snapshot.location.x, snapshot.location.y, snapshot.location.z)
                saveState(ctx)
                CommandResult.ok("Home '$name' set for $player")
            }

            spec(
                command = "home",
                argsSchema = listOf(CommandArgSpec("name", CommandArgType.STRING, required = false)),
                usage = "home [name]"
            ) { inv ->
                val ctx = inv.pluginContext
                val player = inv.sender.id
                val name = (inv.parsedArgs.string("name") ?: "default").trim().lowercase()
                val location = homes[player.lowercase()]?.get(name)
                    ?: return@spec CommandResult.error("Home '$name' not found", code = "E_NOT_FOUND")
                val taskId = "living-home-${player.lowercase()}"
                ctx.scheduler.cancel(taskId)
                ctx.scheduler.once(taskId, teleportDelayTicks) {
                    ctx.host.movePlayer(player, HostLocationRef(location.world, location.x, location.y, location.z))
                    ctx.host.sendPlayerMessage(player, "[Living] Teleported to home '$name'")
                }
                CommandResult.ok("Teleporting to '$name' in ${teleportDelayTicks} ticks")
            }

            spec(command = "homes", usage = "homes") { inv ->
                val player = inv.sender.id.lowercase()
                val list = homes[player].orEmpty().keys.sorted()
                if (list.isEmpty()) return@spec CommandResult.ok("No homes set")
                CommandResult.ok("Homes: ${list.joinToString(", ")}")
            }

            spec(
                command = "delhome",
                argsSchema = listOf(CommandArgSpec("name", CommandArgType.STRING, required = false)),
                usage = "delhome [name]"
            ) { inv ->
                val ctx = inv.pluginContext
                val player = inv.sender.id.lowercase()
                val name = (inv.parsedArgs.string("name") ?: "default").trim().lowercase()
                val removed = homes[player]?.remove(name)
                    ?: return@spec CommandResult.error("Home '$name' not found", code = "E_NOT_FOUND")
                if (homes[player].isNullOrEmpty()) homes.remove(player)
                saveState(ctx)
                CommandResult.ok("Deleted home '$name' (${removed.world})")
            }

            spec(
                command = "home-delay",
                argsSchema = listOf(CommandArgSpec("ticks", CommandArgType.INT)),
                usage = "home-delay <ticks>"
            ) { inv ->
                teleportDelayTicks = inv.parsedArgs.requiredInt("ticks").coerceIn(0, 1200)
                saveState(inv.pluginContext)
                CommandResult.ok("Home teleport delay set to $teleportDelayTicks ticks")
            }
        }
    }

    override fun onEnable(ctx: PluginContext) {
        val state = ctx.storage.store(stateKey, LivingState::class.java, version = 1).load() ?: LivingState()
        homes.clear()
        state.homes.forEach { (player, map) -> homes[player] = map.toMutableMap() }
        teleportDelayTicks = state.teleportDelayTicks.coerceIn(0, 1200)
        delegate.onEnable(ctx)
    }

    override fun onDisable(ctx: PluginContext) {
        saveState(ctx)
        delegate.onDisable(ctx)
    }

    private fun saveState(ctx: PluginContext) {
        ctx.storage.store(stateKey, LivingState::class.java, version = 1).save(
            LivingState(
                homes = homes.mapValues { it.value.toMap() },
                teleportDelayTicks = teleportDelayTicks
            )
        )
    }
}
