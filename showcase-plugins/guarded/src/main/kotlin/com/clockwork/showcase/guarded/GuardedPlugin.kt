package com.clockwork.showcase.guarded

import com.clockwork.api.*
import java.util.concurrent.ConcurrentHashMap

data class GuardSelection(
    var pos1: Triple<Int, Int, Int>? = null,
    var pos2: Triple<Int, Int, Int>? = null,
    var world: String = "world"
)

data class GuardArea(
    val id: String,
    val owner: String,
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

data class GuardedState(
    val areas: List<GuardArea> = emptyList(),
    val enforceWithoutActor: Boolean = false
)

class GuardedPlugin : GigaPlugin {
    private val stateKey = "guarded-state"
    private val selections = ConcurrentHashMap<String, GuardSelection>()
    private val areas = ConcurrentHashMap<String, GuardArea>()

    @Volatile
    private var enforceWithoutActor = false

    @Volatile
    private var pluginContextRef: PluginContext? = null

    private val delegate = gigaPlugin(id = "showcase-guarded", name = "Showcase Guarded", version = "1.1.0") {
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
                val sender = normalize(inv.sender.id)
                val world = inv.parsedArgs.requiredString("world").trim()
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
                val sender = normalize(inv.sender.id)
                val world = inv.parsedArgs.requiredString("world").trim()
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
                val sender = normalize(inv.sender.id)
                val id = normalize(inv.parsedArgs.requiredString("id"))
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
                    owner = sender,
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
                    allowedPlayers = setOf(sender)
                )
                saveState(inv.pluginContext)
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
                val sender = normalize(inv.sender.id)
                val id = normalize(inv.parsedArgs.requiredString("id"))
                val player = normalize(inv.parsedArgs.requiredString("player"))
                val current = areas[id] ?: return@spec CommandResult.error("Unknown area", code = "E_NOT_FOUND")
                if (!canManage(current, sender)) return@spec CommandResult.error("Not allowed to edit area '$id'", code = "E_PERMISSION")
                areas[id] = current.copy(allowedPlayers = current.allowedPlayers + player)
                saveState(inv.pluginContext)
                CommandResult.ok("Player '$player' allowed in '$id'")
            }

            spec(
                command = "guard-disallow",
                argsSchema = listOf(
                    CommandArgSpec("id", CommandArgType.STRING),
                    CommandArgSpec("player", CommandArgType.STRING)
                ),
                usage = "guard-disallow <id> <player>"
            ) { inv ->
                val sender = normalize(inv.sender.id)
                val id = normalize(inv.parsedArgs.requiredString("id"))
                val player = normalize(inv.parsedArgs.requiredString("player"))
                val current = areas[id] ?: return@spec CommandResult.error("Unknown area", code = "E_NOT_FOUND")
                if (!canManage(current, sender)) return@spec CommandResult.error("Not allowed to edit area '$id'", code = "E_PERMISSION")
                areas[id] = current.copy(allowedPlayers = current.allowedPlayers - player)
                saveState(inv.pluginContext)
                CommandResult.ok("Player '$player' removed from '$id'")
            }

            spec(
                command = "guard-delete",
                argsSchema = listOf(CommandArgSpec("id", CommandArgType.STRING)),
                usage = "guard-delete <id>"
            ) { inv ->
                val sender = normalize(inv.sender.id)
                val id = normalize(inv.parsedArgs.requiredString("id"))
                val current = areas[id] ?: return@spec CommandResult.error("Unknown area", code = "E_NOT_FOUND")
                if (!canManage(current, sender)) return@spec CommandResult.error("Not allowed to delete area '$id'", code = "E_PERMISSION")
                areas.remove(id)
                saveState(inv.pluginContext)
                CommandResult.ok("Guard area '$id' deleted")
            }

            spec(
                command = "guard-list",
                usage = "guard-list"
            ) { _ ->
                if (areas.isEmpty()) return@spec CommandResult.ok("No guard areas")
                val body = areas.values
                    .sortedBy { it.id }
                    .joinToString(" | ") { "${it.id}@${it.world} [${it.minX},${it.minY},${it.minZ}..${it.maxX},${it.maxY},${it.maxZ}]" }
                CommandResult.ok("Guards (${areas.size}): $body")
            }

            spec(
                command = "guard-info",
                argsSchema = listOf(CommandArgSpec("id", CommandArgType.STRING)),
                usage = "guard-info <id>"
            ) { inv ->
                val id = normalize(inv.parsedArgs.requiredString("id"))
                val area = areas[id] ?: return@spec CommandResult.error("Unknown area", code = "E_NOT_FOUND")
                CommandResult.ok(
                    "${area.id} owner=${area.owner} world=${area.world} denyBreak=${area.denyBreak} " +
                        "denyBuild=${area.denyBuild} denyPvp=${area.denyPvp} allowed=${area.allowedPlayers.sorted().joinToString(",") }"
                )
            }

            spec(
                command = "guard-enforce-unknown",
                argsSchema = listOf(CommandArgSpec("enabled", CommandArgType.BOOLEAN)),
                usage = "guard-enforce-unknown <true|false>"
            ) { inv ->
                val enabled = inv.parsedArgs.boolean("enabled")
                    ?: return@spec CommandResult.error("enabled must be true/false", code = "E_ARGS")
                enforceWithoutActor = enabled
                saveState(inv.pluginContext)
                CommandResult.ok("Guard unknown-actor enforcement ${if (enabled) "enabled" else "disabled"}")
            }
        }

        events {
            subscribe(GigaBlockBreakPreEvent::class.java) { event ->
                val actor = resolveActor(event.cause)
                if (actor == null && !enforceWithoutActor) return@subscribe
                val match = areas.values.firstOrNull { area ->
                    area.world.equals(event.world, ignoreCase = true) &&
                        event.x in area.minX..area.maxX &&
                        event.y in area.minY..area.maxY &&
                        event.z in area.minZ..area.maxZ
                } ?: return@subscribe

                if (!match.denyBreak) return@subscribe
                val blocked = actor == null || actor !in match.allowedPlayers
                if (!blocked) return@subscribe

                event.cancelled = true
                event.cancelReason = "Protected area '${match.id}'"
            }
        }
    }

    override fun onEnable(ctx: PluginContext) {
        pluginContextRef = ctx
        loadState(ctx)
        delegate.onEnable(ctx)
    }

    override fun onDisable(ctx: PluginContext) {
        saveState(ctx)
        pluginContextRef = null
        delegate.onDisable(ctx)
    }

    private fun canManage(area: GuardArea, sender: String): Boolean {
        return area.owner == sender || sender in area.allowedPlayers
    }

    private fun resolveActor(cause: String): String? {
        val normalized = cause.trim().lowercase()
        if (normalized.startsWith("player:")) {
            return normalize(normalized.removePrefix("player:"))
        }
        if (normalized.startsWith("command:player:")) {
            return normalize(normalized.removePrefix("command:player:"))
        }
        return null
    }

    private fun normalize(raw: String): String = raw.trim().lowercase()

    private fun loadState(ctx: PluginContext) {
        val state = ctx.storage.store(stateKey, GuardedState::class.java, version = 1).load() ?: GuardedState()
        areas.clear()
        state.areas.forEach { area -> areas[area.id] = area }
        enforceWithoutActor = state.enforceWithoutActor
    }

    private fun saveState(ctx: PluginContext? = pluginContextRef) {
        val local = ctx ?: return
        local.storage.store(stateKey, GuardedState::class.java, version = 1).save(
            GuardedState(
                areas = areas.values.sortedBy { it.id },
                enforceWithoutActor = enforceWithoutActor
            )
        )
    }
}
