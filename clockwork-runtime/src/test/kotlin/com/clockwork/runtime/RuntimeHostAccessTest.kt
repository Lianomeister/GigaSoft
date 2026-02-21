package com.clockwork.runtime

import com.clockwork.api.GigaLogger
import com.clockwork.api.HostAccess
import com.clockwork.api.HostEntitySnapshot
import com.clockwork.api.HostLocationRef
import com.clockwork.api.HostPlayerSnapshot
import com.clockwork.api.HostPlayerStatusSnapshot
import com.clockwork.api.HostServerSnapshot
import com.clockwork.api.HostWorldSnapshot
import com.clockwork.api.HostInventorySnapshot
import com.clockwork.api.HostMutationBatch
import com.clockwork.api.HostMutationBatchResult
import com.clockwork.api.HostMutationOp
import com.clockwork.api.HostMutationType
import com.clockwork.api.HostPermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RuntimeHostAccessTest {
    @Test
    fun `mutation batch is denied when permission is missing`() {
        val delegate = FakeHostAccess()
        val access = RuntimeHostAccess(
            delegate = delegate,
            pluginId = "plugin-x",
            rawPermissions = listOf(HostPermissions.WORLD_READ),
            logger = GigaLogger { }
        )

        val result = access.applyMutationBatch(
            HostMutationBatch(
                id = "batch-denied",
                operations = listOf(
                    HostMutationOp(
                        type = HostMutationType.SET_WORLD_TIME,
                        target = "world",
                        longValue = 200L
                    )
                )
            )
        )

        assertEquals(false, result.success)
        assertEquals(0, result.appliedOperations)
        assertEquals(false, result.rolledBack)
        assertTrue(result.error?.contains(HostPermissions.MUTATION_BATCH) == true)
    }

    @Test
    fun `mutation batch delegates when permissions are present`() {
        val delegate = FakeHostAccess()
        val access = RuntimeHostAccess(
            delegate = delegate,
            pluginId = "plugin-x",
            rawPermissions = listOf(HostPermissions.WORLD_WRITE, HostPermissions.MUTATION_BATCH),
            logger = GigaLogger { }
        )
        val batch = HostMutationBatch(
            id = "batch-ok",
            operations = listOf(
                HostMutationOp(
                    type = HostMutationType.SET_WORLD_TIME,
                    target = "world",
                    longValue = 200L
                )
            )
        )

        val result = access.applyMutationBatch(batch)

        assertEquals(true, result.success)
        assertEquals("batch-ok", result.batchId)
        assertEquals("batch-ok", delegate.lastBatchId)
    }

    @Test
    fun `new host operations honor permission checks`() {
        val delegate = FakeHostAccess()
        val access = RuntimeHostAccess(
            delegate = delegate,
            pluginId = "plugin-x",
            rawPermissions = listOf(
                HostPermissions.WORLD_WRITE,
                HostPermissions.WORLD_READ,
                HostPermissions.WORLD_DATA_READ,
                HostPermissions.WORLD_DATA_WRITE,
                HostPermissions.WORLD_WEATHER_READ,
                HostPermissions.WORLD_WEATHER_WRITE,
                HostPermissions.ENTITY_REMOVE,
                HostPermissions.ENTITY_DATA_READ,
                HostPermissions.ENTITY_DATA_WRITE,
                HostPermissions.PLAYER_MESSAGE,
                HostPermissions.PLAYER_KICK,
                HostPermissions.PLAYER_OP_READ,
                HostPermissions.PLAYER_OP_WRITE,
                HostPermissions.PLAYER_PERMISSION_READ,
                HostPermissions.PLAYER_PERMISSION_WRITE,
                HostPermissions.PLAYER_MOVE,
                HostPermissions.PLAYER_GAMEMODE_READ,
                HostPermissions.PLAYER_GAMEMODE_WRITE,
                HostPermissions.PLAYER_STATUS_READ,
                HostPermissions.PLAYER_STATUS_WRITE,
                HostPermissions.PLAYER_EFFECT_WRITE,
                HostPermissions.INVENTORY_WRITE,
                HostPermissions.BLOCK_READ,
                HostPermissions.BLOCK_WRITE,
                HostPermissions.BLOCK_DATA_READ,
                HostPermissions.BLOCK_DATA_WRITE
            ),
            logger = GigaLogger { }
        )

        assertNotNull(access.createWorld("mod", 1L))
        assertEquals(50L, access.worldTime("mod"))
        assertTrue(access.setWorldTime("mod", 200L))
        assertEquals("normal", access.worldData("mod")?.get("difficulty"))
        assertEquals("hard", access.setWorldData("mod", mapOf("difficulty" to "hard"))?.get("difficulty"))
        assertEquals("clear", access.worldWeather("mod"))
        assertTrue(access.setWorldWeather("mod", "rain"))
        assertTrue(access.sendPlayerMessage("Alex", "hello"))
        assertTrue(access.kickPlayer("Alex", "bye"))
        assertEquals(false, access.playerIsOp("Alex"))
        assertTrue(access.setPlayerOp("Alex", true))
        assertEquals(setOf("plugin.debug"), access.playerPermissions("Alex"))
        assertEquals(true, access.hasPlayerPermission("Alex", "plugin.debug"))
        assertTrue(access.grantPlayerPermission("Alex", "plugin.extra"))
        assertTrue(access.revokePlayerPermission("Alex", "plugin.debug"))
        assertTrue(access.removeEntity("e1"))
        assertEquals("true", access.entityData("e1")?.get("angry"))
        assertEquals("false", access.setEntityData("e1", mapOf("angry" to "false"))?.get("angry"))
        assertNotNull(access.movePlayer("Alex", HostLocationRef("mod", 3.0, 64.0, 3.0)))
        assertEquals("survival", access.playerGameMode("Alex"))
        assertTrue(access.setPlayerGameMode("Alex", "creative"))
        assertEquals(20.0, access.playerStatus("Alex")?.health)
        val updatedStatus = access.setPlayerStatus(
            "Alex",
            HostPlayerStatusSnapshot(
                health = 18.0,
                maxHealth = 20.0,
                foodLevel = 16,
                saturation = 3.0,
                experienceLevel = 2,
                experienceProgress = 0.5,
                effects = mapOf("speed" to 120)
            )
        )
        assertEquals(18.0, updatedStatus?.health)
        assertTrue(access.addPlayerEffect("Alex", "haste", 100, 1))
        assertTrue(access.removePlayerEffect("Alex", "haste"))
        assertEquals(2, access.givePlayerItem("Alex", "iron_ingot", 2))
        assertEquals("stone", access.blockAt("world", 1, 64, 1)?.blockId)
        assertEquals("dirt", access.setBlock("world", 1, 64, 1, "dirt")?.blockId)
        assertTrue(access.breakBlock("world", 1, 64, 1))
        assertEquals("north", access.blockData("world", 1, 64, 1)?.get("facing"))
        assertEquals("2", access.setBlockData("world", 1, 64, 1, mapOf("level" to "2"))?.get("level"))
        assertNull(access.findEntity("e1"))
        assertEquals(null, access.inventoryItem("Alex", 0))
    }

    private class FakeHostAccess : HostAccess {
        override fun serverInfo(): HostServerSnapshot? = null
        override fun broadcast(message: String): Boolean = true
        override fun findPlayer(name: String): HostPlayerSnapshot? = null
        override fun worlds(): List<HostWorldSnapshot> = emptyList()
        override fun entities(world: String?): List<HostEntitySnapshot> = emptyList()
        override fun spawnEntity(type: String, location: HostLocationRef): HostEntitySnapshot? = null
        override fun playerInventory(name: String): HostInventorySnapshot? = null
        override fun setPlayerInventoryItem(name: String, slot: Int, itemId: String): Boolean = true
        override fun createWorld(name: String, seed: Long): HostWorldSnapshot? = HostWorldSnapshot(name, 0)
        override fun worldTime(name: String): Long? = 50L
        override fun setWorldTime(name: String, time: Long): Boolean = true
        override fun worldData(name: String): Map<String, String>? = mapOf("difficulty" to "normal")
        override fun setWorldData(name: String, data: Map<String, String>): Map<String, String>? = data
        override fun worldWeather(name: String): String? = "clear"
        override fun setWorldWeather(name: String, weather: String): Boolean = true
        override fun sendPlayerMessage(name: String, message: String): Boolean = true
        override fun kickPlayer(name: String, reason: String): Boolean = true
        override fun playerIsOp(name: String): Boolean? = false
        override fun setPlayerOp(name: String, op: Boolean): Boolean = true
        override fun playerPermissions(name: String): Set<String>? = setOf("plugin.debug")
        override fun hasPlayerPermission(name: String, permission: String): Boolean? = permission == "plugin.debug"
        override fun grantPlayerPermission(name: String, permission: String): Boolean = true
        override fun revokePlayerPermission(name: String, permission: String): Boolean = true
        override fun findEntity(uuid: String): HostEntitySnapshot? {
            return HostEntitySnapshot(uuid, "zombie", HostLocationRef("world", 0.0, 64.0, 0.0))
        }
        override fun removeEntity(uuid: String): Boolean = true
        override fun entityData(uuid: String): Map<String, String>? = mapOf("angry" to "true")
        override fun setEntityData(uuid: String, data: Map<String, String>): Map<String, String>? = data
        override fun movePlayer(name: String, location: HostLocationRef): HostPlayerSnapshot? {
            return HostPlayerSnapshot("u1", name, location)
        }
        override fun playerGameMode(name: String): String? = "survival"
        override fun setPlayerGameMode(name: String, gameMode: String): Boolean = true
        override fun playerStatus(name: String): HostPlayerStatusSnapshot? {
            return HostPlayerStatusSnapshot(
                health = 20.0,
                maxHealth = 20.0,
                foodLevel = 20,
                saturation = 5.0,
                experienceLevel = 0,
                experienceProgress = 0.0,
                effects = emptyMap()
            )
        }
        override fun setPlayerStatus(name: String, status: HostPlayerStatusSnapshot): HostPlayerStatusSnapshot? = status
        override fun addPlayerEffect(name: String, effectId: String, durationTicks: Int, amplifier: Int): Boolean = true
        override fun removePlayerEffect(name: String, effectId: String): Boolean = true
        override fun inventoryItem(name: String, slot: Int): String? = "stone"
        override fun givePlayerItem(name: String, itemId: String, count: Int): Int = count
        override fun blockAt(world: String, x: Int, y: Int, z: Int) =
            com.clockwork.api.HostBlockSnapshot(world = world, x = x, y = y, z = z, blockId = "stone")
        override fun setBlock(world: String, x: Int, y: Int, z: Int, blockId: String) =
            com.clockwork.api.HostBlockSnapshot(world = world, x = x, y = y, z = z, blockId = blockId)
        override fun breakBlock(world: String, x: Int, y: Int, z: Int, dropLoot: Boolean): Boolean = true
        override fun blockData(world: String, x: Int, y: Int, z: Int): Map<String, String>? {
            return mapOf("facing" to "north")
        }
        override fun setBlockData(world: String, x: Int, y: Int, z: Int, data: Map<String, String>): Map<String, String>? {
            return data
        }
        var lastBatchId: String? = null
        override fun applyMutationBatch(batch: HostMutationBatch): HostMutationBatchResult {
            lastBatchId = batch.id
            return HostMutationBatchResult(
                batchId = batch.id,
                success = true,
                appliedOperations = batch.operations.size,
                rolledBack = false
            )
        }
    }
}
