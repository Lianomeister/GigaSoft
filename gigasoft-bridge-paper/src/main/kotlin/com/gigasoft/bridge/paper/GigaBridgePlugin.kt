package com.gigasoft.bridge.paper

import com.fasterxml.jackson.databind.ObjectMapper
import com.gigasoft.api.AdapterInvocation
import com.gigasoft.runtime.GigaRuntime
import com.gigasoft.runtime.LoadedPlugin
import com.gigasoft.runtime.PluginRuntimeProfile
import com.gigasoft.runtime.ReloadStatus
import com.gigasoft.runtime.RuntimeCommandRegistry
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.plugin.java.JavaPlugin
import java.nio.file.Files

class GigaBridgePlugin : JavaPlugin() {
    private lateinit var runtime: GigaRuntime
    private val objectMapper = ObjectMapper()

    override fun onEnable() {
        saveDefaultConfig()
        reloadConfig()

        val gigaPluginsDir = dataFolder.toPath().resolve("giga-plugins")
        val gigaDataDir = dataFolder.toPath().resolve("giga-data")
        Files.createDirectories(gigaPluginsDir)
        Files.createDirectories(gigaDataDir)

        ViaProvisioner(this, objectMapper).ensureViaStack()

        runtime = GigaRuntime(
            pluginsDirectory = gigaPluginsDir,
            dataDirectory = gigaDataDir
        ) { msg -> logger.info(msg) }

        runtime.scanAndLoad()
        registerBridgeAdapters(runtime.loadedPlugins())

        server.scheduler.runTaskTimer(this, Runnable {
            runtime.loadedPlugins().forEach { plugin ->
                plugin.context.registry.systems().toSortedMap().forEach { (systemId, system) ->
                    val started = System.nanoTime()
                    var success = true
                    try {
                        system.onTick(plugin.context)
                    } catch (t: Throwable) {
                        success = false
                        logger.warning("System ${plugin.manifest.id}:$systemId failed: ${t.message}")
                    } finally {
                        runtime.recordSystemTick(
                            pluginId = plugin.manifest.id,
                            systemId = systemId,
                            durationNanos = System.nanoTime() - started,
                            success = success
                        )
                    }
                }
            }
        }, 1L, 1L)

        logger.info("GigaSoft bridge enabled")
    }

