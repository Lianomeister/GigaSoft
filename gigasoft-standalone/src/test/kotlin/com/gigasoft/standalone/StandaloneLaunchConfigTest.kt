package com.gigasoft.standalone

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StandaloneLaunchConfigTest {
    @Test
    fun `config file values are loaded`() {
        val file = Files.createTempFile("gigasoft-standalone-config", ".properties")
        Files.writeString(
            file,
            """
            plugins=plugins-from-file
            data=data-from-file
            adapterTimeoutMillis=333
            adapterExecutionMode=fast
            adapterRateLimitPerMinute=99
            netTextFlushEveryResponses=2
            netFrameFlushEveryResponses=3
            standalone.maxPlayers=7
            """.trimIndent()
        )

        val cfg = parseLaunchConfig(arrayOf("--config", file.toString()))
        assertEquals("plugins-from-file", cfg.pluginsDir)
        assertEquals("data-from-file", cfg.dataDir)
        assertEquals(333L, cfg.adapterTimeoutMillis)
        assertEquals("fast", cfg.adapterExecutionMode)
        assertEquals(99, cfg.adapterRateLimitPerMinute)
        assertEquals(2, cfg.netTextFlushEveryResponses)
        assertEquals(3, cfg.netFrameFlushEveryResponses)
        assertEquals(7, cfg.maxPlayers)
    }

    @Test
    fun `cli args override config file`() {
        val file = Files.createTempFile("gigasoft-standalone-config", ".properties")
        Files.writeString(
            file,
            """
            netAuthRequired=false
            adapterTimeoutMillis=400
            """.trimIndent()
        )

        val cfg = parseLaunchConfig(
            arrayOf(
                "--config", file.toString(),
                "--net-auth-required", "true",
                "--adapter-timeout-ms", "120",
                "--adapter-mode", "fast",
                "--net-text-flush-every", "4"
            )
        )
        assertTrue(cfg.netAuthRequired)
        assertEquals(120L, cfg.adapterTimeoutMillis)
        assertEquals("fast", cfg.adapterExecutionMode)
        assertEquals(4, cfg.netTextFlushEveryResponses)
    }
}
