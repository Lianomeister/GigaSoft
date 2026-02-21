package com.clockwork.runtime

import com.clockwork.api.DependencyKind
import com.clockwork.api.DependencySpec
import com.clockwork.api.PluginManifest
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DependencyResolverTest {
    @Test
    fun `resolves deterministic topological order`() {
        val a = descriptor("a")
        val b = descriptor("b", dependencies = listOf(dep("a")))
        val c = descriptor("c", dependencies = listOf(dep("b")))

        val result = DependencyResolver.resolve(listOf(c, b, a))

        assertTrue(result.rejected.isEmpty())
        assertEquals(listOf("a", "b", "c"), result.ordered.map { it.manifest.id })
    }

    @Test
    fun `supports optional dependencies without hard rejection when missing`() {
        val addon = descriptor(
            "addon",
            dependencies = listOf(dep("economy", kind = DependencyKind.OPTIONAL))
        )

        val result = DependencyResolver.resolve(listOf(addon))

        assertTrue(result.rejected.isEmpty())
        assertEquals(listOf("addon"), result.ordered.map { it.manifest.id })
    }

    @Test
    fun `supports softAfter ordering without hard rejection when target missing`() {
        val addon = descriptor(
            "addon",
            dependencies = listOf(dep("core", kind = DependencyKind.SOFT_AFTER))
        )
        val core = descriptor("core")

        val resolvedWithCore = DependencyResolver.resolve(listOf(addon, core))
        assertEquals(listOf("core", "addon"), resolvedWithCore.ordered.map { it.manifest.id })

        val resolvedWithoutCore = DependencyResolver.resolve(listOf(addon))
        assertEquals(listOf("addon"), resolvedWithoutCore.ordered.map { it.manifest.id })
    }

    @Test
    fun `rejects conflicts with diagnostic code and hint`() {
        val core = descriptor("core")
        val addon = descriptor(
            "addon",
            dependencies = listOf(dep("core", kind = DependencyKind.CONFLICTS))
        )

        val result = DependencyResolver.resolve(listOf(core, addon))

        assertEquals("DEP_CONFLICT", result.diagnostics["addon"]?.code)
        assertTrue(result.rejected["addon"].orEmpty().contains("conflicts with 'core'"))
    }

    @Test
    fun `rejects missing required dependency with cause chain`() {
        val a = descriptor("a", dependencies = listOf(dep("missing")))
        val b = descriptor("b", dependencies = listOf(dep("a")))

        val result = DependencyResolver.resolve(listOf(a, b))

        assertEquals("DEP_REQUIRED_MISSING", result.diagnostics["a"]?.code)
        assertEquals("DEP_REQUIRED_UNRESOLVED", result.diagnostics["b"]?.code)
        assertTrue(result.diagnostics["b"]?.causes?.any { it.contains("a [DEP_REQUIRED_MISSING]") } == true)
    }

    @Test
    fun `rejects required dependency version mismatch`() {
        val core = descriptor("core", version = "1.1.0")
        val addon = descriptor("addon", dependencies = listOf(dep("core", ">=1.2.0 <2.0.0")))

        val result = DependencyResolver.resolve(listOf(core, addon))

        assertEquals("DEP_REQUIRED_VERSION_MISMATCH", result.diagnostics["addon"]?.code)
        assertEquals(
            "Dependency version mismatch: core requires '>=1.2.0 <2.0.0', found '1.1.0'",
            result.versionMismatches["addon"]
        )
    }

    @Test
    fun `accepts external dependency versions for required dependencies`() {
        val addon = descriptor("addon", dependencies = listOf(dep("core", ">=1.2.0 <2.0.0")))

        val result = DependencyResolver.resolve(
            descriptors = listOf(addon),
            externallyAvailable = mapOf("core" to "1.2.5")
        )

        assertTrue(result.rejected.isEmpty())
        assertEquals(listOf("addon"), result.ordered.map { it.manifest.id })
    }

    @Test
    fun `rejects incompatible api version with diagnostic code`() {
        val incompatible = descriptor("addon", apiVersion = "2.0")

        val result = DependencyResolver.resolve(listOf(incompatible))

        assertEquals("DEP_API_INCOMPATIBLE", result.diagnostics["addon"]?.code)
        assertEquals(
            "incompatible (plugin=2.0, runtime=${RuntimeVersion.API_VERSION})",
            result.apiCompatibility["addon"]
        )
    }

    @Test
    fun `rejects required cycles with deterministic diagnostics`() {
        val a = descriptor("a", dependencies = listOf(dep("b")))
        val b = descriptor("b", dependencies = listOf(dep("a")))

        val result = DependencyResolver.resolve(listOf(a, b))

        assertEquals("DEP_REQUIRED_CYCLE", result.diagnostics["a"]?.code)
        assertEquals("DEP_REQUIRED_CYCLE", result.diagnostics["b"]?.code)
        assertTrue(result.ordered.isEmpty())
    }

    private fun dep(
        id: String,
        range: String? = null,
        kind: DependencyKind = DependencyKind.REQUIRED
    ): DependencySpec = DependencySpec(id = id, versionRange = range, kind = kind)

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
