package com.clockwork.standalone

import com.fasterxml.jackson.databind.ObjectMapper
import com.clockwork.api.CommandSender
import com.clockwork.api.CommandSenderType
import com.clockwork.core.GigaStandaloneCore
import com.clockwork.core.StandaloneCapacityException
import com.clockwork.core.StandaloneCoreConfig
import com.clockwork.runtime.AdapterExecutionMode
import com.clockwork.runtime.AdapterSecurityConfig
import com.clockwork.runtime.EventDispatchMode
import com.clockwork.runtime.FaultBudgetEscalationPolicy
import com.clockwork.runtime.FaultBudgetPolicy
import com.clockwork.runtime.MetricSnapshot
import com.clockwork.net.StandaloneNetConfig
import com.clockwork.net.StandaloneNetServer
import com.clockwork.net.SessionActionResult
import com.clockwork.net.SessionJoinContext
import com.clockwork.net.StandaloneSessionHandler
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
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
            defaultWorld = launchConfig.defaultWorld,
            maxPlayers = launchConfig.maxPlayers,
            maxWorlds = launchConfig.maxWorlds,
            maxEntities = launchConfig.maxEntities,
            tickPeriodMillis = launchConfig.tickPeriodMillis,
            autoSaveEveryTicks = launchConfig.autoSaveEveryTicks,
            chunkViewDistance = launchConfig.chunkViewDistance,
            maxChunkLoadsPerTick = launchConfig.maxChunkLoadsPerTick,
            maxLoadedChunksPerWorld = launchConfig.maxLoadedChunksPerWorld,
            runtimeSchedulerWorkerThreads = launchConfig.runtimeSchedulerWorkerThreads,
            adapterSecurity = AdapterSecurityConfig(
                maxPayloadEntries = launchConfig.adapterMaxPayloadEntries,
                maxPayloadKeyChars = launchConfig.adapterMaxPayloadKeyChars,
                maxPayloadValueChars = launchConfig.adapterMaxPayloadValueChars,
                maxPayloadTotalChars = launchConfig.adapterMaxPayloadTotalChars,
                maxCallsPerMinute = launchConfig.adapterRateLimitPerMinute,
                maxCallsPerMinutePerPlugin = launchConfig.adapterRateLimitPerMinutePerPlugin,
                maxConcurrentInvocationsPerAdapter = launchConfig.adapterMaxConcurrentInvocationsPerAdapter,
                invocationTimeoutMillis = launchConfig.adapterTimeoutMillis,
                auditLogEnabled = launchConfig.adapterAuditLogEnabled,
                auditLogSuccesses = launchConfig.adapterAuditLogSuccesses,
                auditRetentionMaxEntriesPerPlugin = launchConfig.adapterAuditRetentionMaxEntriesPerPlugin,
                auditRetentionMaxEntriesPerAdapter = launchConfig.adapterAuditRetentionMaxEntriesPerAdapter,
                auditRetentionMaxAgeMillis = launchConfig.adapterAuditRetentionMaxAgeMillis,
                auditRetentionMaxMemoryBytes = launchConfig.adapterAuditRetentionMaxMemoryBytes,
                payloadPolicyProfile = when (launchConfig.adapterPayloadPolicyProfile.lowercase()) {
                    "strict" -> com.clockwork.runtime.AdapterPayloadPolicyProfile.STRICT
                    "perf" -> com.clockwork.runtime.AdapterPayloadPolicyProfile.PERF
                    else -> com.clockwork.runtime.AdapterPayloadPolicyProfile.BALANCED
                },
                executionMode = when (launchConfig.adapterExecutionMode.lowercase()) {
                    "fast" -> AdapterExecutionMode.FAST
                    else -> AdapterExecutionMode.SAFE
                }
            ),
            faultBudgetPolicy = FaultBudgetPolicy(
                maxFaultsPerWindow = launchConfig.faultBudgetMaxFaultsPerWindow,
                windowMillis = launchConfig.faultBudgetWindowMillis
            ),
            faultBudgetEscalationPolicy = FaultBudgetEscalationPolicy(
                warnUsageRatio = launchConfig.faultBudgetWarnUsageRatio,
                throttleUsageRatio = launchConfig.faultBudgetThrottleUsageRatio,
                isolateUsageRatio = launchConfig.faultBudgetIsolateUsageRatio,
                throttleBudgetMultiplier = launchConfig.faultBudgetThrottleBudgetMultiplier
            ),
            eventDispatchMode = when (launchConfig.eventDispatchMode.lowercase()) {
                "polymorphic" -> EventDispatchMode.POLYMORPHIC
                "hybrid" -> EventDispatchMode.HYBRID
                else -> EventDispatchMode.EXACT
            }
        )
    )

    var netListenAddress: String? = null
    val netServer = if (launchConfig.netPort > 0) {
        StandaloneNetServer(
            config = StandaloneNetConfig(
                port = launchConfig.netPort,
                authRequired = launchConfig.netAuthRequired,
                sharedSecret = launchConfig.netSharedSecret,
                adminSecret = launchConfig.netAdminSecret,
                sessionTtlSeconds = launchConfig.netSessionTtlSeconds,
                maxTextLineBytes = launchConfig.netMaxTextLineBytes,
                readTimeoutMillis = launchConfig.netReadTimeoutMillis,
                maxConcurrentSessions = launchConfig.netMaxConcurrentSessions,
                maxSessionsPerIp = launchConfig.netMaxSessionsPerIp,
                maxRequestsPerMinutePerConnection = launchConfig.netMaxRequestsPerMinutePerConnection,
                maxRequestsPerMinutePerIp = launchConfig.netMaxRequestsPerMinutePerIp,
                maxJsonPayloadEntries = launchConfig.netMaxJsonPayloadEntries,
                maxJsonPayloadKeyChars = launchConfig.netMaxJsonPayloadKeyChars,
                maxJsonPayloadValueChars = launchConfig.netMaxJsonPayloadValueChars,
                maxJsonPayloadTotalChars = launchConfig.netMaxJsonPayloadTotalChars,
                workerThreads = launchConfig.netWorkerThreads,
                workerQueueCapacity = launchConfig.netWorkerQueueCapacity,
                auditLogEnabled = launchConfig.netAuditLogEnabled,
                textFlushEveryResponses = launchConfig.netTextFlushEveryResponses,
                frameFlushEveryResponses = launchConfig.netFrameFlushEveryResponses,
                minecraftBridgeEnabled = launchConfig.netMinecraftBridgeEnabled,
                minecraftBridgeHost = launchConfig.netMinecraftBridgeHost,
                minecraftBridgePort = launchConfig.netMinecraftBridgePort,
                minecraftBridgeConnectTimeoutMillis = launchConfig.netMinecraftBridgeConnectTimeoutMillis,
                minecraftBridgeStreamBufferBytes = launchConfig.netMinecraftBridgeStreamBufferBytes,
                minecraftBridgeSocketBufferBytes = launchConfig.netMinecraftBridgeSocketBufferBytes,
                minecraftMode = launchConfig.netMinecraftMode,
                minecraftSupportedProtocolVersion = launchConfig.netMinecraftProtocolVersion,
                minecraftStatusDescription = launchConfig.netMinecraftStatusDescription,
                minecraftOnlineSessionServerUrl = launchConfig.netMinecraftOnlineSessionServerUrl,
                minecraftOnlineAuthTimeoutMillis = launchConfig.netMinecraftOnlineAuthTimeoutMillis
            ),
            logger = { message ->
                if (message.contains("listening on", ignoreCase = true)) {
                    netListenAddress = message.substringAfter("listening on", "").trim().ifEmpty { null }
                } else {
                    println("[GigaNet] $message")
                }
            },
            handler = object : StandaloneSessionHandler {
                override fun joinWithContext(
                    name: String,
                    world: String,
                    x: Double,
                    y: Double,
                    z: Double,
                    context: SessionJoinContext
                ): SessionActionResult {
                    val blocked = firstBlockedClientMod(
                        reportedMods = context.clientMods,
                        bannedMods = launchConfig.bannedClientMods
                    )
                    if (blocked != null) {
                        return SessionActionResult(
                            success = false,
                            code = "MOD_BANNED",
                            message = "Client mod '$blocked' is banned on this server."
                        )
                    }
                    return join(name, world, x, y, z)
                }

                override fun join(name: String, world: String, x: Double, y: Double, z: Double): SessionActionResult {
                    val player = try {
                        core.joinPlayer(name, world, x, y, z)
                    } catch (limit: StandaloneCapacityException) {
                        return SessionActionResult(false, limit.code, limit.message ?: "capacity limit reached")
                    }
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
                    val world = try {
                        core.createWorld(name, seed)
                    } catch (limit: StandaloneCapacityException) {
                        return SessionActionResult(false, limit.code, limit.message ?: "capacity limit reached")
                    }
                    return SessionActionResult(
                        success = true,
                        code = "WORLD_CREATED",
                        message = "world created ${world.name} seed=${world.seed}",
                        payload = mapOf("name" to world.name, "seed" to world.seed.toString(), "time" to world.time.toString())
                    )
                }

                override fun entitySpawn(type: String, world: String, x: Double, y: Double, z: Double): SessionActionResult {
                    val entity = try {
                        core.spawnEntity(type, world, x, y, z)
                    } catch (limit: StandaloneCapacityException) {
                        return SessionActionResult(false, limit.code, limit.message ?: "capacity limit reached")
                    } catch (_: Exception) {
                        return SessionActionResult(false, "ENTITY_SPAWN_FAILED", "entity spawn cancelled or failed")
                    }
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
    val commandHelp = """
        Commands:
          help
          status [--json]
          save | load
          plugins
          plugin list [--json]
          plugin error [pluginId] [--json]
          plugin scan
          plugin reload <id|all|changed>
          worlds | world create
          entities | entity spawn
          players | player join|leave|move
          inventory
          scan | sync | reload <id|all|changed>
          doctor [--json] [--pretty|--compact]
          profile <id> [--json] [--pretty|--compact]
          diag export [--compact] [path]
          profile export <id> [--compact] [path]
          run <plugin> <command...>
          adapters <plugin> [--json]
          adapter invoke <plugin> <adapterId> <action> [k=v ...] [--json]
          bridge status
          stop
    """.trimIndent()
    println(
        """
        Clockwork Standalone
        --------------------
        Name: ${launchConfig.serverName}
        Version: ${launchConfig.serverVersion}
        World: ${launchConfig.defaultWorld}
        Limits: players=${launchConfig.maxPlayers}, worlds=${launchConfig.maxWorlds}, entities=${launchConfig.maxEntities}
        Tick: ${launchConfig.tickPeriodMillis}ms (autosave=${launchConfig.autoSaveEveryTicks} ticks)
        Runtime: schedulerThreads=${launchConfig.runtimeSchedulerWorkerThreads}, eventDispatch=${launchConfig.eventDispatchMode}
        Network: ${if (netServer == null) "disabled" else "enabled (port=${launchConfig.netPort}, auth=${launchConfig.netAuthRequired}, mcMode=${launchConfig.netMinecraftMode}, mcProtocol=${launchConfig.netMinecraftProtocolVersion}, mcBridge=${if (launchConfig.netMinecraftBridgeEnabled) "on:${launchConfig.netMinecraftBridgeHost}:${launchConfig.netMinecraftBridgePort}@${launchConfig.netMinecraftBridgeConnectTimeoutMillis}ms buf=${launchConfig.netMinecraftBridgeStreamBufferBytes}/${launchConfig.netMinecraftBridgeSocketBufferBytes}" else "off"})"}
        Adapter: schema=${launchConfig.securityConfigSchemaVersion}, mode=${launchConfig.adapterExecutionMode}, timeoutMs=${launchConfig.adapterTimeoutMillis}
        Fault Budget: maxFaults=${launchConfig.faultBudgetMaxFaultsPerWindow}, windowMs=${launchConfig.faultBudgetWindowMillis}, warn=${launchConfig.faultBudgetWarnUsageRatio}, throttle=${launchConfig.faultBudgetThrottleUsageRatio}, isolate=${launchConfig.faultBudgetIsolateUsageRatio}
        """.trimIndent()
    )
    launchConfig.securityConfigWarnings.forEach { warning ->
        println("[security-config] $warning")
    }
    println(commandHelp)
    val ipDisplay = if (netServer == null) {
        "disabled"
    } else {
        netListenAddress ?: "0.0.0.0:${launchConfig.netPort}"
    }
    println("IP: $ipDisplay")

    while (true) {
        print("> ")
        val line = readLine()?.trim().orEmpty()
        if (line.isEmpty()) continue
        val parts = line.split(" ").filter { it.isNotBlank() }
        when (parts.first().lowercase()) {
            "help" -> println(commandHelp)
            "status" -> {
                val jsonMode = parts.any { it.equals("--json", ignoreCase = true) }
                val s = core.status()
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
                if (jsonMode) {
                    println(objectMapper.writeValueAsString(statusView(core = core, netServer = netServer, topSystems = topSystems)))
                } else {
                    println("running=${s.running} uptimeMs=${s.uptimeMillis} ticks=${s.tickCount} avgTickNs=${s.averageTickDurationNanos} lastTickNs=${s.lastTickDurationNanos} tickFailures=${s.tickFailures} plugins=${s.loadedPlugins} players=${s.onlinePlayers} worlds=${s.worlds} entities=${s.entities}")
                    println("tickPhases.avgNs queue=${s.averageQueueDrainNanos} world=${s.averageWorldTickNanos} events=${s.averageEventPublishNanos} systems=${s.averageSystemsNanos}")
                    println("tickStability jitterAvgNs=${s.averageTickJitterNanos} jitterMaxNs=${s.maxTickJitterNanos} overruns=${s.tickOverruns} pluginBudgetExhaustions=${s.pluginBudgetExhaustions} faultWarnTicks=${s.faultBudgetWarnTicks} faultThrottleTicks=${s.faultBudgetThrottleTicks} faultIsolateTicks=${s.faultBudgetIsolateTicks}")
                    if (netServer != null) {
                        val nm = netServer.metrics()
                        println("net req=${nm.totalRequests} json=${nm.jsonRequests} legacy=${nm.legacyRequests} avgReqNs=${nm.averageRequestNanos}")
                        println("net.mcBridge handshakes=${nm.minecraftBridge.handshakesDetected} proxied=${nm.minecraftBridge.proxiedSessions} active=${nm.minecraftBridge.activeProxiedSessions} failConnect=${nm.minecraftBridge.connectFailures} upBytes=${nm.minecraftBridge.bytesClientToUpstream} downBytes=${nm.minecraftBridge.bytesUpstreamToClient} avgProxyNs=${nm.minecraftBridge.averageProxySessionNanos} maxProxyNs=${nm.minecraftBridge.maxProxySessionNanos} nativeLogins=${nm.minecraftBridge.nativeLoginAttempts} nativeRejected=${nm.minecraftBridge.nativeLoginRejected}")
                        val top = nm.actionMetrics.entries
                            .sortedByDescending { it.value.totalNanos }
                            .take(3)
                        top.forEach { (action, metric) ->
                            println("net.action[$action]=count:${metric.count},fail:${metric.failures},avgNs:${metric.averageNanos},maxNs:${metric.maxNanos}")
                        }
                    }
                    if (topSystems.isNotEmpty()) {
                        topSystems.forEach { (id, _, metric) ->
                            println("core.system[$id]=runs:${metric.runs},fail:${metric.failures},avgNs:${metric.averageNanos},maxNs:${metric.maxNanos}")
                        }
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
                val world = try {
                    core.createWorld(name, seed)
                } catch (limit: StandaloneCapacityException) {
                    println(limit.message ?: "world limit reached")
                    continue
                }
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
                val entity = try {
                    core.spawnEntity(type, world, x, y, z)
                } catch (limit: StandaloneCapacityException) {
                    println(limit.message ?: "entity limit reached")
                    continue
                } catch (_: Exception) {
                    null
                }
                if (entity == null) {
                    println("entity spawn cancelled or failed")
                    continue
                }
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
                        val world = parts.getOrNull(3) ?: launchConfig.defaultWorld
                        val x = parts.getOrNull(4)?.toDoubleOrNull() ?: 0.0
                        val y = parts.getOrNull(5)?.toDoubleOrNull() ?: 64.0
                        val z = parts.getOrNull(6)?.toDoubleOrNull() ?: 0.0
                        val player = try {
                            core.joinPlayer(name, world, x, y, z)
                        } catch (limit: StandaloneCapacityException) {
                            println(limit.message ?: "join rejected by capacity limit")
                            continue
                        }
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
            "plugin" -> {
                when (parts.getOrNull(1)?.lowercase()) {
                    "list" -> {
                        val list = core.plugins()
                        val jsonMode = parts.any { it.equals("--json", ignoreCase = true) }
                        if (jsonMode) {
                            println(objectMapper.writeValueAsString(list))
                        } else {
                            println("plugins=${list.size}")
                            list.forEach { println("- $it") }
                        }
                    }
                    "scan" -> println("Loaded ${core.loadNewPlugins()} new plugin(s)")
                    "reload" -> {
                        val target = parts.getOrNull(2)
                        if (target == null) {
                            println("Usage: plugin reload <id|all|changed>")
                            continue
                        }
                        val report = when (target.lowercase()) {
                            "all" -> core.reloadAll()
                            "changed" -> {
                                val changed = core.syncPlugins()
                                println(
                                    "loaded=${changed.loadedNewPlugins} changed=${changed.changedPluginsDetected} " +
                                        "reloadStatus=${changed.reloadStatus} reloaded=${changed.reloadedPlugins.joinToString(",")} " +
                                        "reason=${changed.reason ?: "<none>"}"
                                )
                                continue
                            }
                            else -> core.reload(target)
                        }
                        println(
                            "status=${report.status} reloaded=${report.reloadedPlugins.joinToString(",")} " +
                                "changedData=${report.checkpointChangedPlugins.joinToString(",")} " +
                                "rollbackRecovered=${report.rollbackRecoveredPlugins.joinToString(",")} " +
                                "rollbackFailed=${report.rollbackFailedPlugins.joinToString(",")} " +
                                "rollbackDataRestored=${report.rollbackDataRestored} reason=${report.reason ?: "<none>"}"
                        )
                    }
                    "error", "errors" -> {
                        val jsonMode = parts.any { it.equals("--json", ignoreCase = true) }
                        val pluginIdFilter = parts
                            .drop(2)
                            .firstOrNull { !it.equals("--json", ignoreCase = true) }
                        val diagnostics = core.doctor()
                        val errors = collectPluginLoadIssues(diagnostics, pluginIdFilter)
                        if (jsonMode) {
                            println(
                                objectMapper.writeValueAsString(
                                    mapOf(
                                        "pluginIdFilter" to pluginIdFilter,
                                        "errors" to errors
                                    )
                                )
                            )
                        } else {
                            if (errors.isEmpty()) {
                                if (pluginIdFilter.isNullOrBlank()) {
                                    println("No plugin load errors recorded.")
                                } else {
                                    println("No plugin load errors for '$pluginIdFilter'.")
                                }
                            } else {
                                errors.forEach { issue ->
                                    println("plugin.error[${issue.pluginId}] class=${issue.errorClass} source=${issue.source} reason=${issue.reason}")
                                }
                            }
                        }
                    }
                    else -> println("Usage: plugin list [--json] | plugin error [pluginId] [--json] | plugin scan | plugin reload <id|all|changed>")
                }
            }
            "scan" -> println("Loaded ${core.loadNewPlugins()} new plugin(s)")
            "sync" -> {
                val report = core.syncPlugins()
                println(
                    "loaded=${report.loadedNewPlugins} changed=${report.changedPluginsDetected} " +
                        "reloadStatus=${report.reloadStatus} reloaded=${report.reloadedPlugins.joinToString(",")} " +
                        "reason=${report.reason ?: "<none>"}"
                )
            }
            "reload" -> {
                val target = parts.getOrNull(1)
                if (target == null) {
                    println("Usage: reload <id|all|changed>")
                    continue
                }
                val report = when (target.lowercase()) {
                    "all" -> core.reloadAll()
                    "changed" -> {
                        val changed = core.syncPlugins()
                        println(
                            "loaded=${changed.loadedNewPlugins} changed=${changed.changedPluginsDetected} " +
                                "reloadStatus=${changed.reloadStatus} reloaded=${changed.reloadedPlugins.joinToString(",")} " +
                                "reason=${changed.reason ?: "<none>"}"
                        )
                        continue
                    }
                    else -> core.reload(target)
                }
                println(
                    "status=${report.status} reloaded=${report.reloadedPlugins.joinToString(",")} " +
                        "changedData=${report.checkpointChangedPlugins.joinToString(",")} " +
                        "rollbackRecovered=${report.rollbackRecoveredPlugins.joinToString(",")} " +
                        "rollbackFailed=${report.rollbackFailedPlugins.joinToString(",")} " +
                        "rollbackDataRestored=${report.rollbackDataRestored} reason=${report.reason ?: "<none>"}"
                )
            }
            "doctor" -> {
                val d = core.doctor()
                val jsonMode = parts.any { it.equals("--json", ignoreCase = true) }
                val outputMode = parseOutputMode(parts)
                if (outputMode == null) {
                    println("Usage: doctor [--json] [--pretty|--compact]")
                    continue
                }
                val recommendations = buildDoctorRecommendations(d)
                if (jsonMode) {
                    printJson(
                        mapper = objectMapper,
                        value = mapOf(
                            "diagnostics" to d,
                            "recommendations" to recommendations
                        ),
                        mode = outputMode
                    )
                } else {
                    val pluginHotspots = d.pluginPerformance
                        .toSortedMap()
                        .filterValues {
                            it.slowSystems.isNotEmpty() ||
                                it.adapterHotspots.isNotEmpty() ||
                                it.isolatedSystems.any { system -> system.isolated || system.isolationCount > 0L }
                        }

                    if (outputMode == OperatorOutputMode.COMPACT) {
                        val recommendationCount = recommendations.values.sumOf { it.size }
                        println(
                            "loaded=${d.loadedPlugins.size} issues=${d.currentDependencyIssues.size} hotspots=${pluginHotspots.size} recommendations=$recommendationCount"
                        )
                        recommendations.toSortedMap().forEach { (pluginId, recs) ->
                            if (recs.isNotEmpty()) {
                                println(
                                    "recommend[$pluginId]=" + recs.joinToString("|") { "${it.code}:${it.severity}:${it.errorClass}" }
                                )
                            }
                        }
                        continue
                    }

                    println("loaded=${d.loadedPlugins.size} loadOrder=${d.currentLoadOrder.joinToString("->")}")
                    if (d.currentDependencyIssues.isNotEmpty()) {
                        d.currentDependencyIssues.toSortedMap().forEach { (id, reason) ->
                            println("issue[$id]=$reason")
                        }
                    }
                    if (pluginHotspots.isNotEmpty()) {
                        pluginHotspots.forEach { (pluginId, perf) ->
                            if (perf.slowSystems.isNotEmpty()) {
                                val top = perf.slowSystems.take(3)
                                top.forEach { system ->
                                    println(
                                        "slow.system[$pluginId:${system.systemId}]=runs:${system.runs},avgNs:${system.averageNanos},maxNs:${system.maxNanos},failRate:${"%.3f".format(system.failureRate)} reasons=${system.reasons.joinToString("|")}"
                                    )
                                }
                            }
                            if (perf.adapterHotspots.isNotEmpty()) {
                                val top = perf.adapterHotspots.take(3)
                                top.forEach { adapter ->
                                    println(
                                        "hot.adapter[$pluginId:${adapter.adapterId}]=total:${adapter.total},denyRate:${"%.3f".format(adapter.deniedRate)},timeoutRate:${"%.3f".format(adapter.timeoutRate)},failRate:${"%.3f".format(adapter.failureRate)} reasons=${adapter.reasons.joinToString("|")}"
                                    )
                                }
                            }
                            if (perf.isolatedSystems.isNotEmpty()) {
                                perf.isolatedSystems
                                    .filter { it.isolated || it.isolationCount > 0L }
                                    .take(3)
                                    .forEach { system ->
                                        println(
                                            "isolated.system[$pluginId:${system.systemId}]=isolated:${system.isolated},remainingTicks:${system.remainingTicks},isolations:${system.isolationCount},skippedTicks:${system.skippedTicks},lastError:${system.lastError ?: "<none>"}"
                                        )
                                    }
                            }
                            if (perf.faultBudget.used > 0) {
                                println(
                                    "fault.budget[$pluginId]=used:${perf.faultBudget.used},remaining:${perf.faultBudget.remaining},tripped:${perf.faultBudget.tripped},stage:${perf.faultBudget.stage},usageRatio:${"%.3f".format(perf.faultBudget.usageRatio)}"
                                )
                            }
                            recommendations[pluginId].orEmpty().forEach { rec ->
                                println("recommend[$pluginId]=${rec.code}(${rec.severity}/${rec.errorClass}) ${rec.message}")
                            }
                        }
                    }
                }
            }
            "profile" -> {
                if (parts.getOrNull(1)?.equals("export", ignoreCase = true) == true) {
                    val id = parts.getOrNull(2)
                    if (id.isNullOrBlank()) {
                        println("Usage: profile export <id> [--compact] [path]")
                        continue
                    }
                    val compact = parts.any { it.equals("--compact", ignoreCase = true) }
                    val explicitPath = parts
                        .drop(3)
                        .firstOrNull { !it.startsWith("--") }
                    val targetDir = if (explicitPath.isNullOrBlank()) {
                        Path.of(launchConfig.dataDir).resolve("diagnostics").resolve("latest")
                    } else {
                        Path.of(explicitPath)
                    }
                    val exported = exportProfileBundle(
                        core = core,
                        mapper = objectMapper,
                        pluginId = id,
                        outputDir = targetDir,
                        compact = compact
                    )
                    if (!exported) {
                        println("No profile for plugin '$id'")
                    } else {
                        println("profile.exported plugin=$id path=${targetDir.toAbsolutePath()}")
                    }
                    continue
                }
                val id = parts.getOrNull(1)
                if (id == null) {
                    println("Usage: profile <id> [--json] [--pretty|--compact]")
                    continue
                }
                val jsonMode = parts.any { it.equals("--json", ignoreCase = true) }
                val outputMode = parseOutputMode(parts)
                if (outputMode == null) {
                    println("Usage: profile <id> [--json] [--pretty|--compact]")
                    continue
                }
                val p = core.profile(id)
                if (p == null) {
                    if (jsonMode) {
                        printJson(
                            mapper = objectMapper,
                            value = mapOf("pluginId" to id, "found" to false),
                            mode = outputMode
                        )
                    } else {
                        println("No profile for plugin '$id'")
                    }
                    continue
                }
                val recommendations = buildProfileRecommendations(p)
                val dependencyDiagnostic = core.doctor().currentDependencyDiagnostics[id]
                if (jsonMode) {
                    printJson(
                        mapper = objectMapper,
                        value = mapOf(
                            "profile" to p,
                            "dependencyDiagnostic" to dependencyDiagnostic,
                            "recommendations" to recommendations
                        ),
                        mode = outputMode
                    )
                } else {
                    if (outputMode == OperatorOutputMode.COMPACT) {
                        println(
                            "plugin=${p.pluginId} systems=${p.systems.size} slow=${p.slowSystems.size} adapters=${p.adapters.size} hotspots=${p.adapterHotspots.size} isolated=${p.isolatedSystems.count { it.isolated || it.isolationCount > 0L }} recommendations=${recommendations.size}"
                        )
                        if (recommendations.isNotEmpty()) {
                            println("recommend=${recommendations.joinToString("|") { "${it.code}:${it.severity}:${it.errorClass}" }}")
                        }
                        continue
                    }
                    println("plugin=${p.pluginId} activeTasks=${p.activeTasks}")
                    if (p.systems.isNotEmpty()) {
                        p.systems.forEach { (name, metric) ->
                            println("system[$name]=runs:${metric.runs},fail:${metric.failures},avgNs:${metric.averageNanos},maxNs:${metric.maxNanos}")
                        }
                    }
                    if (p.slowSystems.isNotEmpty()) {
                        p.slowSystems.take(5).forEach { system ->
                            println(
                                "slow.system[${system.systemId}]=runs:${system.runs},fail:${system.failures},avgNs:${system.averageNanos},maxNs:${system.maxNanos},failRate:${"%.3f".format(system.failureRate)} reasons=${system.reasons.joinToString("|")}"
                            )
                        }
                    }
                    if (p.adapters.isNotEmpty()) {
                        p.adapters.forEach { (name, metric) ->
                            println("adapter[$name]=total:${metric.total},ok:${metric.accepted},deny:${metric.denied},timeout:${metric.timeouts},fail:${metric.failures}")
                        }
                    }
                    if (p.adapterHotspots.isNotEmpty()) {
                        p.adapterHotspots.take(5).forEach { adapter ->
                            println(
                                "hot.adapter[${adapter.adapterId}]=total:${adapter.total},denyRate:${"%.3f".format(adapter.deniedRate)},timeoutRate:${"%.3f".format(adapter.timeoutRate)},failRate:${"%.3f".format(adapter.failureRate)} reasons=${adapter.reasons.joinToString("|")}"
                            )
                        }
                    }
                    if (p.isolatedSystems.isNotEmpty()) {
                        p.isolatedSystems
                            .filter { it.isolated || it.isolationCount > 0L }
                            .take(5)
                            .forEach { system ->
                                println(
                                    "isolated.system[${system.systemId}]=isolated:${system.isolated},remainingTicks:${system.remainingTicks},isolations:${system.isolationCount},skippedTicks:${system.skippedTicks},lastError:${system.lastError ?: "<none>"}"
                                )
                            }
                    }
                    if (p.faultBudget.used > 0) {
                        println(
                            "fault.budget=used:${p.faultBudget.used},remaining:${p.faultBudget.remaining},tripped:${p.faultBudget.tripped},stage:${p.faultBudget.stage},usageRatio:${"%.3f".format(p.faultBudget.usageRatio)}"
                        )
                    }
                    if (dependencyDiagnostic != null) {
                        println(
                            "dependency.issue[${dependencyDiagnostic.code}]=${dependencyDiagnostic.message} hint=${dependencyDiagnostic.hint}"
                        )
                    }
                    recommendations.forEach { rec ->
                        println("recommend=${rec.code}(${rec.severity}/${rec.errorClass}) ${rec.message}")
                    }
                }
            }
            "diag" -> {
                when (parts.getOrNull(1)?.lowercase()) {
                    "export" -> {
                        val compact = parts.any { it.equals("--compact", ignoreCase = true) }
                        val explicitPath = parts
                            .drop(2)
                            .firstOrNull { !it.startsWith("--") }
                        val targetDir = if (explicitPath.isNullOrBlank()) {
                            Path.of(launchConfig.dataDir).resolve("diagnostics").resolve("latest")
                        } else {
                            Path.of(explicitPath)
                        }
                        val summary = exportDiagnosticsBundle(
                            core = core,
                            mapper = objectMapper,
                            outputDir = targetDir,
                            compact = compact
                        )
                        println(
                            "diag.exported plugins=${summary.plugins.size} json=${summary.snapshotFile.toAbsolutePath()} html=${summary.htmlFile.toAbsolutePath()}"
                        )
                    }
                    else -> println("Usage: diag export [--compact] [path]")
                }
            }
            "run" -> {
                val pluginId = parts.getOrNull(1)
                val commandLine = parts.drop(2).joinToString(" ")
                if (pluginId == null || commandLine.isBlank()) {
                    println("Usage: run <plugin> <command...>")
                    continue
                }
                println(
                    core.run(
                        pluginId = pluginId,
                        sender = CommandSender(id = "standalone-console", type = CommandSenderType.CONSOLE),
                        commandLine = commandLine
                    )
                )
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
            "bridge" -> {
                if (parts.getOrNull(1)?.lowercase() != "status") {
                    println("Usage: bridge status")
                    continue
                }
                if (launchConfig.netMinecraftMode != "bridge") {
                    println("bridge.status unavailable because --net-minecraft-mode=${launchConfig.netMinecraftMode}")
                    continue
                }
                if (!launchConfig.netMinecraftBridgeEnabled) {
                    println("bridge=disabled")
                    continue
                }
                val started = System.nanoTime()
                val reachable = runCatching {
                    java.net.Socket().use { s ->
                        s.connect(
                            java.net.InetSocketAddress(
                                launchConfig.netMinecraftBridgeHost,
                                launchConfig.netMinecraftBridgePort
                            ),
                            launchConfig.netMinecraftBridgeConnectTimeoutMillis
                        )
                    }
                    true
                }.getOrElse { false }
                val latencyMs = (System.nanoTime() - started) / 1_000_000
                println(
                    "bridge target=${launchConfig.netMinecraftBridgeHost}:${launchConfig.netMinecraftBridgePort} timeoutMs=${launchConfig.netMinecraftBridgeConnectTimeoutMillis} streamBuf=${launchConfig.netMinecraftBridgeStreamBufferBytes} socketBuf=${launchConfig.netMinecraftBridgeSocketBufferBytes} reachable=$reachable latencyMs=$latencyMs"
                )
                netServer?.metrics()?.let { nm ->
                    println(
                        "bridge metrics handshakes=${nm.minecraftBridge.handshakesDetected} proxied=${nm.minecraftBridge.proxiedSessions} active=${nm.minecraftBridge.activeProxiedSessions} failConnect=${nm.minecraftBridge.connectFailures} upBytes=${nm.minecraftBridge.bytesClientToUpstream} downBytes=${nm.minecraftBridge.bytesUpstreamToClient} avgProxyNs=${nm.minecraftBridge.averageProxySessionNanos} maxProxyNs=${nm.minecraftBridge.maxProxySessionNanos} nativeLogins=${nm.minecraftBridge.nativeLoginAttempts} nativeRejected=${nm.minecraftBridge.nativeLoginRejected}"
                    )
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

private fun firstBlockedClientMod(reportedMods: Set<String>, bannedMods: Set<String>): String? {
    if (reportedMods.isEmpty() || bannedMods.isEmpty()) return null
    val normalizedBans = bannedMods.asSequence()
        .map { normalizeClientModToken(it) }
        .filter { it.isNotEmpty() }
        .toList()
    if (normalizedBans.isEmpty()) return null
    for (reported in reportedMods) {
        val normalizedReported = normalizeClientModToken(reported)
        if (normalizedReported.isEmpty()) continue
        val hit = normalizedBans.firstOrNull { ban ->
            normalizedReported == ban || normalizedReported.contains(ban)
        }
        if (hit != null) return reported
    }
    return null
}

private fun normalizeClientModToken(value: String): String {
    return value.trim().lowercase()
        .replace(Regex("[^a-z0-9]+"), "")
}

private data class DiagnosticsExportSummary(
    val generatedAtUtc: String,
    val plugins: List<String>,
    val snapshotFile: Path,
    val htmlFile: Path
)

private fun exportDiagnosticsBundle(
    core: GigaStandaloneCore,
    mapper: ObjectMapper,
    outputDir: Path,
    compact: Boolean
): DiagnosticsExportSummary {
    Files.createDirectories(outputDir)
    val generatedAt = DateTimeFormatter.ISO_INSTANT.format(Instant.now().atOffset(ZoneOffset.UTC))
    val diagnostics = core.doctor()
    val recommendations = buildDoctorRecommendations(diagnostics)
    val pluginIds = core.plugins()
        .map { it.substringBefore('@') }
        .distinct()
        .sorted()
    val profiles = pluginIds.associateWith { id -> core.profile(id) }

    val payload = linkedMapOf<String, Any?>(
        "generatedAtUtc" to generatedAt,
        "diagnostics" to diagnostics,
        "recommendations" to recommendations,
        "profiles" to profiles
    )
    val snapshotFile = outputDir.resolve("diagnostics.json")
    val snapshotJson = if (compact) mapper.writeValueAsString(payload) else mapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload)
    Files.writeString(snapshotFile, snapshotJson, StandardCharsets.UTF_8)

    val htmlFile = outputDir.resolve("index.html")
    Files.writeString(
        htmlFile,
        renderDiagnosticsHtml(generatedAt, pluginIds, recommendations, profiles),
        StandardCharsets.UTF_8
    )

    return DiagnosticsExportSummary(
        generatedAtUtc = generatedAt,
        plugins = pluginIds,
        snapshotFile = snapshotFile,
        htmlFile = htmlFile
    )
}

private fun exportProfileBundle(
    core: GigaStandaloneCore,
    mapper: ObjectMapper,
    pluginId: String,
    outputDir: Path,
    compact: Boolean
): Boolean {
    val profile = core.profile(pluginId) ?: return false
    Files.createDirectories(outputDir)
    val recommendations = buildProfileRecommendations(profile)
    val payload = linkedMapOf<String, Any?>(
        "generatedAtUtc" to DateTimeFormatter.ISO_INSTANT.format(Instant.now().atOffset(ZoneOffset.UTC)),
        "profile" to profile,
        "recommendations" to recommendations
    )
    val outFile = outputDir.resolve("profile-$pluginId.json")
    val json = if (compact) mapper.writeValueAsString(payload) else mapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload)
    Files.writeString(outFile, json, StandardCharsets.UTF_8)
    return true
}

internal fun renderDiagnosticsHtml(
    generatedAtUtc: String,
    pluginIds: List<String>,
    recommendations: Map<String, List<OperatorRecommendation>>,
    profiles: Map<String, com.clockwork.runtime.PluginRuntimeProfile?>
): String {
    val rows = pluginIds.joinToString("\n") { pluginId ->
        val profile = profiles[pluginId]
        val rec = recommendations[pluginId].orEmpty()
        val slow = profile?.slowSystems?.size ?: 0
        val hotspots = profile?.adapterHotspots?.size ?: 0
        val faults = profile?.faultBudget?.used ?: 0
        val recText = if (rec.isEmpty()) "none" else rec.joinToString("; ") { "${it.code}(${it.severity}/${it.errorClass})" }
        "<tr><td>$pluginId</td><td>$slow</td><td>$hotspots</td><td>$faults</td><td>$recText</td></tr>"
    }
    return """
        <!doctype html>
        <html lang="en">
        <head>
          <meta charset="utf-8" />
          <meta name="viewport" content="width=device-width, initial-scale=1" />
          <title>Clockwork Diagnostics Preview</title>
          <style>
            :root { --bg:#0f1419; --panel:#1a222c; --line:#2c3947; --text:#ecf1f6; --muted:#9fb2c7; --accent:#40c4aa; }
            body { margin:0; font-family:Consolas, "Liberation Mono", monospace; background:var(--bg); color:var(--text); }
            .wrap { max-width:1100px; margin:0 auto; padding:24px; }
            .head { margin-bottom:16px; }
            .meta { color:var(--muted); font-size:13px; }
            .panel { background:var(--panel); border:1px solid var(--line); border-radius:10px; padding:14px; }
            table { width:100%; border-collapse:collapse; font-size:13px; }
            th, td { border-bottom:1px solid var(--line); text-align:left; padding:8px; vertical-align:top; }
            th { color:var(--accent); }
            code { color:var(--accent); }
          </style>
        </head>
        <body>
          <div class="wrap">
            <div class="head">
              <h1>Clockwork Diagnostics Preview</h1>
              <div class="meta">Generated at: <code>$generatedAtUtc</code></div>
              <div class="meta">Source snapshot: <code>diagnostics.json</code></div>
            </div>
            <div class="panel">
              <table>
                <thead>
                  <tr><th>Plugin</th><th>Slow Systems</th><th>Adapter Hotspots</th><th>Fault Budget Used</th><th>Recommendations</th></tr>
                </thead>
                <tbody>
                  $rows
                </tbody>
              </table>
            </div>
          </div>
        </body>
        </html>
    """.trimIndent()
}

private data class PluginLoadIssue(
    val pluginId: String,
    val source: String,
    val reason: String,
    val errorClass: String
)

internal enum class OperatorOutputMode {
    PRETTY,
    COMPACT
}

internal data class OperatorRecommendation(
    val code: String,
    val severity: String,
    val errorClass: String,
    val message: String
)

private fun collectPluginLoadIssues(
    diagnostics: com.clockwork.runtime.RuntimeDiagnostics,
    pluginIdFilter: String?
): List<PluginLoadIssue> {
    val issues = mutableListOf<PluginLoadIssue>()
    fun addAll(source: String, data: Map<String, String>) {
        data.forEach { (pluginId, reason) ->
            issues += PluginLoadIssue(
                pluginId = pluginId,
                source = source,
                reason = reason,
                errorClass = classifyPluginLoadIssue(source = source, reason = reason)
            )
        }
    }
    addAll("lastScanRejected", diagnostics.lastScanRejected)
    addAll("lastScanVersionMismatch", diagnostics.lastScanVersionMismatches)
    addAll("lastScanApiCompatibility", diagnostics.lastScanApiCompatibility)
    addAll("currentDependencyIssue", diagnostics.currentDependencyIssues)
    addAll("currentVersionMismatch", diagnostics.versionMismatches)
    addAll("currentApiCompatibility", diagnostics.apiCompatibility)

    val normalizedFilter = pluginIdFilter?.trim()?.lowercase().orEmpty()
    return issues
        .asSequence()
        .filter { issue ->
            normalizedFilter.isEmpty() || issue.pluginId.equals(normalizedFilter, ignoreCase = true)
        }
        .distinctBy { "${it.source}|${it.pluginId}|${it.reason}" }
        .sortedWith(compareBy<PluginLoadIssue> { it.pluginId.lowercase() }.thenBy { it.source })
        .toList()
}

private fun classifyPluginLoadIssue(source: String, reason: String): String {
    val normalizedSource = source.lowercase()
    val normalizedReason = reason.lowercase()
    return when {
        "dependency" in normalizedSource || "dependency" in normalizedReason || "missing plugin" in normalizedReason -> "dependency"
        "version" in normalizedSource || "api" in normalizedSource || "incompatible" in normalizedReason -> "compatibility"
        "permission" in normalizedReason || "security" in normalizedReason -> "security"
        else -> "runtime"
    }
}

internal fun statusView(
    core: GigaStandaloneCore,
    netServer: StandaloneNetServer?,
    topSystems: List<Triple<String, Long, MetricSnapshot>>
): Map<String, Any?> {
    val s = core.status()
    val net = netServer?.metrics()
    val topNetActions = net?.actionMetrics
        ?.entries
        ?.sortedByDescending { it.value.totalNanos }
        ?.take(3)
        ?.map { (action, metric) ->
            mapOf(
                "action" to action,
                "count" to metric.count,
                "failures" to metric.failures,
                "averageNanos" to metric.averageNanos,
                "maxNanos" to metric.maxNanos
            )
        }
        ?: emptyList()
    val topCoreSystems = topSystems.map { (id, _, metric) ->
        mapOf(
            "id" to id,
            "runs" to metric.runs,
            "failures" to metric.failures,
            "averageNanos" to metric.averageNanos,
            "maxNanos" to metric.maxNanos
        )
    }
    return linkedMapOf<String, Any?>(
        "core" to linkedMapOf(
            "running" to s.running,
            "uptimeMillis" to s.uptimeMillis,
            "tickCount" to s.tickCount,
            "averageTickDurationNanos" to s.averageTickDurationNanos,
            "lastTickDurationNanos" to s.lastTickDurationNanos,
            "tickFailures" to s.tickFailures,
            "averageTickJitterNanos" to s.averageTickJitterNanos,
            "maxTickJitterNanos" to s.maxTickJitterNanos,
            "tickOverruns" to s.tickOverruns,
            "pluginBudgetExhaustions" to s.pluginBudgetExhaustions,
            "faultBudgetWarnTicks" to s.faultBudgetWarnTicks,
            "faultBudgetThrottleTicks" to s.faultBudgetThrottleTicks,
            "faultBudgetIsolateTicks" to s.faultBudgetIsolateTicks,
            "loadedPlugins" to s.loadedPlugins,
            "onlinePlayers" to s.onlinePlayers,
            "worlds" to s.worlds,
            "entities" to s.entities,
            "queuedMutations" to s.queuedMutations,
            "tickPhasesAverageNanos" to linkedMapOf(
                "queue" to s.averageQueueDrainNanos,
                "world" to s.averageWorldTickNanos,
                "events" to s.averageEventPublishNanos,
                "systems" to s.averageSystemsNanos
            )
        ),
        "net" to if (net == null) {
            null as Any?
        } else {
            linkedMapOf(
                "totalRequests" to net.totalRequests,
                "jsonRequests" to net.jsonRequests,
                "legacyRequests" to net.legacyRequests,
                "averageRequestNanos" to net.averageRequestNanos,
                "minecraftBridge" to linkedMapOf(
                    "handshakesDetected" to net.minecraftBridge.handshakesDetected,
                    "proxiedSessions" to net.minecraftBridge.proxiedSessions,
                    "activeProxiedSessions" to net.minecraftBridge.activeProxiedSessions,
                    "bytesClientToUpstream" to net.minecraftBridge.bytesClientToUpstream,
                    "bytesUpstreamToClient" to net.minecraftBridge.bytesUpstreamToClient,
                    "connectFailures" to net.minecraftBridge.connectFailures,
                    "averageProxySessionNanos" to net.minecraftBridge.averageProxySessionNanos,
                    "maxProxySessionNanos" to net.minecraftBridge.maxProxySessionNanos,
                    "nativeLoginAttempts" to net.minecraftBridge.nativeLoginAttempts,
                    "nativeLoginRejected" to net.minecraftBridge.nativeLoginRejected
                ),
                "topActions" to topNetActions
            )
        },
        "topCoreSystems" to topCoreSystems
    )
}

internal fun buildDoctorRecommendations(
    diagnostics: com.clockwork.runtime.RuntimeDiagnostics
): Map<String, List<OperatorRecommendation>> {
    val out = linkedMapOf<String, List<OperatorRecommendation>>()
    diagnostics.pluginPerformance.toSortedMap().forEach { (pluginId, perf) ->
        val rec = buildPluginPerformanceRecommendations(pluginId, perf)
        if (rec.isNotEmpty()) {
            out[pluginId] = rec
        }
    }
    return out
}

internal fun buildProfileRecommendations(
    profile: com.clockwork.runtime.PluginRuntimeProfile
): List<OperatorRecommendation> {
    val perf = com.clockwork.runtime.PluginPerformanceDiagnostics(
        adapterCounters = profile.adapterCounters,
        adapterAudit = profile.adapterAudit,
        slowSystems = profile.slowSystems,
        adapterHotspots = profile.adapterHotspots,
        isolatedSystems = profile.isolatedSystems,
        faultBudget = profile.faultBudget
    )
    return buildPluginPerformanceRecommendations(profile.pluginId, perf)
}

internal fun buildPluginPerformanceRecommendations(
    pluginId: String,
    perf: com.clockwork.runtime.PluginPerformanceDiagnostics
): List<OperatorRecommendation> {
    val out = mutableListOf<OperatorRecommendation>()
    if (perf.slowSystems.isNotEmpty()) {
        val worst = perf.slowSystems.first()
        out += OperatorRecommendation(
            code = "SYS_SLOW",
            severity = "warning",
            errorClass = "performance",
            message = "Optimize system '${worst.systemId}' (avgNs=${worst.averageNanos}, maxNs=${worst.maxNanos}); consider batching/cache reductions."
        )
    }
    if (perf.adapterHotspots.isNotEmpty()) {
        val worst = perf.adapterHotspots.first()
        out += OperatorRecommendation(
            code = "ADAPTER_HOTSPOT",
            severity = "warning",
            errorClass = "performance",
            message = "Review adapter '${worst.adapterId}' usage (denyRate=${"%.3f".format(worst.deniedRate)}, timeoutRate=${"%.3f".format(worst.timeoutRate)}); tighten payloads/capabilities or raise quotas intentionally."
        )
    }
    val isolated = perf.isolatedSystems.filter { it.isolated || it.isolationCount > 0L }
    if (isolated.isNotEmpty()) {
        val worst = isolated.first()
        out += OperatorRecommendation(
            code = "SYSTEM_ISOLATED",
            severity = "warning",
            errorClass = "stability",
            message = "System isolation detected for '${worst.systemId}' (isolations=${worst.isolationCount}); add failure guards and backoff-safe logic."
        )
    }
    if (perf.faultBudget.stage != com.clockwork.runtime.FaultBudgetStage.NORMAL || perf.faultBudget.remaining <= 10) {
        val code = when (perf.faultBudget.stage) {
            com.clockwork.runtime.FaultBudgetStage.WARN -> "FAULT_BUDGET_WARN"
            com.clockwork.runtime.FaultBudgetStage.THROTTLE -> "FAULT_BUDGET_THROTTLE"
            com.clockwork.runtime.FaultBudgetStage.ISOLATE -> "FAULT_BUDGET_ISOLATE"
            com.clockwork.runtime.FaultBudgetStage.NORMAL -> "FAULT_BUDGET_PRESSURE"
        }
        out += OperatorRecommendation(
            code = code,
            severity = if (perf.faultBudget.stage == com.clockwork.runtime.FaultBudgetStage.ISOLATE || perf.faultBudget.tripped) "critical" else "warning",
            errorClass = "stability",
            message = "Fault budget stage=${perf.faultBudget.stage} ratio=${"%.3f".format(perf.faultBudget.usageRatio)} (used=${perf.faultBudget.used}, remaining=${perf.faultBudget.remaining}); reduce repeated failures/timeouts in plugin '$pluginId'."
        )
    }
    return out
}

private fun parseOutputMode(parts: List<String>): OperatorOutputMode? {
    val pretty = parts.any { it.equals("--pretty", ignoreCase = true) }
    val compact = parts.any { it.equals("--compact", ignoreCase = true) }
    if (pretty && compact) return null
    return if (compact) OperatorOutputMode.COMPACT else OperatorOutputMode.PRETTY
}

private fun printJson(
    mapper: ObjectMapper,
    value: Any,
    mode: OperatorOutputMode
) {
    when (mode) {
        OperatorOutputMode.PRETTY -> println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value))
        OperatorOutputMode.COMPACT -> println(mapper.writeValueAsString(value))
    }
}
