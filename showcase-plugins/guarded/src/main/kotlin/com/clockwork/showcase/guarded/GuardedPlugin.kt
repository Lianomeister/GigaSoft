package com.clockwork.showcase.guarded

import com.clockwork.api.*
import java.util.concurrent.ConcurrentHashMap

data class GuardSelection(var pos1: Triple<Int, Int, Int>? = null, var pos2: Triple<Int, Int, Int>? = null, var world: String = "world")
data class GuardArea(
    val id: String,
    val world: String,
    val minX: Int,
    val minY: Int,
    val minZ: Int,
    val maxX: Int,
    val maxY: Int,
    val maxZ: Int,
    val denyBuild: Boolean,
    val denyBreak: Boolean,
    val denyPvp: Boolean,
    val allowedPlayers: Set<String>
)

class GuardedPlugin : GigaPlugin {
    private val selections = ConcurrentHashMap<String, GuardSelection>()
    private val areas = ConcurrentHashMap<String, GuardArea>()

    private val delegate = gigaPlugin(id = "showcase-guarded", name = "Showcase Guarded", version = "1.0.0") {
        commands {
            spec(
                command = "guard-pos1",
                argsSchema = listOf(
                    CommandArgSpec("world", CommandArgType.STRING),
                    CommandArgSpec("x", CommandArgType.INT),
                    CommandArgSpec("y", CommandArgType.INT),
                    CommandArgSpec("z", CommandArgType.INT)
                ),
                usage = "guard-pos1 <world> <x> <y> <z>"
            ) { inv ->
                val sender = inv.sender.id.lowercase()
                val world = inv.parsedArgs.requiredString("world")
                val x = inv.parsedArgs.requiredInt("x")
                val y = inv.parsedArgs.requiredInt("y")
                val z = inv.parsedArgs.requiredInt("z")
                val sel = selections.computeIfAbsent(sender) { GuardSelection() }
                sel.world = world
                sel.pos1 = Triple(x, y, z)
                CommandResult.ok("pos1 set: $world $x $y $z")
            }

            spec(
                command = "guard-pos2",
                argsSchema = listOf(
                    CommandArgSpec("world", CommandArgType.STRING),
                    CommandArgSpec("x", CommandArgType.INT),
                    CommandArgSpec("y", CommandArgType.INT),
                    CommandArgSpec("z", CommandArgType.INT)
                ),
                usage = "guard-pos2 <world> <x> <y> <z>"
            ) { inv ->
                val sender = inv.sender.id.lowercase()
                val world = inv.parsedArgs.requiredString("world")
                val x = inv.parsedArgs.requiredInt("x")
                val y = inv.parsedArgs.requiredInt("y")
                val z = inv.parsedArgs.requiredInt("z")
                val sel = selections.computeIfAbsent(sender) { GuardSelection() }
                sel.world = world
                sel.pos2 = Triple(x, y, z)
                CommandResult.ok("pos2 set: $world $x $y $z")
            }

            spec(
                command = "guard-create",
                argsSchema = listOf(
                    CommandArgSpec("id", CommandArgType.STRING),
                    CommandArgSpec("denyBuild", CommandArgType.BOOLEAN, required = false),
                    CommandArgSpec("denyBreak", CommandArgType.BOOLEAN, required = false),
                    CommandArgSpec("denyPvp", CommandArgType.BOOLEAN, required = false)
                ),
                usage = "guard-create <id> [denyBuild] [denyBreak] [denyPvp]"
            ) { inv ->
                val sender = inv.sender.id.lowercase()
                val id = inv.parsedArgs.requiredString("id").lowercase()
                val sel = selections[sender] ?: return@spec CommandResult.error("Set pos1/pos2 first", code = "E_SELECTION")
                val p1 = sel.pos1 ?: return@spec CommandResult.error("Missing pos1", code = "E_SELECTION")
                val p2 = sel.pos2 ?: return@spec CommandResult.error("Missing pos2", code = "E_SELECTION")
                val minX = minOf(p1.first, p2.first)
                val minY = minOf(p1.second, p2.second)
                val minZ = minOf(p1.third, p2.third)
                val maxX = maxOf(p1.first, p2.first)
                val maxY = maxOf(p1.second, p2.second)
                val maxZ = maxOf(p1.third, p2.third)
                areas[id] = GuardArea(
                    id = id,
                    world = sel.world,
                    minX = minX,
                    minY = minY,
                    minZ = minZ,
                    maxX = maxX,
                    maxY = maxY,
                    maxZ = maxZ,
                    denyBuild = inv.parsedArgs.boolean("denyBuild") ?: true,
                    denyBreak = inv.parsedArgs.boolean("denyBreak") ?: true,
                    denyPvp = inv.parsedArgs.boolean("denyPvp") ?: false,
                    allowedPlayers = setOf(inv.sender.id.lowercase())
                )
                CommandResult.ok("Guard area '$id' created")
            }

            spec(
                command = "guard-allow",
                argsSchema = listOf(
                    CommandArgSpec("id", CommandArgType.STRING),
                    CommandArgSpec("player", CommandArgType.STRING)
                ),
                usage = "guard-allow <id> <player>"
            ) { inv ->
                val id = inv.parsedArgs.requiredString("id").lowercase()
                val player = inv.parsedArgs.requiredString("player").lowercase()
                val current = areas[id] ?: return@spec CommandResult.error("Unknown area", code = "E_NOT_FOUND")
                areas[id] = current.copy(allowedPlayers = current.allowedPlayers + player)
                CommandResult.ok("Player '$player' allowed in '$id'")
            }
        }

        events {
            subscribe(GigaBlockBreakPreEvent::class.java) { event ->
                val player = "unknown" // player identity not exposed by current pre-event contract
                val blocked = areas.values.any { area ->
                    area.world.equals(event.world, ignoreCase = true) &&
                        event.x in area.minX..area.maxX &&
                        event.y in area.minY..area.maxY &&
                        event.z in area.minZ..area.maxZ &&
                        area.denyBreak &&
                        player !in area.allowedPlayers
                }
                if (blocked) {
                    event.cancelled = true
                    event.cancelReason = "Protected area"
                }
            }
        }
    }

    override fun onEnable(ctx: PluginContext) = delegate.onEnable(ctx)
    override fun onDisable(ctx: PluginContext) = delegate.onDisable(ctx)
}
