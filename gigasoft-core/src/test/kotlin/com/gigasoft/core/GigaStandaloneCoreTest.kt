package com.gigasoft.core

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GigaStandaloneCoreTest {
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
}
