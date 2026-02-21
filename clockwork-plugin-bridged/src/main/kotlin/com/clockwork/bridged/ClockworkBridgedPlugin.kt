package com.clockwork.bridged

import com.clockwork.api.BridgeRuntime
import com.clockwork.api.BridgeCompatibilityGrade
import com.clockwork.api.CommandArgSpec
import com.clockwork.api.CommandArgType
import com.clockwork.api.CommandResult
import com.clockwork.api.GigaPlugin
import com.clockwork.api.PluginContext
import com.clockwork.api.assessBridgeVersion
import com.clockwork.api.detectBridgeRuntime
import com.clockwork.api.gigaPlugin
import com.clockwork.api.label
import com.clockwork.api.pluginKv
import java.util.Locale

private data class PlatformSupport(
    val runtime: BridgeRuntime,
    val key: String,
    val status: String,
    val minVersion: String,
    val recommendedVersion: String,
    val notes: String
)

class ClockworkBridgedPlugin : GigaPlugin {
    private val support = listOf(
        PlatformSupport(BridgeRuntime.BUKKIT, "bukkit", "supported", "1.20.0", "1.21.0", "Legacy bridge path"),
        PlatformSupport(BridgeRuntime.SPIGOT, "spigot", "supported", "1.20.0", "1.21.1", "Stable event/command bridge"),
        PlatformSupport(BridgeRuntime.PAPER, "paper", "supported", "1.20.2", "1.21.1", "Primary runtime target"),
        PlatformSupport(BridgeRuntime.FOLIA, "folia", "supported-beta", "1.20.2", "1.21.1", "Region scheduler rules apply"),
        PlatformSupport(BridgeRuntime.PURPUR, "purpur", "supported", "1.20.2", "1.21.1", "Paper-compatible path"),
        PlatformSupport(BridgeRuntime.SPONGE, "sponge", "supported-beta", "11.0.0", "11.0.2", "Sponge mapping subset")
    )
    private val byKey = support.associateBy { it.key.lowercase(Locale.ROOT) }

