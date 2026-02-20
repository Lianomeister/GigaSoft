package com.gigasoft.core

import com.gigasoft.api.GigaLogger
import com.gigasoft.host.api.HostBridgePort
import com.gigasoft.host.api.HostEntitySnapshot
import com.gigasoft.host.api.HostInventorySnapshot
import com.gigasoft.host.api.HostLocationRef
import com.gigasoft.host.api.HostServerSnapshot
import com.gigasoft.host.api.HostPlayerSnapshot
import com.gigasoft.host.api.HostWorldSnapshot

class StandaloneHostBridge(
    private val serverName: String,
    private val serverVersion: String,
    private val maxPlayers: Int,
    private val logger: GigaLogger,
    private val hostState: StandaloneHostState
) : HostBridgePort {
    override fun serverInfo(): HostServerSnapshot {
        return HostServerSnapshot(
            name = serverName,
            version = serverVersion,
            bukkitVersion = null,
            onlinePlayers = hostState.onlinePlayerCount(),
            maxPlayers = maxPlayers,
            worldCount = hostState.worldCount()
        )
    }

    override fun broadcast(message: String) {
        logger.info("[broadcast] $message")
    }

    override fun findPlayer(name: String): HostPlayerSnapshot? {
        val player = hostState.findPlayer(name) ?: return null
        return HostPlayerSnapshot(
            uuid = player.uuid,
            name = player.name,
            location = HostLocationRef(
                world = player.world,
                x = player.x,
                y = player.y,
                z = player.z
            )
        )
    }

    override fun worlds(): List<HostWorldSnapshot> {
        return hostState.worlds().map { world ->
            HostWorldSnapshot(
                name = world.name,
                entityCount = hostState.entityCount(world.name)
            )
        }
    }

    override fun entities(world: String?): List<HostEntitySnapshot> {
        return hostState.entities(world).map { entity ->
            HostEntitySnapshot(
                uuid = entity.uuid,
                type = entity.type,
                location = HostLocationRef(
                    world = entity.world,
                    x = entity.x,
                    y = entity.y,
                    z = entity.z
                )
            )
        }
    }

    override fun spawnEntity(type: String, location: HostLocationRef): HostEntitySnapshot? {
        val entity = hostState.spawnEntity(
            type = type,
            world = location.world,
            x = location.x,
            y = location.y,
            z = location.z
        )
        return HostEntitySnapshot(
            uuid = entity.uuid,
            type = entity.type,
            location = HostLocationRef(
                world = entity.world,
                x = entity.x,
                y = entity.y,
                z = entity.z
            )
        )
    }

    override fun playerInventory(name: String): HostInventorySnapshot? {
        val inv = hostState.inventory(name) ?: return null
        return HostInventorySnapshot(
            owner = inv.owner,
            size = inv.size,
            nonEmptySlots = inv.slots.size
        )
    }

    override fun setPlayerInventoryItem(name: String, slot: Int, itemId: String): Boolean {
        return hostState.setInventoryItem(name, slot, itemId)
    }
}
