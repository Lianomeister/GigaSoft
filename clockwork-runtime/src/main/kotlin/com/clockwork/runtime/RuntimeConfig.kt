package com.clockwork.runtime

enum class AdapterExecutionMode {
    // Full guardrails: payload validation, quotas, concurrency limits, timeout sandbox.
    SAFE,
    // Low-latency mode: keeps input validation, but bypasses quotas/concurrency/timeout checks.
    FAST
}

enum class EventDispatchMode {
    EXACT,
    POLYMORPHIC,
    // Dispatches exact listeners first, then matching supertypes/interfaces in deterministic order.
    HYBRID
}

data class AdapterSecurityConfig(
    val maxPayloadEntries: Int = 32,
    val maxPayloadKeyChars: Int = 64,
    val maxPayloadValueChars: Int = 512,
    val maxPayloadTotalChars: Int = 4096,
    val maxCallsPerMinute: Int = 180,
    val maxCallsPerMinutePerPlugin: Int = 0,
    val maxConcurrentInvocationsPerAdapter: Int = 0,
    val invocationTimeoutMillis: Long = 250L,
    val auditLogEnabled: Boolean = true,
    val auditLogSuccesses: Boolean = false,
    val auditRetentionMaxEntriesPerPlugin: Int = 512,
    val auditRetentionMaxEntriesPerAdapter: Int = 128,
    val auditRetentionMaxAgeMillis: Long = 300_000L,
    val executionMode: AdapterExecutionMode = AdapterExecutionMode.SAFE
) {
    init {
        require(maxPayloadEntries > 0) { "maxPayloadEntries must be > 0" }
        require(maxPayloadKeyChars > 0) { "maxPayloadKeyChars must be > 0" }
        require(maxPayloadValueChars > 0) { "maxPayloadValueChars must be > 0" }
        require(maxPayloadTotalChars > 0) { "maxPayloadTotalChars must be > 0" }
        require(maxCallsPerMinute >= 0) { "maxCallsPerMinute must be >= 0" }
        require(maxCallsPerMinutePerPlugin >= 0) { "maxCallsPerMinutePerPlugin must be >= 0" }
        require(maxConcurrentInvocationsPerAdapter >= 0) { "maxConcurrentInvocationsPerAdapter must be >= 0" }
        require(invocationTimeoutMillis >= 0L) { "invocationTimeoutMillis must be >= 0" }
        require(auditRetentionMaxEntriesPerPlugin > 0) { "auditRetentionMaxEntriesPerPlugin must be > 0" }
        require(auditRetentionMaxEntriesPerAdapter > 0) { "auditRetentionMaxEntriesPerAdapter must be > 0" }
        require(auditRetentionMaxAgeMillis > 0L) { "auditRetentionMaxAgeMillis must be > 0" }
    }
}

data class RuntimeSecurityThresholdsConfig(
    val schemaVersion: Int = LATEST_SCHEMA_VERSION,
    val adapter: AdapterSecurityConfig = AdapterSecurityConfig(),
    val faultBudget: FaultBudgetPolicy = FaultBudgetPolicy()
) {
    companion object {
        const val LATEST_SCHEMA_VERSION: Int = 1
    }
}

data class RuntimeSecurityThresholdsValidationResult(
    val config: RuntimeSecurityThresholdsConfig,
    val warnings: List<String>
)

