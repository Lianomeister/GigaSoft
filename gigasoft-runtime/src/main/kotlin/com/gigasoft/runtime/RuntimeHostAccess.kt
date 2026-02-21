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

    private fun allowed(permission: String): Boolean {
        if (permission in permissions) return true
        logger.info("Denied host access for plugin '$pluginId': missing permission '$permission'")
        return false
    }
}
