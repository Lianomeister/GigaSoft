package com.clockwork.host.api

interface HostInventoryPort {
    fun playerInventory(name: String): HostInventorySnapshot?
    fun setPlayerInventoryItem(name: String, slot: Int, itemId: String): Boolean
    fun inventoryItem(name: String, slot: Int): String? = null
    fun givePlayerItem(name: String, itemId: String, count: Int = 1): Int = 0
}

interface HostPlayerPort : HostInventoryPort {
    fun findPlayer(name: String): HostPlayerSnapshot?
    fun lookupPlayer(name: String): HostPlayerSnapshot? = findPlayer(name)
    fun sendPlayerMessage(name: String, message: String): Boolean = false
    fun kickPlayer(name: String, reason: String = "Kicked by host"): Boolean = false
    fun playerIsOp(name: String): Boolean? = null
    fun isPlayerOp(name: String): Boolean = playerIsOp(name) == true
    fun setPlayerOp(name: String, op: Boolean): Boolean = false
    fun playerPermissions(name: String): Set<String>? = null
    fun permissionsOfPlayer(name: String): Set<String> = playerPermissions(name).orEmpty()
    fun hasPlayerPermission(name: String, permission: String): Boolean? = null
    fun playerHasPermission(name: String, permission: String): Boolean = hasPlayerPermission(name, permission) == true
    fun grantPlayerPermission(name: String, permission: String): Boolean = false
    fun revokePlayerPermission(name: String, permission: String): Boolean = false
    fun movePlayer(name: String, location: HostLocationRef): HostPlayerSnapshot? = null
    fun playerGameMode(name: String): String? = null
    fun setPlayerGameMode(name: String, gameMode: String): Boolean = false
    fun playerStatus(name: String): HostPlayerStatusSnapshot? = null
    fun setPlayerStatus(name: String, status: HostPlayerStatusSnapshot): HostPlayerStatusSnapshot? = null
    fun addPlayerEffect(name: String, effectId: String, durationTicks: Int, amplifier: Int = 0): Boolean = false
    fun removePlayerEffect(name: String, effectId: String): Boolean = false
}

interface HostWorldPort {
    fun worlds(): List<HostWorldSnapshot>
    fun listWorlds(): List<HostWorldSnapshot> = worlds()
    fun createWorld(name: String, seed: Long = 0L): HostWorldSnapshot? = null
    fun worldTime(name: String): Long? = null
    fun setWorldTime(name: String, time: Long): Boolean = false
    fun worldData(name: String): Map<String, String>? = null
    fun worldDataOrEmpty(name: String): Map<String, String> = worldData(name).orEmpty()
    fun setWorldData(name: String, data: Map<String, String>): Map<String, String>? = null
    fun worldWeather(name: String): String? = null
    fun setWorldWeather(name: String, weather: String): Boolean = false
    fun blockAt(world: String, x: Int, y: Int, z: Int): HostBlockSnapshot? = null
    fun setBlock(world: String, x: Int, y: Int, z: Int, blockId: String): HostBlockSnapshot? = null
    fun breakBlock(world: String, x: Int, y: Int, z: Int, dropLoot: Boolean = true): Boolean = false
    fun blockData(world: String, x: Int, y: Int, z: Int): Map<String, String>? = null
    fun setBlockData(world: String, x: Int, y: Int, z: Int, data: Map<String, String>): Map<String, String>? = null
}

interface HostEntityPort {
    fun entities(world: String? = null): List<HostEntitySnapshot>
    fun listEntities(world: String? = null): List<HostEntitySnapshot> = entities(world)
    fun spawnEntity(type: String, location: HostLocationRef): HostEntitySnapshot?
    fun findEntity(uuid: String): HostEntitySnapshot? = null
    fun removeEntity(uuid: String): Boolean = false
    fun entityData(uuid: String): Map<String, String>? = null
    fun entityDataOrEmpty(uuid: String): Map<String, String> = entityData(uuid).orEmpty()
    fun setEntityData(uuid: String, data: Map<String, String>): Map<String, String>? = null
}