    private val delegate = gigaPlugin(
        id = "clockwork-plugin-bridged",
        name = "Clockwork Bridged",
        version = "1.2.0"
    ) {
        commands {
            spec(command = "bridged", usage = "bridged") { _ ->
                CommandResult.ok(
                    "Bridged commands: bridged-platforms, bridged-runtime, bridged-check <platform> [version], bridged-status, bridged-report, bridged-metrics"
                )
            }

            spec(
                command = "bridged-platforms",
                aliases = listOf("bridged-list"),
                usage = "bridged-platforms"
            ) { _ ->
                val summary = support.joinToString(" | ") {
                    "${it.key}:${it.status} min=${it.minVersion} rec=${it.recommendedVersion}"
                }
                CommandResult.ok("Bridged support matrix => $summary")
            }

            spec(
                command = "bridged-runtime",
                usage = "bridged-runtime"
            ) { inv ->
                val kv = inv.pluginContext.pluginKv(namespace = "bridged", version = 1)
                val runtime = inv.pluginContext.detectBridgeRuntime()
                kv.increment("runtime.lookups")
                kv.put("runtime.last", runtime.runtime.name.lowercase(Locale.ROOT))
                CommandResult.ok(
                    "Detected runtime=${runtime.runtime.name.lowercase(Locale.ROOT)} source=${runtime.detectedFrom} " +
                        "name=${runtime.serverName} version=${runtime.serverVersion} platformVersion=${runtime.platformVersion ?: "n/a"}"
                )
            }

            spec(
                command = "bridged-check",
                argsSchema = listOf(
                    CommandArgSpec("platform", CommandArgType.STRING),
                    CommandArgSpec("version", CommandArgType.STRING, required = false)
                ),
                usage = "bridged-check <platform> [version]"
            ) { inv ->
                val kv = inv.pluginContext.pluginKv(namespace = "bridged", version = 1)
                val platform = inv.parsedArgs.requiredString("platform").trim().lowercase(Locale.ROOT)
                val target = byKey[platform]
                    ?: return@spec CommandResult.error("Unknown platform '$platform'. Use bridged-platforms", code = "E_ARGS")

                val server = inv.pluginContext.host.serverInfo()
                val detected = inv.pluginContext.detectBridgeRuntime()
                val version = inv.parsedArgs.string("version")?.trim()?.takeIf { it.isNotEmpty() }
                    ?: server?.platformVersion
                    ?: server?.version
                    ?: "unknown"
                val compatibility = compatibility(target, version)
                kv.increment("checks.total")
                kv.increment("checks.$platform")
                kv.put("last.platform", platform)
                kv.put("last.version", version)
                kv.put("last.compatibility", compatibility.label())

                CommandResult.ok(
                    "${target.key}: ${target.status} min=${target.minVersion} rec=${target.recommendedVersion} " +
                        "version=$version compatibility=${compatibility.label()} notes=${target.notes} " +
                        "detectedRuntime=${detected.runtime.name.lowercase(Locale.ROOT)}"
                )
            }

            spec(
                command = "bridged-status",
                usage = "bridged-status"
            ) { inv ->
                val kv = inv.pluginContext.pluginKv(namespace = "bridged", version = 1)
                val runtime = inv.pluginContext.detectBridgeRuntime()
                val match = support.firstOrNull { it.runtime == runtime.runtime }
                val status = if (match == null) {
                    "runtime=${runtime.runtime.name.lowercase(Locale.ROOT)} unsupported-by-bridged"
                } else {
                    val currentVersion = runtime.platformVersion ?: runtime.serverVersion
                    val grade = compatibility(match, currentVersion)
                    "runtime=${match.key} status=${match.status} currentVersion=$currentVersion compatibility=${grade.label()} recommended=${match.recommendedVersion}"
                }
                kv.put("status.lastRuntime", runtime.runtime.name.lowercase(Locale.ROOT))
                CommandResult.ok("Bridged status => $status")
            }

            spec(
                command = "bridged-report",
                usage = "bridged-report"
            ) { inv ->
                val runtime = inv.pluginContext.detectBridgeRuntime()
                val report = support.joinToString(" | ") { target ->
                    val version = runtime.platformVersion ?: runtime.serverVersion
                    val compat = compatibility(target, version)
                    "${target.key}:{status=${target.status},compat=${compat.label()},min=${target.minVersion},rec=${target.recommendedVersion}}"
                }
                CommandResult.ok("Bridged report runtime=${runtime.runtime.name.lowercase(Locale.ROOT)} $report")
            }

            spec(
                command = "bridged-metrics",
                usage = "bridged-metrics"
            ) { inv ->
                val kv = inv.pluginContext.pluginKv(namespace = "bridged", version = 1)
                val checks = kv.getLong("checks.total") ?: 0L
                val lookups = kv.getLong("runtime.lookups") ?: 0L
                val lastPlatform = kv.get("last.platform") ?: "n/a"
                val lastVersion = kv.get("last.version") ?: "n/a"
                val lastCompatibility = kv.get("last.compatibility") ?: "n/a"
                CommandResult.ok(
                    "Bridged metrics checks=$checks runtimeLookups=$lookups lastPlatform=$lastPlatform " +
                        "lastVersion=$lastVersion lastCompatibility=$lastCompatibility"
                )
            }
        }
    }

    override fun onEnable(ctx: PluginContext) = delegate.onEnable(ctx)

    override fun onDisable(ctx: PluginContext) = delegate.onDisable(ctx)

    private fun compatibility(target: PlatformSupport, version: String): BridgeCompatibilityGrade {
        return assessBridgeVersion(
            version = version,
            minimumVersion = target.minVersion,
            recommendedVersion = target.recommendedVersion
        )
    }
}
