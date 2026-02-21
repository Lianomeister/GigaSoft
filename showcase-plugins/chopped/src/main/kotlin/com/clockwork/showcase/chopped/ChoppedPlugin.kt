package com.clockwork.showcase.chopped

import com.clockwork.api.*
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

data class ChoppedConfig(
    val maxNodes: Int = 196,
    val delayPerLogTicks: Int = 1,
    val maxDelayTicks: Int = 80,
    val dropLoot: Boolean = true
)

class ChoppedPlugin : GigaPlugin {
    private val configKey = "chopped-config"
    private val suppressed = ConcurrentHashMap.newKeySet<String>()
    private val counter = AtomicLong(0L)

    @Volatile
    private var config = ChoppedConfig()

    @Volatile
    private var pluginContextRef: PluginContext? = null

    private val delegate = gigaPlugin(id = "showcase-chopped", name = "Showcase Chopped", version = "1.0.0") {
        events {
            subscribe(GigaBlockBreakPostEvent::class.java) { event ->
                if (!event.success || event.cancelled || event.previousBlockId == null) return@subscribe
                val id = event.previousBlockId.lowercase()
                if (!id.endsWith("_log") && !id.contains("stem")) return@subscribe
                val originKey = key(event.world, event.x, event.y, event.z)
                if (suppressed.remove(originKey)) return@subscribe
                val ctx = pluginContextRef ?: return@subscribe

                val nodes = collectTree(ctx, event.world, event.x, event.y, event.z, id, config.maxNodes)
                if (nodes.size <= 1) return@subscribe
                val delay = (nodes.size * config.delayPerLogTicks).coerceIn(1, config.maxDelayTicks)
                val taskId = "chopped-${counter.incrementAndGet()}"
                ctx.scheduler.once(taskId, delay) {
                    nodes.drop(1).forEach { (x, y, z) ->
                        suppressed.add(key(event.world, x, y, z))
                        ctx.host.breakBlock(event.world, x, y, z, dropLoot = config.dropLoot)
                    }
                }
            }
        }

        commands {
            spec(command = "chopped-status", usage = "chopped-status") { _ ->
                CommandResult.ok("Chopped config maxNodes=${config.maxNodes} delayPerLog=${config.delayPerLogTicks} maxDelay=${config.maxDelayTicks} dropLoot=${config.dropLoot}")
            }
            spec(
                command = "chopped-config",
                argsSchema = listOf(
                    CommandArgSpec("maxNodes", CommandArgType.INT, required = false),
                    CommandArgSpec("maxDelayTicks", CommandArgType.INT, required = false)
                ),
                usage = "chopped-config [maxNodes] [maxDelayTicks]"
            ) { inv ->
                if (inv.rawArgs.isEmpty()) return@spec CommandResult.ok("Chopped config unchanged")
                val maxNodes = (inv.parsedArgs.int("maxNodes") ?: config.maxNodes).coerceIn(16, 1024)
                val maxDelay = (inv.parsedArgs.int("maxDelayTicks") ?: config.maxDelayTicks).coerceIn(1, 300)
                config = config.copy(maxNodes = maxNodes, maxDelayTicks = maxDelay)
                saveConfig(inv.pluginContext)
                CommandResult.ok("Chopped config updated")
            }
        }
    }

    override fun onEnable(ctx: PluginContext) {
        pluginContextRef = ctx
        config = ctx.storage.store(configKey, ChoppedConfig::class.java, version = 1).load() ?: ChoppedConfig()
        delegate.onEnable(ctx)
    }

    override fun onDisable(ctx: PluginContext) {
        saveConfig(ctx)
        pluginContextRef = null
        suppressed.clear()
        delegate.onDisable(ctx)
    }

    private fun saveConfig(ctx: PluginContext) {
        ctx.storage.store(configKey, ChoppedConfig::class.java, version = 1).save(config)
    }

    private fun collectTree(ctx: PluginContext, world: String, x: Int, y: Int, z: Int, target: String, max: Int): List<Triple<Int, Int, Int>> {
        val queue = ArrayDeque<Triple<Int, Int, Int>>()
        val visited = linkedSetOf<Triple<Int, Int, Int>>()
        val start = Triple(x, y, z)
        queue.add(start)
        visited.add(start)

        while (queue.isNotEmpty() && visited.size < max) {
            val (cx, cy, cz) = queue.removeFirst()
            for (dx in -1..1) {
                for (dy in 0..1) {
                    for (dz in -1..1) {
                        if (dx == 0 && dy == 0 && dz == 0) continue
                        val nx = cx + dx
                        val ny = cy + dy
                        val nz = cz + dz
                        val p = Triple(nx, ny, nz)
                        if (p in visited) continue
                        val blockId = ctx.host.blockAt(world, nx, ny, nz)?.blockId?.lowercase() ?: continue
                        if (blockId == target) {
                            visited.add(p)
                            queue.add(p)
                        }
                    }
                }
            }
        }
        return visited.toList()
    }

    private fun key(world: String, x: Int, y: Int, z: Int): String = "$world:$x:$y:$z"
}
