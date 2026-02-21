package com.clockwork.host.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HostAccessAdapterPortContractsTest {
    @Test
    fun `adapter maps world player and entity ports into HostAccess`() {
        val bridge = object : HostBridgePort {
            override fun serverInfo(): HostServerSnapshot {
                return HostServerSnapshot(
                    name = "test",
                    version = "1.0",
                    platformVersion = "standalone",
                    onlinePlayers = 1,
                    maxPlayers = 50,
                    worldCount = 1
                )
            }

            override fun broadcast(message: String) {}

            override fun findPlayer(name: String): HostPlayerSnapshot? {
                if (!name.equals("alex", ignoreCase = true)) return null
                return HostPlayerSnapshot(
                    uuid = "u1",
                    name = "Alex",
                    location = HostLocationRef(world = "world", x = 1.0, y = 64.0, z = 2.0)
                )
            }

            override fun playerInventory(name: String): HostInventorySnapshot? {
                return HostInventorySnapshot(owner = name, size = 36, nonEmptySlots = 3)
            }

            override fun setPlayerInventoryItem(name: String, slot: Int, itemId: String): Boolean = true

            override fun worlds(): List<HostWorldSnapshot> {
                return listOf(HostWorldSnapshot(name = "world", entityCount = 1))
            }

            override fun entities(world: String?): List<HostEntitySnapshot> {
                return listOf(
                    HostEntitySnapshot(
                        uuid = "e1",
                        type = "zombie",
                        location = HostLocationRef(world = world ?: "world", x = 0.0, y = 64.0, z = 0.0)
                    )
                )
            }

            override fun spawnEntity(type: String, location: HostLocationRef): HostEntitySnapshot {
                return HostEntitySnapshot(uuid = "e2", type = type, location = location)
            }
        }

        val host = bridge.asHostAccess()
        val worlds = host.worlds()
        assertEquals(1, worlds.size)
        assertEquals("world", worlds.first().name)

        val player = host.findPlayer("alex")
        assertNotNull(player)
        assertEquals("Alex", player.name)

        val spawned = host.spawnEntity(
            type = "pig",
            location = com.clockwork.api.HostLocationRef(world = "world", x = 4.0, y = 70.0, z = 8.0)
        )
        assertNotNull(spawned)
        assertEquals("pig", spawned.type)

        val listed = host.entities("world")
        assertTrue(listed.isNotEmpty())
        assertEquals("zombie", listed.first().type)
    }
}
