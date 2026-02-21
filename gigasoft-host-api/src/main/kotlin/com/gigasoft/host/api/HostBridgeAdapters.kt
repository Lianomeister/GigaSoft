package com.gigasoft.host.api

import com.gigasoft.api.AdapterInvocation
import com.gigasoft.api.AdapterResponse
import com.gigasoft.api.GigaLogger
import com.gigasoft.api.HostPermissions
import com.gigasoft.api.ModAdapter
import com.gigasoft.api.ModAdapterRegistry
import kotlin.math.roundToInt

object HostBridgeAdapters {
    object Permission {
        const val HOST_SERVER_READ = HostPermissions.SERVER_READ
        const val HOST_BROADCAST = HostPermissions.SERVER_BROADCAST
        const val HOST_WORLD_READ = HostPermissions.WORLD_READ
        const val HOST_WORLD_WRITE = HostPermissions.WORLD_WRITE
        const val HOST_WORLD_DATA_READ = HostPermissions.WORLD_DATA_READ
        const val HOST_WORLD_DATA_WRITE = HostPermissions.WORLD_DATA_WRITE
        const val HOST_WORLD_WEATHER_READ = HostPermissions.WORLD_WEATHER_READ
        const val HOST_WORLD_WEATHER_WRITE = HostPermissions.WORLD_WEATHER_WRITE
        const val HOST_ENTITY_READ = HostPermissions.ENTITY_READ
        const val HOST_ENTITY_SPAWN = HostPermissions.ENTITY_SPAWN
        const val HOST_ENTITY_REMOVE = HostPermissions.ENTITY_REMOVE
        const val HOST_ENTITY_DATA_READ = HostPermissions.ENTITY_DATA_READ
        const val HOST_ENTITY_DATA_WRITE = HostPermissions.ENTITY_DATA_WRITE
        const val HOST_INVENTORY_READ = HostPermissions.INVENTORY_READ
        const val HOST_INVENTORY_WRITE = HostPermissions.INVENTORY_WRITE
        const val HOST_PLAYER_READ = HostPermissions.PLAYER_READ
        const val HOST_PLAYER_MOVE = HostPermissions.PLAYER_MOVE
        const val HOST_BLOCK_READ = HostPermissions.BLOCK_READ
        const val HOST_BLOCK_WRITE = HostPermissions.BLOCK_WRITE
        const val HOST_BLOCK_DATA_READ = HostPermissions.BLOCK_DATA_READ
        const val HOST_BLOCK_DATA_WRITE = HostPermissions.BLOCK_DATA_WRITE
    }

    fun registerDefaults(
        pluginId: String,
        registry: ModAdapterRegistry,
        hostBridge: HostBridgePort,
        logger: GigaLogger,
        bridgeName: String,
        grantedPermissions: Set<String> = emptySet()
    ) {
        registerIfMissing(registry, HostAdapterIds.SERVER) {
            ServerBridgeAdapter(
                adapterId = HostAdapterIds.SERVER,
                hostBridge = hostBridge,
                bridgeName = bridgeName,
                pluginId = pluginId,
                grantedPermissions = grantedPermissions
            )
        }
        registerIfMissing(registry, HostAdapterIds.PLAYER) {
            PlayerBridgeAdapter(
                adapterId = HostAdapterIds.PLAYER,
                hostBridge = hostBridge,
                bridgeName = bridgeName,
                pluginId = pluginId,
                grantedPermissions = grantedPermissions
            )
        }
        logger.info("Installed $bridgeName bridge adapters for $pluginId")
    }

    private fun registerIfMissing(registry: ModAdapterRegistry, id: String, adapterFactory: () -> ModAdapter) {
        if (registry.find(id) == null) {
            registry.register(adapterFactory())
        }
    }

