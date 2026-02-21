package com.clockwork.runtime

import com.clockwork.api.GigaPlugin
import com.clockwork.api.ModelDefinition
import com.clockwork.api.PluginContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GigaRuntimeReloadRollbackTest {
    @Test
    fun `reload rolls back when updated jar fails on enable`() {
        val root = Files.createTempDirectory("giga-runtime-rollback")
        val pluginsDir = root.resolve("plugins")
        val dataDir = root.resolve("data")
        Files.createDirectories(pluginsDir)
        Files.createDirectories(dataDir)

        val sourceJar = pluginsDir.resolve("demo.jar")
        createPluginJar(
            targetJar = sourceJar,
            pluginId = "demo",
            mainClass = "com.clockwork.runtime.TestGoodPlugin",
            version = "1.0.0"
        )

        val runtime = GigaRuntime(pluginsDir, dataDir)
        val initiallyLoaded = runtime.scanAndLoad()
        assertEquals(1, initiallyLoaded.size)
        assertEquals("demo", initiallyLoaded.first().manifest.id)

        createPluginJar(
            targetJar = sourceJar,
            pluginId = "demo",
            mainClass = "com.example.MissingPlugin",
            version = "2.0.0"
        )

        val report = runtime.reloadWithReport("demo")
        assertEquals(ReloadStatus.ROLLED_BACK, report.status)
        assertTrue(report.reason?.contains("com.example.MissingPlugin") == true)

        val after = runtime.loadedPlugins()
        assertEquals(1, after.size)
        assertEquals("demo", after.first().manifest.id)
        assertEquals("1.0.0", after.first().manifest.version)

        runtime.unload("demo")
    }

    @Test
    fun `reload rolls back when updated jar fails asset validation`() {
        val root = Files.createTempDirectory("giga-runtime-asset-rollback")
        val pluginsDir = root.resolve("plugins")
        val dataDir = root.resolve("data")
        Files.createDirectories(pluginsDir)
        Files.createDirectories(dataDir)

        val sourceJar = pluginsDir.resolve("demo.jar")
        createPluginJar(
            targetJar = sourceJar,
            pluginId = "demo",
            mainClass = "com.clockwork.runtime.TestGoodPlugin",
            version = "1.0.0"
        )

        val runtime = GigaRuntime(pluginsDir, dataDir)
        runtime.scanAndLoad()

        createPluginJar(
            targetJar = sourceJar,
            pluginId = "demo",
            mainClass = "com.clockwork.runtime.TestInvalidAssetPlugin",
            version = "2.0.0"
        )

        val report = runtime.reloadWithReport("demo")
        assertEquals(ReloadStatus.ROLLED_BACK, report.status)
        assertTrue(report.reason?.contains("Asset validation failed") == true)

        val after = runtime.loadedPlugins()
        assertEquals(1, after.size)
        assertEquals("1.0.0", after.first().manifest.version)
        runtime.unload("demo")
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

class TestGoodPlugin : GigaPlugin {
    override fun onEnable(ctx: PluginContext) {
        ctx.logger.info("enabled")
    }

    override fun onDisable(ctx: PluginContext) {}
}

class TestInvalidAssetPlugin : GigaPlugin {
    override fun onEnable(ctx: PluginContext) {
        ctx.registry.registerModel(
            ModelDefinition(
                id = "bad_model",
                geometryPath = "assets/demo/models/item/bad_model.json",
                textures = mapOf("layer0" to "missing_texture")
            )
        )
    }

    override fun onDisable(ctx: PluginContext) {}
}