    override fun onDisable() {
        runtime.loadedPlugins().forEach { runtime.unload(it.manifest.id) }
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (command.name != "giga") return false
        if (args.isEmpty()) {
            sender.sendMessage("/giga plugins | /giga reload <plugin|all> | /giga doctor [--json] | /giga profile <plugin> [--json] | /giga run <plugin> <command...> | /giga adapters <plugin> [--json] | /giga adapter invoke <plugin> <adapterId> <action> [k=v ...] [--json]")
            return true
        }

        when (args[0].lowercase()) {
            "plugins" -> {
                if (!ensurePermission(sender, "gigasoft.admin.plugins")) return true
                val plugins = runtime.loadedPlugins()
                sender.sendMessage("Loaded GigaPlugins: ${plugins.size}")
                plugins.forEach { p ->
                    sender.sendMessage("- ${p.manifest.id}@${p.manifest.version} (items=${p.context.registry.items().size}, blocks=${p.context.registry.blocks().size}, recipes=${p.context.registry.recipes().size}, systems=${p.context.registry.systems().size})")
                }
            }

            "reload" -> {
                if (!ensurePermission(sender, "gigasoft.admin.reload")) return true
                val target = args.getOrNull(1)
                when {
                    target == null -> sender.sendMessage("Usage: /giga reload <plugin|all>")
                    target == "all" -> {
                        val report = runtime.reloadAllWithReport()
                        when (report.status) {
                            ReloadStatus.SUCCESS -> {
                                sender.sendMessage("Reloaded ${report.reloadedPlugins.size} plugins")
                                registerBridgeAdapters(runtime.loadedPlugins())
                            }
                            ReloadStatus.ROLLED_BACK -> sender.sendMessage("Reload rolled back (${report.reason})")
                            ReloadStatus.FAILED -> sender.sendMessage("Reload failed (${report.reason})")
                        }
                    }
                    else -> {
                        val report = runtime.reloadWithReport(target)
                        when (report.status) {
                            ReloadStatus.SUCCESS -> {
                                sender.sendMessage("Reloaded ${report.reloadedPlugins.joinToString(", ")}")
                                registerBridgeAdapters(runtime.loadedPlugins().filter { it.manifest.id in report.reloadedPlugins.toSet() })
                            }
                            ReloadStatus.ROLLED_BACK -> sender.sendMessage("Reload rolled back (${report.reason})")
                            ReloadStatus.FAILED -> sender.sendMessage("Reload failed (${report.reason})")
                        }
                    }
                }
            }

            "doctor" -> {
                if (!ensurePermission(sender, "gigasoft.admin.doctor")) return true
                val diagnostics = runtime.diagnostics()
                val jsonMode = args.getOrNull(1)?.equals("--json", ignoreCase = true) == true
                if (jsonMode) {
                    sender.sendMessage(objectMapper.writeValueAsString(diagnostics))
                    return true
                }
                sender.sendMessage("Giga doctor")
                sender.sendMessage("- Runtime active: true")
                sender.sendMessage("- Loaded plugins: ${diagnostics.loadedPlugins.size}")
                sender.sendMessage("- Tick loop: active")
                sender.sendMessage("- Current load order: ${if (diagnostics.currentLoadOrder.isEmpty()) "<none>" else diagnostics.currentLoadOrder.joinToString(" -> ")}")
                if (diagnostics.currentDependencyIssues.isEmpty()) {
                    sender.sendMessage("- Current dependency issues: none")
                } else {
                    sender.sendMessage("- Current dependency issues:")
                    diagnostics.currentDependencyIssues.toSortedMap().forEach { (id, reason) ->
                        sender.sendMessage("  * $id: $reason")
                    }
                }
                if (diagnostics.versionMismatches.isEmpty()) {
                    sender.sendMessage("- Version mismatches: none")
                } else {
                    sender.sendMessage("- Version mismatches:")
                    diagnostics.versionMismatches.toSortedMap().forEach { (id, reason) ->
                        sender.sendMessage("  * $id: $reason")
                    }
                }
                sender.sendMessage("- API compatibility:")
                diagnostics.apiCompatibility.toSortedMap().forEach { (id, status) ->
                    sender.sendMessage("  * $id: $status")
                }
                if (diagnostics.lastScanRejected.isEmpty()) {
                    sender.sendMessage("- Last scan rejections: none")
                } else {
                    sender.sendMessage("- Last scan rejections:")
                    diagnostics.lastScanRejected.toSortedMap().forEach { (id, reason) ->
                        sender.sendMessage("  * $id: $reason")
                    }
                }
                if (diagnostics.lastScanVersionMismatches.isEmpty()) {
                    sender.sendMessage("- Last scan version mismatches: none")
                } else {
                    sender.sendMessage("- Last scan version mismatches:")
                    diagnostics.lastScanVersionMismatches.toSortedMap().forEach { (id, reason) ->
                        sender.sendMessage("  * $id: $reason")
                    }
                }
                sender.sendMessage("- Last scan API compatibility:")
                diagnostics.lastScanApiCompatibility.toSortedMap().forEach { (id, status) ->
                    sender.sendMessage("  * $id: $status")
                }
                if (diagnostics.dependencyGraph.isEmpty()) {
                    sender.sendMessage("- Dependency graph: <none>")
                } else {
                    sender.sendMessage("- Dependency graph:")
                    diagnostics.dependencyGraph.toSortedMap().forEach { (id, deps) ->
                        val depText = if (deps.isEmpty()) "<none>" else deps.joinToString(", ")
                        sender.sendMessage("  * $id -> $depText")
                    }
                }
            }

            "profile" -> {
                if (!ensurePermission(sender, "gigasoft.admin.profile")) return true
                val target = args.getOrNull(1)
                val jsonMode = args.any { it.equals("--json", ignoreCase = true) }
                if (target == null || target.startsWith("--")) {
                    sender.sendMessage("Usage: /giga profile <plugin> [--json]")
                    return true
                }
                val plugin = runtime.loadedPlugins().find { it.manifest.id == target } ?: run {
                    sender.sendMessage("Unknown plugin: $target")
                    return true
                }
                val profile = runtime.profile(target) ?: run {
                    sender.sendMessage("No runtime profile available for $target")
                    return true
                }

                if (jsonMode) {
                    sender.sendMessage(objectMapper.writeValueAsString(profileJson(plugin, profile)))
                    return true
                }

                sender.sendMessage("Profile ${plugin.manifest.id}")
                sender.sendMessage("- items: ${plugin.context.registry.items().size}")
                sender.sendMessage("- blocks: ${plugin.context.registry.blocks().size}")
                sender.sendMessage("- recipes: ${plugin.context.registry.recipes().size}")
                sender.sendMessage("- machines: ${plugin.context.registry.machines().size}")
                sender.sendMessage("- systems: ${plugin.context.registry.systems().size}")
                sender.sendMessage("- active tasks: ${profile.activeTasks}")
                if (profile.activeTaskIds.isNotEmpty()) {
                    sender.sendMessage("- active task ids: ${profile.activeTaskIds.joinToString(", ")}")
                }
                if (profile.systems.isEmpty()) {
                    sender.sendMessage("- system runtime: no samples yet")
                } else {
                    sender.sendMessage("- system runtime:")
                    profile.systems.forEach { (systemId, metric) ->
                        val avgMs = nanosToMs(metric.averageNanos)
                        val maxMs = nanosToMs(metric.maxNanos)
                        sender.sendMessage("  * $systemId: runs=${metric.runs}, fail=${metric.failures}, avg=${avgMs}ms, max=${maxMs}ms")
                    }
                }
                if (profile.tasks.isEmpty()) {
                    sender.sendMessage("- scheduler tasks: no samples yet")
                } else {
                    sender.sendMessage("- scheduler tasks:")
                    profile.tasks.forEach { (taskId, metric) ->
                        val avgMs = nanosToMs(metric.averageNanos)
                        val maxMs = nanosToMs(metric.maxNanos)
                        sender.sendMessage("  * $taskId: runs=${metric.runs}, fail=${metric.failures}, avg=${avgMs}ms, max=${maxMs}ms")
                    }
                }
                if (profile.adapters.isEmpty()) {
                    sender.sendMessage("- adapters: no calls yet")
                } else {
                    sender.sendMessage("- adapters:")
                    profile.adapters.forEach { (adapterId, metric) ->
                        sender.sendMessage("  * $adapterId: total=${metric.total}, ok=${metric.accepted}, denied=${metric.denied}, timeout=${metric.timeouts}, fail=${metric.failures}")
                    }
                }
            }

            "run" -> {
                if (!ensurePermission(sender, "gigasoft.admin.run")) return true
                val pluginId = args.getOrNull(1)
                val commandLine = args.drop(2).joinToString(" ")
                if (pluginId == null || commandLine.isBlank()) {
                    sender.sendMessage("Usage: /giga run <plugin> <command...>")
                    return true
                }

                val plugin = runtime.loadedPlugins().find { it.manifest.id == pluginId }
                if (plugin == null) {
                    sender.sendMessage("Unknown plugin: $pluginId")
                    return true
                }

                val registry = plugin.context.commands as? RuntimeCommandRegistry
                if (registry == null) {
                    sender.sendMessage("Plugin command registry unavailable")
                    return true
                }

                val response = registry.execute(plugin.context, sender.name, commandLine)
                sender.sendMessage(response)
            }

            "adapters" -> {
                if (!ensurePermission(sender, "gigasoft.admin.adapters.view")) return true
                val pluginId = args.getOrNull(1)
                val jsonMode = args.any { it.equals("--json", ignoreCase = true) }
                if (pluginId == null || pluginId.startsWith("--")) {
                    sender.sendMessage("Usage: /giga adapters <plugin> [--json]")
                    return true
                }
                val plugin = runtime.loadedPlugins().find { it.manifest.id == pluginId }
                if (plugin == null) {
                    sender.sendMessage("Unknown plugin: $pluginId")
                    return true
                }
                val adapters = plugin.context.adapters.list()
                if (jsonMode) {
                    sender.sendMessage(
                        objectMapper.writeValueAsString(
                            mapOf(
                                "plugin" to plugin.manifest.id,
                                "count" to adapters.size,
                                "adapters" to adapters.map { adapter ->
                                    mapOf(
                                        "id" to adapter.id,
                                        "name" to adapter.name,
                                        "version" to adapter.version,
                                        "capabilities" to adapter.capabilities.sorted()
                                    )
                                }
                            )
                        )
                    )
                    return true
                }
                sender.sendMessage("Adapters for ${plugin.manifest.id}: ${adapters.size}")
                adapters.forEach { adapter ->
                    val caps = if (adapter.capabilities.isEmpty()) "<none>" else adapter.capabilities.sorted().joinToString(",")
                    sender.sendMessage("- ${adapter.id}@${adapter.version} caps=$caps")
                }
            }

            "adapter" -> {
                if (args.getOrNull(1)?.lowercase() != "invoke") {
                    sender.sendMessage("Usage: /giga adapter invoke <plugin> <adapterId> <action> [k=v ...] [--json]")
                    return true
                }
                if (!ensurePermission(sender, "gigasoft.admin.adapters.invoke")) return true
                val pluginId = args.getOrNull(2)
                val adapterId = args.getOrNull(3)
                val action = args.getOrNull(4)
                if (pluginId == null || adapterId == null || action == null) {
                    sender.sendMessage("Usage: /giga adapter invoke <plugin> <adapterId> <action> [k=v ...] [--json]")
                    return true
                }
                val plugin = runtime.loadedPlugins().find { it.manifest.id == pluginId }
                if (plugin == null) {
                    sender.sendMessage("Unknown plugin: $pluginId")
                    return true
                }

                val payloadArgs = args.drop(5)
                val jsonMode = payloadArgs.any { it.equals("--json", ignoreCase = true) }
                val payload = parsePayload(payloadArgs.filterNot { it.equals("--json", ignoreCase = true) })
                val response = plugin.context.adapters.invoke(adapterId, AdapterInvocation(action, payload))
                logger.info("Adapter invoke sender=${sender.name} plugin=$pluginId adapter=$adapterId action=$action success=${response.success}")

                if (jsonMode) {
                    sender.sendMessage(
                        objectMapper.writeValueAsString(
                            mapOf(
                                "plugin" to pluginId,
                                "adapterId" to adapterId,
                                "action" to action,
                                "success" to response.success,
                                "message" to response.message,
                                "payload" to response.payload
                            )
                        )
                    )
                    return true
                }

                sender.sendMessage("adapter.success=${response.success}")
                if (response.message != null) {
                    sender.sendMessage("adapter.message=${response.message}")
                }
                if (response.payload.isNotEmpty()) {
                    response.payload.toSortedMap().forEach { (k, v) ->
                        sender.sendMessage("adapter.payload.$k=$v")
                    }
                }
            }

            else -> sender.sendMessage("Unknown subcommand: ${args[0]}")
        }
        return true
    }

