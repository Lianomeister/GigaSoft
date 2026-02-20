package com.gigasoft.host.api

interface HostBridgePort {
    fun serverInfo(): HostServerSnapshot
    fun broadcast(message: String)
    fun findPlayer(name: String): HostPlayerSnapshot?
    fun worlds(): List<HostWorldSnapshot>
    fun entities(world: String? = null): List<HostEntitySnapshot>
    fun spawnEntity(type: String, location: HostLocationRef): HostEntitySnapshot?
    fun playerInventory(name: String): HostInventorySnapshot?
    fun setPlayerInventoryItem(name: String, slot: Int, itemId: String): Boolean
}
