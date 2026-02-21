package com.clockwork.showcase.spawn

import com.clockwork.api.*

data class SpawnPoint(
    val world: String,
    val x: Double,
    val y: Double,
    val z: Double
)

data class SpawnState(
    val spawn: SpawnPoint? = null,
    val teleportDelayTicks: Int = 0
)

class SpawnPlugin : GigaPlugin {
    private val stateKey = "spawn-state"

    @Volatile
    private var spawnPoint: SpawnPoint? = null

    @Volatile
    private var teleportDelayTicks: Int = 0

    private val delegate = gigaPlugin(id = "showcase-spawn", name = "Showcase Spawn", version = "1.0.0") {
        commands {
            spec(
                command = "spawn",
                argsSchema = listOf(CommandArgSpec("action", CommandArgType.STRING, required = false)),
                usage = "spawn [set]"
            ) { inv ->
                val ctx = inv.pluginContext
                val action = inv.parsedArgs.string("action")?.trim()?.lowercase().orEmpty()
                if (action == "set") {
                    val me = ctx.host.findPlayer(inv.sender.id) ?: return@spec CommandResult.error("Player not found", code = "E_NOT_FOUND")
                    spawnPoint = SpawnPoint(me.location.world, me.location.x, me.location.y, me.location.z)
                    saveState(ctx)
                    return@spec CommandResult.ok("Spawn set to ${me.location.world} ${me.location.x.toInt()} ${me.location.y.toInt()} ${me.location.z.toInt()}")
                }

                val spawn = spawnPoint ?: return@spec CommandResult.error("Spawn not set", code = "E_NOT_FOUND")
                val taskId = "spawn-tp-${inv.sender.id.lowercase()}"
                ctx.scheduler.cancel(taskId)
                ctx.scheduler.once(taskId, teleportDelayTicks) {
                    ctx.host.movePlayer(inv.sender.id, HostLocationRef(spawn.world, spawn.x, spawn.y, spawn.z))
                    ctx.host.sendPlayerMessage(inv.sender.id, "[Spawn] Teleported to spawn")
                }
                CommandResult.ok("Teleporting to spawn in ${teleportDelayTicks} ticks")
            }

            spec(
                command = "spawn-delay",
                argsSchema = listOf(CommandArgSpec("ticks", CommandArgType.INT)),
                usage = "spawn-delay <ticks>"
            ) { inv ->
                teleportDelayTicks = inv.parsedArgs.requiredInt("ticks").coerceIn(0, 1200)
                saveState(inv.pluginContext)
                CommandResult.ok("Spawn delay set to $teleportDelayTicks ticks")
            }

            spec(command = "spawn-info", usage = "spawn-info") { _ ->
                val spawn = spawnPoint
                if (spawn == null) return@spec CommandResult.ok("Spawn not set")
                CommandResult.ok("Spawn: ${spawn.world} ${spawn.x.toInt()} ${spawn.y.toInt()} ${spawn.z.toInt()} delay=${teleportDelayTicks}t")
            }
        }
    }

    override fun onEnable(ctx: PluginContext) {
        val state = ctx.storage.store(stateKey, SpawnState::class.java, version = 1).load() ?: SpawnState()
        spawnPoint = state.spawn
        teleportDelayTicks = state.teleportDelayTicks.coerceIn(0, 1200)
        delegate.onEnable(ctx)
    }

    override fun onDisable(ctx: PluginContext) {
        saveState(ctx)
        delegate.onDisable(ctx)
    }

    private fun saveState(ctx: PluginContext) {
        ctx.storage.store(stateKey, SpawnState::class.java, version = 1).save(
            SpawnState(spawn = spawnPoint, teleportDelayTicks = teleportDelayTicks)
        )
    }
}
