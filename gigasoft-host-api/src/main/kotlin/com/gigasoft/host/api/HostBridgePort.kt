package com.gigasoft.host.api

interface HostBridgePort {
    fun serverInfo(): HostServerSnapshot
    fun broadcast(message: String)
    fun findPlayer(name: String): HostPlayerSnapshot?
    fun sendPlayerMessage(name: String, message: String): Boolean = false
    fun kickPlayer(name: String, reason: String = "Kicked by host"): Boolean = false
    fun playerIsOp(name: String): Boolean? = null
    fun setPlayerOp(name: String, op: Boolean): Boolean = false
    fun playerPermissions(name: String): Set<String>? = null
    fun hasPlayerPermission(name: String, permission: String): Boolean? = null
    fun grantPlayerPermission(name: String, permission: String): Boolean = false
    fun revokePlayerPermission(name: String, permission: String): Boolean = false
    fun worlds(): List<HostWorldSnapshot>
    fun entities(world: String? = null): List<HostEntitySnapshot>
    fun spawnEntity(type: String, location: HostLocationRef): HostEntitySnapshot?
    fun playerInventory(name: String): HostInventorySnapshot?
    fun setPlayerInventoryItem(name: String, slot: Int, itemId: String): Boolean
    fun createWorld(name: String, seed: Long = 0L): HostWorldSnapshot? = null
    fun worldTime(name: String): Long? = null
    fun setWorldTime(name: String, time: Long): Boolean = false
    fun worldData(name: String): Map<String, String>? = null
    fun setWorldData(name: String, data: Map<String, String>): Map<String, String>? = null
    fun worldWeather(name: String): String? = null
    fun setWorldWeather(name: String, weather: String): Boolean = false
    fun findEntity(uuid: String): HostEntitySnapshot? = null
    fun removeEntity(uuid: String): Boolean = false
    fun entityData(uuid: String): Map<String, String>? = null
    fun setEntityData(uuid: String, data: Map<String, String>): Map<String, String>? = null
    fun movePlayer(name: String, location: HostLocationRef): HostPlayerSnapshot? = null
    fun playerGameMode(name: String): String? = null
    fun setPlayerGameMode(name: String, gameMode: String): Boolean = false
    fun playerStatus(name: String): HostPlayerStatusSnapshot? = null
    fun setPlayerStatus(name: String, status: HostPlayerStatusSnapshot): HostPlayerStatusSnapshot? = null
    fun addPlayerEffect(name: String, effectId: String, durationTicks: Int, amplifier: Int = 0): Boolean = false
    fun removePlayerEffect(name: String, effectId: String): Boolean = false
    fun inventoryItem(name: String, slot: Int): String? = null
    fun givePlayerItem(name: String, itemId: String, count: Int = 1): Int = 0
    fun blockAt(world: String, x: Int, y: Int, z: Int): HostBlockSnapshot? = null
    fun setBlock(world: String, x: Int, y: Int, z: Int, blockId: String): HostBlockSnapshot? = null
    fun breakBlock(world: String, x: Int, y: Int, z: Int, dropLoot: Boolean = true): Boolean = false
    fun blockData(world: String, x: Int, y: Int, z: Int): Map<String, String>? = null
    fun setBlockData(world: String, x: Int, y: Int, z: Int, data: Map<String, String>): Map<String, String>? = null
}
