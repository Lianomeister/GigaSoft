package com.clockwork.cli

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GigaCliTest {
    @Test
    fun `plugin id validation accepts stable ids`() {
        assertNull(validatePluginId("clockwork-demo"))
        assertNull(validatePluginId("demo_01"))
        assertNotNull(validatePluginId("A"))
        assertNotNull(validatePluginId("x"))
        assertNotNull(validatePluginId("bad id"))
    }

    @Test
    fun `package validation accepts java style package names`() {
        assertNull(validatePackageName("com.example"))
        assertNull(validatePackageName("io.clockwork.plugin"))
        assertNotNull(validatePackageName("bad-package"))
        assertNotNull(validatePackageName("1com.example"))
    }

    @Test
    fun `class name conversion is deterministic`() {
        assertEquals("ClockworkDemoPlugin", toPluginClassName("clockwork-demo"))
        assertEquals("Demo01Plugin", toPluginClassName("demo_01"))
    }

    @Test
    fun `doctor report marks required dirs and strict health`() {
        val runtime = Files.createTempDirectory("clockwork-cli-doctor")
        Files.createDirectories(runtime.resolve("plugins"))
        val report = runtimeDoctor(runtime)
        assertFalse(report.ok)
        assertTrue(report.checks.any { it.name == "plugins" && it.exists })
        assertTrue(report.checks.any { it.name == "giga-plugins" && !it.exists && it.required })

        Files.createDirectories(runtime.resolve("giga-plugins"))
        val healthy = runtimeDoctor(runtime)
        assertTrue(healthy.ok)
    }
}

