package com.gigasoft.core

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.gigasoft.api.HostMutationBatch
import com.gigasoft.api.HostMutationOp
import com.gigasoft.api.HostMutationType
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
        core1.setPlayerOp("Alex", true)
        core1.grantPlayerPermission("Alex", "plugin.debug")
        core1.setPlayerGameMode("Alex", "creative")
        core1.setPlayerStatus(
            "Alex",
            StandalonePlayerStatus(
                health = 18.0,
                maxHealth = 20.0,
                foodLevel = 16,
                saturation = 3.0,
                experienceLevel = 2,
                experienceProgress = 0.4,
                effects = mapOf("speed" to 120)
            )
        )
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
        assertEquals(true, core2.playerIsOp("Alex"))
        assertEquals(true, core2.hasPlayerPermission("Alex", "plugin.debug"))
        assertEquals("creative", core2.playerGameMode("Alex"))
        assertEquals(18.0, core2.playerStatus("Alex")?.health)
        assertEquals(120, core2.playerStatus("Alex")?.effects?.get("speed"))
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
            assertEquals("normal", core.setWorldData("adventure", mapOf("difficulty" to "normal"), "test")?.get("difficulty"))
            assertEquals("normal", core.worldData("adventure")?.get("difficulty"))
            assertEquals("clear", core.worldWeather("adventure"))
            assertTrue(core.setWorldWeather("adventure", "rain", "test"))
            assertEquals("rain", core.worldWeather("adventure"))

            core.joinPlayer("Alex", "adventure", 0.0, 64.0, 0.0)
            assertTrue(core.setPlayerOp("Alex", true, cause = "test"))
            assertEquals(true, core.playerIsOp("Alex"))
            assertTrue(core.grantPlayerPermission("Alex", "plugin.debug", cause = "test"))
            assertEquals(true, core.hasPlayerPermission("Alex", "plugin.debug"))
            assertTrue(core.revokePlayerPermission("Alex", "plugin.debug", cause = "test"))
            assertEquals(false, core.hasPlayerPermission("Alex", "plugin.debug"))
            assertTrue(core.sendPlayerMessage("Alex", "hello", cause = "test"))
            assertEquals("creative", core.setPlayerGameMode("Alex", "creative", "test"))
            assertEquals("creative", core.playerGameMode("Alex"))
            assertTrue(core.addPlayerEffect("Alex", "speed", 100, amplifier = 1, cause = "test"))
            assertEquals(300, core.playerStatus("Alex")?.effects?.get("speed"))
            assertTrue(core.removePlayerEffect("Alex", "speed", cause = "test"))
            assertEquals(null, core.playerStatus("Alex")?.effects?.get("speed"))
            val updatedStatus = core.setPlayerStatus(
                "Alex",
                StandalonePlayerStatus(
                    health = 15.0,
                    maxHealth = 20.0,
                    foodLevel = 14,
                    saturation = 2.0,
                    experienceLevel = 4,
                    experienceProgress = 0.8,
                    effects = mapOf("regeneration" to 80)
                ),
                cause = "test"
            )
            assertNotNull(updatedStatus)
            assertEquals(15.0, core.playerStatus("Alex")?.health)
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
            assertNotNull(core.kickPlayer("Alex", "bye", cause = "test"))
            assertEquals(null, core.players().find { it.name == "Alex" })
        } finally {
            core.stop()
        }
    }

    @Test
    fun `mutation batch applies atomically on success`() {
        val root = Files.createTempDirectory("gigasoft-core-test-batch-success")
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
            core.joinPlayer("Alex", "world", 0.0, 64.0, 0.0)
            val result = core.applyMutationBatch(
                HostMutationBatch(
                    id = "batch-success",
                    operations = listOf(
                        HostMutationOp(
                            type = HostMutationType.SET_WORLD_TIME,
                            target = "world",
                            longValue = 1234L
                        ),
                        HostMutationOp(
                            type = HostMutationType.SET_PLAYER_INVENTORY_ITEM,
                            target = "Alex",
                            intValue = 0,
                            stringValue = "diamond"
                        )
                    )
                )
            )
            assertTrue(result.success)
            assertEquals(2, result.appliedOperations)
            assertFalse(result.rolledBack)
            assertTrue((core.worldTime("world") ?: 0L) >= 1234L)
            assertEquals("diamond", core.inventoryItem("Alex", 0))
        } finally {
            core.stop()
        }
    }

    @Test
    fun `mutation batch rolls back all changes on failure`() {
        val root = Files.createTempDirectory("gigasoft-core-test-batch-rollback")
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
            core.joinPlayer("Alex", "world", 0.0, 64.0, 0.0)
            core.setInventoryItem("Alex", 0, "stick")
            val baselineTime = core.worldTime("world")
            val result = core.applyMutationBatch(
                HostMutationBatch(
                    id = "batch-rollback",
                    operations = listOf(
                        HostMutationOp(
                            type = HostMutationType.SET_WORLD_TIME,
                            target = "world",
                            longValue = 9000L
                        ),
                        HostMutationOp(
                            type = HostMutationType.SET_PLAYER_INVENTORY_ITEM,
                            target = "Alex",
                            intValue = 0,
                            stringValue = "gold_ingot"
                        ),
                        HostMutationOp(
                            type = HostMutationType.SET_WORLD_TIME,
                            target = "missing-world",
                            longValue = 1L
                        )
                    )
                )
            )
            assertFalse(result.success)
            assertTrue(result.rolledBack)
            assertEquals(2, result.appliedOperations)
            val restoredTime = core.worldTime("world")
            assertTrue(restoredTime != null && restoredTime < 9000L)
            assertTrue((restoredTime ?: 0L) >= (baselineTime ?: 0L))
            assertEquals("stick", core.inventoryItem("Alex", 0))
        } finally {
            core.stop()
        }
    }
}
