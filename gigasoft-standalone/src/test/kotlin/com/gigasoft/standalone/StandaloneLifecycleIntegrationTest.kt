package com.gigasoft.standalone

import com.gigasoft.api.CommandSender
import com.gigasoft.core.GigaStandaloneCore
import com.gigasoft.core.StandaloneCoreConfig
import com.gigasoft.runtime.ReloadStatus
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
                pluginId = "gigasoft-demo",
                permissions = listOf(
                    "host.server.read",
                    "host.server.broadcast",
                    "host.player.message",
                    "host.world.write",
                    "host.mutation.batch"
                )
            )

            assertEquals(1, core.loadNewPlugins())
            assertTrue(core.plugins().any { it.startsWith("gigasoft-demo@") })
            core.joinPlayer("Alex")

            val runResponse = core.run(
                pluginId = "gigasoft-demo",
                sender = sender(),
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
                sender = sender(),
                commandLine = "demo-stats"
            )
            assertTrue(stats.startsWith("Produced:"))

            val assets = core.run(
                pluginId = "gigasoft-demo",
                sender = sender(),
                commandLine = "demo-assets"
            )
            assertTrue(assets.contains("Assets=valid"))

            val network = core.run(
                pluginId = "gigasoft-demo",
                sender = sender(),
                commandLine = "demo-network chat hello"
            )
            assertTrue(network.contains("Network status=ACCEPTED"))

            val notify = core.run(
                pluginId = "gigasoft-demo",
                sender = sender(),
                commandLine = "demo-notify Alex success Welcome"
            )
            assertEquals("UI notice delivered to 'Alex'", notify)

            val actionBar = core.run(
                pluginId = "gigasoft-demo",
                sender = sender(),
                commandLine = "demo-actionbar Alex Run"
            )
            assertEquals("Actionbar delivered to 'Alex'", actionBar)

            val menu = core.run(
                pluginId = "gigasoft-demo",
                sender = sender(),
                commandLine = "demo-menu Alex"
            )
            assertEquals("Menu opened for 'Alex'", menu)

            val dialog = core.run(
                pluginId = "gigasoft-demo",
                sender = sender(),
                commandLine = "demo-dialog Alex"
            )
            assertEquals("Dialog opened for 'Alex'", dialog)

            val uiClose = core.run(
                pluginId = "gigasoft-demo",
                sender = sender(),
                commandLine = "demo-ui-close Alex"
            )
            assertEquals("UI closed for 'Alex'", uiClose)

            val chat = core.run(
                pluginId = "gigasoft-demo",
                sender = sender(),
                commandLine = "demo-chat broadcast hello"
            )
            assertEquals("Broadcast sent", chat)

            val worldTime = core.run(
                pluginId = "gigasoft-demo",
                sender = sender(),
                commandLine = "demo-time world 6000"
            )
            assertEquals("World 'world' time updated to 6000", worldTime)
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
            val beforeReload = core.run("gigasoft-demo", sender(), "demo-joins")
            assertEquals("Joins=1", beforeReload)

            val reloadAll = core.reloadAll()
            assertEquals(ReloadStatus.SUCCESS, reloadAll.status)

            core.joinPlayer("Steve")
            val afterReload = core.run("gigasoft-demo", sender(), "demo-joins")
            assertEquals("Joins=1", afterReload)
        } finally {
            core.stop()
        }
    }

    @Test
    fun `host access and bridge adapters are denied without permissions`() {
        val root = Files.createTempDirectory("gigasoft-standalone-it-perms")
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
                jarPath = pluginsDir.resolve("gigasoft-demo-it-perms.jar"),
                pluginId = "gigasoft-demo",
                permissions = emptyList()
            )
            assertEquals(1, core.loadNewPlugins())

            val runResponse = core.run(
                pluginId = "gigasoft-demo",
                sender = sender(),
                commandLine = "demo-host"
            )
            assertEquals("[E_PERMISSION] Missing permission 'host.server.read' for command 'demo-host'", runResponse)

            val invoke = core.invokeAdapter(
                pluginId = "gigasoft-demo",
                adapterId = "bridge.host.server",
                action = "server.info",
                payload = emptyMap()
            )
            assertFalse(invoke.success)
            assertTrue((invoke.message ?: "").contains("host.server.read"))
        } finally {
            core.stop()
        }
    }

    @Test
    fun `sync reloads changed plugin jar for dev workflow`() {
        val root = Files.createTempDirectory("gigasoft-standalone-it-sync")
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
            val jar = pluginsDir.resolve("gigasoft-demo-it-sync.jar")
            writeDemoManifestJar(jarPath = jar, pluginId = "gigasoft-demo", version = "1.0.0")
            assertEquals(1, core.loadNewPlugins())
            assertTrue(core.plugins().any { it == "gigasoft-demo@1.0.0" })

            writeDemoManifestJar(jarPath = jar, pluginId = "gigasoft-demo", version = "1.0.1")
            val sync = core.syncPlugins()
            assertEquals(ReloadStatus.SUCCESS, sync.reloadStatus)
            assertTrue(sync.reloadedPlugins.contains("gigasoft-demo"))
            assertTrue(core.plugins().any { it == "gigasoft-demo@1.0.1" })
        } finally {
            core.stop()
        }
    }

    private fun writeDemoManifestJar(
        jarPath: Path,
        pluginId: String,
        version: String = "1.0.0",
        permissions: List<String> = listOf("host.server.read")
    ) {
        val lines = mutableListOf(
            "id: $pluginId",
            "name: GigaSoft Demo",
            "version: $version",
            "main: com.gigasoft.demo.DemoGigaPlugin",
            "apiVersion: 1",
            "dependencies: []"
        )
        if (permissions.isEmpty()) {
            lines += "permissions: []"
        } else {
            lines += "permissions:"
            permissions.forEach { lines += "  - $it" }
        }
        val yaml = lines.joinToString("\n")
        JarOutputStream(Files.newOutputStream(jarPath)).use { out ->
            out.putNextEntry(JarEntry("gigaplugin.yml"))
            out.write(yaml.toByteArray(Charsets.UTF_8))
            out.closeEntry()
        }
    }

    private fun sender(): CommandSender = CommandSender.system("test")
}
