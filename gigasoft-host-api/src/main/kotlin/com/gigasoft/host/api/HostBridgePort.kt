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
    fun createWorld(name: String, seed: Long = 0L): HostWorldSnapshot? = null
    fun worldTime(name: String): Long? = null
    fun setWorldTime(name: String, time: Long): Boolean = false
    fun findEntity(uuid: String): HostEntitySnapshot? = null
    fun removeEntity(uuid: String): Boolean = false
    fun movePlayer(name: String, location: HostLocationRef): HostPlayerSnapshot? = null
    fun inventoryItem(name: String, slot: Int): String? = null
    fun givePlayerItem(name: String, itemId: String, count: Int = 1): Int = 0
    fun blockAt(world: String, x: Int, y: Int, z: Int): HostBlockSnapshot? = null
    fun setBlock(world: String, x: Int, y: Int, z: Int, blockId: String): HostBlockSnapshot? = null
    fun breakBlock(world: String, x: Int, y: Int, z: Int, dropLoot: Boolean = true): Boolean = false
    fun blockData(world: String, x: Int, y: Int, z: Int): Map<String, String>? = null
    fun setBlockData(world: String, x: Int, y: Int, z: Int, data: Map<String, String>): Map<String, String>? = null
}
