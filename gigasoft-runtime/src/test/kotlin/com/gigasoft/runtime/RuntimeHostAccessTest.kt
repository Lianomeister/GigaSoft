package com.gigasoft.runtime

import com.gigasoft.api.GigaLogger
import com.gigasoft.api.HostAccess
import com.gigasoft.api.HostEntitySnapshot
import com.gigasoft.api.HostLocationRef
import com.gigasoft.api.HostPlayerSnapshot
import com.gigasoft.api.HostServerSnapshot
import com.gigasoft.api.HostWorldSnapshot
import com.gigasoft.api.HostInventorySnapshot
import com.gigasoft.api.HostPermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RuntimeHostAccessTest {
    @Test
    fun `new host operations honor permission checks`() {
        val delegate = FakeHostAccess()
        val access = RuntimeHostAccess(
            delegate = delegate,
            pluginId = "plugin-x",
            rawPermissions = listOf(
                HostPermissions.WORLD_WRITE,
                HostPermissions.WORLD_READ,
                HostPermissions.ENTITY_REMOVE,
                HostPermissions.PLAYER_MOVE,
                HostPermissions.INVENTORY_WRITE
            ),
            logger = GigaLogger { }
        )

        assertNotNull(access.createWorld("mod", 1L))
        assertEquals(50L, access.worldTime("mod"))
        assertTrue(access.setWorldTime("mod", 200L))
        assertTrue(access.removeEntity("e1"))
        assertNotNull(access.movePlayer("Alex", HostLocationRef("mod", 3.0, 64.0, 3.0)))
        assertEquals(2, access.givePlayerItem("Alex", "iron_ingot", 2))
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
        override fun findEntity(uuid: String): HostEntitySnapshot? {
            return HostEntitySnapshot(uuid, "zombie", HostLocationRef("world", 0.0, 64.0, 0.0))
        }
        override fun removeEntity(uuid: String): Boolean = true
        override fun movePlayer(name: String, location: HostLocationRef): HostPlayerSnapshot? {
            return HostPlayerSnapshot("u1", name, location)
        }
        override fun inventoryItem(name: String, slot: Int): String? = "stone"
        override fun givePlayerItem(name: String, itemId: String, count: Int): Int = count
    }
}
