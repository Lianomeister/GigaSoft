package com.clockwork.showcase.veinminer

import com.clockwork.api.*
import java.util.ArrayDeque
import kotlin.math.min

data class VeinTask(
    val world: String,
    val originX: Int,
    val originY: Int,
    val originZ: Int,
    val blockId: String,
    val nodes: List<Triple<Int, Int, Int>>
)

class VeinminerPlugin : GigaPlugin {
    private val orePrefixes = setOf("minecraft:coal_ore", "minecraft:iron_ore", "minecraft:copper_ore", "minecraft:gold_ore", "minecraft:redstone_ore", "minecraft:diamond_ore", "minecraft:emerald_ore")

    private val delegate = gigaPlugin(id = "showcase-veinminer", name = "Showcase Veinminer", version = "1.0.0") {
        events {
            subscribe(GigaBlockBreakPostEvent::class.java) { event ->
                if (!event.success || event.previousBlockId == null) return@subscribe
                val blockId = event.previousBlockId
                if (blockId !in orePrefixes) return@subscribe

                val task = collectVein(event.world, event.x, event.y, event.z, blockId, this@VeinminerPlugin::blockAt)
                if (task.nodes.size <= 1) return@subscribe

                val delayTicks = min(120, task.nodes.size / 2)
                val ctx = pluginContextRef ?: return@subscribe
                ctx.scheduler.once("vein-break-${event.x}-${event.y}-${event.z}", delayTicks) {
                    task.nodes.drop(1).forEach { (x, y, z) ->
                        ctx.host.breakBlock(task.world, x, y, z, dropLoot = true)
                    }
                }
            }
        }
    }

    @Volatile
    private var pluginContextRef: PluginContext? = null

    override fun onEnable(ctx: PluginContext) {
        pluginContextRef = ctx
        delegate.onEnable(ctx)
    }

    override fun onDisable(ctx: PluginContext) {
        pluginContextRef = null
        delegate.onDisable(ctx)
    }

    private fun blockAt(world: String, x: Int, y: Int, z: Int): String? {
        return pluginContextRef?.host?.blockAt(world, x, y, z)?.blockId
    }

    private fun collectVein(
        world: String,
        x: Int,
        y: Int,
        z: Int,
        target: String,
        lookup: (String, Int, Int, Int) -> String?
    ): VeinTask {
        val queue = ArrayDeque<Triple<Int, Int, Int>>()
        val visited = linkedSetOf<Triple<Int, Int, Int>>()
        val start = Triple(x, y, z)
        queue.add(start)
        visited.add(start)

        while (queue.isNotEmpty() && visited.size < 256) {
            val (cx, cy, cz) = queue.removeFirst()
            for (dx in -1..1) {
                for (dy in -1..1) {
                    for (dz in -1..1) {
                        if (dx == 0 && dy == 0 && dz == 0) continue
                        val nx = cx + dx
                        val ny = cy + dy
                        val nz = cz + dz
                        val key = Triple(nx, ny, nz)
                        if (key in visited) continue
                        if (lookup(world, nx, ny, nz) == target) {
                            visited.add(key)
                            queue.add(key)
                        }
                    }
                }
            }
        }

        return VeinTask(world, x, y, z, target, visited.toList())
    }
}
