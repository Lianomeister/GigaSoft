package com.gigasoft.runtime

enum class AdapterExecutionMode {
    // Full guardrails: payload validation, quotas, concurrency limits, timeout sandbox.
    SAFE,
    // Low-latency mode: keeps input validation, but bypasses quotas/concurrency/timeout checks.
    FAST
}

enum class EventDispatchMode {
    EXACT,
    POLYMORPHIC
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
    }
}
