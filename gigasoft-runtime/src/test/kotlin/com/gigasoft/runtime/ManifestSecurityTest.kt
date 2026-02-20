package com.gigasoft.runtime

import com.gigasoft.api.DependencySpec
import com.gigasoft.api.PluginManifest
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ManifestSecurityTest {
    @Test
    fun `accepts valid manifest`() {
        ManifestSecurity.validate(
            PluginManifest(
                id = "demo.plugin",
                name = "Demo Plugin",
                version = "1.2.3",
                main = "com.example.DemoPlugin",
                apiVersion = "1.0",
                dependencies = listOf(DependencySpec("core", ">=1.0.0 <2.0.0")),
                permissions = listOf("demo.use")
            )
        )
    }

    @Test
    fun `rejects invalid plugin id`() {
        assertFailsWith<IllegalArgumentException> {
            ManifestSecurity.validate(
                PluginManifest(
                    id = "Bad Id",
                    name = "Demo",
                    version = "1.0.0",
                    main = "com.example.Demo",
                    apiVersion = "1"
                )
            )
        }
    }

    @Test
    fun `rejects self dependency`() {
        assertFailsWith<IllegalArgumentException> {
            ManifestSecurity.validate(
                PluginManifest(
                    id = "demo",
                    name = "Demo",
                    version = "1.0.0",
                    main = "com.example.Demo",
                    apiVersion = "1",
                    dependencies = listOf(DependencySpec("demo"))
                )
            )
        }
    }

    @Test
    fun `rejects invalid main class`() {
        assertFailsWith<IllegalArgumentException> {
            ManifestSecurity.validate(
                PluginManifest(
                    id = "demo",
                    name = "Demo",
                    version = "1.0.0",
                    main = "invalid-class",
                    apiVersion = "1"
                )
            )
        }
    }
}