    private class ServerBridgeAdapter(
        private val adapterId: String,
        private val hostBridge: HostBridgePort,
        private val bridgeName: String,
        private val pluginId: String,
        private val grantedPermissions: Set<String>
    ) : ModAdapter {
        override val id: String = adapterId
        override val name: String = "$bridgeName Server Bridge"
        override val version: String = "1.0.0"
        override val capabilities: Set<String> = setOf(
            "server.info",
            "server.broadcast",
            "world.list",
            "entity.list",
            "entity.spawn",
            "inventory.peek",
            "inventory.set"
        )

        override fun invoke(invocation: AdapterInvocation): AdapterResponse {
            val permissionDenied = requirePermission(
                pluginId = pluginId,
                grantedPermissions = grantedPermissions,
                required = requiredPermissionForServerAction(invocation.action)
            )
            if (permissionDenied != null) return permissionDenied
            return when (invocation.action) {
                "server.info" -> {
                    val info = hostBridge.serverInfo()
                    AdapterResponse(
                        success = true,
                        payload = mapOf(
                            "name" to info.name,
                            "version" to info.version,
                            "platformVersion" to (info.platformVersion ?: ""),
                            "onlinePlayers" to info.onlinePlayers.toString(),
                            "maxPlayers" to info.maxPlayers.toString(),
                            "worldCount" to info.worldCount.toString()
                        )
                    )
                }

                "server.broadcast" -> {
                    val message = invocation.payload["message"]?.takeIf { it.isNotBlank() }
                        ?: return AdapterResponse(success = false, message = "Missing payload key 'message'")
                    hostBridge.broadcast(message)
                    AdapterResponse(success = true, message = "Broadcast sent")
                }

                "world.list" -> {
                    val worlds = hostBridge.worlds()
                    AdapterResponse(
                        success = true,
                        payload = mapOf(
                            "count" to worlds.size.toString(),
                            "worlds" to worlds.joinToString(",") { it.name }
                        )
                    )
                }

                "entity.list" -> {
                    val world = invocation.payload["world"]?.trim()?.ifBlank { null }
                    val entities = hostBridge.entities(world)
                    AdapterResponse(
                        success = true,
                        payload = mapOf(
                            "count" to entities.size.toString(),
                            "entities" to entities.joinToString(",") { "${it.type}:${it.uuid}" }
                        )
                    )
                }

                "entity.spawn" -> {
                    val type = invocation.payload["type"]?.trim().orEmpty()
                    val world = invocation.payload["world"]?.trim().orEmpty()
                    if (type.isEmpty() || world.isEmpty()) {
                        return AdapterResponse(success = false, message = "Missing payload keys 'type' or 'world'")
                    }
                    val x = invocation.payload["x"]?.toDoubleOrNull() ?: 0.0
                    val y = invocation.payload["y"]?.toDoubleOrNull() ?: 64.0
                    val z = invocation.payload["z"]?.toDoubleOrNull() ?: 0.0
                    val created = hostBridge.spawnEntity(
                        type = type,
                        location = HostLocationRef(world = world, x = x, y = y, z = z)
                    ) ?: return AdapterResponse(success = false, message = "Failed to spawn entity")
                    AdapterResponse(
                        success = true,
                        payload = mapOf(
                            "uuid" to created.uuid,
                            "type" to created.type,
                            "world" to created.location.world,
                            "x" to created.location.x.roundToInt().toString(),
                            "y" to created.location.y.roundToInt().toString(),
                            "z" to created.location.z.roundToInt().toString()
                        )
                    )
                }

                "inventory.peek" -> {
                    val playerName = invocation.payload["name"]?.trim().orEmpty()
                    if (playerName.isEmpty()) {
                        return AdapterResponse(success = false, message = "Missing payload key 'name'")
                    }
                    val inventory = hostBridge.playerInventory(playerName)
                        ?: return AdapterResponse(success = false, message = "Inventory not found for '$playerName'")
                    AdapterResponse(
                        success = true,
                        payload = mapOf(
                            "owner" to inventory.owner,
                            "size" to inventory.size.toString(),
                            "nonEmptySlots" to inventory.nonEmptySlots.toString()
                        )
                    )
                }

                "inventory.set" -> {
                    val playerName = invocation.payload["name"]?.trim().orEmpty()
                    val slot = invocation.payload["slot"]?.toIntOrNull()
                    val item = invocation.payload["item"]?.trim().orEmpty()
                    if (playerName.isEmpty() || slot == null || item.isEmpty()) {
                        return AdapterResponse(success = false, message = "Missing payload keys 'name', 'slot', or 'item'")
                    }
                    val ok = hostBridge.setPlayerInventoryItem(playerName, slot, item)
                    AdapterResponse(
                        success = ok,
                        message = if (ok) "Inventory updated" else "Failed to update inventory"
                    )
                }

                else -> AdapterResponse(success = false, message = "Unsupported action '${invocation.action}'")
            }
        }
    }

    private class PlayerBridgeAdapter(
        private val adapterId: String,
        private val hostBridge: HostBridgePort,
        private val bridgeName: String,
        private val pluginId: String,
        private val grantedPermissions: Set<String>
    ) : ModAdapter {
        override val id: String = adapterId
        override val name: String = "$bridgeName Player Bridge"
        override val version: String = "1.0.0"
        override val capabilities: Set<String> = setOf("player.lookup")

        override fun invoke(invocation: AdapterInvocation): AdapterResponse {
            val permissionDenied = requirePermission(
                pluginId = pluginId,
                grantedPermissions = grantedPermissions,
                required = requiredPermissionForPlayerAction(invocation.action)
            )
            if (permissionDenied != null) return permissionDenied
            if (invocation.action != "player.lookup") {
                return AdapterResponse(success = false, message = "Unsupported action '${invocation.action}'")
            }
            val playerName = invocation.payload["name"]?.trim().orEmpty()
            if (playerName.isEmpty()) {
                return AdapterResponse(success = false, message = "Missing payload key 'name'")
            }
            val player = hostBridge.findPlayer(playerName)
                ?: return AdapterResponse(success = false, message = "Player '$playerName' not online")
            val loc = player.location
            return AdapterResponse(
                success = true,
                payload = mapOf(
                    "uuid" to player.uuid,
                    "name" to player.name,
                    "world" to loc.world,
                    "x" to loc.x.roundToInt().toString(),
                    "y" to loc.y.roundToInt().toString(),
                    "z" to loc.z.roundToInt().toString()
                )
            )
        }
    }

    private fun requiredPermissionForServerAction(action: String): String? {
        return when (action) {
            "server.info" -> Permission.HOST_SERVER_READ
            "server.broadcast" -> Permission.HOST_BROADCAST
            "world.list" -> Permission.HOST_WORLD_READ
            "entity.list" -> Permission.HOST_ENTITY_READ
            "entity.spawn" -> Permission.HOST_ENTITY_SPAWN
            "inventory.peek" -> Permission.HOST_INVENTORY_READ
            "inventory.set" -> Permission.HOST_INVENTORY_WRITE
            else -> null
        }
    }

    private fun requiredPermissionForPlayerAction(action: String): String? {
        return when (action) {
            "player.lookup" -> Permission.HOST_PLAYER_READ
            else -> null
        }
    }

    private fun requirePermission(
        pluginId: String,
        grantedPermissions: Set<String>,
        required: String?
    ): AdapterResponse? {
        if (required == null || required in grantedPermissions) return null
        return AdapterResponse(
            success = false,
            message = "Permission '$required' is required for plugin '$pluginId'"
        )
    }
}
