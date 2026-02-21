package com.clockwork.standalone

import com.clockwork.api.CommandSender
import com.clockwork.core.GigaStandaloneCore
import com.clockwork.core.StandaloneCoreConfig
import com.clockwork.runtime.ReloadStatus
import org.junit.jupiter.api.Tag
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Tag("smoke")
class StandaloneParitySmokeTest {
    @Test
    fun `standalone parity checks for scan reload doctor profile and deterministic tick loop`() {
        val root = Files.createTempDirectory("clockwork-standalone-parity-smoke")
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
            writeDemoManifestJar(pluginsDir.resolve("clockwork-demo-parity.jar"), pluginId = "clockwork-demo")

            // scan parity: first scan loads plugin, next scan is idempotent with no changes.
            assertEquals(1, core.loadNewPlugins())
            assertEquals(0, core.loadNewPlugins())
            assertTrue(core.plugins().any { it.startsWith("clockwork-demo@") })

            // demo flow check for acceptance: plugin command bridge is functional.
            val hostResponse = core.run(
                pluginId = "clockwork-demo",
                sender = CommandSender.system("smoke"),
                commandLine = "demo-host"
            )
            assertTrue(hostResponse.contains("Host="))

            // doctor parity: diagnostics include loaded plugin and stable load order entries.
            val doctor = core.doctor()
            assertTrue(doctor.loadedPlugins.contains("clockwork-demo"))
            assertTrue(doctor.currentLoadOrder.contains("clockwork-demo"))

            // profile parity: profile exists and reports system runtime samples.
            waitUntil(Duration.ofSeconds(5)) {
                core.profile("clockwork-demo")?.systems?.get("crusher_tick")?.runs ?: 0L > 0L
            }
            val profileBeforeReload = core.profile("clockwork-demo")
            assertNotNull(profileBeforeReload)
            assertTrue(profileBeforeReload.systems.containsKey("crusher_tick"))

            val statusBeforeReload = core.status()
            assertTrue(statusBeforeReload.tickCount > 0L)
            assertEquals(0L, statusBeforeReload.tickFailures)

            // reload parity: both targeted reload and reloadAll must succeed.
            val singleReload = core.reload("clockwork-demo")
            assertEquals(ReloadStatus.SUCCESS, singleReload.status)
            val reloadAll = core.reloadAll()
            assertEquals(ReloadStatus.SUCCESS, reloadAll.status)

            waitUntil(Duration.ofSeconds(5)) { core.status().tickCount > statusBeforeReload.tickCount + 20L }
            val statusAfterReload = core.status()
            assertTrue(statusAfterReload.tickCount > statusBeforeReload.tickCount)
            assertEquals(0L, statusAfterReload.tickFailures)

            val profileAfterReload = core.profile("clockwork-demo")
            assertNotNull(profileAfterReload)
            assertTrue(profileAfterReload.systems.containsKey("crusher_tick"))
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

    private fun waitUntil(timeout: Duration, condition: () -> Boolean) {
        val deadline = Instant.now().plus(timeout)
        while (Instant.now().isBefore(deadline)) {
            if (condition()) return
            Thread.sleep(10)
        }
        check(condition()) { "Condition not met within ${timeout.toMillis()}ms" }
    }
}
