package com.clockwork.standalone

import com.fasterxml.jackson.databind.ObjectMapper
import com.clockwork.api.CommandSender
import com.clockwork.core.GigaStandaloneCore
import com.clockwork.core.StandaloneCoreConfig
import com.clockwork.runtime.ReloadStatus
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StandaloneLifecycleIntegrationTest {
    private val objectMapper = ObjectMapper()

    @Test
    fun `standalone core supports scan run adapters and reload lifecycle`() {
        val root = Files.createTempDirectory("clockwork-standalone-it")
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
                jarPath = pluginsDir.resolve("clockwork-demo-it.jar"),
                pluginId = "clockwork-demo",
                permissions = listOf(
                    "host.server.read",
                    "host.server.broadcast",
                    "host.player.message",
                    "host.world.read",
                    "host.world.write",
                    "host.mutation.batch"
                )
            )

            assertEquals(1, core.loadNewPlugins())
            assertTrue(core.plugins().any { it.startsWith("clockwork-demo@") })
            core.joinPlayer("Alex")

            val runResponse = core.run(
                pluginId = "clockwork-demo",
                sender = sender(),
                commandLine = "demo-host"
            )
            assertTrue(runResponse.contains("Host="))

            val adapters = core.adapters("clockwork-demo")
            assertTrue(adapters.any { it.id == "bridge.host.server" })
            assertTrue(adapters.any { it.id == "bridge.host.world" })
            assertTrue(adapters.any { it.id == "bridge.host.entity" })
            assertTrue(adapters.any { it.id == "bridge.host.inventory" })

            val invoke = core.invokeAdapter(
                pluginId = "clockwork-demo",
                adapterId = "bridge.host.server",
                action = "server.info",
                payload = emptyMap()
            )
            assertTrue(invoke.success)
            assertEquals("Clockwork Standalone", invoke.payload["name"])

            val worldInvoke = core.invokeAdapter(
                pluginId = "clockwork-demo",
                adapterId = "bridge.host.world",
                action = "world.list",
                payload = emptyMap()
            )
            assertTrue(worldInvoke.success)
            assertEquals("1", worldInvoke.payload["count"])

            val reload = core.reload("clockwork-demo")
            assertEquals(ReloadStatus.SUCCESS, reload.status)

            val stats = core.run(
                pluginId = "clockwork-demo",
                sender = sender(),
                commandLine = "demo-stats"
            )
            assertTrue(stats.startsWith("Produced:"))

            val assets = core.run(
                pluginId = "clockwork-demo",
                sender = sender(),
                commandLine = "demo-assets"
            )
            assertTrue(assets.contains("Assets=valid"))

            val network = core.run(
                pluginId = "clockwork-demo",
                sender = sender(),
                commandLine = "demo-network chat hello"
            )
            assertTrue(network.contains("Network status=ACCEPTED"))

            val machineStatus = core.run(
                pluginId = "clockwork-demo",
                sender = sender(),
                commandLine = "demo-machine status"
            )
            assertTrue(machineStatus.contains("Machine=crusher_machine"))

            val burst = core.run(
                pluginId = "clockwork-demo",
                sender = sender(),
                commandLine = "demo-network-burst 4 metrics"
            )
            assertTrue(burst.contains("Burst sent count=4"))

            val uiTour = core.run(
                pluginId = "clockwork-demo",
                sender = sender(),
                commandLine = "demo-ui-tour Alex"
            )
            assertTrue(uiTour.contains("UI tour delivered"))

            val notify = core.run(
                pluginId = "clockwork-demo",
                sender = sender(),
                commandLine = "demo-notify Alex success Welcome"
            )
            assertEquals("UI notice delivered to 'Alex'", notify)

            val actionBar = core.run(
                pluginId = "clockwork-demo",
                sender = sender(),
                commandLine = "demo-actionbar Alex Run"
            )
            assertEquals("Actionbar delivered to 'Alex'", actionBar)

            val menu = core.run(
                pluginId = "clockwork-demo",
                sender = sender(),
                commandLine = "demo-menu Alex"
            )
            assertEquals("Menu opened for 'Alex'", menu)

            val dialog = core.run(
                pluginId = "clockwork-demo",
                sender = sender(),
                commandLine = "demo-dialog Alex"
            )
            assertEquals("Dialog opened for 'Alex'", dialog)

            val uiClose = core.run(
                pluginId = "clockwork-demo",
                sender = sender(),
                commandLine = "demo-ui-close Alex"
            )
            assertEquals("UI closed for 'Alex'", uiClose)

            val chat = core.run(
                pluginId = "clockwork-demo",
                sender = sender(),
                commandLine = "demo-chat broadcast hello"
            )
            assertEquals("Broadcast sent", chat)

            val worldTime = core.run(
                pluginId = "clockwork-demo",
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
        val root = Files.createTempDirectory("clockwork-standalone-it-events")
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
                jarPath = pluginsDir.resolve("clockwork-demo-it-events.jar"),
                pluginId = "clockwork-demo"
            )
            assertEquals(1, core.loadNewPlugins())

            core.joinPlayer("Alex")
            val beforeReload = core.run("clockwork-demo", sender(), "demo-joins")
            assertEquals("Joins=1", beforeReload)

            val reloadAll = core.reloadAll()
            assertEquals(ReloadStatus.SUCCESS, reloadAll.status)

            core.joinPlayer("Steve")
            val afterReload = core.run("clockwork-demo", sender(), "demo-joins")
            assertEquals("Joins=1", afterReload)
        } finally {
            core.stop()
        }
    }

    @Test
    fun `host access and bridge adapters are denied without permissions`() {
        val root = Files.createTempDirectory("clockwork-standalone-it-perms")
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
                jarPath = pluginsDir.resolve("clockwork-demo-it-perms.jar"),
                pluginId = "clockwork-demo",
                permissions = emptyList()
            )
            assertEquals(1, core.loadNewPlugins())

            val runResponse = core.run(
                pluginId = "clockwork-demo",
                sender = sender(),
                commandLine = "demo-host"
            )
            assertEquals("[E_PERMISSION] Missing permission 'host.server.read' for command 'demo-host'", runResponse)

            val invoke = core.invokeAdapter(
                pluginId = "clockwork-demo",
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
        val root = Files.createTempDirectory("clockwork-standalone-it-sync")
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
            val jar = pluginsDir.resolve("clockwork-demo-it-sync.jar")
            writeDemoManifestJar(jarPath = jar, pluginId = "clockwork-demo", version = "1.0.0")
            assertEquals(1, core.loadNewPlugins())
            assertTrue(core.plugins().any { it == "clockwork-demo@1.0.0" })

            writeDemoManifestJar(jarPath = jar, pluginId = "clockwork-demo", version = "1.0.1")
            val sync = core.syncPlugins()
            assertEquals(ReloadStatus.SUCCESS, sync.reloadStatus)
            assertTrue(sync.reloadedPlugins.contains("clockwork-demo"))
            assertTrue(core.plugins().any { it == "clockwork-demo@1.0.1" })
        } finally {
            core.stop()
        }
    }

    @Test
    fun `adapters json output has stable schema`() {
        val root = Files.createTempDirectory("clockwork-standalone-it-adapters-json")
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
                jarPath = pluginsDir.resolve("clockwork-demo-it-adapters-json.jar"),
                pluginId = "clockwork-demo"
            )
            assertEquals(1, core.loadNewPlugins())

            val json = objectMapper.writeValueAsString(core.adapters("clockwork-demo"))
            val tree = objectMapper.readTree(json)
            assertTrue(tree.isArray)
            assertTrue(tree.size() >= 1)
            val first = tree.first()
            assertTrue(first.has("id"))
            assertTrue(first.has("name"))
            assertTrue(first.has("version"))
            assertTrue(first.has("capabilities"))
            assertTrue(first.get("capabilities").isArray)
        } finally {
            core.stop()
        }
    }

    @Test
    fun `adapter invoke json output and permission gates are enforced`() {
        runAdapterInvokeScenario(
            permissions = listOf("adapter.invoke.bridge.host.server", "host.server.read"),
            assertResult = { allowed ->
                val allowedJson = objectMapper.readTree(objectMapper.writeValueAsString(allowed))
                assertTrue(allowedJson.has("success"))
                assertTrue(allowedJson.has("payload"))
                assertTrue(allowedJson.path("success").asBoolean())
                assertEquals("Clockwork Standalone", allowedJson.path("payload").path("name").asText())
            }
        )

        runAdapterInvokeScenario(
            permissions = listOf("adapter.invoke.bridge.host.player", "host.server.read"),
            assertResult = { denied ->
                val deniedJson = objectMapper.readTree(objectMapper.writeValueAsString(denied))
                assertTrue(deniedJson.has("success"))
                assertTrue(deniedJson.has("message"))
                assertTrue(!deniedJson.path("success").asBoolean())
                assertTrue(deniedJson.path("message").asText().contains("not allowed"))
            }
        )
    }

    @Test
    fun `profile and diagnostics snapshots expose adapter counters and bounded audit`() {
        val root = Files.createTempDirectory("clockwork-standalone-it-profile-audit")
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
                jarPath = pluginsDir.resolve("clockwork-demo-it-profile-audit.jar"),
                pluginId = "clockwork-demo",
                permissions = listOf("adapter.invoke.bridge.host.server", "host.server.read")
            )
            assertEquals(1, core.loadNewPlugins())

            repeat(30) {
                val response = core.invokeAdapter(
                    pluginId = "clockwork-demo",
                    adapterId = "bridge.host.server",
                    action = "server.info",
                    payload = emptyMap()
                )
                assertTrue(response.success)
            }

            val profileJson = objectMapper.readTree(
                objectMapper.writeValueAsString(
                    mapOf(
                        "profile" to core.profile("clockwork-demo"),
                        "recommendations" to emptyList<String>()
                    )
                )
            )
            assertTrue(profileJson.path("profile").has("adapterCounters"))
            assertTrue(profileJson.path("profile").has("adapterAudit"))
            assertTrue(profileJson.path("profile").path("adapterCounters").path("total").asLong() >= 30L)
            val retained = profileJson.path("profile").path("adapterAudit").path("retainedEntries").asInt()
            val retentionCap = profileJson.path("profile").path("adapterAudit").path("retention").path("maxEntriesPerPlugin").asInt()
            assertTrue(retained <= retentionCap)

            val diagnosticsJson = objectMapper.readTree(
                objectMapper.writeValueAsString(
                    mapOf(
                        "diagnostics" to core.doctor(),
                        "recommendations" to emptyMap<String, List<String>>()
                    )
                )
            )
            val perf = diagnosticsJson.path("diagnostics").path("pluginPerformance").path("clockwork-demo")
            assertTrue(perf.has("adapterCounters"))
            assertTrue(perf.has("adapterAudit"))
            assertTrue(perf.path("adapterCounters").path("total").asLong() >= 30L)
        } finally {
            core.stop()
        }
    }

    @Test
    fun `tick stability metrics and plugin budget exhaustion are tracked`() {
        val root = Files.createTempDirectory("clockwork-standalone-it-tick-stability")
        val pluginsDir = root.resolve("plugins")
        val dataDir = root.resolve("data")
        Files.createDirectories(pluginsDir)
        Files.createDirectories(dataDir)

        val core = GigaStandaloneCore(
            config = StandaloneCoreConfig(
                pluginsDirectory = pluginsDir,
                dataDirectory = dataDir,
                tickPeriodMillis = 1L,
                autoSaveEveryTicks = 0L,
                perPluginTickBudgetNanos = 1L
            ),
            logger = {}
        )
        core.start()
        try {
            writeDemoManifestJar(
                jarPath = pluginsDir.resolve("clockwork-demo-it-tick-stability.jar"),
                pluginId = "clockwork-demo",
                permissions = listOf("host.server.read")
            )
            assertEquals(1, core.loadNewPlugins())

            Thread.sleep(60L)

            val status = core.status()
            assertTrue(status.tickCount > 0L)
            assertTrue(status.averageTickJitterNanos >= 0L)
            assertTrue(status.maxTickJitterNanos >= status.averageTickJitterNanos)
            assertTrue(status.pluginBudgetExhaustions > 0L)
        } finally {
            core.stop()
        }
    }

    @Test
    fun `deterministic execution order snapshot is stable and sorted`() {
        val root = Files.createTempDirectory("clockwork-standalone-it-order")
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
                jarPath = pluginsDir.resolve("clockwork-demo-it-order.jar"),
                pluginId = "clockwork-demo"
            )
            assertEquals(1, core.loadNewPlugins())
            val first = core.deterministicExecutionOrder()
            val second = core.deterministicExecutionOrder()
            assertEquals(first, second)
            assertEquals(first.keys.toList(), first.keys.toList().sorted())
            first.values.forEach { systems ->
                assertEquals(systems, systems.sorted())
            }
        } finally {
            core.stop()
        }
    }

    private fun runAdapterInvokeScenario(
        permissions: List<String>,
        assertResult: (com.clockwork.api.AdapterResponse) -> Unit
    ) {
        val root = Files.createTempDirectory("clockwork-standalone-it-adapter-invoke-json")
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
                jarPath = pluginsDir.resolve("clockwork-demo-it-adapter.jar"),
                pluginId = "clockwork-demo",
                permissions = permissions
            )
            assertEquals(1, core.loadNewPlugins())

            val response = core.invokeAdapter(
                pluginId = "clockwork-demo",
                adapterId = "bridge.host.server",
                action = "server.info",
                payload = emptyMap()
            )
            assertResult(response)
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
            "name: Clockwork Demo",
            "version: $version",
            "main: com.clockwork.demo.DemoGigaPlugin",
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
