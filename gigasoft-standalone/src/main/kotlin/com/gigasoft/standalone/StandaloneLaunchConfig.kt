package com.gigasoft.standalone

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
    val netTextFlushEveryResponses: Int,
    val netFrameFlushEveryResponses: Int,
    val adapterTimeoutMillis: Long,
    val adapterExecutionMode: String,
    val adapterRateLimitPerMinute: Int,
    val adapterMaxPayloadEntries: Int,
    val adapterMaxPayloadTotalChars: Int,
    val adapterMaxPayloadKeyChars: Int,
    val adapterMaxPayloadValueChars: Int
)

fun parseLaunchConfig(args: Array<String>): StandaloneLaunchConfig {
    val fileConfig = loadConfig(argValue(args, "--config"))
    val pluginsDir = stringOption(args, fileConfig, "--plugins", "plugins", "dev-runtime/giga-plugins")
    val dataDir = stringOption(args, fileConfig, "--data", "data", "dev-runtime/giga-data")
    val serverName = stringOption(args, fileConfig, "--name", "name", "GigaSoft Standalone")
    val serverVersion = stringOption(args, fileConfig, "--version", "version", "0.1.0-rc.2")
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
    val netTextFlushEveryResponses = intOption(args, fileConfig, "--net-text-flush-every", "netTextFlushEveryResponses", 1)
    val netFrameFlushEveryResponses = intOption(args, fileConfig, "--net-frame-flush-every", "netFrameFlushEveryResponses", 1)
    val adapterTimeoutMillis = longOption(args, fileConfig, "--adapter-timeout-ms", "adapterTimeoutMillis", 250L)
    val adapterExecutionMode = stringOption(args, fileConfig, "--adapter-mode", "adapterExecutionMode", "safe")
    val adapterRateLimitPerMinute = intOption(args, fileConfig, "--adapter-rate-limit-per-minute", "adapterRateLimitPerMinute", 180)
    val adapterMaxPayloadEntries = intOption(args, fileConfig, "--adapter-max-payload-entries", "adapterMaxPayloadEntries", 32)
    val adapterMaxPayloadTotalChars = intOption(args, fileConfig, "--adapter-max-payload-total-chars", "adapterMaxPayloadTotalChars", 4096)
    val adapterMaxPayloadKeyChars = intOption(args, fileConfig, "--adapter-max-payload-key-chars", "adapterMaxPayloadKeyChars", 64)
    val adapterMaxPayloadValueChars = intOption(args, fileConfig, "--adapter-max-payload-value-chars", "adapterMaxPayloadValueChars", 512)

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
        netTextFlushEveryResponses = netTextFlushEveryResponses,
        netFrameFlushEveryResponses = netFrameFlushEveryResponses,
        adapterTimeoutMillis = adapterTimeoutMillis,
        adapterExecutionMode = adapterExecutionMode,
        adapterRateLimitPerMinute = adapterRateLimitPerMinute,
        adapterMaxPayloadEntries = adapterMaxPayloadEntries,
        adapterMaxPayloadTotalChars = adapterMaxPayloadTotalChars,
        adapterMaxPayloadKeyChars = adapterMaxPayloadKeyChars,
        adapterMaxPayloadValueChars = adapterMaxPayloadValueChars
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
