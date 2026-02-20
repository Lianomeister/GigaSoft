package com.gigasoft.standalone

import com.fasterxml.jackson.databind.ObjectMapper
import com.gigasoft.core.GigaStandaloneCore
import com.gigasoft.core.StandaloneCoreConfig
import com.gigasoft.runtime.AdapterExecutionMode
import com.gigasoft.runtime.AdapterSecurityConfig
import com.gigasoft.net.StandaloneNetConfig
import com.gigasoft.net.StandaloneNetServer
import com.gigasoft.net.SessionActionResult
import com.gigasoft.net.StandaloneSessionHandler
import java.nio.file.Path
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val launchConfig = parseLaunchConfig(args)
    val objectMapper = ObjectMapper()

    val core = GigaStandaloneCore(
        config = StandaloneCoreConfig(
            pluginsDirectory = Path.of(launchConfig.pluginsDir),
            dataDirectory = Path.of(launchConfig.dataDir),
            serverName = launchConfig.serverName,
            serverVersion = launchConfig.serverVersion,
            maxPlayers = launchConfig.maxPlayers,
            tickPeriodMillis = launchConfig.tickPeriodMillis,
            autoSaveEveryTicks = launchConfig.autoSaveEveryTicks,
            adapterSecurity = AdapterSecurityConfig(
                maxPayloadEntries = launchConfig.adapterMaxPayloadEntries,
                maxPayloadKeyChars = launchConfig.adapterMaxPayloadKeyChars,
                maxPayloadValueChars = launchConfig.adapterMaxPayloadValueChars,
                maxPayloadTotalChars = launchConfig.adapterMaxPayloadTotalChars,
                maxCallsPerMinute = launchConfig.adapterRateLimitPerMinute,
                invocationTimeoutMillis = launchConfig.adapterTimeoutMillis,
                executionMode = when (launchConfig.adapterExecutionMode.lowercase()) {
                    "fast" -> AdapterExecutionMode.FAST
                    else -> AdapterExecutionMode.SAFE
                }
            )
        )
    )

    val netServer = if (launchConfig.netPort > 0) {
        StandaloneNetServer(
            config = StandaloneNetConfig(
                port = launchConfig.netPort,
                authRequired = launchConfig.netAuthRequired,
                sharedSecret = launchConfig.netSharedSecret,
                adminSecret = launchConfig.netAdminSecret,
                sessionTtlSeconds = launchConfig.netSessionTtlSeconds,
                textFlushEveryResponses = launchConfig.netTextFlushEveryResponses,
                frameFlushEveryResponses = launchConfig.netFrameFlushEveryResponses
            ),
            logger = { message -> println("[GigaNet] $message") },
            handler = object : StandaloneSessionHandler {
                override fun join(name: String, world: String, x: Double, y: Double, z: Double): SessionActionResult {
                    val player = core.joinPlayer(name, world, x, y, z)
                    return SessionActionResult(
                        success = true,
                        code = "JOINED",
                        message = "joined ${player.name} (${player.uuid}) @ ${player.world} ${player.x},${player.y},${player.z}",
                        payload = mapOf(
                            "name" to player.name,
                            "uuid" to player.uuid,
                            "world" to player.world,
                            "x" to player.x.toString(),
                            "y" to player.y.toString(),
                            "z" to player.z.toString()
                        )
                    )
                }

                override fun leave(name: String): SessionActionResult {
                    val left = core.leavePlayer(name)
                    return if (left == null) {
                        SessionActionResult(false, "NOT_FOUND", "player '$name' not online")
                    } else {
                        SessionActionResult(
                            success = true,
                            code = "LEFT",
                            message = "left ${left.name} (${left.uuid})",
                            payload = mapOf("name" to left.name, "uuid" to left.uuid)
                        )
                    }
                }

                override fun move(name: String, x: Double, y: Double, z: Double, world: String?): SessionActionResult {
                    val moved = core.movePlayer(name, x, y, z, world)
                    return if (moved == null) {
                        SessionActionResult(false, "NOT_FOUND", "player '$name' not online")
                    } else {
                        SessionActionResult(
                            success = true,
                            code = "MOVED",
                            message = "moved ${moved.name} @ ${moved.world} ${moved.x},${moved.y},${moved.z}",
                            payload = mapOf(
                                "name" to moved.name,
                                "uuid" to moved.uuid,
                                "world" to moved.world,
                                "x" to moved.x.toString(),
                                "y" to moved.y.toString(),
                                "z" to moved.z.toString()
                            )
                        )
                    }
                }

                override fun lookup(name: String): SessionActionResult {
                    val player = core.players().find { it.name.equals(name, ignoreCase = true) }
                    return if (player == null) {
                        SessionActionResult(false, "NOT_FOUND", "player '$name' not online")
                    } else {
                        SessionActionResult(
                            success = true,
                            code = "FOUND",
                            message = "${player.name} (${player.uuid}) @ ${player.world} ${player.x},${player.y},${player.z}",
                            payload = mapOf(
                                "name" to player.name,
                                "uuid" to player.uuid,
                                "world" to player.world,
                                "x" to player.x.toString(),
                                "y" to player.y.toString(),
                                "z" to player.z.toString()
                            )
                        )
                    }
                }

                override fun who(name: String?): SessionActionResult {
                    if (name.isNullOrBlank()) {
                        return SessionActionResult(success = true, code = "WHOAMI", message = "anonymous")
                    }
                    return lookup(name)
                }

                override fun worldCreate(name: String, seed: Long): SessionActionResult {
                    val world = core.createWorld(name, seed)
                    return SessionActionResult(
                        success = true,
                        code = "WORLD_CREATED",
                        message = "world created ${world.name} seed=${world.seed}",
                        payload = mapOf("name" to world.name, "seed" to world.seed.toString(), "time" to world.time.toString())
                    )
                }

                override fun entitySpawn(type: String, world: String, x: Double, y: Double, z: Double): SessionActionResult {
                    val entity = core.spawnEntity(type, world, x, y, z)
                    return SessionActionResult(
                        success = true,
                        code = "ENTITY_SPAWNED",
                        message = "spawned ${entity.type} (${entity.uuid}) @ ${entity.world} ${entity.x},${entity.y},${entity.z}",
                        payload = mapOf(
                            "uuid" to entity.uuid,
                            "type" to entity.type,
                            "world" to entity.world,
                            "x" to entity.x.toString(),
                            "y" to entity.y.toString(),
                            "z" to entity.z.toString()
                        )
                    )
                }

                override fun inventorySet(owner: String, slot: Int, itemId: String): SessionActionResult {
                    val updated = core.setInventoryItem(owner, slot, itemId)
                    return if (!updated) {
                        SessionActionResult(false, "INVENTORY_UPDATE_FAILED", "inventory update failed")
                    } else {
                        SessionActionResult(
                            success = true,
                            code = "INVENTORY_UPDATED",
                            message = "inventory updated",
                            payload = mapOf("owner" to owner, "slot" to slot.toString(), "itemId" to itemId)
                        )
                    }
                }
            }
        )
    } else {
        null
    }

    core.start()
    netServer?.start()
    println("GigaSoft standalone core is running (name=${launchConfig.serverName} version=${launchConfig.serverVersion} maxPlayers=${launchConfig.maxPlayers} tickMs=${launchConfig.tickPeriodMillis} autosaveTicks=${launchConfig.autoSaveEveryTicks} netPort=${if (netServer == null) "disabled" else launchConfig.netPort} netAuthRequired=${launchConfig.netAuthRequired} netSessionTtlSeconds=${launchConfig.netSessionTtlSeconds} netTextFlushEvery=${launchConfig.netTextFlushEveryResponses} netFrameFlushEvery=${launchConfig.netFrameFlushEveryResponses} adapterMode=${launchConfig.adapterExecutionMode} adapterTimeoutMs=${launchConfig.adapterTimeoutMillis} adapterRateLimitPerMinute=${launchConfig.adapterRateLimitPerMinute}).")
    println("Commands: help, status, save, load, plugins, worlds, world create, entities, entity spawn, players, player join|leave|move, inventory, scan, reload <id|all>, doctor [--json], profile <id> [--json], run <plugin> <command...>, adapters <plugin> [--json], adapter invoke <plugin> <adapterId> <action> [k=v ...] [--json], stop")

    while (true) {
        print("> ")
        val line = readLine()?.trim().orEmpty()
        if (line.isEmpty()) continue
        val parts = line.split(" ").filter { it.isNotBlank() }
        when (parts.first().lowercase()) {
            "help" -> println("Commands: help, status, save, load, plugins, worlds, world create, entities, entity spawn, players, player join|leave|move, inventory, scan, reload <id|all>, doctor [--json], profile <id> [--json], run <plugin> <command...>, adapters <plugin> [--json], adapter invoke <plugin> <adapterId> <action> [k=v ...] [--json], stop")
            "status" -> {
                val s = core.status()
                println("running=${s.running} uptimeMs=${s.uptimeMillis} ticks=${s.tickCount} avgTickNs=${s.averageTickDurationNanos} lastTickNs=${s.lastTickDurationNanos} tickFailures=${s.tickFailures} plugins=${s.loadedPlugins} players=${s.onlinePlayers} worlds=${s.worlds} entities=${s.entities}")
                println("tickPhases.avgNs queue=${s.averageQueueDrainNanos} world=${s.averageWorldTickNanos} events=${s.averageEventPublishNanos} systems=${s.averageSystemsNanos}")
                if (netServer != null) {
                    val nm = netServer.metrics()
                    println("net req=${nm.totalRequests} json=${nm.jsonRequests} legacy=${nm.legacyRequests} avgReqNs=${nm.averageRequestNanos}")
                    val top = nm.actionMetrics.entries
                        .sortedByDescending { it.value.totalNanos }
                        .take(3)
                    top.forEach { (action, metric) ->
                        println("net.action[$action]=count:${metric.count},fail:${metric.failures},avgNs:${metric.averageNanos},maxNs:${metric.maxNanos}")
                    }
                }
                val topSystems = core.plugins()
                    .map { it.substringBefore('@') }
                    .mapNotNull { id -> core.profile(id) }
                    .flatMap { p ->
                        p.systems.entries.map { (name, metric) ->
                            Triple("${p.pluginId}:$name", metric.averageNanos * metric.runs, metric)
                        }
                    }
                    .sortedByDescending { it.second }
                    .take(3)
                if (topSystems.isNotEmpty()) {
                    topSystems.forEach { (id, _, metric) ->
                        println("core.system[$id]=runs:${metric.runs},fail:${metric.failures},avgNs:${metric.averageNanos},maxNs:${metric.maxNanos}")
                    }
                }
            }
            "save" -> {
                core.saveState()
                println("state saved")
            }
            "load" -> {
                core.loadState()
                println("state loaded")
            }
            "plugins" -> {
                val plugins = core.plugins()
                println("Loaded: ${plugins.size}")
                plugins.forEach { println("- $it") }
            }
            "worlds" -> {
                val worlds = core.worlds()
                println("Worlds: ${worlds.size}")
                worlds.forEach { world ->
                    println("- ${world.name} seed=${world.seed} time=${world.time}")
                }
            }
            "world" -> {
                if (parts.getOrNull(1)?.lowercase() != "create") {
                    println("Usage: world create <name> [seed]")
                    continue
                }
                val name = parts.getOrNull(2)
                if (name.isNullOrBlank()) {
                    println("Usage: world create <name> [seed]")
                    continue
                }
                val seed = parts.getOrNull(3)?.toLongOrNull() ?: 0L
                val world = core.createWorld(name, seed)
                println("world created ${world.name} seed=${world.seed}")
            }
            "entities" -> {
                val world = parts.getOrNull(1)
                val entities = core.entities(world)
                println("Entities: ${entities.size}")
                entities.forEach { entity ->
                    println("- ${entity.type} (${entity.uuid}) @ ${entity.world} ${entity.x},${entity.y},${entity.z}")
                }
            }
            "entity" -> {
                if (parts.getOrNull(1)?.lowercase() != "spawn") {
                    println("Usage: entity spawn <type> <world> <x> <y> <z>")
                    continue
                }
                val type = parts.getOrNull(2)
                val world = parts.getOrNull(3)
                val x = parts.getOrNull(4)?.toDoubleOrNull()
                val y = parts.getOrNull(5)?.toDoubleOrNull()
                val z = parts.getOrNull(6)?.toDoubleOrNull()
                if (type.isNullOrBlank() || world.isNullOrBlank() || x == null || y == null || z == null) {
                    println("Usage: entity spawn <type> <world> <x> <y> <z>")
                    continue
                }
                val entity = core.spawnEntity(type, world, x, y, z)
                println("spawned ${entity.type} (${entity.uuid}) @ ${entity.world} ${entity.x},${entity.y},${entity.z}")
            }
            "players" -> {
                val players = core.players()
                println("Online players: ${players.size}")
                players.forEach { player ->
                    println("- ${player.name} (${player.uuid}) @ ${player.world} ${player.x},${player.y},${player.z}")
                }
            }
            "player" -> {
                when (parts.getOrNull(1)?.lowercase()) {
                    "join" -> {
                        val name = parts.getOrNull(2)
                        if (name.isNullOrBlank()) {
                            println("Usage: player join <name> [world] [x] [y] [z]")
                            continue
                        }
                        val world = parts.getOrNull(3) ?: "world"
                        val x = parts.getOrNull(4)?.toDoubleOrNull() ?: 0.0
                        val y = parts.getOrNull(5)?.toDoubleOrNull() ?: 64.0
                        val z = parts.getOrNull(6)?.toDoubleOrNull() ?: 0.0
                        val player = core.joinPlayer(name, world, x, y, z)
                        println("joined ${player.name} (${player.uuid}) @ ${player.world} ${player.x},${player.y},${player.z}")
                    }
                    "leave" -> {
                        val name = parts.getOrNull(2)
                        if (name.isNullOrBlank()) {
                            println("Usage: player leave <name>")
                            continue
                        }
                        val left = core.leavePlayer(name)
                        if (left == null) {
                            println("Player '$name' not online")
                        } else {
                            println("left ${left.name} (${left.uuid})")
                        }
                    }
                    "move" -> {
                        val name = parts.getOrNull(2)
                        val x = parts.getOrNull(3)?.toDoubleOrNull()
                        val y = parts.getOrNull(4)?.toDoubleOrNull()
                        val z = parts.getOrNull(5)?.toDoubleOrNull()
                        if (name.isNullOrBlank() || x == null || y == null || z == null) {
                            println("Usage: player move <name> <x> <y> <z> [world]")
                            continue
                        }
                        val world = parts.getOrNull(6)
                        val moved = core.movePlayer(name, x, y, z, world)
                        if (moved == null) {
                            println("Player '$name' not online")
                        } else {
                            println("moved ${moved.name} @ ${moved.world} ${moved.x},${moved.y},${moved.z}")
                        }
                    }
                    else -> println("Usage: player join|leave|move ...")
                }
            }
            "inventory" -> {
                when (parts.getOrNull(1)?.lowercase()) {
                    "set" -> {
                        val owner = parts.getOrNull(2)
                        val slot = parts.getOrNull(3)?.toIntOrNull()
                        val item = parts.getOrNull(4)
                        if (owner.isNullOrBlank() || slot == null || item.isNullOrBlank()) {
                            println("Usage: inventory set <player> <slot> <itemId|air>")
                            continue
                        }
                        val ok = core.setInventoryItem(owner, slot, item)
                        println(if (ok) "inventory updated" else "inventory update failed")
                    }
                    else -> {
                        val owner = parts.getOrNull(1)
                        if (owner.isNullOrBlank()) {
                            println("Usage: inventory <player> | inventory set <player> <slot> <itemId|air>")
                            continue
                        }
                        val inv = core.inventory(owner)
                        if (inv == null) {
                            println("No inventory for '$owner'")
                        } else {
                            println("inventory ${inv.owner} size=${inv.size} used=${inv.slots.size}")
                            inv.slots.toSortedMap().forEach { (slot, item) ->
                                println("- slot[$slot]=$item")
                            }
                        }
                    }
                }
            }
            "scan" -> println("Loaded ${core.loadNewPlugins()} new plugin(s)")
            "reload" -> {
                val target = parts.getOrNull(1)
                if (target == null) {
                    println("Usage: reload <id|all>")
                    continue
                }
                val report = if (target == "all") core.reloadAll() else core.reload(target)
                println("status=${report.status} reloaded=${report.reloadedPlugins.joinToString(",")} reason=${report.reason ?: "<none>"}")
            }
            "doctor" -> {
                val d = core.doctor()
                val jsonMode = parts.any { it.equals("--json", ignoreCase = true) }
                if (jsonMode) {
                    println(objectMapper.writeValueAsString(d))
                } else {
                    println("loaded=${d.loadedPlugins.size} loadOrder=${d.currentLoadOrder.joinToString("->")}")
                    if (d.currentDependencyIssues.isNotEmpty()) {
                        d.currentDependencyIssues.toSortedMap().forEach { (id, reason) ->
                            println("issue[$id]=$reason")
                        }
                    }
                }
            }
            "profile" -> {
                val id = parts.getOrNull(1)
                if (id == null) {
                    println("Usage: profile <id>")
                    continue
                }
                val p = core.profile(id)
                if (p == null) {
                    println("No profile for plugin '$id'")
                    continue
                }
                val jsonMode = parts.any { it.equals("--json", ignoreCase = true) }
                if (jsonMode) {
                    println(objectMapper.writeValueAsString(p))
                } else {
                    println("plugin=${p.pluginId} activeTasks=${p.activeTasks}")
                    if (p.systems.isNotEmpty()) {
                        p.systems.forEach { (name, metric) ->
                            println("system[$name]=runs:${metric.runs},fail:${metric.failures},avgNs:${metric.averageNanos},maxNs:${metric.maxNanos}")
                        }
                    }
                    if (p.adapters.isNotEmpty()) {
                        p.adapters.forEach { (name, metric) ->
                            println("adapter[$name]=total:${metric.total},ok:${metric.accepted},deny:${metric.denied},timeout:${metric.timeouts},fail:${metric.failures}")
                        }
                    }
                }
            }
            "run" -> {
                val pluginId = parts.getOrNull(1)
                val commandLine = parts.drop(2).joinToString(" ")
                if (pluginId == null || commandLine.isBlank()) {
                    println("Usage: run <plugin> <command...>")
                    continue
                }
                println(core.run(pluginId = pluginId, sender = "standalone-console", commandLine = commandLine))
            }
            "adapters" -> {
                val pluginId = parts.getOrNull(1)
                if (pluginId == null) {
                    println("Usage: adapters <plugin> [--json]")
                    continue
                }
                val adapters = core.adapters(pluginId)
                if (adapters.isEmpty()) {
                    println("No adapters (or unknown plugin): $pluginId")
                    continue
                }
                val jsonMode = parts.any { it.equals("--json", ignoreCase = true) }
                if (jsonMode) {
                    println(objectMapper.writeValueAsString(adapters))
                } else {
                    println("Adapters for $pluginId: ${adapters.size}")
                    adapters.forEach { adapter ->
                        val caps = if (adapter.capabilities.isEmpty()) "<none>" else adapter.capabilities.sorted().joinToString(",")
                        println("- ${adapter.id}@${adapter.version} caps=$caps")
                    }
                }
            }
            "adapter" -> {
                if (parts.getOrNull(1)?.lowercase() != "invoke") {
                    println("Usage: adapter invoke <plugin> <adapterId> <action> [k=v ...] [--json]")
                    continue
                }
                val pluginId = parts.getOrNull(2)
                val adapterId = parts.getOrNull(3)
                val action = parts.getOrNull(4)
                if (pluginId == null || adapterId == null || action == null) {
                    println("Usage: adapter invoke <plugin> <adapterId> <action> [k=v ...] [--json]")
                    continue
                }
                val rawPayload = parts.drop(5)
                val jsonMode = rawPayload.any { it.equals("--json", ignoreCase = true) }
                val payload = parsePayload(rawPayload.filterNot { it.equals("--json", ignoreCase = true) })
                val response = core.invokeAdapter(pluginId, adapterId, action, payload)
                if (jsonMode) {
                    println(objectMapper.writeValueAsString(response))
                } else {
                    println("adapter.success=${response.success}")
                    if (response.message != null) println("adapter.message=${response.message}")
                    response.payload.toSortedMap().forEach { (k, v) -> println("adapter.payload.$k=$v") }
                }
            }
            "stop", "exit", "quit" -> {
                netServer?.stop()
                core.stop()
                exitProcess(0)
            }
            else -> println("Unknown command. Use: help")
        }
    }
}

private fun parsePayload(rawPairs: List<String>): Map<String, String> {
    if (rawPairs.isEmpty()) return emptyMap()
    return rawPairs.mapNotNull { pair ->
        val idx = pair.indexOf('=')
        if (idx <= 0 || idx == pair.lastIndex) {
            null
        } else {
            val key = pair.substring(0, idx).trim()
            val value = pair.substring(idx + 1).trim()
            if (key.isBlank()) null else key to value
        }
    }.toMap()
}
