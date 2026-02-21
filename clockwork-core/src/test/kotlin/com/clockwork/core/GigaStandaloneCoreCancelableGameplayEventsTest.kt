package com.clockwork.core

import com.clockwork.api.GigaBlockBreakPostEvent
import com.clockwork.api.GigaBlockBreakPreEvent
import com.clockwork.api.GigaEntitySpawnPostEvent
import com.clockwork.api.GigaEntitySpawnPreEvent
import com.clockwork.api.GigaPlayerMovePostEvent
import com.clockwork.api.GigaPlayerMovePreEvent
import com.clockwork.api.GigaPlugin
import com.clockwork.api.PluginContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GigaStandaloneCoreCancelableGameplayEventsTest {
    @Test
    fun `gameplay pre post events support cancel and override behavior`() {
        CancelableGameplayEventProbe.reset()
        val root = Files.createTempDirectory("clockwork-core-cancelable-events")
        val plugins = root.resolve("plugins")
        val data = root.resolve("data")
        Files.createDirectories(plugins)
        Files.createDirectories(data)
        createPluginJar(
            targetJar = plugins.resolve("cancelable-events.jar"),
            pluginId = "cancelable-events",
            mainClass = "com.clockwork.core.CancelableGameplayEventsPlugin",
            version = "1.1.0-test"
        )

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
            core.joinPlayer("Alex", "world", 0.0, 64.0, 0.0)
            core.setBlock("world", 1, 64, 1, "stone", cause = "test")

            val moved = core.movePlayerWithCause("Alex", 10.0, 70.0, 10.0, "world", cause = "test")
            assertNull(moved)
            val alex = core.players().first { it.name == "Alex" }
            assertEquals(0.0, alex.x)
            assertEquals(64.0, alex.y)
            assertEquals(0.0, alex.z)
            assertTrue(CancelableGameplayEventProbe.lastMovePostCancelled)

            val spawned = core.spawnEntity("zombie", "world", 2.0, 64.0, 2.0)
            assertEquals("sheep", spawned.type.lowercase())
            assertEquals("sheep", CancelableGameplayEventProbe.lastSpawnedType)
            assertFalse(CancelableGameplayEventProbe.lastSpawnPostCancelled)

            val broke = core.breakBlock("world", 1, 64, 1, dropLoot = true, cause = "test")
            assertFalse(broke)
            assertNotNull(core.blockAt("world", 1, 64, 1))
            assertTrue(CancelableGameplayEventProbe.lastBreakPostCancelled)
        } finally {
            core.stop()
        }
    }

    private fun createPluginJar(
        targetJar: Path,
        pluginId: String,
        mainClass: String,
        version: String
    ) {
        Files.createDirectories(targetJar.parent)
        JarOutputStream(Files.newOutputStream(targetJar)).use { jar ->
            jar.putNextEntry(JarEntry("gigaplugin.yml"))
            jar.write(
                """
                id: $pluginId
                name: $pluginId
                version: $version
                main: $mainClass
                apiVersion: 1
                dependencies: []
                permissions: []
                """.trimIndent().toByteArray()
            )
            jar.closeEntry()
        }
    }
}

object CancelableGameplayEventProbe {
    @Volatile
    var lastMovePostCancelled: Boolean = false

    @Volatile
    var lastSpawnPostCancelled: Boolean = false

    @Volatile
    var lastSpawnedType: String? = null

    @Volatile
    var lastBreakPostCancelled: Boolean = false

    fun reset() {
        lastMovePostCancelled = false
        lastSpawnPostCancelled = false
        lastSpawnedType = null
        lastBreakPostCancelled = false
    }
}

class CancelableGameplayEventsPlugin : GigaPlugin {
    override fun onEnable(ctx: PluginContext) {
        ctx.events.subscribe(GigaPlayerMovePreEvent::class.java) { event ->
            if (event.player.name.equals("alex", ignoreCase = true)) {
                event.cancelled = true
                event.cancelReason = "blocked-by-test-plugin"
            }
        }
        ctx.events.subscribe(GigaPlayerMovePostEvent::class.java) { event ->
            CancelableGameplayEventProbe.lastMovePostCancelled = event.cancelled
        }
        ctx.events.subscribe(GigaEntitySpawnPreEvent::class.java) { event ->
            event.entityType = "sheep"
        }
        ctx.events.subscribe(GigaEntitySpawnPostEvent::class.java) { event ->
            CancelableGameplayEventProbe.lastSpawnPostCancelled = event.cancelled
            CancelableGameplayEventProbe.lastSpawnedType = event.entity?.type
        }
        ctx.events.subscribe(GigaBlockBreakPreEvent::class.java) { event ->
            if (event.world.equals("world", ignoreCase = true) && event.x == 1 && event.y == 64 && event.z == 1) {
                event.cancelled = true
                event.cancelReason = "protected-by-test-plugin"
            }
        }
        ctx.events.subscribe(GigaBlockBreakPostEvent::class.java) { event ->
            CancelableGameplayEventProbe.lastBreakPostCancelled = event.cancelled
        }
    }

    override fun onDisable(ctx: PluginContext) {
    }
}
