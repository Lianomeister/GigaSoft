package com.gigasoft.api

import kotlin.test.Test
import kotlin.test.assertEquals

class DslDependencySpecTest {
    @Test
    fun `dsl accepts explicit dependency specs`() {
        val plugin = gigaPlugin(
            id = "demo",
            dependencySpecs = listOf(
                dependency("core"),
                dependency("machines", ">=1.2.0 <2.0.0")
            )
        ) {}

        val manifest = plugin.manifest()
        assertEquals(2, manifest.dependencies.size)
        assertEquals("core", manifest.dependencies[0].id)
        assertEquals(null, manifest.dependencies[0].versionRange)
        assertEquals("machines", manifest.dependencies[1].id)
        assertEquals(">=1.2.0 <2.0.0", manifest.dependencies[1].versionRange)
    }
}
