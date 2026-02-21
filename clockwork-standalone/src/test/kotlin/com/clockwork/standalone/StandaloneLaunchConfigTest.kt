package com.clockwork.standalone

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StandaloneLaunchConfigTest {
    @Test
    fun `config file values are loaded`() {
        val file = Files.createTempFile("clockwork-standalone-config", ".properties")
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
            adapterAuditRetentionMaxEntriesPerPlugin=333
            adapterAuditRetentionMaxEntriesPerAdapter=44
            adapterAuditRetentionMaxAgeMillis=120000
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
            securityConfigSchemaVersion=1
            faultBudgetMaxFaultsPerWindow=77
            faultBudgetWindowMillis=45000
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
        assertEquals(333, cfg.adapterAuditRetentionMaxEntriesPerPlugin)
        assertEquals(44, cfg.adapterAuditRetentionMaxEntriesPerAdapter)
        assertEquals(120000L, cfg.adapterAuditRetentionMaxAgeMillis)
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
        assertEquals(1, cfg.securityConfigSchemaVersion)
        assertEquals(77, cfg.faultBudgetMaxFaultsPerWindow)
        assertEquals(45000L, cfg.faultBudgetWindowMillis)
        assertTrue(cfg.securityConfigWarnings.isEmpty())
        assertEquals("polymorphic", cfg.eventDispatchMode)
        assertEquals(7, cfg.maxPlayers)
    }

    @Test
    fun `cli args override config file`() {
        val file = Files.createTempFile("clockwork-standalone-config", ".properties")
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
                "--adapter-audit-retention-max-entries-per-plugin", "222",
                "--adapter-audit-retention-max-entries-per-adapter", "33",
                "--adapter-audit-retention-max-age-ms", "90000",
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
                "--fault-budget-max-faults", "44",
                "--fault-budget-window-ms", "90000",
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
        assertEquals(222, cfg.adapterAuditRetentionMaxEntriesPerPlugin)
        assertEquals(33, cfg.adapterAuditRetentionMaxEntriesPerAdapter)
        assertEquals(90000L, cfg.adapterAuditRetentionMaxAgeMillis)
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
        assertEquals(44, cfg.faultBudgetMaxFaultsPerWindow)
        assertEquals(90000L, cfg.faultBudgetWindowMillis)
        assertEquals("polymorphic", cfg.eventDispatchMode)
    }

    @Test
    fun `invalid security values fall back to safe defaults with warnings`() {
        val file = Files.createTempFile("clockwork-standalone-config", ".properties")
        Files.writeString(
            file,
            """
            securityConfigSchemaVersion=1
            adapterTimeoutMillis=-1
            adapterRateLimitPerMinute=-5
            adapterRateLimitPerMinutePerPlugin=-9
            adapterMaxConcurrentInvocationsPerAdapter=-2
            adapterMaxPayloadEntries=0
            adapterMaxPayloadTotalChars=0
            adapterMaxPayloadKeyChars=-3
            adapterMaxPayloadValueChars=0
            adapterAuditRetentionMaxEntriesPerPlugin=0
            adapterAuditRetentionMaxEntriesPerAdapter=-1
            adapterAuditRetentionMaxAgeMillis=0
            adapterExecutionMode=broken
            faultBudgetMaxFaultsPerWindow=0
            faultBudgetWindowMillis=-100
            """.trimIndent()
        )

        val cfg = parseLaunchConfig(arrayOf("--config", file.toString()))
        assertEquals(250L, cfg.adapterTimeoutMillis)
        assertEquals(180, cfg.adapterRateLimitPerMinute)
        assertEquals(0, cfg.adapterRateLimitPerMinutePerPlugin)
        assertEquals(0, cfg.adapterMaxConcurrentInvocationsPerAdapter)
        assertEquals(32, cfg.adapterMaxPayloadEntries)
        assertEquals(4096, cfg.adapterMaxPayloadTotalChars)
        assertEquals(64, cfg.adapterMaxPayloadKeyChars)
        assertEquals(512, cfg.adapterMaxPayloadValueChars)
        assertEquals(512, cfg.adapterAuditRetentionMaxEntriesPerPlugin)
        assertEquals(128, cfg.adapterAuditRetentionMaxEntriesPerAdapter)
        assertEquals(300000L, cfg.adapterAuditRetentionMaxAgeMillis)
        assertEquals("safe", cfg.adapterExecutionMode)
        assertEquals(100, cfg.faultBudgetMaxFaultsPerWindow)
        assertEquals(60000L, cfg.faultBudgetWindowMillis)
        assertTrue(cfg.securityConfigWarnings.isNotEmpty())
    }

    @Test
    fun `unsupported security schema falls back to full defaults`() {
        val file = Files.createTempFile("clockwork-standalone-config", ".properties")
        Files.writeString(
            file,
            """
            securityConfigSchemaVersion=999
            adapterTimeoutMillis=120
            adapterRateLimitPerMinute=77
            faultBudgetMaxFaultsPerWindow=8
            """.trimIndent()
        )

        val cfg = parseLaunchConfig(arrayOf("--config", file.toString()))
        assertEquals(1, cfg.securityConfigSchemaVersion)
        assertEquals(250L, cfg.adapterTimeoutMillis)
        assertEquals(180, cfg.adapterRateLimitPerMinute)
        assertEquals(100, cfg.faultBudgetMaxFaultsPerWindow)
        assertTrue(cfg.securityConfigWarnings.any { it.contains("Unsupported security config schemaVersion") })
    }
}
