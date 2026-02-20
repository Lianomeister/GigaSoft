package com.gigasoft.standalone

import com.gigasoft.core.GigaStandaloneCore
import com.gigasoft.core.StandaloneCoreConfig
import java.nio.file.Path
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val pluginsDir = argValue(args, "--plugins") ?: "dev-runtime/giga-plugins"
    val dataDir = argValue(args, "--data") ?: "dev-runtime/giga-data"

    val core = GigaStandaloneCore(
        config = StandaloneCoreConfig(
            pluginsDirectory = Path.of(pluginsDir),
            dataDirectory = Path.of(dataDir)
        )
    )

    core.start()
    println("GigaSoft standalone core is running.")
    println("Commands: help, plugins, scan, reload <id|all>, doctor, profile <id>, stop")

    while (true) {
        print("> ")
        val line = readLine()?.trim().orEmpty()
        if (line.isEmpty()) continue
        val parts = line.split(" ").filter { it.isNotBlank() }
        when (parts.first().lowercase()) {
            "help" -> println("Commands: help, plugins, scan, reload <id|all>, doctor, profile <id>, stop")
            "plugins" -> {
                val plugins = core.plugins()
                println("Loaded: ${plugins.size}")
                plugins.forEach { println("- $it") }
            }
            "scan" -> println("Loaded ${core.loadNewPlugins()} new plugin(s)")
            "reload" -> {
                val target = parts.getOrNull(1)
                if (target == null) {
                    println("Usage: reload <id|all>")
                    continue
                }
                val report = if (target == "all") core.reloadAll() else core.reload(target)
                println("status=${report.status} reloaded=${report.reloadedPlugins.joinToString(",")} reason=${report.reason ?: "<none>"}")
            }
            "doctor" -> {
                val d = core.doctor()
                println("loaded=${d.loadedPlugins.size} loadOrder=${d.currentLoadOrder.joinToString("->")}")
                if (d.currentDependencyIssues.isNotEmpty()) {
                    d.currentDependencyIssues.toSortedMap().forEach { (id, reason) ->
                        println("issue[$id]=$reason")
                    }
                }
            }
            "profile" -> {
                val id = parts.getOrNull(1)
                if (id == null) {
                    println("Usage: profile <id>")
                    continue
                }
                val p = core.profile(id)
                if (p == null) {
                    println("No profile for plugin '$id'")
                    continue
                }
                println("plugin=${p.pluginId} activeTasks=${p.activeTasks}")
                if (p.systems.isNotEmpty()) {
                    p.systems.forEach { (name, metric) ->
                        println("system[$name]=runs:${metric.runs},fail:${metric.failures},avgNs:${metric.averageNanos},maxNs:${metric.maxNanos}")
                    }
                }
                if (p.adapters.isNotEmpty()) {
                    p.adapters.forEach { (name, metric) ->
                        println("adapter[$name]=total:${metric.total},ok:${metric.accepted},deny:${metric.denied},timeout:${metric.timeouts},fail:${metric.failures}")
                    }
                }
            }
            "stop", "exit", "quit" -> {
                core.stop()
                exitProcess(0)
            }
            else -> println("Unknown command. Use: help")
        }
    }
}

private fun argValue(args: Array<String>, key: String): String? {
    val idx = args.indexOf(key)
    if (idx < 0 || idx + 1 >= args.size) return null
    return args[idx + 1]
}
