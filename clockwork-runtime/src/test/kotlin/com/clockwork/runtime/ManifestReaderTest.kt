package com.clockwork.runtime

import com.clockwork.api.DependencyKind
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

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
        assertEquals(DependencyKind.REQUIRED, manifest.dependencies[2].kind)
    }

    @Test
    fun `parses dependency resolver v2 buckets`() {
        val jar = createJar(
            """
            id: demo
            name: demo
            version: 1.0.0
            main: com.example.Demo
            apiVersion: 1
            dependencies:
              required:
                - core
              optional:
                - economy >=1.0.0 <2.0.0
              softAfter:
                - map-sync
              conflicts:
                - legacy-pvp
            permissions: []
            """.trimIndent()
        )

        val manifest = ManifestReader.readFromJar(jar)
        val byId = manifest.dependencies.associateBy { it.id }
        assertEquals(DependencyKind.REQUIRED, byId.getValue("core").kind)
        assertEquals(DependencyKind.OPTIONAL, byId.getValue("economy").kind)
        assertEquals(">=1.0.0 <2.0.0", byId.getValue("economy").versionRange)
        assertEquals(DependencyKind.SOFT_AFTER, byId.getValue("map-sync").kind)
        assertEquals(DependencyKind.CONFLICTS, byId.getValue("legacy-pvp").kind)
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

    @Test
    fun `uses primary manifest name when present`() {
        val jar = createJar(
            mapOf(
                ManifestReader.PRIMARY_MANIFEST_FILE to validManifest("primary-demo")
            )
        )

        val read = ManifestReader.readFromJarDetailed(jar)
        assertEquals("primary-demo", read.manifest.id)
        assertEquals(ManifestReader.PRIMARY_MANIFEST_FILE, read.sourceFile)
    }

    @Test
    fun `prefers primary manifest when multiple supported names exist`() {
        val jar = createJar(
            mapOf(
                ManifestReader.PRIMARY_MANIFEST_FILE to validManifest("primary-demo"),
                ManifestReader.TEST_MANIFEST_FILE to validManifest("test-demo"),
                ManifestReader.DEMO_MANIFEST_FILE to validManifest("demo-demo")
            )
        )

        val read = ManifestReader.readFromJarDetailed(jar)
        assertEquals("primary-demo", read.manifest.id)
        assertEquals(ManifestReader.PRIMARY_MANIFEST_FILE, read.sourceFile)
    }

    @Test
    fun `supports test manifest name`() {
        val jar = createJar(
            mapOf(
                ManifestReader.TEST_MANIFEST_FILE to validManifest("test-demo")
            )
        )

        val read = ManifestReader.readFromJarDetailed(jar)
        assertEquals("test-demo", read.manifest.id)
        assertEquals(ManifestReader.TEST_MANIFEST_FILE, read.sourceFile)
    }

    @Test
    fun `supports demo manifest name`() {
        val jar = createJar(
            mapOf(
                ManifestReader.DEMO_MANIFEST_FILE to validManifest("demo-demo")
            )
        )

        val read = ManifestReader.readFromJarDetailed(jar)
        assertEquals("demo-demo", read.manifest.id)
        assertEquals(ManifestReader.DEMO_MANIFEST_FILE, read.sourceFile)
    }

    @Test
    fun `rejects legacy gigaplugin manifest name`() {
        val jar = createJar(
            mapOf(
                "gigaplugin.yml" to validManifest("legacy-demo")
            )
        )

        val error = assertFailsWith<IllegalStateException> {
            ManifestReader.readFromJarDetailed(jar)
        }
        assertTrue(error.message.orEmpty().contains("Expected one of"))
    }

    private fun createJar(manifest: String): Path {
        return createJar(mapOf(ManifestReader.PRIMARY_MANIFEST_FILE to manifest))
    }

    private fun createJar(entries: Map<String, String>): Path {
        val dir = Files.createTempDirectory("manifest-reader")
        val jar = dir.resolve("plugin.jar")
        JarOutputStream(Files.newOutputStream(jar)).use { out ->
            entries.forEach { (entryName, content) ->
                out.putNextEntry(JarEntry(entryName))
                out.write(content.toByteArray())
                out.closeEntry()
            }
        }
        return jar
    }

    private fun validManifest(id: String): String {
        return """
            id: $id
            name: demo
            version: 1.0.0
            main: com.example.Demo
            apiVersion: 1
            dependencies: []
            permissions: [demo.use]
        """.trimIndent()
    }
}
