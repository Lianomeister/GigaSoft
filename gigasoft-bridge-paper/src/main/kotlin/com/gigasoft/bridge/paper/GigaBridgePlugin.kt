package com.gigasoft.bridge.paper

import com.gigasoft.runtime.GigaRuntime
import com.gigasoft.runtime.ReloadStatus
import com.gigasoft.runtime.RuntimeCommandRegistry
import com.fasterxml.jackson.databind.ObjectMapper
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.plugin.java.JavaPlugin
import java.nio.file.Files

class GigaBridgePlugin : JavaPlugin() {
    private lateinit var runtime: GigaRuntime
    private val objectMapper = ObjectMapper()

    override fun onEnable() {
        val gigaPluginsDir = dataFolder.toPath().resolve("giga-plugins")
        val gigaDataDir = dataFolder.toPath().resolve("giga-data")
        Files.createDirectories(gigaPluginsDir)
        Files.createDirectories(gigaDataDir)

        runtime = GigaRuntime(
            pluginsDirectory = gigaPluginsDir,
            dataDirectory = gigaDataDir
        ) { msg -> logger.info(msg) }

        runtime.scanAndLoad()

        server.scheduler.runTaskTimer(this, Runnable {
            runtime.loadedPlugins().forEach { plugin ->
                plugin.context.registry.systems().toSortedMap().forEach { (systemId, system) ->
                    try {
                        system.onTick(plugin.context)
                    } catch (t: Throwable) {
                        logger.warning("System ${plugin.manifest.id}:$systemId failed: ${t.message}")
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
            sender.sendMessage("/giga plugins | /giga reload <plugin|all> | /giga doctor [--json] | /giga profile <plugin> | /giga run <plugin> <command...>")
            return true
        }

        when (args[0].lowercase()) {
            "plugins" -> {
                val plugins = runtime.loadedPlugins()
                sender.sendMessage("Loaded GigaPlugins: ${plugins.size}")
                plugins.forEach { p ->
                    sender.sendMessage("- ${p.manifest.id}@${p.manifest.version} (items=${p.context.registry.items().size}, blocks=${p.context.registry.blocks().size}, recipes=${p.context.registry.recipes().size}, systems=${p.context.registry.systems().size})")
                }
            }
            "reload" -> {
                val target = args.getOrNull(1)
                when {
                    target == null -> sender.sendMessage("Usage: /giga reload <plugin|all>")
                    target == "all" -> {
                        val report = runtime.reloadAllWithReport()
                        when (report.status) {
                            ReloadStatus.SUCCESS -> sender.sendMessage("Reloaded ${report.reloadedPlugins.size} plugins")
                            ReloadStatus.ROLLED_BACK -> sender.sendMessage("Reload rolled back (${report.reason})")
                            ReloadStatus.FAILED -> sender.sendMessage("Reload failed (${report.reason})")
                        }
                    }
                    else -> {
                        val report = runtime.reloadWithReport(target)
                        when (report.status) {
                            ReloadStatus.SUCCESS -> sender.sendMessage("Reloaded ${report.reloadedPlugins.joinToString(", ")}")
                            ReloadStatus.ROLLED_BACK -> sender.sendMessage("Reload rolled back (${report.reason})")
                            ReloadStatus.FAILED -> sender.sendMessage("Reload failed (${report.reason})")
                        }
                    }
                }
            }
            "doctor" -> {
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
                val target = args.getOrNull(1)
                if (target == null) {
                    sender.sendMessage("Usage: /giga profile <plugin>")
                    return true
                }
                val plugin = runtime.loadedPlugins().find { it.manifest.id == target }
                if (plugin == null) {
                    sender.sendMessage("Unknown plugin: $target")
                    return true
                }
                sender.sendMessage("Profile ${plugin.manifest.id}")
                sender.sendMessage("- items: ${plugin.context.registry.items().size}")
                sender.sendMessage("- blocks: ${plugin.context.registry.blocks().size}")
                sender.sendMessage("- recipes: ${plugin.context.registry.recipes().size}")
                sender.sendMessage("- machines: ${plugin.context.registry.machines().size}")
                sender.sendMessage("- systems: ${plugin.context.registry.systems().size}")
            }
            "run" -> {
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
            else -> sender.sendMessage("Unknown subcommand: ${args[0]}")
        }
        return true
    }
}
