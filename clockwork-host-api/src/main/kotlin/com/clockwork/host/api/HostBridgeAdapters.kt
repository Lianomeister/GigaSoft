package com.clockwork.host.api

import com.clockwork.api.AdapterInvocation
import com.clockwork.api.AdapterResponse
import com.clockwork.api.GigaLogger
import com.clockwork.api.HostPermissions
import com.clockwork.api.ModAdapter
import com.clockwork.api.ModAdapterRegistry
import kotlin.math.roundToInt

object HostBridgeAdapters {
    object Permission {
        const val HOST_SERVER_READ = HostPermissions.SERVER_READ
        const val HOST_BROADCAST = HostPermissions.SERVER_BROADCAST
        const val HOST_WORLD_READ = HostPermissions.WORLD_READ
        const val HOST_WORLD_WRITE = HostPermissions.WORLD_WRITE
        const val HOST_ENTITY_READ = HostPermissions.ENTITY_READ
        const val HOST_ENTITY_SPAWN = HostPermissions.ENTITY_SPAWN
        const val HOST_ENTITY_REMOVE = HostPermissions.ENTITY_REMOVE
        const val HOST_INVENTORY_READ = HostPermissions.INVENTORY_READ
        const val HOST_INVENTORY_WRITE = HostPermissions.INVENTORY_WRITE
        const val HOST_PLAYER_READ = HostPermissions.PLAYER_READ
        const val HOST_PLAYER_MESSAGE = HostPermissions.PLAYER_MESSAGE
        const val HOST_PLAYER_MOVE = HostPermissions.PLAYER_MOVE
        const val HOST_BLOCK_READ = HostPermissions.BLOCK_READ
        const val HOST_BLOCK_WRITE = HostPermissions.BLOCK_WRITE
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
            SimpleBridgeAdapter(
                adapterId = HostAdapterIds.SERVER,
                bridgeName = bridgeName,
                pluginId = pluginId,
                grantedPermissions = grantedPermissions,
                hostBridge = hostBridge
            )
        }
        registerIfMissing(registry, HostAdapterIds.PLAYER) {
            PlayerBridgeAdapter(HostAdapterIds.PLAYER, bridgeName, pluginId, grantedPermissions, hostBridge)
        }
        registerIfMissing(registry, HostAdapterIds.WORLD) {
            WorldBridgeAdapter(HostAdapterIds.WORLD, bridgeName, pluginId, grantedPermissions, hostBridge)
        }
        registerIfMissing(registry, HostAdapterIds.ENTITY) {
            EntityBridgeAdapter(HostAdapterIds.ENTITY, bridgeName, pluginId, grantedPermissions, hostBridge)
        }
        registerIfMissing(registry, HostAdapterIds.INVENTORY) {
            InventoryBridgeAdapter(HostAdapterIds.INVENTORY, bridgeName, pluginId, grantedPermissions, hostBridge)
        }
        logger.info("Installed $bridgeName bridge adapters for $pluginId")
    }

    private fun registerIfMissing(registry: ModAdapterRegistry, id: String, adapterFactory: () -> ModAdapter) {
        if (registry.find(id) == null) registry.register(adapterFactory())
    }

    private class SimpleBridgeAdapter(
        adapterId: String,
        bridgeName: String,
        private val pluginId: String,
        private val grantedPermissions: Set<String>,
        private val hostBridge: HostBridgePort
    ) : ModAdapter {
        override val id: String = adapterId
        override val name: String = "$bridgeName Server Bridge"
        override val version: String = "1.1.0"
        override val capabilities: Set<String> = setOf("server.info", "server.broadcast")
        override fun invoke(invocation: AdapterInvocation): AdapterResponse {
            val required = when (invocation.action) {
                "server.info" -> Permission.HOST_SERVER_READ
                "server.broadcast" -> Permission.HOST_BROADCAST
                else -> null
            }
            val denied = requirePermission(pluginId, grantedPermissions, required)
            if (denied != null) return denied
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
                    val msg = invocation.payload["message"]?.trim().orEmpty()
                    if (msg.isEmpty()) AdapterResponse(success = false, message = "Missing payload key 'message'")
                    else {
                        hostBridge.broadcast(msg)
                        AdapterResponse(success = true, message = "Broadcast sent")
                    }
                }
                else -> AdapterResponse(success = false, message = "Unsupported action '${invocation.action}'")
            }
        }
    }

    private class PlayerBridgeAdapter(
        adapterId: String,
        bridgeName: String,
        private val pluginId: String,
        private val grantedPermissions: Set<String>,
        private val hostBridge: HostBridgePort
    ) : ModAdapter {
        override val id: String = adapterId
        override val name: String = "$bridgeName Player Bridge"
        override val version: String = "1.1.0"
        override val capabilities: Set<String> = setOf("player.lookup", "player.message", "player.move")
        override fun invoke(invocation: AdapterInvocation): AdapterResponse {
            val required = when (invocation.action) {
                "player.lookup" -> Permission.HOST_PLAYER_READ
                "player.message" -> Permission.HOST_PLAYER_MESSAGE
                "player.move" -> Permission.HOST_PLAYER_MOVE
                else -> null
            }
            val denied = requirePermission(pluginId, grantedPermissions, required)
            if (denied != null) return denied
            return when (invocation.action) {
                "player.lookup" -> {
                    val name = requiredString(invocation, "name") ?: return missingPayload("name")
                    val p = hostBridge.findPlayer(name) ?: return AdapterResponse(success = false, message = "Player '$name' not online")
                    AdapterResponse(
                        success = true,
                        payload = mapOf(
                            "uuid" to p.uuid,
                            "name" to p.name,
                            "world" to p.location.world,
                            "x" to p.location.x.roundToInt().toString(),
                            "y" to p.location.y.roundToInt().toString(),
                            "z" to p.location.z.roundToInt().toString()
                        )
                    )
                }
                "player.message" -> {
                    val name = requiredString(invocation, "name") ?: return missingPayload("name")
                    val message = requiredString(invocation, "message") ?: return missingPayload("message")
                    val ok = hostBridge.sendPlayerMessage(name, message)
                    AdapterResponse(success = ok, message = if (ok) "Message delivered" else "Player not found")
                }
                "player.move" -> {
                    val name = requiredString(invocation, "name") ?: return missingPayload("name")
                    val location = parseLocation(invocation) ?: return missingPayload("world/x/y/z")
                    val moved = hostBridge.movePlayer(name, location) ?: return AdapterResponse(success = false, message = "Player move failed")
                    AdapterResponse(success = true, payload = mapOf("uuid" to moved.uuid, "name" to moved.name))
                }
                else -> AdapterResponse(success = false, message = "Unsupported action '${invocation.action}'")
            }
        }
    }

    private class WorldBridgeAdapter(
        adapterId: String,
        bridgeName: String,
        private val pluginId: String,
        private val grantedPermissions: Set<String>,
        private val hostBridge: HostBridgePort
    ) : ModAdapter {
        override val id: String = adapterId
        override val name: String = "$bridgeName World Bridge"
        override val version: String = "1.0.0"
        override val capabilities: Set<String> = setOf("world.list", "world.time.get", "world.time.set", "block.get", "block.set")
        override fun invoke(invocation: AdapterInvocation): AdapterResponse {
            val required = when (invocation.action) {
                "world.list", "world.time.get" -> Permission.HOST_WORLD_READ
                "world.time.set" -> Permission.HOST_WORLD_WRITE
                "block.get" -> Permission.HOST_BLOCK_READ
                "block.set" -> Permission.HOST_BLOCK_WRITE
                else -> null
            }
            val denied = requirePermission(pluginId, grantedPermissions, required)
            if (denied != null) return denied
            return when (invocation.action) {
                "world.list" -> {
                    val worlds = hostBridge.worlds()
                    AdapterResponse(success = true, payload = mapOf("count" to worlds.size.toString(), "worlds" to worlds.joinToString(",") { it.name }))
                }
                "world.time.get" -> {
                    val name = requiredString(invocation, "name") ?: return missingPayload("name")
                    val time = hostBridge.worldTime(name) ?: return AdapterResponse(success = false, message = "World not found")
                    AdapterResponse(success = true, payload = mapOf("name" to name, "time" to time.toString()))
                }
                "world.time.set" -> {
                    val name = requiredString(invocation, "name") ?: return missingPayload("name")
                    val time = invocation.payload["time"]?.toLongOrNull() ?: return AdapterResponse(success = false, message = "Missing/invalid payload key 'time'")
                    val ok = hostBridge.setWorldTime(name, time)
                    AdapterResponse(success = ok, message = if (ok) "World time updated" else "World not found")
                }
                "block.get" -> {
                    val b = parseBlock(invocation) ?: return missingPayload("world/x/y/z")
                    val block = hostBridge.blockAt(b.world, b.x, b.y, b.z) ?: return AdapterResponse(success = false, message = "Block not found")
                    AdapterResponse(success = true, payload = mapOf("world" to block.world, "x" to block.x.toString(), "y" to block.y.toString(), "z" to block.z.toString(), "blockId" to block.blockId))
                }
                "block.set" -> {
                    val b = parseBlock(invocation) ?: return missingPayload("world/x/y/z")
                    val blockId = requiredString(invocation, "blockId") ?: return missingPayload("blockId")
                    val block = hostBridge.setBlock(b.world, b.x, b.y, b.z, blockId) ?: return AdapterResponse(success = false, message = "Block update failed")
                    AdapterResponse(success = true, payload = mapOf("world" to block.world, "x" to block.x.toString(), "y" to block.y.toString(), "z" to block.z.toString(), "blockId" to block.blockId))
                }
                else -> AdapterResponse(success = false, message = "Unsupported action '${invocation.action}'")
            }
        }
    }

    private class EntityBridgeAdapter(
        adapterId: String,
        bridgeName: String,
        private val pluginId: String,
        private val grantedPermissions: Set<String>,
        private val hostBridge: HostBridgePort
    ) : ModAdapter {
        override val id: String = adapterId
        override val name: String = "$bridgeName Entity Bridge"
        override val version: String = "1.0.0"
        override val capabilities: Set<String> = setOf("entity.list", "entity.spawn", "entity.remove")
        override fun invoke(invocation: AdapterInvocation): AdapterResponse {
            val required = when (invocation.action) {
                "entity.list" -> Permission.HOST_ENTITY_READ
                "entity.spawn" -> Permission.HOST_ENTITY_SPAWN
                "entity.remove" -> Permission.HOST_ENTITY_REMOVE
                else -> null
            }
            val denied = requirePermission(pluginId, grantedPermissions, required)
            if (denied != null) return denied
            return when (invocation.action) {
                "entity.list" -> {
                    val world = invocation.payload["world"]?.trim()?.ifBlank { null }
                    val entities = hostBridge.entities(world)
                    AdapterResponse(success = true, payload = mapOf("count" to entities.size.toString(), "entities" to entities.joinToString(",") { "${it.type}:${it.uuid}" }))
                }
                "entity.spawn" -> {
                    val type = requiredString(invocation, "type") ?: return missingPayload("type")
                    val location = parseLocation(invocation) ?: return missingPayload("world/x/y/z")
                    val entity = hostBridge.spawnEntity(type, location) ?: return AdapterResponse(success = false, message = "Failed to spawn entity")
                    AdapterResponse(success = true, payload = mapOf("uuid" to entity.uuid, "type" to entity.type))
                }
                "entity.remove" -> {
                    val uuid = requiredString(invocation, "uuid") ?: return missingPayload("uuid")
                    val ok = hostBridge.removeEntity(uuid)
                    AdapterResponse(success = ok, message = if (ok) "Entity removed" else "Entity not found")
                }
                else -> AdapterResponse(success = false, message = "Unsupported action '${invocation.action}'")
            }
        }
    }

    private class InventoryBridgeAdapter(
        adapterId: String,
        bridgeName: String,
        private val pluginId: String,
        private val grantedPermissions: Set<String>,
        private val hostBridge: HostBridgePort
    ) : ModAdapter {
        override val id: String = adapterId
        override val name: String = "$bridgeName Inventory Bridge"
        override val version: String = "1.0.0"
        override val capabilities: Set<String> = setOf("inventory.peek", "inventory.item", "inventory.set")
        override fun invoke(invocation: AdapterInvocation): AdapterResponse {
            val required = when (invocation.action) {
                "inventory.peek", "inventory.item" -> Permission.HOST_INVENTORY_READ
                "inventory.set" -> Permission.HOST_INVENTORY_WRITE
                else -> null
            }
            val denied = requirePermission(pluginId, grantedPermissions, required)
            if (denied != null) return denied
            return when (invocation.action) {
                "inventory.peek" -> {
                    val name = requiredString(invocation, "name") ?: return missingPayload("name")
                    val inv = hostBridge.playerInventory(name) ?: return AdapterResponse(success = false, message = "Inventory not found for '$name'")
                    AdapterResponse(success = true, payload = mapOf("owner" to inv.owner, "size" to inv.size.toString(), "nonEmptySlots" to inv.nonEmptySlots.toString()))
                }
                "inventory.item" -> {
                    val name = requiredString(invocation, "name") ?: return missingPayload("name")
                    val slot = invocation.payload["slot"]?.toIntOrNull() ?: return AdapterResponse(success = false, message = "Missing/invalid payload key 'slot'")
                    val item = hostBridge.inventoryItem(name, slot) ?: return AdapterResponse(success = false, message = "Slot empty or player not found")
                    AdapterResponse(success = true, payload = mapOf("name" to name, "slot" to slot.toString(), "item" to item))
                }
                "inventory.set" -> {
                    val name = requiredString(invocation, "name") ?: return missingPayload("name")
                    val slot = invocation.payload["slot"]?.toIntOrNull() ?: return AdapterResponse(success = false, message = "Missing/invalid payload key 'slot'")
                    val item = requiredString(invocation, "item") ?: return missingPayload("item")
                    val ok = hostBridge.setPlayerInventoryItem(name, slot, item)
                    AdapterResponse(success = ok, message = if (ok) "Inventory updated" else "Failed to update inventory")
                }
                else -> AdapterResponse(success = false, message = "Unsupported action '${invocation.action}'")
            }
        }
    }

    private fun requirePermission(pluginId: String, grantedPermissions: Set<String>, required: String?): AdapterResponse? {
        if (required == null || required in grantedPermissions) return null
        return AdapterResponse(success = false, message = "Permission '$required' is required for plugin '$pluginId'")
    }

    private fun requiredString(invocation: AdapterInvocation, key: String): String? {
        return invocation.payload[key]?.trim()?.ifBlank { null }
    }

    private fun missingPayload(key: String): AdapterResponse {
        return AdapterResponse(success = false, message = "Missing payload key '$key'")
    }

    private fun parseLocation(invocation: AdapterInvocation): HostLocationRef? {
        val world = requiredString(invocation, "world") ?: return null
        val x = invocation.payload["x"]?.toDoubleOrNull() ?: return null
        val y = invocation.payload["y"]?.toDoubleOrNull() ?: return null
        val z = invocation.payload["z"]?.toDoubleOrNull() ?: return null
        return HostLocationRef(world = world, x = x, y = y, z = z)
    }

    private data class BlockRef(val world: String, val x: Int, val y: Int, val z: Int)

    private fun parseBlock(invocation: AdapterInvocation): BlockRef? {
        val world = requiredString(invocation, "world") ?: return null
        val x = invocation.payload["x"]?.toIntOrNull() ?: return null
        val y = invocation.payload["y"]?.toIntOrNull() ?: return null
        val z = invocation.payload["z"]?.toIntOrNull() ?: return null
        return BlockRef(world, x, y, z)
    }
}