object RuntimeSecurityThresholdsValidator {
    fun normalize(
        schemaVersion: Int,
        adapterTimeoutMillis: Long,
        adapterRateLimitPerMinute: Int,
        adapterRateLimitPerMinutePerPlugin: Int,
        adapterMaxPayloadEntries: Int,
        adapterMaxPayloadTotalChars: Int,
        adapterMaxPayloadKeyChars: Int,
        adapterMaxPayloadValueChars: Int,
        adapterMaxConcurrentInvocationsPerAdapter: Int,
        adapterAuditLogEnabled: Boolean,
        adapterAuditLogSuccesses: Boolean,
        adapterAuditRetentionMaxEntriesPerPlugin: Int,
        adapterAuditRetentionMaxEntriesPerAdapter: Int,
        adapterAuditRetentionMaxAgeMillis: Long,
        adapterExecutionMode: String,
        faultBudgetMaxFaultsPerWindow: Int,
        faultBudgetWindowMillis: Long
    ): RuntimeSecurityThresholdsValidationResult {
        val warnings = mutableListOf<String>()
        val defaults = RuntimeSecurityThresholdsConfig()
        if (schemaVersion != RuntimeSecurityThresholdsConfig.LATEST_SCHEMA_VERSION) {
            warnings += "Unsupported security config schemaVersion=$schemaVersion; falling back to safe defaults (schema=${RuntimeSecurityThresholdsConfig.LATEST_SCHEMA_VERSION})."
            return RuntimeSecurityThresholdsValidationResult(defaults, warnings)
        }

        fun positiveInt(name: String, value: Int, default: Int): Int {
            return if (value > 0) value else {
                warnings += "Invalid $name=$value; using safe default $default."
                default
            }
        }

        fun nonNegativeInt(name: String, value: Int, default: Int): Int {
            return if (value >= 0) value else {
                warnings += "Invalid $name=$value; using safe default $default."
                default
            }
        }

        fun positiveLong(name: String, value: Long, default: Long): Long {
            return if (value > 0L) value else {
                warnings += "Invalid $name=$value; using safe default $default."
                default
            }
        }

        val mode = when (adapterExecutionMode.trim().lowercase()) {
            "fast" -> AdapterExecutionMode.FAST
            "safe" -> AdapterExecutionMode.SAFE
            else -> {
                warnings += "Invalid adapterExecutionMode='$adapterExecutionMode'; using safe."
                AdapterExecutionMode.SAFE
            }
        }

        val adapter = AdapterSecurityConfig(
            maxPayloadEntries = positiveInt("adapterMaxPayloadEntries", adapterMaxPayloadEntries, defaults.adapter.maxPayloadEntries),
            maxPayloadKeyChars = positiveInt("adapterMaxPayloadKeyChars", adapterMaxPayloadKeyChars, defaults.adapter.maxPayloadKeyChars),
            maxPayloadValueChars = positiveInt("adapterMaxPayloadValueChars", adapterMaxPayloadValueChars, defaults.adapter.maxPayloadValueChars),
            maxPayloadTotalChars = positiveInt("adapterMaxPayloadTotalChars", adapterMaxPayloadTotalChars, defaults.adapter.maxPayloadTotalChars),
            maxCallsPerMinute = nonNegativeInt("adapterRateLimitPerMinute", adapterRateLimitPerMinute, defaults.adapter.maxCallsPerMinute),
            maxCallsPerMinutePerPlugin = nonNegativeInt("adapterRateLimitPerMinutePerPlugin", adapterRateLimitPerMinutePerPlugin, defaults.adapter.maxCallsPerMinutePerPlugin),
            maxConcurrentInvocationsPerAdapter = nonNegativeInt(
                "adapterMaxConcurrentInvocationsPerAdapter",
                adapterMaxConcurrentInvocationsPerAdapter,
                defaults.adapter.maxConcurrentInvocationsPerAdapter
            ),
            invocationTimeoutMillis = positiveLong("adapterTimeoutMillis", adapterTimeoutMillis, defaults.adapter.invocationTimeoutMillis),
            auditLogEnabled = adapterAuditLogEnabled,
            auditLogSuccesses = adapterAuditLogSuccesses,
            auditRetentionMaxEntriesPerPlugin = positiveInt(
                "adapterAuditRetentionMaxEntriesPerPlugin",
                adapterAuditRetentionMaxEntriesPerPlugin,
                defaults.adapter.auditRetentionMaxEntriesPerPlugin
            ),
            auditRetentionMaxEntriesPerAdapter = positiveInt(
                "adapterAuditRetentionMaxEntriesPerAdapter",
                adapterAuditRetentionMaxEntriesPerAdapter,
                defaults.adapter.auditRetentionMaxEntriesPerAdapter
            ),
            auditRetentionMaxAgeMillis = positiveLong(
                "adapterAuditRetentionMaxAgeMillis",
                adapterAuditRetentionMaxAgeMillis,
                defaults.adapter.auditRetentionMaxAgeMillis
            ),
            executionMode = mode
        )

        val faultBudget = FaultBudgetPolicy(
            maxFaultsPerWindow = positiveInt(
                "faultBudgetMaxFaultsPerWindow",
                faultBudgetMaxFaultsPerWindow,
                defaults.faultBudget.maxFaultsPerWindow
            ),
            windowMillis = positiveLong(
                "faultBudgetWindowMillis",
                faultBudgetWindowMillis,
                defaults.faultBudget.windowMillis
            )
        )

        return RuntimeSecurityThresholdsValidationResult(
            config = RuntimeSecurityThresholdsConfig(
                schemaVersion = schemaVersion,
                adapter = adapter,
                faultBudget = faultBudget
            ),
            warnings = warnings
        )
    }
}
