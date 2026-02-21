package com.clockwork.showcase.restocker

import com.clockwork.api.*
import java.util.concurrent.atomic.AtomicInteger

class RestockerPlugin : GigaPlugin {
    private val restockTicks = AtomicInteger(1200)

    private val delegate = gigaPlugin(id = "showcase-restocker", name = "Showcase Restocker", version = "1.0.0") {
        systems {
            system("restocker-loop") { ctx ->
                val interval = restockTicks.get().coerceAtLeast(20)
                val tick = (ctx.host.serverInfo()?.onlinePlayers ?: 0) + (System.currentTimeMillis() / 50L).toInt()
                if (tick % interval != 0) return@system

                val villagers = ctx.host.entities().filter { it.type.equals("VILLAGER", ignoreCase = true) }
                villagers.forEach { villager ->
                    val existing = ctx.host.entityData(villager.uuid).orEmpty().toMutableMap()
                    existing["restock.lastTick"] = (System.currentTimeMillis() / 50L).toString()
                    ctx.host.setEntityData(villager.uuid, existing)
                }
            }
        }

        commands {
            spec(
                command = "restocker-interval",
                description = "Set villager restock interval in ticks",
                argsSchema = listOf(CommandArgSpec("ticks", CommandArgType.INT)),
                usage = "restocker-interval <ticks>"
            ) { inv ->
                val ticks = inv.parsedArgs.requiredInt("ticks").coerceIn(20, 72_000)
                restockTicks.set(ticks)
                CommandResult.ok("Restock interval set to $ticks ticks")
            }

            spec(
                command = "restocker-status",
                description = "Show restocker configuration",
                usage = "restocker-status"
            ) { _ ->
                CommandResult.ok("Restocker interval=${restockTicks.get()} ticks")
            }
        }
    }

    override fun onEnable(ctx: PluginContext) = delegate.onEnable(ctx)
    override fun onDisable(ctx: PluginContext) = delegate.onDisable(ctx)
}
