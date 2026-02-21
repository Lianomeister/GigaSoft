package com.clockwork.runtime

enum class AdapterExecutionMode {
    // Full guardrails: payload validation, quotas, concurrency limits, timeout sandbox.
    SAFE,
    // Low-latency mode: keeps input validation, but bypasses quotas/concurrency/timeout checks.
    FAST
}

enum class AdapterPayloadPolicyProfile {
    STRICT,
    BALANCED,
    PERF
}

data class AdapterPayloadPolicyPreset(
    val maxPayloadEntries: Int,
    val maxPayloadKeyChars: Int,
    val maxPayloadValueChars: Int,
    val maxPayloadTotalChars: Int
)

object AdapterPayloadPolicyPresets {
    val STRICT: AdapterPayloadPolicyPreset = AdapterPayloadPolicyPreset(
        maxPayloadEntries = 16,
        maxPayloadKeyChars = 48,
        maxPayloadValueChars = 256,
        maxPayloadTotalChars = 2048
    )
    val BALANCED: AdapterPayloadPolicyPreset = AdapterPayloadPolicyPreset(
        maxPayloadEntries = 32,
        maxPayloadKeyChars = 64,
        maxPayloadValueChars = 512,
        maxPayloadTotalChars = 4096
    )
    val PERF: AdapterPayloadPolicyPreset = AdapterPayloadPolicyPreset(
        maxPayloadEntries = 64,
        maxPayloadKeyChars = 96,
        maxPayloadValueChars = 1024,
        maxPayloadTotalChars = 8192
    )

    fun forProfile(profile: AdapterPayloadPolicyProfile): AdapterPayloadPolicyPreset {
        return when (profile) {
            AdapterPayloadPolicyProfile.STRICT -> STRICT
            AdapterPayloadPolicyProfile.BALANCED -> BALANCED
            AdapterPayloadPolicyProfile.PERF -> PERF
        }
    }
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
    val auditRetentionMaxMemoryBytes: Long = 512L * 1024L,
    val payloadPolicyProfile: AdapterPayloadPolicyProfile = AdapterPayloadPolicyProfile.BALANCED,
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
        require(auditRetentionMaxMemoryBytes > 0L) { "auditRetentionMaxMemoryBytes must be > 0" }
    }
}

data class RuntimeSecurityThresholdsConfig(
    val schemaVersion: Int = LATEST_SCHEMA_VERSION,
    val adapter: AdapterSecurityConfig = AdapterSecurityConfig(),
    val faultBudget: FaultBudgetPolicy = FaultBudgetPolicy(),
    val faultBudgetEscalation: FaultBudgetEscalationPolicy = FaultBudgetEscalationPolicy()
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
        adapterAuditRetentionMaxMemoryBytes: Long,
        adapterPayloadPolicyProfile: String,
        adapterExecutionMode: String,
        faultBudgetMaxFaultsPerWindow: Int,
        faultBudgetWindowMillis: Long,
        faultBudgetWarnUsageRatio: Double,
        faultBudgetThrottleUsageRatio: Double,
        faultBudgetIsolateUsageRatio: Double,
        faultBudgetThrottleBudgetMultiplier: Double
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

        fun ratio(name: String, value: Double, default: Double): Double {
            return if (value > 0.0 && value <= 1.0) value else {
                warnings += "Invalid $name=$value; using safe default $default."
                default
            }
        }

        fun fraction(name: String, value: Double, default: Double): Double {
            return if (value > 0.0 && value <= 1.0) value else {
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

        val payloadPolicyProfile = when (adapterPayloadPolicyProfile.trim().lowercase()) {
            "strict" -> AdapterPayloadPolicyProfile.STRICT
            "balanced" -> AdapterPayloadPolicyProfile.BALANCED
            "perf" -> AdapterPayloadPolicyProfile.PERF
            else -> {
                warnings += "Invalid adapterPayloadPolicyProfile='$adapterPayloadPolicyProfile'; using balanced."
                AdapterPayloadPolicyProfile.BALANCED
            }
        }
        val payloadPolicyPreset = AdapterPayloadPolicyPresets.forProfile(payloadPolicyProfile)

        fun payloadThreshold(name: String, value: Int, profileDefault: Int): Int {
            if (value == -1) return profileDefault
            return positiveInt(name, value, profileDefault)
        }

        val adapter = AdapterSecurityConfig(
            maxPayloadEntries = payloadThreshold("adapterMaxPayloadEntries", adapterMaxPayloadEntries, payloadPolicyPreset.maxPayloadEntries),
            maxPayloadKeyChars = payloadThreshold("adapterMaxPayloadKeyChars", adapterMaxPayloadKeyChars, payloadPolicyPreset.maxPayloadKeyChars),
            maxPayloadValueChars = payloadThreshold("adapterMaxPayloadValueChars", adapterMaxPayloadValueChars, payloadPolicyPreset.maxPayloadValueChars),
            maxPayloadTotalChars = payloadThreshold("adapterMaxPayloadTotalChars", adapterMaxPayloadTotalChars, payloadPolicyPreset.maxPayloadTotalChars),
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
            auditRetentionMaxMemoryBytes = positiveLong(
                "adapterAuditRetentionMaxMemoryBytes",
                adapterAuditRetentionMaxMemoryBytes,
                defaults.adapter.auditRetentionMaxMemoryBytes
            ),
            payloadPolicyProfile = payloadPolicyProfile,
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

        val escalationDefaults = defaults.faultBudgetEscalation
        val warnRatio = ratio(
            "faultBudgetWarnUsageRatio",
            faultBudgetWarnUsageRatio,
            escalationDefaults.warnUsageRatio
        )
        val throttleRatio = ratio(
            "faultBudgetThrottleUsageRatio",
            faultBudgetThrottleUsageRatio,
            escalationDefaults.throttleUsageRatio
        )
        val isolateRatio = ratio(
            "faultBudgetIsolateUsageRatio",
            faultBudgetIsolateUsageRatio,
            escalationDefaults.isolateUsageRatio
        )
        val throttleMultiplier = fraction(
            "faultBudgetThrottleBudgetMultiplier",
            faultBudgetThrottleBudgetMultiplier,
            escalationDefaults.throttleBudgetMultiplier
        )
        val normalizedWarn = if (warnRatio > throttleRatio) {
            warnings += "faultBudgetWarnUsageRatio=$warnRatio exceeds throttle ratio=$throttleRatio; clamping to throttle."
            throttleRatio
        } else warnRatio
        val normalizedThrottle = if (throttleRatio > isolateRatio) {
            warnings += "faultBudgetThrottleUsageRatio=$throttleRatio exceeds isolate ratio=$isolateRatio; clamping to isolate."
            isolateRatio
        } else throttleRatio
        val faultBudgetEscalation = FaultBudgetEscalationPolicy(
            warnUsageRatio = normalizedWarn,
            throttleUsageRatio = normalizedThrottle,
            isolateUsageRatio = isolateRatio,
            throttleBudgetMultiplier = throttleMultiplier
        )

        return RuntimeSecurityThresholdsValidationResult(
            config = RuntimeSecurityThresholdsConfig(
                schemaVersion = schemaVersion,
                adapter = adapter,
                faultBudget = faultBudget,
                faultBudgetEscalation = faultBudgetEscalation
            ),
            warnings = warnings
        )
    }
}
