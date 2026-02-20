package com.gigasoft.bridge.paper

import com.gigasoft.api.AdapterInvocation
import com.gigasoft.api.AdapterResponse
import com.gigasoft.api.GigaLogger
import com.gigasoft.api.ModAdapter
import com.gigasoft.runtime.LoadedPlugin
import org.bukkit.Location
import org.bukkit.Server
import org.bukkit.World
import org.bukkit.entity.Entity
import org.bukkit.inventory.Inventory
import kotlin.math.roundToInt

// Minimal bridge DTOs for V1 so DSL-facing code can remain decoupled from Paper internals.
data class WorldRef(val name: String)
data class EntityRef(val uuid: String, val type: String)
data class InventoryRef(val size: Int)

object PaperBridgeAdapters {
    private const val SERVER_ADAPTER_ID = "bridge.paper.server"
    private const val PLAYER_ADAPTER_ID = "bridge.paper.player"

    fun world(world: World): WorldRef = WorldRef(world.name)
    fun entity(entity: Entity): EntityRef = EntityRef(entity.uniqueId.toString(), entity.type.name)
    fun inventory(inventory: Inventory): InventoryRef = InventoryRef(inventory.size)
    fun location(location: Location): Map<String, Double> = mapOf(
        "x" to location.x,
        "y" to location.y,
        "z" to location.z
    )

    fun registerDefaults(plugin: LoadedPlugin, server: Server, logger: GigaLogger) {
        val registry = plugin.context.adapters
        if (registry.find(SERVER_ADAPTER_ID) == null) {
            registry.register(ServerBridgeAdapter(server))
        }
        if (registry.find(PLAYER_ADAPTER_ID) == null) {
            registry.register(PlayerBridgeAdapter(server))
        }
        logger.info("Installed bridge adapters for ${plugin.manifest.id}")
    }

    private class ServerBridgeAdapter(
        private val server: Server
    ) : ModAdapter {
        override val id: String = SERVER_ADAPTER_ID
        override val name: String = "Paper Server Bridge"
        override val version: String = "1.0.0"
        override val capabilities: Set<String> = setOf("server.info", "server.broadcast")

        override fun invoke(invocation: AdapterInvocation): AdapterResponse {
            return when (invocation.action) {
                "server.info" -> AdapterResponse(
                    success = true,
                    payload = mapOf(
                        "name" to server.name,
                        "version" to server.version,
                        "bukkitVersion" to server.bukkitVersion,
                        "onlinePlayers" to server.onlinePlayers.size.toString(),
                        "maxPlayers" to server.maxPlayers.toString(),
                        "worldCount" to server.worlds.size.toString()
                    )
                )

                "server.broadcast" -> {
                    val message = invocation.payload["message"]?.takeIf { it.isNotBlank() }
                        ?: return AdapterResponse(success = false, message = "Missing payload key 'message'")
                    server.onlinePlayers.forEach { player -> player.sendMessage(message) }
                    server.consoleSender.sendMessage(message)
                    AdapterResponse(success = true, message = "Broadcast sent")
                }

                else -> AdapterResponse(success = false, message = "Unsupported action '${invocation.action}'")
            }
        }
    }

    private class PlayerBridgeAdapter(
        private val server: Server
    ) : ModAdapter {
        override val id: String = PLAYER_ADAPTER_ID
        override val name: String = "Paper Player Bridge"
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
            val player = server.getPlayerExact(playerName)
                ?: return AdapterResponse(success = false, message = "Player '$playerName' not online")
            val loc = player.location
            val worldName = loc.world?.name ?: "unknown"
            return AdapterResponse(
                success = true,
                payload = mapOf(
                    "uuid" to player.uniqueId.toString(),
                    "name" to player.name,
                    "world" to worldName,
                    "x" to loc.x.roundToInt().toString(),
                    "y" to loc.y.roundToInt().toString(),
                    "z" to loc.z.roundToInt().toString()
                )
            )
        }
    }
}
