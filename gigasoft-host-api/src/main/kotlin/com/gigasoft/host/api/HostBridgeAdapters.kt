package com.gigasoft.host.api

import com.gigasoft.api.AdapterInvocation
import com.gigasoft.api.AdapterResponse
import com.gigasoft.api.GigaLogger
import com.gigasoft.api.ModAdapter
import com.gigasoft.api.ModAdapterRegistry
import kotlin.math.roundToInt

object HostBridgeAdapters {
    fun registerDefaults(
        pluginId: String,
        registry: ModAdapterRegistry,
        hostBridge: HostBridgePort,
        logger: GigaLogger,
        bridgeName: String
    ) {
        registerIfMissing(registry, HostAdapterIds.SERVER) {
            ServerBridgeAdapter(HostAdapterIds.SERVER, hostBridge, bridgeName)
        }
        registerIfMissing(registry, HostAdapterIds.PLAYER) {
            PlayerBridgeAdapter(HostAdapterIds.PLAYER, hostBridge, bridgeName)
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
        private val bridgeName: String
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
            return when (invocation.action) {
                "server.info" -> {
                    val info = hostBridge.serverInfo()
                    AdapterResponse(
                        success = true,
                        payload = mapOf(
                            "name" to info.name,
                            "version" to info.version,
                            "bukkitVersion" to (info.bukkitVersion ?: ""),
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
        private val bridgeName: String
    ) : ModAdapter {
        override val id: String = adapterId
        override val name: String = "$bridgeName Player Bridge"
        override val version: String = "1.0.0"
        override val capabilities: Set<String> = setOf("player.lookup")

        override fun invoke(invocation: AdapterInvocation): AdapterResponse {
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
}
