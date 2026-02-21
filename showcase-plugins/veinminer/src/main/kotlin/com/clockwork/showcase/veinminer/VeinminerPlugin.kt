package com.clockwork.showcase.veinminer

import com.clockwork.api.*
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

data class VeinTask(
    val world: String,
    val originX: Int,
    val originY: Int,
    val originZ: Int,
    val blockId: String,
    val nodes: List<Triple<Int, Int, Int>>
)

data class VeinminerConfig(
    val maxScanNodes: Int = 256,
    val delayDivisor: Int = 2,
    val maxDelayTicks: Int = 120,
    val dropLoot: Boolean = true
)

class VeinminerPlugin : GigaPlugin {
    private val configKey = "veinminer-config"
    private val taskCounter = AtomicLong(0L)
    private val suppressedBreaks = ConcurrentHashMap.newKeySet<String>()

    private val oreIds = setOf(
        "minecraft:coal_ore",
        "minecraft:deepslate_coal_ore",
        "minecraft:iron_ore",
        "minecraft:deepslate_iron_ore",
        "minecraft:copper_ore",
        "minecraft:deepslate_copper_ore",
        "minecraft:gold_ore",
        "minecraft:deepslate_gold_ore",
        "minecraft:redstone_ore",
        "minecraft:deepslate_redstone_ore",
        "minecraft:diamond_ore",
        "minecraft:deepslate_diamond_ore",
        "minecraft:emerald_ore",
        "minecraft:deepslate_emerald_ore",
        "minecraft:lapis_ore",
        "minecraft:deepslate_lapis_ore",
        "minecraft:nether_quartz_ore",
        "minecraft:nether_gold_ore"
    )

    @Volatile
    private var config = VeinminerConfig()

    @Volatile
    private var pluginContextRef: PluginContext? = null

    private val delegate = gigaPlugin(id = "showcase-veinminer", name = "Showcase Veinminer", version = "1.1.0") {
        events {
            subscribe(GigaBlockBreakPostEvent::class.java) { event ->
                if (!event.success || event.cancelled || event.previousBlockId == null) return@subscribe
                val blockId = event.previousBlockId
                val breakKey = key(event.world, event.x, event.y, event.z)
                if (suppressedBreaks.remove(breakKey)) return@subscribe
                if (blockId !in oreIds) return@subscribe

                val ctx = pluginContextRef ?: return@subscribe
                val task = collectVein(
                    world = event.world,
                    x = event.x,
                    y = event.y,
                    z = event.z,
                    target = blockId,
                    maxNodes = config.maxScanNodes,
                    lookup = this@VeinminerPlugin::blockAt
                )
                if (task.nodes.size <= 1) return@subscribe

                val delayTicks = (task.nodes.size / config.delayDivisor.coerceAtLeast(1)).coerceIn(1, config.maxDelayTicks)
                val taskId = "vein-break-${taskCounter.incrementAndGet()}"
                ctx.scheduler.once(taskId, delayTicks) {
                    task.nodes.drop(1).forEach { (x, y, z) ->
                        suppressedBreaks.add(key(task.world, x, y, z))
                        ctx.host.breakBlock(task.world, x, y, z, dropLoot = config.dropLoot)
                    }
                }
            }
        }

        commands {
            spec(
                command = "veinminer-status",
                description = "Show veinminer configuration",
                usage = "veinminer-status"
            ) { _ ->
                CommandResult.ok(
                    "Veinminer maxScan=${config.maxScanNodes} delayDivisor=${config.delayDivisor} " +
                        "maxDelay=${config.maxDelayTicks} dropLoot=${config.dropLoot}"
                )
            }

            spec(
                command = "veinminer-max-scan",
                description = "Set max scanned nodes per vein",
                argsSchema = listOf(CommandArgSpec("nodes", CommandArgType.INT)),
                usage = "veinminer-max-scan <nodes>"
            ) { inv ->
                val nodes = inv.parsedArgs.requiredInt("nodes").coerceIn(16, 4096)
                config = config.copy(maxScanNodes = nodes)
                saveConfig(inv.pluginContext)
                CommandResult.ok("Veinminer maxScan set to $nodes")
            }

            spec(
                command = "veinminer-delay-divisor",
                description = "Set delay divisor for deferred vein breaks",
                argsSchema = listOf(CommandArgSpec("value", CommandArgType.INT)),
                usage = "veinminer-delay-divisor <value>"
            ) { inv ->
                val divisor = inv.parsedArgs.requiredInt("value").coerceIn(1, 32)
                config = config.copy(delayDivisor = divisor)
                saveConfig(inv.pluginContext)
                CommandResult.ok("Veinminer delay divisor set to $divisor")
            }

            spec(
                command = "veinminer-max-delay",
                description = "Set maximum deferred break delay",
                argsSchema = listOf(CommandArgSpec("ticks", CommandArgType.INT)),
                usage = "veinminer-max-delay <ticks>"
            ) { inv ->
                val ticks = inv.parsedArgs.requiredInt("ticks").coerceIn(1, 600)
                config = config.copy(maxDelayTicks = ticks)
                saveConfig(inv.pluginContext)
                CommandResult.ok("Veinminer max delay set to $ticks ticks")
            }

            spec(
                command = "veinminer-drop-loot",
                description = "Enable/disable loot drop for deferred block breaks",
                argsSchema = listOf(CommandArgSpec("enabled", CommandArgType.BOOLEAN)),
                usage = "veinminer-drop-loot <true|false>"
            ) { inv ->
                val enabled = inv.parsedArgs.boolean("enabled")
                    ?: return@spec CommandResult.error("enabled must be true/false", code = "E_ARGS")
                config = config.copy(dropLoot = enabled)
                saveConfig(inv.pluginContext)
                CommandResult.ok("Veinminer drop loot ${if (enabled) "enabled" else "disabled"}")
            }
        }
    }

