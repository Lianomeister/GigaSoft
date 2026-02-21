package com.clockwork.standalone

import com.clockwork.core.GigaStandaloneCore
import com.clockwork.core.StandaloneCoreConfig
import com.clockwork.runtime.ReloadStatus
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class StandaloneCoreStabilityIntegrationTest {
    @Test
    fun `scan reload doctor profile remain stable under repeated cycles`() {
        val root = Files.createTempDirectory("clockwork-standalone-stability")
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
            writeDemoManifestJar(pluginsDir.resolve("clockwork-demo-stability.jar"), pluginId = "clockwork-demo")
            assertEquals(1, core.loadNewPlugins())

            val errors = ConcurrentLinkedQueue<Throwable>()
            val observer = thread(start = true, name = "clockwork-stability-observer") {
                repeat(240) {
                    try {
                        val doctor = core.doctor()
                        assertTrue(doctor.loadedPlugins.contains("clockwork-demo"))
                        assertNotNull(core.profile("clockwork-demo"))
                    } catch (t: Throwable) {
                        errors += t
                        return@thread
                    }
                }
            }

            repeat(60) {
                val scanLoaded = core.loadNewPlugins()
                assertTrue(scanLoaded >= 0)
                val reload = core.reload("clockwork-demo")
                assertEquals(ReloadStatus.SUCCESS, reload.status)
            }

            observer.join(20_000L)
            assertTrue(!observer.isAlive, "Observer thread did not finish within timeout")
            assertTrue(errors.isEmpty(), "Observed stability errors: ${errors.joinToString { it.message ?: it::class.simpleName.orEmpty() }}")

            val finalDoctor = core.doctor()
            assertTrue(finalDoctor.loadedPlugins.contains("clockwork-demo"))
            assertNotNull(core.profile("clockwork-demo"))
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
}
