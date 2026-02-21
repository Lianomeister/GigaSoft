package com.clockwork.showcase.restocker

import com.clockwork.api.*
import java.util.concurrent.atomic.AtomicLong

data class RestockerConfig(
    val intervalTicks: Int = 1200,
    val maxVillagersPerCycle: Int = 128
)

data class RestockerStats(
    val lastCycleTick: Long = 0L,
    val lastVillagersTouched: Int = 0,
    val totalCycles: Long = 0L,
    val totalVillagersTouched: Long = 0L
)

class RestockerPlugin : GigaPlugin {
    private val configKey = "restocker-config"
    private val tickCounter = AtomicLong(0L)

    @Volatile
    private var config = RestockerConfig()

    @Volatile
    private var stats = RestockerStats()

    @Volatile
    private var pluginContextRef: PluginContext? = null

    private val delegate = gigaPlugin(id = "showcase-restocker", name = "Showcase Restocker", version = "1.1.0") {
        systems {
            system("restocker-loop") {
                val tick = tickCounter.incrementAndGet()
                val interval = config.intervalTicks.coerceAtLeast(20)
                if (tick % interval.toLong() != 0L) return@system
                runRestockCycle(it, tick)
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
                config = config.copy(intervalTicks = ticks)
                saveConfig(inv.pluginContext)
                CommandResult.ok("Restock interval set to $ticks ticks")
            }

            spec(
                command = "restocker-max",
                description = "Set max villagers updated per cycle",
                argsSchema = listOf(CommandArgSpec("count", CommandArgType.INT)),
                usage = "restocker-max <count>"
            ) { inv ->
                val count = inv.parsedArgs.requiredInt("count").coerceIn(1, 2_000)
                config = config.copy(maxVillagersPerCycle = count)
                saveConfig(inv.pluginContext)
                CommandResult.ok("Restocker max villagers per cycle set to $count")
            }

            spec(
                command = "restocker-run",
                description = "Run one restock cycle immediately",
                usage = "restocker-run"
            ) { inv ->
                val tick = tickCounter.get().coerceAtLeast(1L)
                val touched = runRestockCycle(inv.pluginContext, tick)
                CommandResult.ok("Restock cycle complete (touched $touched villager(s))")
            }

            spec(
                command = "restocker-status",
                description = "Show restocker configuration and stats",
                usage = "restocker-status"
            ) { _ ->
                val cfg = config
                val s = stats
                CommandResult.ok(
                    "Restocker interval=${cfg.intervalTicks} max=${cfg.maxVillagersPerCycle} " +
                        "last=${s.lastVillagersTouched}@tick${s.lastCycleTick} totalCycles=${s.totalCycles} totalTouched=${s.totalVillagersTouched}"
                )
            }
        }
    }

    override fun onEnable(ctx: PluginContext) {
        pluginContextRef = ctx
        config = loadConfig(ctx)
        tickCounter.set(0L)
        stats = RestockerStats()
        delegate.onEnable(ctx)
    }

    override fun onDisable(ctx: PluginContext) {
        saveConfig(ctx)
        pluginContextRef = null
        delegate.onDisable(ctx)
    }

    private fun loadConfig(ctx: PluginContext): RestockerConfig {
        return ctx.storage.store(configKey, RestockerConfig::class.java, version = 1).load() ?: RestockerConfig()
    }

    private fun saveConfig(ctx: PluginContext? = pluginContextRef) {
        val local = ctx ?: return
        local.storage.store(configKey, RestockerConfig::class.java, version = 1).save(config)
    }

    private fun runRestockCycle(ctx: PluginContext, tick: Long): Int {
        val max = config.maxVillagersPerCycle.coerceAtLeast(1)
        val villagers = ctx.host.entities()
            .asSequence()
            .filter { it.type.equals("VILLAGER", ignoreCase = true) }
            .sortedBy { it.uuid }
            .take(max)
            .toList()

        villagers.forEach { villager ->
            val data = ctx.host.entityDataOrEmpty(villager.uuid).toMutableMap()
            data["restock.lastTick"] = tick.toString()
            data["restock.intervalTicks"] = config.intervalTicks.toString()
            ctx.host.setEntityData(villager.uuid, data)
        }

        stats = stats.copy(
            lastCycleTick = tick,
            lastVillagersTouched = villagers.size,
            totalCycles = stats.totalCycles + 1,
            totalVillagersTouched = stats.totalVillagersTouched + villagers.size
        )
        return villagers.size
    }
}
