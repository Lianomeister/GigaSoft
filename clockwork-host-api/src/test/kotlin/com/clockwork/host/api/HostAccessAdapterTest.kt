package com.clockwork.host.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HostAccessAdapterTest {
    @Test
    fun `asHostAccess maps bridge snapshots`() {
        val access = FakeBridge().asHostAccess()
        val info = access.serverInfo()
        assertNotNull(info)
        assertEquals("TestHost", info.name)
        assertEquals(1, info.onlinePlayers)
        assertTrue(access.broadcast("hello"))
        assertEquals(1, access.worlds().size)
        assertEquals(1, access.entities("world").size)
        assertEquals(true, access.setPlayerInventoryItem("Alex", 0, "stone"))
        assertNotNull(access.createWorld("custom", 7L))
        assertEquals(100L, access.worldTime("world"))
        assertTrue(access.setWorldTime("world", 200L))
        assertEquals("normal", access.worldData("world")?.get("difficulty"))
        assertEquals("hard", access.setWorldData("world", mapOf("difficulty" to "hard"))?.get("difficulty"))
        assertEquals("clear", access.worldWeather("world"))
        assertTrue(access.setWorldWeather("world", "rain"))
        assertTrue(access.sendPlayerMessage("Alex", "hello"))
        assertTrue(access.kickPlayer("Alex", "bye"))
        assertEquals(false, access.playerIsOp("Alex"))
        assertEquals(false, access.isPlayerOp("Alex"))
        assertTrue(access.setPlayerOp("Alex", true))
        assertEquals(setOf("plugin.debug"), access.playerPermissions("Alex"))
        assertEquals(setOf("plugin.debug"), access.permissionsOfPlayer("Alex"))
        assertEquals(true, access.hasPlayerPermission("Alex", "plugin.debug"))
        assertEquals(true, access.playerHasPermission("Alex", "plugin.debug"))
        assertTrue(access.grantPlayerPermission("Alex", "plugin.extra"))
        assertTrue(access.revokePlayerPermission("Alex", "plugin.debug"))
        assertNotNull(access.findEntity("e1"))
        assertTrue(access.removeEntity("e1"))
        assertEquals("adult", access.entityData("e1")?.get("variant"))
        assertEquals("adult", access.entityDataOrEmpty("e1")["variant"])
        assertEquals("baby", access.setEntityData("e1", mapOf("variant" to "baby"))?.get("variant"))
        assertNotNull(access.movePlayer("Alex", com.clockwork.api.HostLocationRef("world", 1.0, 65.0, 1.0)))
        assertEquals("survival", access.playerGameMode("Alex"))
        assertTrue(access.setPlayerGameMode("Alex", "creative"))
        assertEquals(20.0, access.playerStatus("Alex")?.health)
        assertEquals(
            "speed",
            access.setPlayerStatus(
                "Alex",
                com.clockwork.api.HostPlayerStatusSnapshot(
                    health = 18.0,
                    maxHealth = 20.0,
                    foodLevel = 18,
                    saturation = 4.0,
                    experienceLevel = 2,
                    experienceProgress = 0.3,
                    effects = mapOf("speed" to 120)
                )
            )?.effects?.keys?.firstOrNull()
        )
        assertTrue(access.addPlayerEffect("Alex", "haste", 200, 1))
        assertTrue(access.removePlayerEffect("Alex", "haste"))
        assertEquals("stone", access.inventoryItem("Alex", 0))
        assertEquals(2, access.givePlayerItem("Alex", "stone", 2))
        val block = access.setBlock("world", 1, 64, 1, "stone")
        assertNotNull(block)
        assertEquals("stone", access.blockAt("world", 1, 64, 1)?.blockId)
        assertTrue(access.breakBlock("world", 1, 64, 1, true))
        assertEquals("north", access.blockData("world", 1, 64, 1)?.get("facing"))
        assertEquals("normal", access.worldDataOrEmpty("world")["difficulty"])
        assertEquals(1, access.listWorlds().size)
        assertEquals(1, access.listEntities("world").size)
        assertEquals("3", access.setBlockData("world", 1, 64, 1, mapOf("level" to "3"))?.get("level"))
    }

    private class FakeBridge : HostBridgePort {
        override fun serverInfo(): HostServerSnapshot {
            return HostServerSnapshot(
                name = "TestHost",
                version = "1.0",
                onlinePlayers = 1,
                maxPlayers = 20,
                worldCount = 1
            )
        }

        override fun broadcast(message: String) = Unit

        override fun findPlayer(name: String): HostPlayerSnapshot? {
            return HostPlayerSnapshot(
                uuid = "u1",
                name = name,
                location = HostLocationRef(world = "world", x = 0.0, y = 64.0, z = 0.0)
            )
        }

        override fun worlds(): List<HostWorldSnapshot> = listOf(HostWorldSnapshot(name = "world", entityCount = 1))

        override fun entities(world: String?): List<HostEntitySnapshot> {
            return listOf(
                HostEntitySnapshot(
                    uuid = "e1",
                    type = "ZOMBIE",
                    location = HostLocationRef(world = world ?: "world", x = 0.0, y = 64.0, z = 0.0)
                )
            )
        }

        override fun spawnEntity(type: String, location: HostLocationRef): HostEntitySnapshot? {
            return HostEntitySnapshot(uuid = "spawned", type = type, location = location)
        }

        override fun playerInventory(name: String): HostInventorySnapshot? {
            return HostInventorySnapshot(owner = name, size = 36, nonEmptySlots = 1)
        }

        override fun setPlayerInventoryItem(name: String, slot: Int, itemId: String): Boolean = true
        override fun createWorld(name: String, seed: Long): HostWorldSnapshot? = HostWorldSnapshot(name, 0)
        override fun worldTime(name: String): Long? = 100L
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
        override fun findEntity(uuid: String): HostEntitySnapshot? = entities("world").firstOrNull()
        override fun removeEntity(uuid: String): Boolean = true
        override fun entityData(uuid: String): Map<String, String>? = mapOf("variant" to "adult")
        override fun setEntityData(uuid: String, data: Map<String, String>): Map<String, String>? = data
        override fun movePlayer(name: String, location: HostLocationRef): HostPlayerSnapshot? {
            return HostPlayerSnapshot(uuid = "u1", name = name, location = location)
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
        override fun blockAt(world: String, x: Int, y: Int, z: Int): HostBlockSnapshot? {
            return HostBlockSnapshot(world = world, x = x, y = y, z = z, blockId = "stone")
        }
        override fun setBlock(world: String, x: Int, y: Int, z: Int, blockId: String): HostBlockSnapshot? {
            return HostBlockSnapshot(world = world, x = x, y = y, z = z, blockId = blockId)
        }
        override fun breakBlock(world: String, x: Int, y: Int, z: Int, dropLoot: Boolean): Boolean = true
        override fun blockData(world: String, x: Int, y: Int, z: Int): Map<String, String>? = mapOf("facing" to "north")
        override fun setBlockData(world: String, x: Int, y: Int, z: Int, data: Map<String, String>): Map<String, String>? = data
    }
}
