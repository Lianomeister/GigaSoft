package com.gigasoft.standalone

import com.gigasoft.core.GigaStandaloneCore
import com.gigasoft.core.StandaloneCoreConfig
import com.gigasoft.runtime.ReloadStatus
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StandaloneLifecycleIntegrationTest {
    @Test
    fun `standalone core supports scan run adapters and reload lifecycle`() {
        val root = Files.createTempDirectory("gigasoft-standalone-it")
        val pluginsDir = root.resolve("plugins")
        val dataDir = root.resolve("data")
        Files.createDirectories(pluginsDir)
        Files.createDirectories(dataDir)

        val core = GigaStandaloneCore(
            config = StandaloneCoreConfig(
                pluginsDirectory = pluginsDir,
                dataDirectory = dataDir,
                tickPeriodMillis = 1L,
                autoSaveEveryTicks = 0L
            ),
            logger = {}
        )
        core.start()
        try {
            assertTrue(core.plugins().isEmpty())

            writeDemoManifestJar(
                jarPath = pluginsDir.resolve("gigasoft-demo-it.jar"),
                pluginId = "gigasoft-demo"
            )

            assertEquals(1, core.loadNewPlugins())
            assertTrue(core.plugins().any { it.startsWith("gigasoft-demo@") })

            val runResponse = core.run(
                pluginId = "gigasoft-demo",
                sender = "test",
                commandLine = "demo-host"
            )
            assertTrue(runResponse.contains("Host="))

            val adapters = core.adapters("gigasoft-demo")
            assertTrue(adapters.any { it.id == "bridge.host.server" })

            val invoke = core.invokeAdapter(
                pluginId = "gigasoft-demo",
                adapterId = "bridge.host.server",
                action = "server.info",
                payload = emptyMap()
            )
            assertTrue(invoke.success)
            assertEquals("GigaSoft Standalone", invoke.payload["name"])

            val reload = core.reload("gigasoft-demo")
            assertEquals(ReloadStatus.SUCCESS, reload.status)

            val stats = core.run(
                pluginId = "gigasoft-demo",
                sender = "test",
                commandLine = "demo-stats"
            )
            assertTrue(stats.startsWith("Produced:"))
        } finally {
            core.stop()
        }
    }

    @Test
    fun `reload all keeps host event pipeline active`() {
        val root = Files.createTempDirectory("gigasoft-standalone-it-events")
        val pluginsDir = root.resolve("plugins")
        val dataDir = root.resolve("data")
        Files.createDirectories(pluginsDir)
        Files.createDirectories(dataDir)

        val core = GigaStandaloneCore(
            config = StandaloneCoreConfig(
                pluginsDirectory = pluginsDir,
                dataDirectory = dataDir,
                tickPeriodMillis = 1L,
                autoSaveEveryTicks = 0L
            ),
            logger = {}
        )
        core.start()
        try {
            writeDemoManifestJar(
                jarPath = pluginsDir.resolve("gigasoft-demo-it-events.jar"),
                pluginId = "gigasoft-demo"
            )
            assertEquals(1, core.loadNewPlugins())

            core.joinPlayer("Alex")
            val beforeReload = core.run("gigasoft-demo", "test", "demo-joins")
            assertEquals("Joins: 1", beforeReload)

            val reloadAll = core.reloadAll()
            assertEquals(ReloadStatus.SUCCESS, reloadAll.status)

            core.joinPlayer("Steve")
            val afterReload = core.run("gigasoft-demo", "test", "demo-joins")
            assertEquals("Joins: 1", afterReload)
        } finally {
            core.stop()
        }
    }

    private fun writeDemoManifestJar(jarPath: Path, pluginId: String) {
        val yaml = """
            id: $pluginId
            name: GigaSoft Demo
            version: 0.1.0-rc.2
            main: com.gigasoft.demo.DemoGigaPlugin
            apiVersion: 1
            dependencies: []
            permissions: []
        """.trimIndent()
        JarOutputStream(Files.newOutputStream(jarPath)).use { out ->
            out.putNextEntry(JarEntry("gigaplugin.yml"))
            out.write(yaml.toByteArray(Charsets.UTF_8))
            out.closeEntry()
        }
    }
}
