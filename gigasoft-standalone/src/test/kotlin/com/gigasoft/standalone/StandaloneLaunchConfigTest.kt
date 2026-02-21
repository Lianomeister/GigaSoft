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
            adapterRateLimitPerMinutePerPlugin=1111
            adapterMaxConcurrentInvocationsPerAdapter=3
            adapterAuditLogEnabled=true
            adapterAuditLogSuccesses=true
            netMaxConcurrentSessions=123
            netMaxSessionsPerIp=7
            netMaxRequestsPerMinutePerConnection=300
            netMaxRequestsPerMinutePerIp=600
            netMaxJsonPayloadEntries=22
            netMaxJsonPayloadKeyChars=33
            netMaxJsonPayloadValueChars=222
            netMaxJsonPayloadTotalChars=2048
            netAuditLogEnabled=false
            netMaxTextLineBytes=8192
            netReadTimeoutMillis=15000
            netTextFlushEveryResponses=2
            netFrameFlushEveryResponses=3
            eventDispatchMode=polymorphic
            standalone.maxPlayers=7
            """.trimIndent()
        )

        val cfg = parseLaunchConfig(arrayOf("--config", file.toString()))
        assertEquals("plugins-from-file", cfg.pluginsDir)
        assertEquals("data-from-file", cfg.dataDir)
        assertEquals(333L, cfg.adapterTimeoutMillis)
        assertEquals("fast", cfg.adapterExecutionMode)
        assertEquals(99, cfg.adapterRateLimitPerMinute)
        assertEquals(1111, cfg.adapterRateLimitPerMinutePerPlugin)
        assertEquals(3, cfg.adapterMaxConcurrentInvocationsPerAdapter)
        assertTrue(cfg.adapterAuditLogEnabled)
        assertTrue(cfg.adapterAuditLogSuccesses)
        assertEquals(123, cfg.netMaxConcurrentSessions)
        assertEquals(7, cfg.netMaxSessionsPerIp)
        assertEquals(300, cfg.netMaxRequestsPerMinutePerConnection)
        assertEquals(600, cfg.netMaxRequestsPerMinutePerIp)
        assertEquals(22, cfg.netMaxJsonPayloadEntries)
        assertEquals(33, cfg.netMaxJsonPayloadKeyChars)
        assertEquals(222, cfg.netMaxJsonPayloadValueChars)
        assertEquals(2048, cfg.netMaxJsonPayloadTotalChars)
        assertTrue(!cfg.netAuditLogEnabled)
        assertEquals(8192, cfg.netMaxTextLineBytes)
        assertEquals(15000, cfg.netReadTimeoutMillis)
        assertEquals(2, cfg.netTextFlushEveryResponses)
        assertEquals(3, cfg.netFrameFlushEveryResponses)
        assertEquals("polymorphic", cfg.eventDispatchMode)
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
                "--adapter-rate-limit-per-minute-per-plugin", "900",
                "--adapter-max-concurrent-per-adapter", "2",
                "--adapter-audit-log-enabled", "false",
                "--adapter-audit-log-successes", "true",
                "--net-max-concurrent-sessions", "111",
                "--net-max-sessions-per-ip", "4",
                "--net-max-rpm-per-connection", "250",
                "--net-max-rpm-per-ip", "500",
                "--net-max-json-payload-entries", "16",
                "--net-max-json-payload-key-chars", "32",
                "--net-max-json-payload-value-chars", "160",
                "--net-max-json-payload-total-chars", "1024",
                "--net-audit-log-enabled", "false",
                "--net-max-text-line-bytes", "4096",
                "--net-read-timeout-ms", "5000",
                "--net-text-flush-every", "4",
                "--event-dispatch-mode", "polymorphic"
            )
        )
        assertTrue(cfg.netAuthRequired)
        assertEquals(120L, cfg.adapterTimeoutMillis)
        assertEquals("fast", cfg.adapterExecutionMode)
        assertEquals(900, cfg.adapterRateLimitPerMinutePerPlugin)
        assertEquals(2, cfg.adapterMaxConcurrentInvocationsPerAdapter)
        assertTrue(!cfg.adapterAuditLogEnabled)
        assertTrue(cfg.adapterAuditLogSuccesses)
        assertEquals(111, cfg.netMaxConcurrentSessions)
        assertEquals(4, cfg.netMaxSessionsPerIp)
        assertEquals(250, cfg.netMaxRequestsPerMinutePerConnection)
        assertEquals(500, cfg.netMaxRequestsPerMinutePerIp)
        assertEquals(16, cfg.netMaxJsonPayloadEntries)
        assertEquals(32, cfg.netMaxJsonPayloadKeyChars)
        assertEquals(160, cfg.netMaxJsonPayloadValueChars)
        assertEquals(1024, cfg.netMaxJsonPayloadTotalChars)
        assertTrue(!cfg.netAuditLogEnabled)
        assertEquals(4096, cfg.netMaxTextLineBytes)
        assertEquals(5000, cfg.netReadTimeoutMillis)
        assertEquals(4, cfg.netTextFlushEveryResponses)
        assertEquals("polymorphic", cfg.eventDispatchMode)
    }
}
