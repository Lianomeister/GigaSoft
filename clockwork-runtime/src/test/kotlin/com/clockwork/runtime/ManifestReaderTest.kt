package com.clockwork.runtime

import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ManifestReaderTest {
    @Test
    fun `parses string and map dependencies`() {
        val jar = createJar(
            """
            id: demo
            name: demo
            version: 1.0.0
            main: com.example.Demo
            apiVersion: 1
            dependencies:
              - core
              - "machines >=1.2.0 <2.0.0"
              - id: economy
                version: ">=3.0.0 <4.0.0"
            permissions: [demo.use]
            """.trimIndent()
        )

        val manifest = ManifestReader.readFromJar(jar)
        assertEquals("demo", manifest.id)
        assertEquals(3, manifest.dependencies.size)
        assertEquals("core", manifest.dependencies[0].id)
        assertEquals("machines", manifest.dependencies[1].id)
        assertEquals(">=1.2.0 <2.0.0", manifest.dependencies[1].versionRange)
        assertEquals("economy", manifest.dependencies[2].id)
        assertEquals(">=3.0.0 <4.0.0", manifest.dependencies[2].versionRange)
    }

    @Test
    fun `rejects invalid manifest`() {
        val jar = createJar(
            """
            id: bad id
            name: demo
            version: 1.0.0
            main: com.example.Demo
            apiVersion: 1
            dependencies: []
            permissions: []
            """.trimIndent()
        )

        assertFailsWith<IllegalArgumentException> {
            ManifestReader.readFromJar(jar)
        }
    }

    private fun createJar(manifest: String): Path {
        val dir = Files.createTempDirectory("manifest-reader")
        val jar = dir.resolve("plugin.jar")
        JarOutputStream(Files.newOutputStream(jar)).use { out ->
            out.putNextEntry(JarEntry("gigaplugin.yml"))
            out.write(manifest.toByteArray())
            out.closeEntry()
        }
        return jar
    }
}
