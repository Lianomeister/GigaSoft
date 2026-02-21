package com.clockwork.runtime

import com.clockwork.api.DependencySpec
import com.clockwork.api.PluginManifest
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

    @Test
    fun `rejects forbidden main class package`() {
        assertFailsWith<IllegalArgumentException> {
            ManifestSecurity.validate(
                PluginManifest(
                    id = "demo",
                    name = "Demo",
                    version = "1.0.0",
                    main = "java.lang.String",
                    apiVersion = "1"
                )
            )
        }
    }

    @Test
    fun `rejects too many dependencies`() {
        val deps = (1..33).map { DependencySpec("dep$it") }
        assertFailsWith<IllegalArgumentException> {
            ManifestSecurity.validate(
                PluginManifest(
                    id = "demo",
                    name = "Demo",
                    version = "1.0.0",
                    main = "com.example.Demo",
                    apiVersion = "1",
                    dependencies = deps
                )
            )
        }
    }

    @Test
    fun `rejects duplicate permissions`() {
        assertFailsWith<IllegalArgumentException> {
            ManifestSecurity.validate(
                PluginManifest(
                    id = "demo",
                    name = "Demo",
                    version = "1.0.0",
                    main = "com.example.Demo",
                    apiVersion = "1",
                    permissions = listOf("host.server.read", "host.server.read")
                )
            )
        }
    }
}