    override fun onEnable(ctx: PluginContext) {
        pluginContextRef = ctx
        config = loadConfig(ctx)
        delegate.onEnable(ctx)
    }

    override fun onDisable(ctx: PluginContext) {
        saveConfig(ctx)
        pluginContextRef = null
        suppressedBreaks.clear()
        delegate.onDisable(ctx)
    }

    private fun key(world: String, x: Int, y: Int, z: Int): String = "$world:$x:$y:$z"

    private fun blockAt(world: String, x: Int, y: Int, z: Int): String? {
        return pluginContextRef?.host?.blockAt(world, x, y, z)?.blockId
    }

    private fun loadConfig(ctx: PluginContext): VeinminerConfig {
        return ctx.storage.store(configKey, VeinminerConfig::class.java, version = 1).load() ?: VeinminerConfig()
    }

    private fun saveConfig(ctx: PluginContext? = pluginContextRef) {
        val local = ctx ?: return
        local.storage.store(configKey, VeinminerConfig::class.java, version = 1).save(config)
    }

    private fun collectVein(
        world: String,
        x: Int,
        y: Int,
        z: Int,
        target: String,
        maxNodes: Int,
        lookup: (String, Int, Int, Int) -> String?
    ): VeinTask {
        val queue = ArrayDeque<Triple<Int, Int, Int>>()
        val visited = linkedSetOf<Triple<Int, Int, Int>>()
        val start = Triple(x, y, z)
        queue.add(start)
        visited.add(start)

        while (queue.isNotEmpty() && visited.size < maxNodes) {
            val (cx, cy, cz) = queue.removeFirst()
            val neighbors = listOf(
                Triple(cx + 1, cy, cz),
                Triple(cx - 1, cy, cz),
                Triple(cx, cy + 1, cz),
                Triple(cx, cy - 1, cz),
                Triple(cx, cy, cz + 1),
                Triple(cx, cy, cz - 1)
            )
            neighbors.forEach { key ->
                if (key in visited) return@forEach
                if (lookup(world, key.first, key.second, key.third) == target) {
                    visited.add(key)
                    queue.add(key)
                }
            }
        }

        return VeinTask(world, x, y, z, target, visited.toList())
    }
}
