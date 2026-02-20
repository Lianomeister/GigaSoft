package com.gigasoft.runtime

import com.gigasoft.api.DependencySpec
import com.gigasoft.api.PluginManifest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.nio.file.Path

class DependencyResolverTest {
    @Test
    fun `resolves topological order`() {
        val a = descriptor("a")
        val b = descriptor("b", dependencies = listOf(dep("a")))
        val c = descriptor("c", dependencies = listOf(dep("b")))

        val result = DependencyResolver.resolve(listOf(c, b, a))

        assertTrue(result.rejected.isEmpty())
        assertEquals(listOf("a", "b", "c"), result.ordered.map { it.manifest.id })
    }

    @Test
    fun `rejects missing dependencies transitively`() {
        val a = descriptor("a", dependencies = listOf(dep("missing")))
        val b = descriptor("b", dependencies = listOf(dep("a")))

        val result = DependencyResolver.resolve(listOf(a, b))

        assertEquals("Missing dependency/dependencies: missing", result.rejected["a"])
        assertEquals("Missing dependency/dependencies: a", result.rejected["b"])
        assertTrue(result.ordered.isEmpty())
    }

    @Test
    fun `rejects cycles`() {
        val a = descriptor("a", dependencies = listOf(dep("b")))
        val b = descriptor("b", dependencies = listOf(dep("a")))

        val result = DependencyResolver.resolve(listOf(a, b))

        assertEquals("Dependency cycle detected", result.rejected["a"])
        assertEquals("Dependency cycle detected", result.rejected["b"])
        assertTrue(result.ordered.isEmpty())
    }

    @Test
    fun `rejects dependency version mismatch`() {
        val core = descriptor("core", version = "1.1.0")
        val addon = descriptor("addon", dependencies = listOf(dep("core", ">=1.2.0 <2.0.0")))

        val result = DependencyResolver.resolve(listOf(core, addon))

        assertEquals(
            "Dependency version mismatch: core requires '>=1.2.0 <2.0.0', found '1.1.0'",
            result.rejected["addon"]
        )
        assertEquals(
            "Dependency version mismatch: core requires '>=1.2.0 <2.0.0', found '1.1.0'",
            result.versionMismatches["addon"]
        )
    }

    @Test
    fun `accepts external dependency versions`() {
        val addon = descriptor("addon", dependencies = listOf(dep("core", ">=1.2.0 <2.0.0")))

        val result = DependencyResolver.resolve(
            descriptors = listOf(addon),
            externallyAvailable = mapOf("core" to "1.2.5")
        )

        assertTrue(result.rejected.isEmpty())
        assertEquals(listOf("addon"), result.ordered.map { it.manifest.id })
    }

    @Test
    fun `rejects incompatible api version`() {
        val incompatible = descriptor("addon", apiVersion = "2.0")

        val result = DependencyResolver.resolve(listOf(incompatible))

        assertEquals(
            "Incompatible apiVersion: plugin=2.0 runtime=${RuntimeVersion.API_VERSION}",
            result.rejected["addon"]
        )
        assertEquals(
            "incompatible (plugin=2.0, runtime=${RuntimeVersion.API_VERSION})",
            result.apiCompatibility["addon"]
        )
    }

    private fun dep(id: String, range: String? = null): DependencySpec = DependencySpec(id, range)

    private fun descriptor(
        id: String,
        version: String = "1.0.0",
        apiVersion: String = "1",
        dependencies: List<DependencySpec> = emptyList()
    ): PluginDescriptor {
        return PluginDescriptor(
            manifest = PluginManifest(
                id = id,
                name = id,
                version = version,
                main = "com.example.$id.Main",
                apiVersion = apiVersion,
                dependencies = dependencies
            ),
            jarPath = Path.of("$id.jar")
        )
    }
}
