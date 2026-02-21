package com.gigasoft.core

import com.gigasoft.api.GigaLogger
import com.gigasoft.host.api.HostBridgePort
import com.gigasoft.host.api.HostEntitySnapshot
import com.gigasoft.host.api.HostInventorySnapshot
import com.gigasoft.host.api.HostBlockSnapshot
import com.gigasoft.host.api.HostLocationRef
import com.gigasoft.host.api.HostServerSnapshot
import com.gigasoft.host.api.HostPlayerSnapshot
import com.gigasoft.host.api.HostPlayerStatusSnapshot
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
            platformVersion = serverVersion,
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

    override fun sendPlayerMessage(name: String, message: String): Boolean {
        val player = hostState.findPlayer(name) ?: return false
        val text = message.trim()
        if (text.isEmpty()) return false
        logger.info("[message:${player.name}] $text")
        return true
    }

    override fun kickPlayer(name: String, reason: String): Boolean {
        val player = hostState.leavePlayer(name) ?: return false
        val text = reason.trim().ifBlank { "Kicked by host" }
        logger.info("[kick:${player.name}] $text")
        return true
    }

    override fun playerIsOp(name: String): Boolean? {
        return hostState.playerIsOp(name)
    }

    override fun setPlayerOp(name: String, op: Boolean): Boolean {
        return hostState.setPlayerOp(name, op) != null
    }

    override fun playerPermissions(name: String): Set<String>? {
        return hostState.playerPermissions(name)
    }

    override fun hasPlayerPermission(name: String, permission: String): Boolean? {
        return hostState.hasPlayerPermission(name, permission)
    }

    override fun grantPlayerPermission(name: String, permission: String): Boolean {
        return hostState.grantPlayerPermission(name, permission)
    }

    override fun revokePlayerPermission(name: String, permission: String): Boolean {
        return hostState.revokePlayerPermission(name, permission)
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

    override fun createWorld(name: String, seed: Long): HostWorldSnapshot? {
        val world = hostState.createWorld(name, seed)
        return HostWorldSnapshot(
            name = world.name,
            entityCount = hostState.entityCount(world.name)
        )
    }

    override fun worldTime(name: String): Long? {
        return hostState.worldTime(name)
    }

    override fun setWorldTime(name: String, time: Long): Boolean {
        return hostState.setWorldTime(name, time) != null
    }

    override fun worldData(name: String): Map<String, String>? {
        return hostState.worldData(name)
    }

    override fun setWorldData(name: String, data: Map<String, String>): Map<String, String>? {
        return hostState.setWorldData(name, data)
    }

    override fun worldWeather(name: String): String? {
        return hostState.worldWeather(name)
    }

    override fun setWorldWeather(name: String, weather: String): Boolean {
        return hostState.setWorldWeather(name, weather) != null
    }

    override fun findEntity(uuid: String): HostEntitySnapshot? {
        val entity = hostState.findEntity(uuid) ?: return null
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

    override fun removeEntity(uuid: String): Boolean {
        return hostState.removeEntity(uuid) != null
    }

    override fun entityData(uuid: String): Map<String, String>? {
        return hostState.entityData(uuid)
    }

    override fun setEntityData(uuid: String, data: Map<String, String>): Map<String, String>? {
        return hostState.setEntityData(uuid, data)
    }

    override fun movePlayer(name: String, location: HostLocationRef): HostPlayerSnapshot? {
        val moved = hostState.movePlayer(name, location.x, location.y, location.z, location.world) ?: return null
        return HostPlayerSnapshot(
            uuid = moved.uuid,
            name = moved.name,
            location = HostLocationRef(
                world = moved.world,
                x = moved.x,
                y = moved.y,
                z = moved.z
            )
        )
    }

    override fun playerGameMode(name: String): String? {
        return hostState.playerGameMode(name)
    }

    override fun setPlayerGameMode(name: String, gameMode: String): Boolean {
        return hostState.setPlayerGameMode(name, gameMode) != null
    }

    override fun playerStatus(name: String): HostPlayerStatusSnapshot? {
        val status = hostState.playerStatus(name) ?: return null
        return HostPlayerStatusSnapshot(
            health = status.health,
            maxHealth = status.maxHealth,
            foodLevel = status.foodLevel,
            saturation = status.saturation,
            experienceLevel = status.experienceLevel,
            experienceProgress = status.experienceProgress,
            effects = status.effects
        )
    }

    override fun setPlayerStatus(name: String, status: HostPlayerStatusSnapshot): HostPlayerStatusSnapshot? {
        val updated = hostState.setPlayerStatus(
            name = name,
            status = StandalonePlayerStatus(
                health = status.health,
                maxHealth = status.maxHealth,
                foodLevel = status.foodLevel,
                saturation = status.saturation,
                experienceLevel = status.experienceLevel,
                experienceProgress = status.experienceProgress,
                effects = status.effects
            )
        ) ?: return null
        return HostPlayerStatusSnapshot(
            health = updated.health,
            maxHealth = updated.maxHealth,
            foodLevel = updated.foodLevel,
            saturation = updated.saturation,
            experienceLevel = updated.experienceLevel,
            experienceProgress = updated.experienceProgress,
            effects = updated.effects
        )
    }

    override fun addPlayerEffect(name: String, effectId: String, durationTicks: Int, amplifier: Int): Boolean {
        return hostState.addPlayerEffect(name, effectId, durationTicks, amplifier)
    }

    override fun removePlayerEffect(name: String, effectId: String): Boolean {
        return hostState.removePlayerEffect(name, effectId)
    }

    override fun inventoryItem(name: String, slot: Int): String? {
        return hostState.inventoryItem(name, slot)
    }

    override fun givePlayerItem(name: String, itemId: String, count: Int): Int {
        return hostState.givePlayerItem(name, itemId, count)
    }

    override fun blockAt(world: String, x: Int, y: Int, z: Int): HostBlockSnapshot? {
        val block = hostState.blockAt(world, x, y, z) ?: return null
        return HostBlockSnapshot(
            world = block.world,
            x = block.x,
            y = block.y,
            z = block.z,
            blockId = block.blockId
        )
    }

    override fun setBlock(world: String, x: Int, y: Int, z: Int, blockId: String): HostBlockSnapshot? {
        val block = hostState.setBlock(world, x, y, z, blockId) ?: return null
        return HostBlockSnapshot(
            world = block.world,
            x = block.x,
            y = block.y,
            z = block.z,
            blockId = block.blockId
        )
    }

    override fun breakBlock(world: String, x: Int, y: Int, z: Int, dropLoot: Boolean): Boolean {
        return hostState.breakBlock(world, x, y, z) != null
    }

    override fun blockData(world: String, x: Int, y: Int, z: Int): Map<String, String>? {
        return hostState.blockData(world, x, y, z)
    }

    override fun setBlockData(world: String, x: Int, y: Int, z: Int, data: Map<String, String>): Map<String, String>? {
        return hostState.setBlockData(world, x, y, z, data)
    }
}
