package com.clockwork.standalone

import com.clockwork.runtime.RuntimeSecurityThresholdsConfig
import com.clockwork.runtime.RuntimeSecurityThresholdsValidator
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

data class StandaloneLaunchConfig(
    val pluginsDir: String,
    val dataDir: String,
    val serverName: String,
    val serverVersion: String,
    val maxPlayers: Int,
    val tickPeriodMillis: Long,
    val autoSaveEveryTicks: Long,
    val netPort: Int,
    val netSharedSecret: String?,
    val netAdminSecret: String?,
    val netSessionTtlSeconds: Long,
    val netAuthRequired: Boolean,
    val netMaxTextLineBytes: Int,
    val netReadTimeoutMillis: Int,
    val netMaxConcurrentSessions: Int,
    val netMaxSessionsPerIp: Int,
    val netMaxRequestsPerMinutePerConnection: Int,
    val netMaxRequestsPerMinutePerIp: Int,
    val netMaxJsonPayloadEntries: Int,
    val netMaxJsonPayloadKeyChars: Int,
    val netMaxJsonPayloadValueChars: Int,
    val netMaxJsonPayloadTotalChars: Int,
    val netAuditLogEnabled: Boolean,
    val netTextFlushEveryResponses: Int,
    val netFrameFlushEveryResponses: Int,
    val adapterTimeoutMillis: Long,
    val adapterExecutionMode: String,
    val adapterRateLimitPerMinute: Int,
    val adapterRateLimitPerMinutePerPlugin: Int,
    val adapterMaxConcurrentInvocationsPerAdapter: Int,
    val adapterAuditLogEnabled: Boolean,
    val adapterAuditLogSuccesses: Boolean,
    val adapterAuditRetentionMaxEntriesPerPlugin: Int,
    val adapterAuditRetentionMaxEntriesPerAdapter: Int,
    val adapterAuditRetentionMaxAgeMillis: Long,
    val adapterMaxPayloadEntries: Int,
    val adapterMaxPayloadTotalChars: Int,
    val adapterMaxPayloadKeyChars: Int,
    val adapterMaxPayloadValueChars: Int,
    val securityConfigSchemaVersion: Int,
    val faultBudgetMaxFaultsPerWindow: Int,
    val faultBudgetWindowMillis: Long,
    val eventDispatchMode: String,
    val securityConfigWarnings: List<String>
)