    private fun nanosToMs(nanos: Long): String {
        return "%.3f".format(nanos / 1_000_000.0)
    }

    private fun ensurePermission(sender: CommandSender, permission: String): Boolean {
        if (sender.hasPermission(permission) || sender.isOp) return true
        sender.sendMessage("Missing permission: $permission")
        return false
    }

    private fun registerBridgeAdapters(plugins: List<LoadedPlugin>) {
        plugins.forEach { plugin ->
            try {
                PaperBridgeAdapters.registerDefaults(plugin, server) { msg -> logger.info(msg) }
            } catch (t: Throwable) {
                logger.warning("Failed installing bridge adapters for ${plugin.manifest.id}: ${t.message}")
            }
        }
    }

    private fun parsePayload(rawPairs: List<String>): Map<String, String> {
        if (rawPairs.isEmpty()) return emptyMap()
        return rawPairs.mapNotNull { pair ->
            val idx = pair.indexOf('=')
            if (idx <= 0 || idx == pair.lastIndex) {
                null
            } else {
                val key = pair.substring(0, idx).trim()
                val value = pair.substring(idx + 1).trim()
                if (key.isBlank()) null else key to value
            }
        }.toMap()
    }

    private fun profileJson(plugin: LoadedPlugin, profile: PluginRuntimeProfile): Map<String, Any> {
        return mapOf(
            "plugin" to plugin.manifest.id,
            "version" to plugin.manifest.version,
            "registry" to mapOf(
                "items" to plugin.context.registry.items().size,
                "blocks" to plugin.context.registry.blocks().size,
                "recipes" to plugin.context.registry.recipes().size,
                "machines" to plugin.context.registry.machines().size,
                "systems" to plugin.context.registry.systems().size
            ),
            "activeTasks" to profile.activeTasks,
            "activeTaskIds" to profile.activeTaskIds,
            "systemMetrics" to profile.systems.mapValues { (_, metric) ->
                mapOf(
                    "runs" to metric.runs,
                    "failures" to metric.failures,
                    "avgNanos" to metric.averageNanos,
                    "maxNanos" to metric.maxNanos
                )
            },
            "taskMetrics" to profile.tasks.mapValues { (_, metric) ->
                mapOf(
                    "runs" to metric.runs,
                    "failures" to metric.failures,
                    "avgNanos" to metric.averageNanos,
                    "maxNanos" to metric.maxNanos
                )
            },
            "adapterMetrics" to profile.adapters.mapValues { (_, metric) ->
                mapOf(
                    "total" to metric.total,
                    "accepted" to metric.accepted,
                    "denied" to metric.denied,
                    "timeouts" to metric.timeouts,
                    "failures" to metric.failures
                )
            }
        )
    }
}
