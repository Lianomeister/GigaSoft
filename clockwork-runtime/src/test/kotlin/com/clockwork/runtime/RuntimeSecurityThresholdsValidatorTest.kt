package com.clockwork.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RuntimeSecurityThresholdsValidatorTest {
    @Test
    fun `valid schema keeps provided values`() {
        val result = RuntimeSecurityThresholdsValidator.normalize(
            schemaVersion = 1,
            adapterTimeoutMillis = 120L,
            adapterRateLimitPerMinute = 70,
            adapterRateLimitPerMinutePerPlugin = 700,
            adapterMaxPayloadEntries = 16,
            adapterMaxPayloadTotalChars = 2048,
            adapterMaxPayloadKeyChars = 40,
            adapterMaxPayloadValueChars = 300,
            adapterMaxConcurrentInvocationsPerAdapter = 4,
            adapterAuditLogEnabled = false,
            adapterAuditLogSuccesses = true,
            adapterAuditRetentionMaxEntriesPerPlugin = 400,
            adapterAuditRetentionMaxEntriesPerAdapter = 80,
            adapterAuditRetentionMaxAgeMillis = 120_000L,
            adapterExecutionMode = "fast",
            faultBudgetMaxFaultsPerWindow = 55,
            faultBudgetWindowMillis = 30_000L
        )

        assertTrue(result.warnings.isEmpty())
        assertEquals(1, result.config.schemaVersion)
        assertEquals(120L, result.config.adapter.invocationTimeoutMillis)
        assertEquals(70, result.config.adapter.maxCallsPerMinute)
        assertEquals(700, result.config.adapter.maxCallsPerMinutePerPlugin)
        assertEquals(16, result.config.adapter.maxPayloadEntries)
        assertEquals(2048, result.config.adapter.maxPayloadTotalChars)
        assertEquals(40, result.config.adapter.maxPayloadKeyChars)
        assertEquals(300, result.config.adapter.maxPayloadValueChars)
        assertEquals(4, result.config.adapter.maxConcurrentInvocationsPerAdapter)
        assertEquals(false, result.config.adapter.auditLogEnabled)
        assertEquals(true, result.config.adapter.auditLogSuccesses)
        assertEquals(400, result.config.adapter.auditRetentionMaxEntriesPerPlugin)
        assertEquals(80, result.config.adapter.auditRetentionMaxEntriesPerAdapter)
        assertEquals(120_000L, result.config.adapter.auditRetentionMaxAgeMillis)
        assertEquals(AdapterExecutionMode.FAST, result.config.adapter.executionMode)
        assertEquals(55, result.config.faultBudget.maxFaultsPerWindow)
        assertEquals(30_000L, result.config.faultBudget.windowMillis)
    }

    @Test
    fun `unsupported schema falls back to safe defaults`() {
        val result = RuntimeSecurityThresholdsValidator.normalize(
            schemaVersion = 999,
            adapterTimeoutMillis = 1L,
            adapterRateLimitPerMinute = 1,
            adapterRateLimitPerMinutePerPlugin = 1,
            adapterMaxPayloadEntries = 1,
            adapterMaxPayloadTotalChars = 1,
            adapterMaxPayloadKeyChars = 1,
            adapterMaxPayloadValueChars = 1,
            adapterMaxConcurrentInvocationsPerAdapter = 1,
            adapterAuditLogEnabled = false,
            adapterAuditLogSuccesses = true,
            adapterAuditRetentionMaxEntriesPerPlugin = 1,
            adapterAuditRetentionMaxEntriesPerAdapter = 1,
            adapterAuditRetentionMaxAgeMillis = 1L,
            adapterExecutionMode = "fast",
            faultBudgetMaxFaultsPerWindow = 1,
            faultBudgetWindowMillis = 1L
        )

        assertEquals(RuntimeSecurityThresholdsConfig(), result.config)
        assertTrue(result.warnings.any { it.contains("Unsupported security config schemaVersion") })
    }

    @Test
    fun `invalid values are sanitized to safe defaults`() {
        val defaults = RuntimeSecurityThresholdsConfig()
        val result = RuntimeSecurityThresholdsValidator.normalize(
            schemaVersion = 1,
            adapterTimeoutMillis = -1L,
            adapterRateLimitPerMinute = -1,
            adapterRateLimitPerMinutePerPlugin = -5,
            adapterMaxPayloadEntries = 0,
            adapterMaxPayloadTotalChars = 0,
            adapterMaxPayloadKeyChars = -4,
            adapterMaxPayloadValueChars = 0,
            adapterMaxConcurrentInvocationsPerAdapter = -1,
            adapterAuditLogEnabled = true,
            adapterAuditLogSuccesses = false,
            adapterAuditRetentionMaxEntriesPerPlugin = 0,
            adapterAuditRetentionMaxEntriesPerAdapter = -1,
            adapterAuditRetentionMaxAgeMillis = 0L,
            adapterExecutionMode = "???",
            faultBudgetMaxFaultsPerWindow = 0,
            faultBudgetWindowMillis = -10L
        )

        assertEquals(defaults.adapter.invocationTimeoutMillis, result.config.adapter.invocationTimeoutMillis)
        assertEquals(defaults.adapter.maxCallsPerMinute, result.config.adapter.maxCallsPerMinute)
        assertEquals(defaults.adapter.maxCallsPerMinutePerPlugin, result.config.adapter.maxCallsPerMinutePerPlugin)
        assertEquals(defaults.adapter.maxPayloadEntries, result.config.adapter.maxPayloadEntries)
        assertEquals(defaults.adapter.maxPayloadTotalChars, result.config.adapter.maxPayloadTotalChars)
        assertEquals(defaults.adapter.maxPayloadKeyChars, result.config.adapter.maxPayloadKeyChars)
        assertEquals(defaults.adapter.maxPayloadValueChars, result.config.adapter.maxPayloadValueChars)
        assertEquals(defaults.adapter.maxConcurrentInvocationsPerAdapter, result.config.adapter.maxConcurrentInvocationsPerAdapter)
        assertEquals(defaults.adapter.auditRetentionMaxEntriesPerPlugin, result.config.adapter.auditRetentionMaxEntriesPerPlugin)
        assertEquals(defaults.adapter.auditRetentionMaxEntriesPerAdapter, result.config.adapter.auditRetentionMaxEntriesPerAdapter)
        assertEquals(defaults.adapter.auditRetentionMaxAgeMillis, result.config.adapter.auditRetentionMaxAgeMillis)
        assertEquals(defaults.adapter.executionMode, result.config.adapter.executionMode)
        assertEquals(defaults.faultBudget.maxFaultsPerWindow, result.config.faultBudget.maxFaultsPerWindow)
        assertEquals(defaults.faultBudget.windowMillis, result.config.faultBudget.windowMillis)
        assertTrue(result.warnings.size >= 8)
    }
}
