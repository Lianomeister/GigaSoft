package com.clockwork.api

import kotlin.test.Test
import kotlin.test.assertEquals

class DslDependencySpecTest {
    @Test
    fun `dsl accepts explicit dependency specs`() {
        val plugin = gigaPlugin(
            id = "demo",
            dependencySpecs = listOf(
                dependency("core"),
                dependency("machines", ">=1.2.0 <2.0.0"),
                optionalDependency("economy"),
                softAfterDependency("map-sync"),
                conflictDependency("legacy")
            )
        ) {}

        val manifest = plugin.manifest()
        assertEquals(5, manifest.dependencies.size)
        assertEquals("core", manifest.dependencies[0].id)
        assertEquals(null, manifest.dependencies[0].versionRange)
        assertEquals(DependencyKind.REQUIRED, manifest.dependencies[0].kind)
        assertEquals("machines", manifest.dependencies[1].id)
        assertEquals(">=1.2.0 <2.0.0", manifest.dependencies[1].versionRange)
        assertEquals(DependencyKind.REQUIRED, manifest.dependencies[1].kind)
        assertEquals(DependencyKind.OPTIONAL, manifest.dependencies[2].kind)
        assertEquals(DependencyKind.SOFT_AFTER, manifest.dependencies[3].kind)
        assertEquals(DependencyKind.CONFLICTS, manifest.dependencies[4].kind)
    }
}
