package com.gigasoft.core

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
}
