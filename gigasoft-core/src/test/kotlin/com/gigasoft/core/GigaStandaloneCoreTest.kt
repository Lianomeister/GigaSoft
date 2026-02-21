package com.gigasoft.core

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.nio.file.Files
import kotlin.test.assertFalse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GigaStandaloneCoreTest {
    private val mapper = ObjectMapper().registerKotlinModule()

    @Test
    fun `persists and restores host state`() {
        val root = Files.createTempDirectory("gigasoft-core-test")
        val plugins = root.resolve("plugins")
        val data = root.resolve("data")

        val core1 = GigaStandaloneCore(
            config = StandaloneCoreConfig(
                pluginsDirectory = plugins,
                dataDirectory = data,
                tickPeriodMillis = 1L,
                autoSaveEveryTicks = 0L
            ),
            logger = {}
        )
        core1.start()
        core1.createWorld("test-world", 42L)
        core1.joinPlayer("Alex", "test-world", 10.0, 70.0, 5.0)
        core1.setInventoryItem("Alex", 0, "iron_ingot")
        core1.spawnEntity("sheep", "test-world", 1.0, 65.0, 1.0)
        core1.saveState()
        core1.stop()

        val core2 = GigaStandaloneCore(
            config = StandaloneCoreConfig(
                pluginsDirectory = plugins,
                dataDirectory = data,
                tickPeriodMillis = 1L,
                autoSaveEveryTicks = 0L
            ),
            logger = {}
        )
        core2.start()

        val alex = core2.players().find { it.name == "Alex" }
        assertNotNull(alex)
        assertEquals("test-world", alex.world)
        assertTrue(core2.worlds().any { it.name == "test-world" })
        assertTrue(core2.entities("test-world").any { it.type == "sheep" })
        assertEquals("iron_ingot", core2.inventory("Alex")?.slots?.get(0))
        core2.stop()
    }

    @Test
    fun `serializes external mutations through core queue`() {
        val root = Files.createTempDirectory("gigasoft-core-test-queue")
        val core = GigaStandaloneCore(
            config = StandaloneCoreConfig(
                pluginsDirectory = root.resolve("plugins"),
                dataDirectory = root.resolve("data"),
                tickPeriodMillis = 1L,
                autoSaveEveryTicks = 0L
            ),
            logger = {}
        )
        core.start()

        val threads = (1..20).map { idx ->
            Thread {
                val name = "P$idx"
                core.joinPlayer(name, "world", idx.toDouble(), 64.0, idx.toDouble())
                core.movePlayer(name, idx + 1.0, 65.0, idx + 1.0, "world")
            }.apply { start() }
        }
        threads.forEach { it.join() }

        assertEquals(20, core.players().size)
        val status = core.status()
        assertTrue(status.queuedMutations >= 0)
        core.stop()
    }

    @Test
    fun `restore sanitizes inconsistent snapshot and keeps host state consistent`() {
        val root = Files.createTempDirectory("gigasoft-core-test-restore-sanitize")
        val plugins = root.resolve("plugins")
        val data = root.resolve("data")
        Files.createDirectories(data)
        val stateFile = data.resolve("standalone-state.json")

        val dirtySnapshot = StandaloneHostSnapshot(
            worlds = listOf(
                StandaloneWorld(name = "Alpha", seed = 1L, time = 5L),
                StandaloneWorld(name = "alpha", seed = 99L, time = 6L),
                StandaloneWorld(name = "   ", seed = 0L, time = 0L)
            ),
            players = listOf(
                StandalonePlayer(uuid = "p1", name = "Alex", world = "Alpha", x = 1.0, y = 64.0, z = 1.0),
                StandalonePlayer(uuid = "p2", name = "Bob", world = "MissingWorld", x = 2.0, y = 64.0, z = 2.0),
                StandalonePlayer(uuid = " ", name = "NoUuid", world = "Alpha", x = 0.0, y = 0.0, z = 0.0)
            ),
            entities = listOf(
                StandaloneEntity(uuid = "e1", type = "sheep", world = "MissingWorld", x = 0.0, y = 65.0, z = 0.0),
                StandaloneEntity(uuid = "p1", type = "zombie", world = "Alpha", x = 99.0, y = 99.0, z = 99.0),
                StandaloneEntity(uuid = " ", type = "cow", world = "Alpha", x = 0.0, y = 0.0, z = 0.0)
            ),
            inventories = mapOf(
                "Alex" to mapOf(0 to "iron_ingot", 99 to "bad_slot", 1 to "air"),
                "Ghost" to mapOf(0 to "stone")
            )
        )
        val envelope = StandaloneStateEnvelope(schemaVersion = 1, snapshot = dirtySnapshot)
        Files.newOutputStream(stateFile).use { mapper.writerWithDefaultPrettyPrinter().writeValue(it, envelope) }

        val core = GigaStandaloneCore(
            config = StandaloneCoreConfig(
                pluginsDirectory = plugins,
                dataDirectory = data,
                tickPeriodMillis = 1000L,
                autoSaveEveryTicks = 0L
            ),
            logger = {}
        )
        core.start()
        try {
            val worlds = core.worlds()
            val players = core.players()
            val entities = core.entities()

            assertTrue(worlds.any { it.name.equals("Alpha", ignoreCase = true) })
            assertTrue(worlds.any { it.name.equals("MissingWorld", ignoreCase = true) })
            assertEquals(2, players.size)

            players.forEach { player ->
                assertTrue(worlds.any { it.name.equals(player.world, ignoreCase = true) })
                val playerEntity = entities.find { it.uuid == player.uuid }
                assertNotNull(playerEntity)
                assertEquals("player", playerEntity.type.lowercase())
                assertEquals(player.world, playerEntity.world)
                assertEquals(player.x, playerEntity.x)
                assertEquals(player.y, playerEntity.y)
                assertEquals(player.z, playerEntity.z)
            }

            entities.forEach { entity ->
                assertTrue(worlds.any { it.name.equals(entity.world, ignoreCase = true) })
            }

            val alexInv = core.inventory("Alex")
            assertNotNull(alexInv)
            assertEquals("iron_ingot", alexInv.slots[0])
            assertFalse(alexInv.slots.containsKey(99))
            assertEquals(null, core.inventory("Ghost"))
        } finally {
            core.stop()
        }
    }

    @Test
    fun `core views are deterministic and stable across save and restore`() {
        val root = Files.createTempDirectory("gigasoft-core-test-deterministic")
        val plugins = root.resolve("plugins")
        val data = root.resolve("data")

        val core1 = GigaStandaloneCore(
            config = StandaloneCoreConfig(
                pluginsDirectory = plugins,
                dataDirectory = data,
                tickPeriodMillis = 1000L,
                autoSaveEveryTicks = 0L
            ),
            logger = {}
        )
        core1.start()
        core1.joinPlayer("zoe", "Beta", 1.0, 64.0, 1.0)
        core1.joinPlayer("Alex", "alpha", 2.0, 64.0, 2.0)
        core1.spawnEntity("Zombie", "Beta", 3.0, 64.0, 3.0)
        core1.spawnEntity("cow", "alpha", 4.0, 64.0, 4.0)
        core1.saveState()
        val worldsFirst = core1.worlds()
        val playersFirst = core1.players()
        val entitiesFirst = core1.entities()
        val worldsSecond = core1.worlds()
        val playersSecond = core1.players()
        val entitiesSecond = core1.entities()
        assertEquals(worldsFirst, worldsSecond)
        assertEquals(playersFirst, playersSecond)
        assertEquals(entitiesFirst, entitiesSecond)
        assertEquals(worldsFirst.map { it.name.lowercase() }, worldsFirst.map { it.name.lowercase() }.sorted())
        assertEquals(playersFirst.map { it.name.lowercase() }, playersFirst.map { it.name.lowercase() }.sorted())
        core1.stop()

        val core2 = GigaStandaloneCore(
            config = StandaloneCoreConfig(
                pluginsDirectory = plugins,
                dataDirectory = data,
                tickPeriodMillis = 1000L,
                autoSaveEveryTicks = 0L
            ),
            logger = {}
        )
        core2.start()
        try {
            assertEquals(worldsFirst, core2.worlds())
            assertEquals(playersFirst, core2.players())
            assertEquals(entitiesFirst, core2.entities())
        } finally {
            core2.stop()
        }
    }

    @Test
    fun `extended host gameplay operations work through core api`() {
        val root = Files.createTempDirectory("gigasoft-core-test-host-extended")
        val core = GigaStandaloneCore(
            config = StandaloneCoreConfig(
                pluginsDirectory = root.resolve("plugins"),
                dataDirectory = root.resolve("data"),
                tickPeriodMillis = 1000L,
                autoSaveEveryTicks = 0L
            ),
            logger = {}
        )
        core.start()
        try {
            core.createWorld("adventure", 10L)
            assertNotNull(core.worldTime("adventure"))
            assertTrue(core.setWorldTime("adventure", 6000L))
            assertTrue((core.worldTime("adventure") ?: 0L) >= 6000L)

            core.joinPlayer("Alex", "adventure", 0.0, 64.0, 0.0)
            assertEquals(2, core.givePlayerItem("Alex", "iron_ingot", 2))
            assertEquals("iron_ingot", core.inventoryItem("Alex", 0))

            core.movePlayerWithCause("Alex", 3.0, 70.0, 3.0, "adventure", "test")
            assertEquals("adventure", core.players().first { it.name == "Alex" }.world)

            val entity = core.spawnEntity("zombie", "adventure", 1.0, 64.0, 1.0)
            assertEquals("1", core.setEntityData(entity.uuid, mapOf("stage" to "1"), "test")?.get("stage"))
            assertEquals("1", core.entityData(entity.uuid)?.get("stage"))
            assertNotNull(core.findEntity(entity.uuid))
            val removed = core.removeEntity(entity.uuid, "test")
            assertNotNull(removed)
            assertEquals(null, core.findEntity(entity.uuid))
            assertEquals(null, core.entityData(entity.uuid))

            val block = core.setBlock("adventure", 1, 64, 1, "stone", "test")
            assertNotNull(block)
            assertEquals("stone", core.blockAt("adventure", 1, 64, 1)?.blockId)
            assertEquals("3", core.setBlockData("adventure", 1, 64, 1, mapOf("level" to "3"), "test")?.get("level"))
            assertEquals("3", core.blockData("adventure", 1, 64, 1)?.get("level"))
            assertTrue(core.breakBlock("adventure", 1, 64, 1, dropLoot = false, cause = "test"))
            assertEquals(null, core.blockAt("adventure", 1, 64, 1))
            assertEquals(null, core.blockData("adventure", 1, 64, 1))
        } finally {
            core.stop()
        }
    }
}
