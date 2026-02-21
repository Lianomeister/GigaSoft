package com.gigasoft.runtime

import com.gigasoft.api.GigaLogger
import com.gigasoft.api.HostAccess
import com.gigasoft.api.HostEntitySnapshot
import com.gigasoft.api.HostInventorySnapshot
import com.gigasoft.api.HostLocationRef
import com.gigasoft.api.HostPermissions
import com.gigasoft.api.HostPlayerSnapshot
import com.gigasoft.api.HostServerSnapshot
import com.gigasoft.api.HostWorldSnapshot

internal class RuntimeHostAccess(
    private val delegate: HostAccess,
    private val pluginId: String,
    rawPermissions: Collection<String>,
    private val logger: GigaLogger
) : HostAccess {
    private val permissions = rawPermissions.map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    override fun serverInfo(): HostServerSnapshot? {
        if (!allowed(HostPermissions.SERVER_READ)) return null
        return delegate.serverInfo()
    }

    override fun broadcast(message: String): Boolean {
        if (!allowed(HostPermissions.SERVER_BROADCAST)) return false
        return delegate.broadcast(message)
    }

    override fun findPlayer(name: String): HostPlayerSnapshot? {
        if (!allowed(HostPermissions.PLAYER_READ)) return null
        return delegate.findPlayer(name)
    }

    override fun worlds(): List<HostWorldSnapshot> {
        if (!allowed(HostPermissions.WORLD_READ)) return emptyList()
        return delegate.worlds()
    }

    override fun entities(world: String?): List<HostEntitySnapshot> {
        if (!allowed(HostPermissions.ENTITY_READ)) return emptyList()
        return delegate.entities(world)
    }

    override fun spawnEntity(type: String, location: HostLocationRef): HostEntitySnapshot? {
        if (!allowed(HostPermissions.ENTITY_SPAWN)) return null
        return delegate.spawnEntity(type, location)
    }

    override fun playerInventory(name: String): HostInventorySnapshot? {
        if (!allowed(HostPermissions.INVENTORY_READ)) return null
        return delegate.playerInventory(name)
    }

    override fun setPlayerInventoryItem(name: String, slot: Int, itemId: String): Boolean {
        if (!allowed(HostPermissions.INVENTORY_WRITE)) return false
        return delegate.setPlayerInventoryItem(name, slot, itemId)
    }

    override fun createWorld(name: String, seed: Long): HostWorldSnapshot? {
        if (!allowed(HostPermissions.WORLD_WRITE)) return null
        return delegate.createWorld(name, seed)
    }

    override fun worldTime(name: String): Long? {
        if (!allowed(HostPermissions.WORLD_READ)) return null
        return delegate.worldTime(name)
    }

    override fun setWorldTime(name: String, time: Long): Boolean {
        if (!allowed(HostPermissions.WORLD_WRITE)) return false
        return delegate.setWorldTime(name, time)
    }

    override fun findEntity(uuid: String): HostEntitySnapshot? {
        if (!allowed(HostPermissions.ENTITY_READ)) return null
        return delegate.findEntity(uuid)
    }

    override fun removeEntity(uuid: String): Boolean {
        if (!allowed(HostPermissions.ENTITY_REMOVE)) return false
        return delegate.removeEntity(uuid)
    }

    override fun entityData(uuid: String): Map<String, String>? {
        if (!allowed(HostPermissions.ENTITY_DATA_READ)) return null
        return delegate.entityData(uuid)
    }

    override fun setEntityData(uuid: String, data: Map<String, String>): Map<String, String>? {
        if (!allowed(HostPermissions.ENTITY_DATA_WRITE)) return null
        return delegate.setEntityData(uuid, data)
    }

    override fun movePlayer(name: String, location: HostLocationRef): HostPlayerSnapshot? {
        if (!allowed(HostPermissions.PLAYER_MOVE)) return null
        return delegate.movePlayer(name, location)
    }

    override fun inventoryItem(name: String, slot: Int): String? {
        if (!allowed(HostPermissions.INVENTORY_READ)) return null
        return delegate.inventoryItem(name, slot)
    }

    override fun givePlayerItem(name: String, itemId: String, count: Int): Int {
        if (!allowed(HostPermissions.INVENTORY_WRITE)) return 0
        return delegate.givePlayerItem(name, itemId, count)
    }

    override fun blockAt(world: String, x: Int, y: Int, z: Int): com.gigasoft.api.HostBlockSnapshot? {
        if (!allowed(HostPermissions.BLOCK_READ)) return null
        return delegate.blockAt(world, x, y, z)
    }

    override fun setBlock(world: String, x: Int, y: Int, z: Int, blockId: String): com.gigasoft.api.HostBlockSnapshot? {
        if (!allowed(HostPermissions.BLOCK_WRITE)) return null
        return delegate.setBlock(world, x, y, z, blockId)
    }

    override fun breakBlock(world: String, x: Int, y: Int, z: Int, dropLoot: Boolean): Boolean {
        if (!allowed(HostPermissions.BLOCK_WRITE)) return false
        return delegate.breakBlock(world, x, y, z, dropLoot)
    }

    override fun blockData(world: String, x: Int, y: Int, z: Int): Map<String, String>? {
        if (!allowed(HostPermissions.BLOCK_DATA_READ)) return null
        return delegate.blockData(world, x, y, z)
    }

    override fun setBlockData(world: String, x: Int, y: Int, z: Int, data: Map<String, String>): Map<String, String>? {
        if (!allowed(HostPermissions.BLOCK_DATA_WRITE)) return null
        return delegate.setBlockData(world, x, y, z, data)
    }

    private fun allowed(permission: String): Boolean {
        if (permission in permissions) return true
        logger.info("Denied host access for plugin '$pluginId': missing permission '$permission'")
        return false
    }
}
