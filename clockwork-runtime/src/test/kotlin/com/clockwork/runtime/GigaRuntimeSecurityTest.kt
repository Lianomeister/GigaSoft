package com.clockwork.runtime

import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertFailsWith

class GigaRuntimeSecurityTest {
    @Test
    fun `loadJar rejects jars outside plugins directory`() {
        val root = Files.createTempDirectory("giga-security")
        val pluginsDir = root.resolve("plugins")
        val dataDir = root.resolve("data")
        Files.createDirectories(pluginsDir)
        Files.createDirectories(dataDir)

        val outsideJar = createPluginJar(root.resolve("outside.jar"))
        val runtime = GigaRuntime(pluginsDir, dataDir)

        assertFailsWith<IllegalArgumentException> {
            runtime.loadJar(outsideJar)
        }
    }

    @Test
    fun `scanAndLoad skips invalid manifests`() {
        val root = Files.createTempDirectory("giga-security-scan")
        val pluginsDir = root.resolve("plugins")
        val dataDir = root.resolve("data")
        Files.createDirectories(pluginsDir)
        Files.createDirectories(dataDir)

        createManifestJar(
            pluginsDir.resolve("bad.jar"),
            """
            id: bad id
            name: bad
            version: 1.0.0
            main: com.example.Bad
            apiVersion: 1
            dependencies: []
            permissions: []
            """.trimIndent()
        )

        val runtime = GigaRuntime(pluginsDir, dataDir)
        val loaded = runtime.scanAndLoad()

        kotlin.test.assertTrue(loaded.isEmpty())
    }

    @Test
    fun `scanAndLoad skips non file jar entries and continues`() {
        val root = Files.createTempDirectory("giga-security-scan-non-file")
        val pluginsDir = root.resolve("plugins")
        val dataDir = root.resolve("data")
        Files.createDirectories(pluginsDir)
        Files.createDirectories(dataDir)

        Files.createDirectories(pluginsDir.resolve("folder.jar"))
        createManifestJar(
            pluginsDir.resolve("bad.jar"),
            """
            id: bad id
            name: bad
            version: 1.0.0
            main: com.example.Bad
            apiVersion: 1
            dependencies: []
            permissions: []
            """.trimIndent()
        )

        val runtime = GigaRuntime(pluginsDir, dataDir)
        val loaded = runtime.scanAndLoad()

        kotlin.test.assertTrue(loaded.isEmpty())
    }

    private fun createPluginJar(targetJar: Path): Path {
        createManifestJar(
            targetJar,
            """
            id: demo
            name: demo
            version: 1.0.0
            main: com.example.Demo
            apiVersion: 1
            dependencies: []
            permissions: []
            """.trimIndent()
        )
        return targetJar
    }

    private fun createManifestJar(targetJar: Path, manifest: String) {
        JarOutputStream(Files.newOutputStream(targetJar)).use { out ->
            out.putNextEntry(JarEntry("clockworkplugin.yml"))
            out.write(manifest.toByteArray())
            out.closeEntry()
        }
    }
}