fun parseLaunchConfig(args: Array<String>): StandaloneLaunchConfig {
    val fileConfig = loadConfig(argValue(args, "--config"))
    val pluginsDir = stringOption(args, fileConfig, "--plugins", "plugins", "dev-runtime/giga-plugins")
    val dataDir = stringOption(args, fileConfig, "--data", "data", "dev-runtime/giga-data")
    val serverName = stringOption(args, fileConfig, "--name", "name", "Clockwork Standalone")
    val serverVersion = stringOption(args, fileConfig, "--version", "version", "1.5.0-rc.2")
    val maxPlayers = intOption(args, fileConfig, "--max-players", "maxPlayers", 0)
    val tickPeriodMillis = longOption(args, fileConfig, "--tick-ms", "tickPeriodMillis", 50L)
    val autoSaveEveryTicks = longOption(args, fileConfig, "--autosave-ticks", "autoSaveEveryTicks", 200L)
    val netPort = intOption(args, fileConfig, "--net-port", "netPort", 25570)
    val netSharedSecret = stringOption(args, fileConfig, "--net-shared-secret", "netSharedSecret", "").ifBlank { null }
    val netAdminSecret = stringOption(args, fileConfig, "--net-admin-secret", "netAdminSecret", "").ifBlank { null }
    val netSessionTtlSeconds = longOption(args, fileConfig, "--net-session-ttl-seconds", "netSessionTtlSeconds", 1800L)
    val netAuthRequired = boolOption(
        args = args,
        fileConfig = fileConfig,
        argKey = "--net-auth-required",
        configKey = "netAuthRequired",
        defaultValue = netSharedSecret != null
    )
    val netMaxTextLineBytes = intOption(args, fileConfig, "--net-max-text-line-bytes", "netMaxTextLineBytes", 16_384)
    val netReadTimeoutMillis = intOption(args, fileConfig, "--net-read-timeout-ms", "netReadTimeoutMillis", 30_000)
    val netMaxConcurrentSessions = intOption(args, fileConfig, "--net-max-concurrent-sessions", "netMaxConcurrentSessions", 256)
    val netMaxSessionsPerIp = intOption(args, fileConfig, "--net-max-sessions-per-ip", "netMaxSessionsPerIp", 32)
    val netMaxRequestsPerMinutePerConnection = intOption(args, fileConfig, "--net-max-rpm-per-connection", "netMaxRequestsPerMinutePerConnection", 6_000)
    val netMaxRequestsPerMinutePerIp = intOption(args, fileConfig, "--net-max-rpm-per-ip", "netMaxRequestsPerMinutePerIp", 20_000)
    val netMaxJsonPayloadEntries = intOption(args, fileConfig, "--net-max-json-payload-entries", "netMaxJsonPayloadEntries", 64)
    val netMaxJsonPayloadKeyChars = intOption(args, fileConfig, "--net-max-json-payload-key-chars", "netMaxJsonPayloadKeyChars", 64)
    val netMaxJsonPayloadValueChars = intOption(args, fileConfig, "--net-max-json-payload-value-chars", "netMaxJsonPayloadValueChars", 1024)
    val netMaxJsonPayloadTotalChars = intOption(args, fileConfig, "--net-max-json-payload-total-chars", "netMaxJsonPayloadTotalChars", 8192)
    val netAuditLogEnabled = boolOption(args, fileConfig, "--net-audit-log-enabled", "netAuditLogEnabled", true)
    val netTextFlushEveryResponses = intOption(args, fileConfig, "--net-text-flush-every", "netTextFlushEveryResponses", 1)
    val netFrameFlushEveryResponses = intOption(args, fileConfig, "--net-frame-flush-every", "netFrameFlushEveryResponses", 1)
    val adapterTimeoutMillis = longOption(args, fileConfig, "--adapter-timeout-ms", "adapterTimeoutMillis", 250L)
    val adapterExecutionMode = stringOption(args, fileConfig, "--adapter-mode", "adapterExecutionMode", "safe")
    val adapterRateLimitPerMinute = intOption(args, fileConfig, "--adapter-rate-limit-per-minute", "adapterRateLimitPerMinute", 180)
    val adapterRateLimitPerMinutePerPlugin = intOption(args, fileConfig, "--adapter-rate-limit-per-minute-per-plugin", "adapterRateLimitPerMinutePerPlugin", 2_000)
    val adapterMaxConcurrentInvocationsPerAdapter = intOption(args, fileConfig, "--adapter-max-concurrent-per-adapter", "adapterMaxConcurrentInvocationsPerAdapter", 8)
    val adapterAuditLogEnabled = boolOption(args, fileConfig, "--adapter-audit-log-enabled", "adapterAuditLogEnabled", true)
    val adapterAuditLogSuccesses = boolOption(args, fileConfig, "--adapter-audit-log-successes", "adapterAuditLogSuccesses", false)
    val adapterAuditRetentionMaxEntriesPerPlugin = intOption(
        args,
        fileConfig,
        "--adapter-audit-retention-max-entries-per-plugin",
        "adapterAuditRetentionMaxEntriesPerPlugin",
        512
    )
    val adapterAuditRetentionMaxEntriesPerAdapter = intOption(
        args,
        fileConfig,
        "--adapter-audit-retention-max-entries-per-adapter",
        "adapterAuditRetentionMaxEntriesPerAdapter",
        128
    )
    val adapterAuditRetentionMaxAgeMillis = longOption(
        args,
        fileConfig,
        "--adapter-audit-retention-max-age-ms",
        "adapterAuditRetentionMaxAgeMillis",
        300_000L
    )
    val adapterMaxPayloadEntries = intOption(args, fileConfig, "--adapter-max-payload-entries", "adapterMaxPayloadEntries", 32)
    val adapterMaxPayloadTotalChars = intOption(args, fileConfig, "--adapter-max-payload-total-chars", "adapterMaxPayloadTotalChars", 4096)
    val adapterMaxPayloadKeyChars = intOption(args, fileConfig, "--adapter-max-payload-key-chars", "adapterMaxPayloadKeyChars", 64)
    val adapterMaxPayloadValueChars = intOption(args, fileConfig, "--adapter-max-payload-value-chars", "adapterMaxPayloadValueChars", 512)
    val securityConfigSchemaVersion = intOption(
        args,
        fileConfig,
        "--security-config-schema-version",
        "securityConfigSchemaVersion",
        RuntimeSecurityThresholdsConfig.LATEST_SCHEMA_VERSION
    )
    val faultBudgetMaxFaultsPerWindow = intOption(
        args,
        fileConfig,
        "--fault-budget-max-faults",
        "faultBudgetMaxFaultsPerWindow",
        100
    )
    val faultBudgetWindowMillis = longOption(
        args,
        fileConfig,
        "--fault-budget-window-ms",
        "faultBudgetWindowMillis",
        60_000L
    )
    val eventDispatchMode = stringOption(args, fileConfig, "--event-dispatch-mode", "eventDispatchMode", "exact")

    val securityValidation = RuntimeSecurityThresholdsValidator.normalize(
        schemaVersion = securityConfigSchemaVersion,
        adapterTimeoutMillis = adapterTimeoutMillis,
        adapterRateLimitPerMinute = adapterRateLimitPerMinute,
        adapterRateLimitPerMinutePerPlugin = adapterRateLimitPerMinutePerPlugin,
        adapterMaxPayloadEntries = adapterMaxPayloadEntries,
        adapterMaxPayloadTotalChars = adapterMaxPayloadTotalChars,
        adapterMaxPayloadKeyChars = adapterMaxPayloadKeyChars,
        adapterMaxPayloadValueChars = adapterMaxPayloadValueChars,
        adapterMaxConcurrentInvocationsPerAdapter = adapterMaxConcurrentInvocationsPerAdapter,
        adapterAuditLogEnabled = adapterAuditLogEnabled,
        adapterAuditLogSuccesses = adapterAuditLogSuccesses,
        adapterAuditRetentionMaxEntriesPerPlugin = adapterAuditRetentionMaxEntriesPerPlugin,
        adapterAuditRetentionMaxEntriesPerAdapter = adapterAuditRetentionMaxEntriesPerAdapter,
        adapterAuditRetentionMaxAgeMillis = adapterAuditRetentionMaxAgeMillis,
        adapterExecutionMode = adapterExecutionMode,
        faultBudgetMaxFaultsPerWindow = faultBudgetMaxFaultsPerWindow,
        faultBudgetWindowMillis = faultBudgetWindowMillis
    )
    val normalizedSecurity = securityValidation.config

    return StandaloneLaunchConfig(
        pluginsDir = pluginsDir,
        dataDir = dataDir,
        serverName = serverName,
        serverVersion = serverVersion,
        maxPlayers = maxPlayers,
        tickPeriodMillis = tickPeriodMillis,
        autoSaveEveryTicks = autoSaveEveryTicks,
        netPort = netPort,
        netSharedSecret = netSharedSecret,
        netAdminSecret = netAdminSecret,
        netSessionTtlSeconds = netSessionTtlSeconds,
        netAuthRequired = netAuthRequired,
        netMaxTextLineBytes = netMaxTextLineBytes,
        netReadTimeoutMillis = netReadTimeoutMillis,
        netMaxConcurrentSessions = netMaxConcurrentSessions,
        netMaxSessionsPerIp = netMaxSessionsPerIp,
        netMaxRequestsPerMinutePerConnection = netMaxRequestsPerMinutePerConnection,
        netMaxRequestsPerMinutePerIp = netMaxRequestsPerMinutePerIp,
        netMaxJsonPayloadEntries = netMaxJsonPayloadEntries,
        netMaxJsonPayloadKeyChars = netMaxJsonPayloadKeyChars,
        netMaxJsonPayloadValueChars = netMaxJsonPayloadValueChars,
        netMaxJsonPayloadTotalChars = netMaxJsonPayloadTotalChars,
        netAuditLogEnabled = netAuditLogEnabled,
        netTextFlushEveryResponses = netTextFlushEveryResponses,
        netFrameFlushEveryResponses = netFrameFlushEveryResponses,
        adapterTimeoutMillis = normalizedSecurity.adapter.invocationTimeoutMillis,
        adapterExecutionMode = normalizedSecurity.adapter.executionMode.name.lowercase(),
        adapterRateLimitPerMinute = normalizedSecurity.adapter.maxCallsPerMinute,
        adapterRateLimitPerMinutePerPlugin = normalizedSecurity.adapter.maxCallsPerMinutePerPlugin,
        adapterMaxConcurrentInvocationsPerAdapter = normalizedSecurity.adapter.maxConcurrentInvocationsPerAdapter,
        adapterAuditLogEnabled = normalizedSecurity.adapter.auditLogEnabled,
        adapterAuditLogSuccesses = normalizedSecurity.adapter.auditLogSuccesses,
        adapterAuditRetentionMaxEntriesPerPlugin = normalizedSecurity.adapter.auditRetentionMaxEntriesPerPlugin,
        adapterAuditRetentionMaxEntriesPerAdapter = normalizedSecurity.adapter.auditRetentionMaxEntriesPerAdapter,
        adapterAuditRetentionMaxAgeMillis = normalizedSecurity.adapter.auditRetentionMaxAgeMillis,
        adapterMaxPayloadEntries = normalizedSecurity.adapter.maxPayloadEntries,
        adapterMaxPayloadTotalChars = normalizedSecurity.adapter.maxPayloadTotalChars,
        adapterMaxPayloadKeyChars = normalizedSecurity.adapter.maxPayloadKeyChars,
        adapterMaxPayloadValueChars = normalizedSecurity.adapter.maxPayloadValueChars,
        securityConfigSchemaVersion = normalizedSecurity.schemaVersion,
        faultBudgetMaxFaultsPerWindow = normalizedSecurity.faultBudget.maxFaultsPerWindow,
        faultBudgetWindowMillis = normalizedSecurity.faultBudget.windowMillis,
        eventDispatchMode = eventDispatchMode,
        securityConfigWarnings = securityValidation.warnings
    )
}

