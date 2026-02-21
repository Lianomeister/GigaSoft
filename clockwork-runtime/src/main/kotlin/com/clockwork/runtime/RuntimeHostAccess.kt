package com.clockwork.runtime

import com.clockwork.api.GigaLogger
import com.clockwork.api.HostAccess
import com.clockwork.api.HostEntitySnapshot
import com.clockwork.api.HostInventorySnapshot
import com.clockwork.api.HostLocationRef
import com.clockwork.api.HostMutationBatch
import com.clockwork.api.HostMutationBatchResult
import com.clockwork.api.HostMutationType
import com.clockwork.api.HostMutationOp
import com.clockwork.api.HostPermissions
import com.clockwork.api.HostHttpResponse
import com.clockwork.api.HostPlayerSnapshot
import com.clockwork.api.HostPluginInstallResult
import com.clockwork.api.HostPlayerStatusSnapshot
import com.clockwork.api.HostServerSnapshot
import com.clockwork.api.HostWorldSnapshot
import com.clockwork.api.EventBus

internal class RuntimeHostAccess(
    private val delegate: HostAccess,
    private val pluginId: String,
    rawPermissions: Collection<String>,
    private val logger: GigaLogger,
    private val isolationPolicy: RuntimeIsolationPolicy = RuntimeIsolationPolicy.legacy(rawPermissions),
    private val isolationAuditor: RuntimeIsolationAuditor? = null,
    private val eventBus: EventBus? = null
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

    override fun sendPlayerMessage(name: String, message: String): Boolean {
        if (!allowed(HostPermissions.PLAYER_MESSAGE)) return false
        return delegate.sendPlayerMessage(name, message)
    }

    override fun kickPlayer(name: String, reason: String): Boolean {
        if (!allowed(HostPermissions.PLAYER_KICK)) return false
        return delegate.kickPlayer(name, reason)
    }

    override fun playerIsOp(name: String): Boolean? {
        if (!allowed(HostPermissions.PLAYER_OP_READ)) return null
        return delegate.playerIsOp(name)
    }

    override fun setPlayerOp(name: String, op: Boolean): Boolean {
        if (!allowed(HostPermissions.PLAYER_OP_WRITE)) return false
        return delegate.setPlayerOp(name, op)
    }

    override fun playerPermissions(name: String): Set<String>? {
        if (!allowed(HostPermissions.PLAYER_PERMISSION_READ)) return null
        return delegate.playerPermissions(name)
    }

    override fun hasPlayerPermission(name: String, permission: String): Boolean? {
        if (!allowed(HostPermissions.PLAYER_PERMISSION_READ)) return null
        return delegate.hasPlayerPermission(name, permission)
    }

    override fun grantPlayerPermission(name: String, permission: String): Boolean {
        if (!allowed(HostPermissions.PLAYER_PERMISSION_WRITE)) return false
        return delegate.grantPlayerPermission(name, permission)
    }

    override fun revokePlayerPermission(name: String, permission: String): Boolean {
        if (!allowed(HostPermissions.PLAYER_PERMISSION_WRITE)) return false
        return delegate.revokePlayerPermission(name, permission)
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
        if (!capabilityAllowed(RuntimeCapability.WORLD_MUTATION, "host.spawn-entity", IsolationDiagnosticCodes.WORLD_MUTATION_CAPABILITY_REQUIRED, "Missing world-mutation capability")) return null
        if (!allowed(HostPermissions.ENTITY_SPAWN)) return null
        return delegate.spawnEntity(type, location)
    }

    override fun playerInventory(name: String): HostInventorySnapshot? {
        if (!allowed(HostPermissions.INVENTORY_READ)) return null
        return delegate.playerInventory(name)
    }

    override fun setPlayerInventoryItem(name: String, slot: Int, itemId: String): Boolean {
        if (!capabilityAllowed(RuntimeCapability.WORLD_MUTATION, "host.set-player-inventory-item", IsolationDiagnosticCodes.WORLD_MUTATION_CAPABILITY_REQUIRED, "Missing world-mutation capability")) return false
        if (!allowed(HostPermissions.INVENTORY_WRITE)) return false
        return delegate.setPlayerInventoryItem(name, slot, itemId)
    }

    override fun createWorld(name: String, seed: Long): HostWorldSnapshot? {
        if (!capabilityAllowed(RuntimeCapability.WORLD_MUTATION, "host.create-world", IsolationDiagnosticCodes.WORLD_MUTATION_CAPABILITY_REQUIRED, "Missing world-mutation capability")) return null
        if (!allowed(HostPermissions.WORLD_WRITE)) return null
        return delegate.createWorld(name, seed)
    }

    override fun worldTime(name: String): Long? {
        if (!allowed(HostPermissions.WORLD_READ)) return null
        return delegate.worldTime(name)
    }

    override fun setWorldTime(name: String, time: Long): Boolean {
        if (!capabilityAllowed(RuntimeCapability.WORLD_MUTATION, "host.set-world-time", IsolationDiagnosticCodes.WORLD_MUTATION_CAPABILITY_REQUIRED, "Missing world-mutation capability")) return false
        if (!allowed(HostPermissions.WORLD_WRITE)) return false
        return delegate.setWorldTime(name, time)
    }

    override fun worldData(name: String): Map<String, String>? {
        if (!allowed(HostPermissions.WORLD_DATA_READ)) return null
        return delegate.worldData(name)
    }

    override fun setWorldData(name: String, data: Map<String, String>): Map<String, String>? {
        if (!capabilityAllowed(RuntimeCapability.WORLD_MUTATION, "host.set-world-data", IsolationDiagnosticCodes.WORLD_MUTATION_CAPABILITY_REQUIRED, "Missing world-mutation capability")) return null
        if (!allowed(HostPermissions.WORLD_DATA_WRITE)) return null
        return delegate.setWorldData(name, data)
    }

    override fun worldWeather(name: String): String? {
        if (!allowed(HostPermissions.WORLD_WEATHER_READ)) return null
        return delegate.worldWeather(name)
    }

    override fun setWorldWeather(name: String, weather: String): Boolean {
        if (!capabilityAllowed(RuntimeCapability.WORLD_MUTATION, "host.set-world-weather", IsolationDiagnosticCodes.WORLD_MUTATION_CAPABILITY_REQUIRED, "Missing world-mutation capability")) return false
        if (!allowed(HostPermissions.WORLD_WEATHER_WRITE)) return false
        return delegate.setWorldWeather(name, weather)
    }

    override fun findEntity(uuid: String): HostEntitySnapshot? {
        if (!allowed(HostPermissions.ENTITY_READ)) return null
        return delegate.findEntity(uuid)
    }

    override fun removeEntity(uuid: String): Boolean {
        if (!capabilityAllowed(RuntimeCapability.WORLD_MUTATION, "host.remove-entity", IsolationDiagnosticCodes.WORLD_MUTATION_CAPABILITY_REQUIRED, "Missing world-mutation capability")) return false
        if (!allowed(HostPermissions.ENTITY_REMOVE)) return false
        return delegate.removeEntity(uuid)
    }

    override fun entityData(uuid: String): Map<String, String>? {
        if (!allowed(HostPermissions.ENTITY_DATA_READ)) return null
        return delegate.entityData(uuid)
    }

    override fun setEntityData(uuid: String, data: Map<String, String>): Map<String, String>? {
        if (!capabilityAllowed(RuntimeCapability.WORLD_MUTATION, "host.set-entity-data", IsolationDiagnosticCodes.WORLD_MUTATION_CAPABILITY_REQUIRED, "Missing world-mutation capability")) return null
        if (!allowed(HostPermissions.ENTITY_DATA_WRITE)) return null
        return delegate.setEntityData(uuid, data)
    }

    override fun movePlayer(name: String, location: HostLocationRef): HostPlayerSnapshot? {
        if (!capabilityAllowed(RuntimeCapability.WORLD_MUTATION, "host.move-player", IsolationDiagnosticCodes.WORLD_MUTATION_CAPABILITY_REQUIRED, "Missing world-mutation capability")) return null
        if (!allowed(HostPermissions.PLAYER_MOVE)) return null
        return delegate.movePlayer(name, location)
    }

    override fun playerGameMode(name: String): String? {
        if (!allowed(HostPermissions.PLAYER_GAMEMODE_READ)) return null
        return delegate.playerGameMode(name)
    }

    override fun setPlayerGameMode(name: String, gameMode: String): Boolean {
        if (!capabilityAllowed(RuntimeCapability.WORLD_MUTATION, "host.set-player-gamemode", IsolationDiagnosticCodes.WORLD_MUTATION_CAPABILITY_REQUIRED, "Missing world-mutation capability")) return false
        if (!allowed(HostPermissions.PLAYER_GAMEMODE_WRITE)) return false
        return delegate.setPlayerGameMode(name, gameMode)
    }

    override fun playerStatus(name: String): HostPlayerStatusSnapshot? {
        if (!allowed(HostPermissions.PLAYER_STATUS_READ)) return null
        return delegate.playerStatus(name)
    }

    override fun setPlayerStatus(name: String, status: HostPlayerStatusSnapshot): HostPlayerStatusSnapshot? {
        if (!capabilityAllowed(RuntimeCapability.WORLD_MUTATION, "host.set-player-status", IsolationDiagnosticCodes.WORLD_MUTATION_CAPABILITY_REQUIRED, "Missing world-mutation capability")) return null
        if (!allowed(HostPermissions.PLAYER_STATUS_WRITE)) return null
        return delegate.setPlayerStatus(name, status)
    }

    override fun addPlayerEffect(name: String, effectId: String, durationTicks: Int, amplifier: Int): Boolean {
        if (!capabilityAllowed(RuntimeCapability.WORLD_MUTATION, "host.add-player-effect", IsolationDiagnosticCodes.WORLD_MUTATION_CAPABILITY_REQUIRED, "Missing world-mutation capability")) return false
        if (!allowed(HostPermissions.PLAYER_EFFECT_WRITE)) return false
        return delegate.addPlayerEffect(name, effectId, durationTicks, amplifier)
    }

    override fun removePlayerEffect(name: String, effectId: String): Boolean {
        if (!capabilityAllowed(RuntimeCapability.WORLD_MUTATION, "host.remove-player-effect", IsolationDiagnosticCodes.WORLD_MUTATION_CAPABILITY_REQUIRED, "Missing world-mutation capability")) return false
        if (!allowed(HostPermissions.PLAYER_EFFECT_WRITE)) return false
        return delegate.removePlayerEffect(name, effectId)
    }

    override fun inventoryItem(name: String, slot: Int): String? {
        if (!allowed(HostPermissions.INVENTORY_READ)) return null
        return delegate.inventoryItem(name, slot)
    }

    override fun givePlayerItem(name: String, itemId: String, count: Int): Int {
        if (!capabilityAllowed(RuntimeCapability.WORLD_MUTATION, "host.give-player-item", IsolationDiagnosticCodes.WORLD_MUTATION_CAPABILITY_REQUIRED, "Missing world-mutation capability")) return 0
        if (!allowed(HostPermissions.INVENTORY_WRITE)) return 0
        return delegate.givePlayerItem(name, itemId, count)
    }

    override fun blockAt(world: String, x: Int, y: Int, z: Int): com.clockwork.api.HostBlockSnapshot? {
        if (!allowed(HostPermissions.BLOCK_READ)) return null
        return delegate.blockAt(world, x, y, z)
    }

    override fun setBlock(world: String, x: Int, y: Int, z: Int, blockId: String): com.clockwork.api.HostBlockSnapshot? {
        if (!capabilityAllowed(RuntimeCapability.WORLD_MUTATION, "host.set-block", IsolationDiagnosticCodes.WORLD_MUTATION_CAPABILITY_REQUIRED, "Missing world-mutation capability")) return null
        if (!allowed(HostPermissions.BLOCK_WRITE)) return null
        return delegate.setBlock(world, x, y, z, blockId)
    }

    override fun breakBlock(world: String, x: Int, y: Int, z: Int, dropLoot: Boolean): Boolean {
        if (!capabilityAllowed(RuntimeCapability.WORLD_MUTATION, "host.break-block", IsolationDiagnosticCodes.WORLD_MUTATION_CAPABILITY_REQUIRED, "Missing world-mutation capability")) return false
        if (!allowed(HostPermissions.BLOCK_WRITE)) return false
        return delegate.breakBlock(world, x, y, z, dropLoot)
    }

    override fun blockData(world: String, x: Int, y: Int, z: Int): Map<String, String>? {
        if (!allowed(HostPermissions.BLOCK_DATA_READ)) return null
        return delegate.blockData(world, x, y, z)
    }

    override fun setBlockData(world: String, x: Int, y: Int, z: Int, data: Map<String, String>): Map<String, String>? {
        if (!capabilityAllowed(RuntimeCapability.WORLD_MUTATION, "host.set-block-data", IsolationDiagnosticCodes.WORLD_MUTATION_CAPABILITY_REQUIRED, "Missing world-mutation capability")) return null
        if (!allowed(HostPermissions.BLOCK_DATA_WRITE)) return null
        return delegate.setBlockData(world, x, y, z, data)
    }

    override fun httpGet(
        url: String,
        connectTimeoutMillis: Int,
        readTimeoutMillis: Int,
        maxBodyChars: Int
    ): HostHttpResponse? {
        if (!capabilityAllowed(RuntimeCapability.NETWORK, "host.http-get", IsolationDiagnosticCodes.NETWORK_CAPABILITY_REQUIRED, "Missing network capability")) return null
        if (isolationPolicy.enabled) {
            val urlValidation = isolationPolicy.validateUrl(url)
            if (!urlValidation.ok) {
                recordViolation(
                    capability = RuntimeCapability.NETWORK,
                    action = "host.http-get",
                    code = urlValidation.code ?: IsolationDiagnosticCodes.NETWORK_PATH_DENIED,
                    detail = urlValidation.detail ?: "URL denied"
                )
                return null
            }
        }
        if (!allowed(HostPermissions.INTERNET_HTTP_GET)) return null
        return delegate.httpGet(
            url = url,
            connectTimeoutMillis = connectTimeoutMillis,
            readTimeoutMillis = readTimeoutMillis,
            maxBodyChars = maxBodyChars
        )
    }

    override fun installPluginFromUrl(url: String, fileName: String?, loadNow: Boolean): HostPluginInstallResult {
        if (!capabilityAllowed(RuntimeCapability.NETWORK, "host.install-plugin", IsolationDiagnosticCodes.NETWORK_CAPABILITY_REQUIRED, "Missing network capability")) {
            return HostPluginInstallResult(
                success = false,
                loaded = false,
                message = "[${IsolationDiagnosticCodes.NETWORK_CAPABILITY_REQUIRED}] Missing network capability"
            )
        }
        if (isolationPolicy.enabled) {
            val urlValidation = isolationPolicy.validateUrl(url)
            if (!urlValidation.ok) {
                val code = urlValidation.code ?: IsolationDiagnosticCodes.NETWORK_PATH_DENIED
                val detail = urlValidation.detail ?: "URL denied"
                recordViolation(RuntimeCapability.NETWORK, "host.install-plugin", code, detail)
                return HostPluginInstallResult(
                    success = false,
                    loaded = false,
                    message = "[$code] $detail"
                )
            }
        }
        if (!allowed(HostPermissions.PLUGIN_INSTALL)) {
            return HostPluginInstallResult(
                success = false,
                loaded = false,
                message = "Missing permission '${HostPermissions.PLUGIN_INSTALL}'"
            )
        }
        return delegate.installPluginFromUrl(
            url = url,
            fileName = fileName,
            loadNow = loadNow
        )
    }

    override fun applyMutationBatch(batch: HostMutationBatch): HostMutationBatchResult {
        if (!capabilityAllowed(RuntimeCapability.WORLD_MUTATION, "host.apply-mutation-batch", IsolationDiagnosticCodes.WORLD_MUTATION_CAPABILITY_REQUIRED, "Missing world-mutation capability")) {
            return HostMutationBatchResult(
                batchId = batch.id,
                success = false,
                appliedOperations = 0,
                rolledBack = false,
                error = "[${IsolationDiagnosticCodes.WORLD_MUTATION_CAPABILITY_REQUIRED}] Missing world-mutation capability"
            )
        }
        if (!allowed(HostPermissions.MUTATION_BATCH)) {
            return HostMutationBatchResult(
                batchId = batch.id,
                success = false,
                appliedOperations = 0,
                rolledBack = false,
                error = "Missing permission '${HostPermissions.MUTATION_BATCH}'"
            )
        }
        val missingPermission = batch.operations
            .asSequence()
            .mapNotNull(::requiredPermissionFor)
            .firstOrNull { permission -> permission !in permissions }
        if (missingPermission != null) {
            logger.info("Denied host mutation batch for plugin '$pluginId': missing permission '$missingPermission'")
            return HostMutationBatchResult(
                batchId = batch.id,
                success = false,
                appliedOperations = 0,
                rolledBack = false,
                error = "Missing permission '$missingPermission'"
            )
        }
        return delegate.applyMutationBatch(batch)
    }

    private fun requiredPermissionFor(op: HostMutationOp): String? {
        return when (op.type) {
            HostMutationType.CREATE_WORLD -> HostPermissions.WORLD_WRITE
            HostMutationType.SET_WORLD_TIME -> HostPermissions.WORLD_WRITE
            HostMutationType.SET_WORLD_DATA -> HostPermissions.WORLD_DATA_WRITE
            HostMutationType.SET_WORLD_WEATHER -> HostPermissions.WORLD_WEATHER_WRITE
            HostMutationType.SPAWN_ENTITY -> HostPermissions.ENTITY_SPAWN
            HostMutationType.REMOVE_ENTITY -> HostPermissions.ENTITY_REMOVE
            HostMutationType.SET_PLAYER_INVENTORY_ITEM -> HostPermissions.INVENTORY_WRITE
            HostMutationType.GIVE_PLAYER_ITEM -> HostPermissions.INVENTORY_WRITE
            HostMutationType.MOVE_PLAYER -> HostPermissions.PLAYER_MOVE
            HostMutationType.SET_PLAYER_GAMEMODE -> HostPermissions.PLAYER_GAMEMODE_WRITE
            HostMutationType.ADD_PLAYER_EFFECT -> HostPermissions.PLAYER_EFFECT_WRITE
            HostMutationType.REMOVE_PLAYER_EFFECT -> HostPermissions.PLAYER_EFFECT_WRITE
            HostMutationType.SET_BLOCK -> HostPermissions.BLOCK_WRITE
            HostMutationType.BREAK_BLOCK -> HostPermissions.BLOCK_WRITE
            HostMutationType.SET_BLOCK_DATA -> HostPermissions.BLOCK_DATA_WRITE
        }
    }

    private fun allowed(permission: String): Boolean {
        if (permission in permissions) return true
        logger.info("Denied host access for plugin '$pluginId': missing permission '$permission'")
        return false
    }

    private fun capabilityAllowed(capability: RuntimeCapability, action: String, code: String, detail: String): Boolean {
        if (!isolationPolicy.enabled) return true
        if (isolationPolicy.hasCapability(capability)) return true
        recordViolation(capability = capability, action = action, code = code, detail = detail)
        return false
    }

    private fun recordViolation(capability: RuntimeCapability, action: String, code: String, detail: String) {
        isolationAuditor?.record(
            pluginId = pluginId,
            capability = capability,
            action = action,
            code = code,
            detail = detail,
            logger = logger,
            eventBus = eventBus
        )
    }
}
