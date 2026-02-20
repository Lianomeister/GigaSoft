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
    }
}
