package com.gigasoft.bridge.paper

import com.gigasoft.host.api.HostBridgePort
import com.gigasoft.host.api.HostEntitySnapshot
import com.gigasoft.host.api.HostInventorySnapshot
import com.gigasoft.host.api.HostLocationRef
import com.gigasoft.host.api.HostPlayerSnapshot
import com.gigasoft.host.api.HostServerSnapshot
import com.gigasoft.host.api.HostWorldSnapshot
import org.bukkit.Server
import org.bukkit.entity.EntityType
import org.bukkit.Material
import org.bukkit.inventory.ItemStack

class PaperHostBridge(
    private val server: Server
) : HostBridgePort {
    override fun serverInfo(): HostServerSnapshot {
        return HostServerSnapshot(
            name = server.name,
            version = server.version,
            bukkitVersion = server.bukkitVersion,
            onlinePlayers = server.onlinePlayers.size,
            maxPlayers = server.maxPlayers,
            worldCount = server.worlds.size
        )
    }

    override fun broadcast(message: String) {
        server.onlinePlayers.forEach { player -> player.sendMessage(message) }
        server.consoleSender.sendMessage(message)
    }

    override fun findPlayer(name: String): HostPlayerSnapshot? {
        val player = server.getPlayerExact(name) ?: return null
        val location = player.location
        return HostPlayerSnapshot(
            uuid = player.uniqueId.toString(),
            name = player.name,
            location = HostLocationRef(
                world = location.world?.name ?: "unknown",
                x = location.x,
                y = location.y,
                z = location.z
            )
        )
    }

    override fun worlds(): List<HostWorldSnapshot> {
        return server.worlds.map { world ->
            HostWorldSnapshot(
                name = world.name,
                entityCount = world.entities.size
            )
        }
    }

    override fun entities(world: String?): List<HostEntitySnapshot> {
        val worlds = if (world.isNullOrBlank()) server.worlds else server.worlds.filter { it.name.equals(world, ignoreCase = true) }
        return worlds.flatMap { w ->
            w.entities.map { entity ->
                val loc = entity.location
                HostEntitySnapshot(
                    uuid = entity.uniqueId.toString(),
                    type = entity.type.name,
                    location = HostLocationRef(
                        world = w.name,
                        x = loc.x,
                        y = loc.y,
                        z = loc.z
                    )
                )
            }
        }
    }

    override fun spawnEntity(type: String, location: HostLocationRef): HostEntitySnapshot? {
        val world = server.worlds.firstOrNull { it.name.equals(location.world, ignoreCase = true) } ?: return null
        val entityType = runCatching { EntityType.valueOf(type.uppercase()) }.getOrNull() ?: return null
        val spawned = world.spawnEntity(
            org.bukkit.Location(world, location.x, location.y, location.z),
            entityType
        )
        val loc = spawned.location
        return HostEntitySnapshot(
            uuid = spawned.uniqueId.toString(),
            type = spawned.type.name,
            location = HostLocationRef(
                world = world.name,
                x = loc.x,
                y = loc.y,
                z = loc.z
            )
        )
    }

    override fun playerInventory(name: String): HostInventorySnapshot? {
        val player = server.getPlayerExact(name) ?: return null
        val inv = player.inventory
        val nonEmpty = inv.contents.count { it != null && it.type != Material.AIR && it.amount > 0 }
        return HostInventorySnapshot(
            owner = player.name,
            size = inv.size,
            nonEmptySlots = nonEmpty
        )
    }

    override fun setPlayerInventoryItem(name: String, slot: Int, itemId: String): Boolean {
        val player = server.getPlayerExact(name) ?: return false
        val inv = player.inventory
        if (slot !in 0 until inv.size) return false
        val material = Material.matchMaterial(itemId) ?: return false
        inv.setItem(slot, ItemStack(material, 1))
        return true
    }
}
