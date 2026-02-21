package com.clockwork.runtime

import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Tag("soak")
class GigaRuntimeReloadStressTest {
    @Test
    fun `reload changed detects modified jars without explicit plugin id`() {
        val root = Files.createTempDirectory("giga-reload-changed")
        val pluginsDir = root.resolve("plugins")
        val dataDir = root.resolve("data")
        Files.createDirectories(pluginsDir)
        Files.createDirectories(dataDir)

        val sourceJar = pluginsDir.resolve("demo.jar")
        createPluginManifestJar(sourceJar, "1.0.0")

        val runtime = GigaRuntime(pluginsDir, dataDir)
        runtime.scanAndLoad()
        createPluginManifestJar(sourceJar, "1.0.1")

        val report = runtime.reloadChangedWithReport()
        assertEquals(ReloadStatus.SUCCESS, report.status)
        assertTrue(report.reloadedPlugins.contains("demo"))
        assertEquals("1.0.1", runtime.loadedPlugins().single().manifest.version)

        runtime.unload("demo")
    }

    @Test
    fun `repeated reload stays stable`() {
        val root = Files.createTempDirectory("giga-reload-stress")
        val pluginsDir = root.resolve("plugins")
        val dataDir = root.resolve("data")
        Files.createDirectories(pluginsDir)
        Files.createDirectories(dataDir)

        val sourceJar = pluginsDir.resolve("demo.jar")
        createPluginManifestJar(sourceJar, "1.0.0")

        val runtime = GigaRuntime(pluginsDir, dataDir)
        runtime.scanAndLoad()

        repeat(25) { index ->
            createPluginManifestJar(sourceJar, "1.0.${index + 1}")
            val report = runtime.reloadWithReport("demo")
            assertEquals(ReloadStatus.SUCCESS, report.status)
            assertEquals("1.0.${index + 1}", runtime.loadedPlugins().single().manifest.version)
        }

        runtime.unload("demo")
    }

    private fun createPluginManifestJar(targetJar: Path, version: String) {
        Files.createDirectories(targetJar.parent)
        JarOutputStream(Files.newOutputStream(targetJar)).use { jar ->
            jar.putNextEntry(JarEntry("clockworkplugin.yml"))
            jar.write(
                """
                id: demo
                name: demo
                version: $version
                main: com.clockwork.runtime.TestGoodPlugin
                apiVersion: 1
                dependencies: []
                permissions: []
                """.trimIndent().toByteArray()
            )
            jar.closeEntry()
        }
    }
}
