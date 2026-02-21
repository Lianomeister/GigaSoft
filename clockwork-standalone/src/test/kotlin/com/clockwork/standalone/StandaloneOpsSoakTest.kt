package com.clockwork.standalone

import com.fasterxml.jackson.databind.ObjectMapper
import com.clockwork.core.GigaStandaloneCore
import com.clockwork.core.StandaloneCoreConfig
import com.clockwork.net.SessionActionResult
import com.clockwork.net.StandaloneNetConfig
import com.clockwork.net.StandaloneNetServer
import com.clockwork.net.StandaloneSessionHandler
import com.clockwork.runtime.ReloadStatus
import org.junit.jupiter.api.Tag
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Tag("soak")
class StandaloneOpsSoakTest {
    private val mapper = ObjectMapper()

    @Test
    fun `net and diagnostics stay stable under sustained load`() {
        val root = Files.createTempDirectory("clockwork-standalone-ops-soak")
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
        val net = StandaloneNetServer(
            config = StandaloneNetConfig(
                host = "127.0.0.1",
                port = 0,
                authRequired = false,
                maxRequestsPerMinutePerConnection = 20_000,
                maxRequestsPerMinutePerIp = 20_000
            ),
            logger = {},
            handler = object : StandaloneSessionHandler {
                override fun join(name: String, world: String, x: Double, y: Double, z: Double): SessionActionResult = SessionActionResult(true, "JOINED", "ok")
                override fun leave(name: String): SessionActionResult = SessionActionResult(true, "LEFT", "ok")
                override fun move(name: String, x: Double, y: Double, z: Double, world: String?): SessionActionResult = SessionActionResult(true, "MOVED", "ok")
                override fun lookup(name: String): SessionActionResult = SessionActionResult(true, "FOUND", "ok")
                override fun who(name: String?): SessionActionResult = SessionActionResult(true, "WHOAMI", "ok")
                override fun worldCreate(name: String, seed: Long): SessionActionResult = SessionActionResult(true, "WORLD_CREATED", "ok")
                override fun entitySpawn(type: String, world: String, x: Double, y: Double, z: Double): SessionActionResult = SessionActionResult(true, "ENTITY_SPAWNED", "ok")
                override fun inventorySet(owner: String, slot: Int, itemId: String): SessionActionResult = SessionActionResult(true, "INVENTORY_UPDATED", "ok")
            }
        )
        net.start()
        try {
            val port = waitForPort(net)
            Socket("127.0.0.1", port).use { socket ->
                val reader = socket.getInputStream().bufferedReader()
                val writer = socket.getOutputStream().bufferedWriter()
                repeat(5_000) { idx ->
                    val request = mapOf(
                        "protocol" to "clockwork-standalone-net",
                        "version" to 1,
                        "requestId" to "soak-$idx",
                        "action" to "ping",
                        "payload" to emptyMap<String, String>()
                    )
                    writer.write(mapper.writeValueAsString(request))
                    writer.newLine()
                    writer.flush()
                    val response = mapper.readTree(reader.readLine())
                    assertTrue(response.path("success").asBoolean())
                }
            }
            val statusJson = mapper.writeValueAsString(statusView(core, net, emptyList()))
            val status = mapper.readTree(statusJson)
            assertTrue(status.path("core").path("running").asBoolean())
            assertTrue(status.path("net").path("totalRequests").asLong() >= 5_000L)
        } finally {
            net.stop()
            core.stop()
        }
    }

    private fun waitForPort(server: StandaloneNetServer): Int {
        repeat(100) {
            val port = server.boundPort()
            if (port > 0) return port
            Thread.sleep(10)
        }
        error("Server did not bind port")
    }

    @Test
    fun `adapter audit remains bounded across invoke and reload cycles`() {
        val root = Files.createTempDirectory("clockwork-standalone-ops-soak-reload")
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
            writeDemoManifestJar(pluginsDir.resolve("clockwork-demo-soak.jar"), pluginId = "clockwork-demo")
            assertEquals(1, core.loadNewPlugins())
            repeat(20) {
                repeat(120) {
                    val response = core.invokeAdapter(
                        pluginId = "clockwork-demo",
                        adapterId = "bridge.host.server",
                        action = "server.info",
                        payload = emptyMap()
                    )
                    assertTrue(response.success)
                }
                val reload = core.reload("clockwork-demo")
                assertEquals(ReloadStatus.SUCCESS, reload.status)
            }
            val postReloadInvoke = core.invokeAdapter(
                pluginId = "clockwork-demo",
                adapterId = "bridge.host.server",
                action = "server.info",
                payload = emptyMap()
            )
            assertTrue(postReloadInvoke.success)

            val profile = core.profile("clockwork-demo")
            assertTrue(profile != null)
            val audit = profile!!.adapterAudit
            assertTrue(audit.totalRecorded > 0L)
            assertTrue(audit.retainedEntries <= audit.retention.maxEntriesPerPlugin)
            val diagnostics = core.doctor().pluginPerformance["clockwork-demo"]
            assertTrue(diagnostics != null)
            assertTrue(diagnostics!!.adapterCounters.total > 0L)
            assertTrue(diagnostics.adapterAudit.retainedEntries <= diagnostics.adapterAudit.retention.maxEntriesPerPlugin)
        } finally {
            core.stop()
        }
    }

    @Test
    fun `tick and reload pipeline remains deterministic under sustained load`() {
        val root = Files.createTempDirectory("clockwork-standalone-ops-soak-deterministic")
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
            writeDemoManifestJar(pluginsDir.resolve("clockwork-demo-deterministic.jar"), pluginId = "clockwork-demo")
            assertEquals(1, core.loadNewPlugins())

            val baselineDoctor = core.doctor()
            assertTrue(baselineDoctor.loadedPlugins.contains("clockwork-demo"))
            val baselineLoadOrder = baselineDoctor.currentLoadOrder
            val statusBefore = core.status()

            repeat(30) { cycle ->
                repeat(80) {
                    val response = core.invokeAdapter(
                        pluginId = "clockwork-demo",
                        adapterId = "bridge.host.server",
                        action = "server.info",
                        payload = emptyMap()
                    )
                    assertTrue(response.success)
                }

                val reload = if (cycle % 2 == 0) core.reload("clockwork-demo") else core.reloadAll()
                assertEquals(ReloadStatus.SUCCESS, reload.status)

                val doctor = core.doctor()
                assertTrue(doctor.loadedPlugins.contains("clockwork-demo"))
                assertEquals(baselineLoadOrder, doctor.currentLoadOrder)

                val profile = core.profile("clockwork-demo")
                assertTrue(profile != null)
                assertTrue(profile!!.pluginId == "clockwork-demo")
            }

            val statusAfter = core.status()
            assertTrue(statusAfter.tickCount > statusBefore.tickCount)
            assertEquals(0L, statusAfter.tickFailures)
            assertEquals(baselineLoadOrder, core.doctor().currentLoadOrder)
        } finally {
            core.stop()
        }
    }

    private fun writeDemoManifestJar(
        jarPath: Path,
        pluginId: String,
        version: String = "1.0.0",
        permissions: List<String> = listOf("host.server.read", "adapter.invoke.bridge.host.server")
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
}
