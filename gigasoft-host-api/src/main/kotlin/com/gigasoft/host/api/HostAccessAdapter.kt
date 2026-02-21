package com.gigasoft.host.api

import com.gigasoft.api.HostAccess
import com.gigasoft.api.HostEntitySnapshot as ApiHostEntitySnapshot
import com.gigasoft.api.HostInventorySnapshot as ApiHostInventorySnapshot
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