private fun argValue(args: Array<String>, key: String): String? {
    val idx = args.indexOf(key)
    if (idx < 0 || idx + 1 >= args.size) return null
    return args[idx + 1]
}

private fun loadConfig(pathValue: String?): Map<String, String> {
    if (pathValue == null) return emptyMap()
    val path = Path.of(pathValue)
    require(Files.exists(path)) { "Config file not found: $path" }
    val properties = Properties()
    Files.newInputStream(path).use(properties::load)
    val map = linkedMapOf<String, String>()
    properties.forEach { (k, v) -> map[k.toString()] = v.toString() }
    return map
}

private fun stringOption(
    args: Array<String>,
    fileConfig: Map<String, String>,
    argKey: String,
    configKey: String,
    defaultValue: String
): String {
    val fromArg = argValue(args, argKey)
    if (!fromArg.isNullOrBlank()) return fromArg
    return fileConfig[configKey]
        ?: fileConfig["standalone.$configKey"]
        ?: defaultValue
}

private fun intOption(
    args: Array<String>,
    fileConfig: Map<String, String>,
    argKey: String,
    configKey: String,
    defaultValue: Int
): Int {
    val fromArg = argValue(args, argKey)?.toIntOrNull()
    if (fromArg != null) return fromArg
    val fromFile = fileConfig[configKey]
        ?: fileConfig["standalone.$configKey"]
    return fromFile?.toIntOrNull() ?: defaultValue
}

private fun longOption(
    args: Array<String>,
    fileConfig: Map<String, String>,
    argKey: String,
    configKey: String,
    defaultValue: Long
): Long {
    val fromArg = argValue(args, argKey)?.toLongOrNull()
    if (fromArg != null) return fromArg
    val fromFile = fileConfig[configKey]
        ?: fileConfig["standalone.$configKey"]
    return fromFile?.toLongOrNull() ?: defaultValue
}

private fun boolOption(
    args: Array<String>,
    fileConfig: Map<String, String>,
    argKey: String,
    configKey: String,
    defaultValue: Boolean
): Boolean {
    val fromArg = argValue(args, argKey)?.trim()?.lowercase()
    if (!fromArg.isNullOrBlank()) {
        return fromArg == "true" || fromArg == "1" || fromArg == "yes" || fromArg == "on"
    }
    val fromFile = fileConfig[configKey]
        ?: fileConfig["standalone.$configKey"]
    return when (fromFile?.trim()?.lowercase()) {
        "true", "1", "yes", "on" -> true
        "false", "0", "no", "off" -> false
        else -> defaultValue
    }
}


