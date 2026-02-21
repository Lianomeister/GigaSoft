package com.gigasoft.host.api

import com.gigasoft.api.HostAccess
import com.gigasoft.api.HostEntitySnapshot as ApiHostEntitySnapshot
import com.gigasoft.api.HostInventorySnapshot as ApiHostInventorySnapshot
import com.gigasoft.api.HostBlockSnapshot as ApiHostBlockSnapshot
import com.gigasoft.api.HostLocationRef as ApiHostLocationRef
import com.gigasoft.api.HostPlayerSnapshot as ApiHostPlayerSnapshot
import com.gigasoft.api.HostServerSnapshot as ApiHostServerSnapshot
import com.gigasoft.api.HostWorldSnapshot as ApiHostWorldSnapshot

fun HostBridgePort.asHostAccess(): HostAccess {
    val bridge = this
    return object : HostAccess {
    override fun serverInfo(): ApiHostServerSnapshot {
        return bridge.serverInfo().toApi()
    }

    override fun broadcast(message: String): Boolean {
        return runCatching { bridge.broadcast(message) }.isSuccess
    }

    override fun findPlayer(name: String): ApiHostPlayerSnapshot? {
        return bridge.findPlayer(name)?.toApi()
    }

    override fun worlds(): List<ApiHostWorldSnapshot> {
        return bridge.worlds().map { it.toApi() }
    }

    override fun entities(world: String?): List<ApiHostEntitySnapshot> {
        return bridge.entities(world).map { it.toApi() }
    }

    override fun spawnEntity(type: String, location: ApiHostLocationRef): ApiHostEntitySnapshot? {
        return bridge.spawnEntity(type, location.toHost()).toApiNullable()
    }

    override fun playerInventory(name: String): ApiHostInventorySnapshot? {
        return bridge.playerInventory(name)?.toApi()
    }

    override fun setPlayerInventoryItem(name: String, slot: Int, itemId: String): Boolean {
        return bridge.setPlayerInventoryItem(name, slot, itemId)
    }

    override fun createWorld(name: String, seed: Long): ApiHostWorldSnapshot? {
        return bridge.createWorld(name, seed)?.toApi()
    }

    override fun worldTime(name: String): Long? {
        return bridge.worldTime(name)
    }

    override fun setWorldTime(name: String, time: Long): Boolean {
        return bridge.setWorldTime(name, time)
    }

    override fun findEntity(uuid: String): ApiHostEntitySnapshot? {
        return bridge.findEntity(uuid)?.toApi()
    }

    override fun removeEntity(uuid: String): Boolean {
        return bridge.removeEntity(uuid)
    }

    override fun entityData(uuid: String): Map<String, String>? {
        return bridge.entityData(uuid)
    }

    override fun setEntityData(uuid: String, data: Map<String, String>): Map<String, String>? {
        return bridge.setEntityData(uuid, data)
    }

    override fun movePlayer(name: String, location: ApiHostLocationRef): ApiHostPlayerSnapshot? {
        return bridge.movePlayer(name, location.toHost())?.toApi()
    }

    override fun inventoryItem(name: String, slot: Int): String? {
        return bridge.inventoryItem(name, slot)
    }

    override fun givePlayerItem(name: String, itemId: String, count: Int): Int {
        return bridge.givePlayerItem(name, itemId, count)
    }

    override fun blockAt(world: String, x: Int, y: Int, z: Int): ApiHostBlockSnapshot? {
        return bridge.blockAt(world, x, y, z)?.toApi()
    }

    override fun setBlock(world: String, x: Int, y: Int, z: Int, blockId: String): ApiHostBlockSnapshot? {
        return bridge.setBlock(world, x, y, z, blockId)?.toApi()
    }

    override fun breakBlock(world: String, x: Int, y: Int, z: Int, dropLoot: Boolean): Boolean {
        return bridge.breakBlock(world, x, y, z, dropLoot)
    }

    override fun blockData(world: String, x: Int, y: Int, z: Int): Map<String, String>? {
        return bridge.blockData(world, x, y, z)
    }

    override fun setBlockData(world: String, x: Int, y: Int, z: Int, data: Map<String, String>): Map<String, String>? {
        return bridge.setBlockData(world, x, y, z, data)
    }
}
}

private fun HostLocationRef.toApi(): ApiHostLocationRef {
    return ApiHostLocationRef(world = world, x = x, y = y, z = z)
}

private fun ApiHostLocationRef.toHost(): HostLocationRef {
    return HostLocationRef(world = world, x = x, y = y, z = z)
}

private fun HostPlayerSnapshot.toApi(): ApiHostPlayerSnapshot {
    return ApiHostPlayerSnapshot(uuid = uuid, name = name, location = location.toApi())
}

private fun HostWorldSnapshot.toApi(): ApiHostWorldSnapshot {
    return ApiHostWorldSnapshot(name = name, entityCount = entityCount)
}

private fun HostEntitySnapshot.toApi(): ApiHostEntitySnapshot {
    return ApiHostEntitySnapshot(uuid = uuid, type = type, location = location.toApi())
}

private fun HostEntitySnapshot?.toApiNullable(): ApiHostEntitySnapshot? {
    return this?.toApi()
}

private fun HostInventorySnapshot.toApi(): ApiHostInventorySnapshot {
    return ApiHostInventorySnapshot(owner = owner, size = size, nonEmptySlots = nonEmptySlots)
}

private fun HostBlockSnapshot.toApi(): ApiHostBlockSnapshot {
    return ApiHostBlockSnapshot(world = world, x = x, y = y, z = z, blockId = blockId)
}

private fun HostServerSnapshot.toApi(): ApiHostServerSnapshot {
    return ApiHostServerSnapshot(
        name = name,
        version = version,
        platformVersion = platformVersion,
        onlinePlayers = onlinePlayers,
        maxPlayers = maxPlayers,
        worldCount = worldCount
    )
}
