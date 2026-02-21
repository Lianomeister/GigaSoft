package com.clockwork.host.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HostDomainPortsContractTest {
    @Test
    fun `port aliases and null-safe helpers are consistent`() {
        val bridge = object : HostBridgePort {
            override fun serverInfo(): HostServerSnapshot = HostServerSnapshot("host", "1", null, 0, 0, 0)
            override fun broadcast(message: String) {}
            override fun findPlayer(name: String): HostPlayerSnapshot? =
                if (name == "Alex") HostPlayerSnapshot("u1", "Alex", HostLocationRef("world", 0.0, 64.0, 0.0)) else null
            override fun playerIsOp(name: String): Boolean? = false
            override fun playerPermissions(name: String): Set<String>? = setOf("demo.use")
            override fun hasPlayerPermission(name: String, permission: String): Boolean? = permission == "demo.use"
            override fun playerInventory(name: String): HostInventorySnapshot? = HostInventorySnapshot(name, 36, 1)
            override fun setPlayerInventoryItem(name: String, slot: Int, itemId: String): Boolean = true
            override fun worlds(): List<HostWorldSnapshot> = listOf(HostWorldSnapshot("world", 2))
            override fun worldData(name: String): Map<String, String>? = mapOf("difficulty" to "hard")
            override fun entities(world: String?): List<HostEntitySnapshot> = listOf(
                HostEntitySnapshot("e1", "zombie", HostLocationRef(world ?: "world", 1.0, 64.0, 1.0))
            )
            override fun spawnEntity(type: String, location: HostLocationRef): HostEntitySnapshot? = null
            override fun entityData(uuid: String): Map<String, String>? = mapOf("state" to "idle")
        }

        assertEquals("Alex", bridge.lookupPlayer("Alex")?.name)
        assertFalse(bridge.isPlayerOp("Alex"))
        assertEquals(setOf("demo.use"), bridge.permissionsOfPlayer("Alex"))
        assertTrue(bridge.playerHasPermission("Alex", "demo.use"))
        assertEquals(1, bridge.listWorlds().size)
        assertEquals("hard", bridge.worldDataOrEmpty("world")["difficulty"])
        assertEquals(1, bridge.listEntities("world").size)
        assertEquals("idle", bridge.entityDataOrEmpty("e1")["state"])
    }
}

