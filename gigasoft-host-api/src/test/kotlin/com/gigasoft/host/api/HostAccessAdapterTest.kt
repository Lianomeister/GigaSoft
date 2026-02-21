package com.gigasoft.host.api

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
        assertNotNull(access.findEntity("e1"))
        assertTrue(access.removeEntity("e1"))
        assertEquals("adult", access.entityData("e1")?.get("variant"))
        assertEquals("baby", access.setEntityData("e1", mapOf("variant" to "baby"))?.get("variant"))
        assertNotNull(access.movePlayer("Alex", com.gigasoft.api.HostLocationRef("world", 1.0, 65.0, 1.0)))
        assertEquals("stone", access.inventoryItem("Alex", 0))
        assertEquals(2, access.givePlayerItem("Alex", "stone", 2))
        val block = access.setBlock("world", 1, 64, 1, "stone")
        assertNotNull(block)
        assertEquals("stone", access.blockAt("world", 1, 64, 1)?.blockId)
        assertTrue(access.breakBlock("world", 1, 64, 1, true))
        assertEquals("north", access.blockData("world", 1, 64, 1)?.get("facing"))
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
        override fun findEntity(uuid: String): HostEntitySnapshot? = entities("world").firstOrNull()
        override fun removeEntity(uuid: String): Boolean = true
        override fun entityData(uuid: String): Map<String, String>? = mapOf("variant" to "adult")
        override fun setEntityData(uuid: String, data: Map<String, String>): Map<String, String>? = data
        override fun movePlayer(name: String, location: HostLocationRef): HostPlayerSnapshot? {
            return HostPlayerSnapshot(uuid = "u1", name = name, location = location)
        }
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
