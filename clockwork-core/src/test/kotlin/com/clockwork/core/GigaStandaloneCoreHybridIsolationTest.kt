package com.clockwork.core

import com.clockwork.api.GigaPlayerMovePreEvent
import com.clockwork.api.GigaPlugin
import com.clockwork.api.PluginContext
import com.clockwork.runtime.EventDispatchMode
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GigaStandaloneCoreHybridIsolationTest {
    @Test
    fun `hybrid dispatch keeps exact first and long-running failures are isolated`() {
        HybridIsolationProbe.reset()
        val root = Files.createTempDirectory("clockwork-core-hybrid-isolation")
        val plugins = root.resolve("plugins")
        val data = root.resolve("data")
        Files.createDirectories(plugins)
        Files.createDirectories(data)
        createPluginJar(
            targetJar = plugins.resolve("hybrid-isolation.jar"),
            pluginId = "hybrid-isolation",
            mainClass = "com.clockwork.core.HybridIsolationPlugin",
            version = "1.1.0-test"
        )

        val core = GigaStandaloneCore(
            config = StandaloneCoreConfig(
                pluginsDirectory = plugins,
                dataDirectory = data,
                tickPeriodMillis = 1L,
                autoSaveEveryTicks = 0L,
                eventDispatchMode = EventDispatchMode.HYBRID,
                systemIsolationFailureThreshold = 1,
                systemIsolationBaseCooldownTicks = 3L,
                systemIsolationMaxCooldownTicks = 16L
            ),
            logger = {}
        )
        core.start()
        try {
            core.joinPlayer("Alex", "world", 0.0, 64.0, 0.0)
            val moved = core.movePlayerWithCause("Alex", 2.0, 65.0, 2.0, "world", cause = "test")
            assertNull(moved)

            // In HYBRID mode exact listener should run before Any/supertype listener.
            assertEquals(listOf("exact", "any"), HybridIsolationProbe.movePreDispatchOrder.toList())

            val isolated = waitFor(2_000L) {
                val profile = core.profile("hybrid-isolation") ?: return@waitFor false
                profile.isolatedSystems.any { it.systemId == "always-fail" && it.isolationCount > 0L }
            }
            assertTrue(isolated, "Expected system isolation snapshot for always-fail system")

            val profile = core.profile("hybrid-isolation")
            val runs = profile?.systems?.get("always-fail")?.runs ?: 0L
            assertTrue(core.status().tickCount > runs, "Isolation should reduce failing system executions across ticks")
        } finally {
            core.stop()
        }
    }

    private fun waitFor(timeoutMillis: Long, condition: () -> Boolean): Boolean {
        val started = System.currentTimeMillis()
        while (System.currentTimeMillis() - started < timeoutMillis) {
            if (condition()) return true
            Thread.sleep(20L)
        }
        return false
    }

    private fun createPluginJar(
        targetJar: Path,
        pluginId: String,
        mainClass: String,
        version: String
    ) {
        Files.createDirectories(targetJar.parent)
        JarOutputStream(Files.newOutputStream(targetJar)).use { jar ->
            jar.putNextEntry(JarEntry("clockworkplugin.yml"))
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

object HybridIsolationProbe {
    val movePreDispatchOrder: MutableList<String> = Collections.synchronizedList(mutableListOf())

    fun reset() {
        movePreDispatchOrder.clear()
    }
}

class HybridIsolationPlugin : GigaPlugin {
    override fun onEnable(ctx: PluginContext) {
        ctx.events.subscribe(GigaPlayerMovePreEvent::class.java) { event ->
            HybridIsolationProbe.movePreDispatchOrder += "exact"
            event.cancelled = true
            event.cancelReason = "blocked by hybrid test policy"
        }
        ctx.events.subscribe(Any::class.java) { any ->
            if (any is GigaPlayerMovePreEvent) {
                HybridIsolationProbe.movePreDispatchOrder += "any"
            }
        }
        ctx.registry.registerSystem("always-fail") {
            error("intentional-failure-for-isolation-test")
        }
    }

    override fun onDisable(ctx: PluginContext) {
    }
}
