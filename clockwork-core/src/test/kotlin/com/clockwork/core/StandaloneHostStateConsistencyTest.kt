package com.clockwork.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class StandaloneHostStateConsistencyTest {
    @Test
    fun `rejoin and move keep player entity and world counters consistent`() {
        val state = StandaloneHostState()
        state.joinPlayer("Alex", "world", 0.0, 64.0, 0.0)
        state.joinPlayer("Alex", "world_nether", 1.0, 70.0, 1.0)
        state.spawnEntity("zombie", "world_nether", 2.0, 70.0, 2.0)
        state.movePlayer("Alex", 3.0, 71.0, 3.0, "world")

        val players = state.players()
        assertEquals(1, players.size)
        val alex = players.first()
        assertEquals("world", alex.world)

        val playerEntity = state.entities().firstOrNull { it.uuid == alex.uuid }
        assertNotNull(playerEntity)
        assertEquals("player", playerEntity.type)
        assertEquals("world", playerEntity.world)
        assertEquals(state.entities("world").size, state.entityCount("world"))
        assertEquals(state.entities("world_nether").size, state.entityCount("world_nether"))
    }

    @Test
    fun `inventory rules enforce slot limits and online owner`() {
        val state = StandaloneHostState()
        assertFalse(state.setInventoryItem("Alex", 0, "stone"))
        state.joinPlayer("Alex", "world", 0.0, 64.0, 0.0)
        assertFalse(state.setInventoryItem("Alex", -1, "stone"))
        assertFalse(state.setInventoryItem("Alex", 36, "stone"))
        assertTrue(state.setInventoryItem("Alex", 0, "stone"))
        assertTrue(state.setInventoryItem("Alex", 0, "air"))
        val inventory = state.inventory("Alex")
        assertNotNull(inventory)
        assertFalse(inventory.slots.containsKey(0))
    }

    @Test
    fun `mod-like host operations are consistent`() {
        val state = StandaloneHostState()
        state.createWorld("mod_world", 99L)
        assertEquals(0L, state.worldTime("mod_world"))
        val updated = state.setWorldTime("mod_world", 1234L)
        assertNotNull(updated)
        assertEquals(1234L, state.worldTime("mod_world"))
        assertEquals(emptyMap(), state.worldData("mod_world"))
        assertEquals("hard", state.setWorldData("mod_world", mapOf("difficulty" to "hard"))?.get("difficulty"))
        assertEquals("hard", state.worldData("mod_world")?.get("difficulty"))
        assertEquals("clear", state.worldWeather("mod_world"))
        assertEquals("rain", state.setWorldWeather("mod_world", "rain"))

        state.joinPlayer("Alex", "mod_world", 0.0, 64.0, 0.0)
        assertEquals(false, state.playerIsOp("Alex"))
        assertEquals(true, state.setPlayerOp("Alex", true))
        assertEquals(true, state.playerIsOp("Alex"))
        assertEquals(emptySet(), state.playerPermissions("Alex"))
        assertTrue(state.grantPlayerPermission("Alex", "plugin.debug"))
        assertEquals(true, state.hasPlayerPermission("Alex", "plugin.debug"))
        assertTrue(state.revokePlayerPermission("Alex", "plugin.debug"))
        assertEquals(false, state.hasPlayerPermission("Alex", "plugin.debug"))
        assertEquals("survival", state.playerGameMode("Alex"))
        assertEquals("creative", state.setPlayerGameMode("Alex", "creative"))
        assertEquals("creative", state.playerGameMode("Alex"))
        assertEquals(20.0, state.playerStatus("Alex")?.health)
        assertTrue(state.addPlayerEffect("Alex", "speed", 120, amplifier = 1))
        assertEquals(320, state.playerStatus("Alex")?.effects?.get("speed"))
        assertTrue(state.removePlayerEffect("Alex", "speed"))
        assertEquals(null, state.playerStatus("Alex")?.effects?.get("speed"))
        val updatedStatus = state.setPlayerStatus(
            "Alex",
            StandalonePlayerStatus(
                health = 17.5,
                maxHealth = 20.0,
                foodLevel = 18,
                saturation = 4.0,
                experienceLevel = 3,
                experienceProgress = 0.25,
                effects = mapOf("regeneration" to 80)
            )
        )
        assertNotNull(updatedStatus)
        assertEquals(17.5, state.playerStatus("Alex")?.health)
        assertEquals(80, state.playerStatus("Alex")?.effects?.get("regeneration"))
        val given = state.givePlayerItem("Alex", "copper_ingot", 3)
        assertEquals(3, given)
        assertEquals("copper_ingot", state.inventoryItem("Alex", 0))
        assertEquals("copper_ingot", state.inventoryItem("Alex", 2))

        val entity = state.spawnEntity("sheep", "mod_world", 1.0, 64.0, 1.0)
        assertEquals(emptyMap(), state.entityData(entity.uuid))
        assertEquals("adult", state.setEntityData(entity.uuid, mapOf("variant" to "adult"))?.get("variant"))
        assertEquals("adult", state.entityData(entity.uuid)?.get("variant"))
        assertNotNull(state.findEntity(entity.uuid))
        val removed = state.removeEntity(entity.uuid)
        assertNotNull(removed)
        assertEquals(null, state.findEntity(entity.uuid))
        assertEquals(null, state.entityData(entity.uuid))

        val block = state.setBlock("mod_world", 1, 64, 1, "stone")
        assertNotNull(block)
        assertEquals("stone", state.blockAt("mod_world", 1, 64, 1)?.blockId)
        assertEquals(emptyMap(), state.blockData("mod_world", 1, 64, 1))
        assertEquals("2", state.setBlockData("mod_world", 1, 64, 1, mapOf("level" to "2"))?.get("level"))
        val broken = state.breakBlock("mod_world", 1, 64, 1)
        assertNotNull(broken)
        assertEquals(null, state.blockAt("mod_world", 1, 64, 1))
        assertEquals(null, state.blockData("mod_world", 1, 64, 1))
    }
}
