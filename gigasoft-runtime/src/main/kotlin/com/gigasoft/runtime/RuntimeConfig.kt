package com.gigasoft.runtime

enum class AdapterExecutionMode {
    SAFE,
    FAST
}

data class AdapterSecurityConfig(
    val maxPayloadEntries: Int = 32,
    val maxPayloadKeyChars: Int = 64,
    val maxPayloadValueChars: Int = 512,
    val maxPayloadTotalChars: Int = 4096,
    val maxCallsPerMinute: Int = 180,
    val invocationTimeoutMillis: Long = 250L,
    val executionMode: AdapterExecutionMode = AdapterExecutionMode.SAFE
) {
    init {
        require(maxPayloadEntries > 0) { "maxPayloadEntries must be > 0" }
        require(maxPayloadKeyChars > 0) { "maxPayloadKeyChars must be > 0" }
        require(maxPayloadValueChars > 0) { "maxPayloadValueChars must be > 0" }
        require(maxPayloadTotalChars > 0) { "maxPayloadTotalChars must be > 0" }
        require(maxCallsPerMinute >= 0) { "maxCallsPerMinute must be >= 0" }
        require(invocationTimeoutMillis >= 0L) { "invocationTimeoutMillis must be >= 0" }
    }
}
