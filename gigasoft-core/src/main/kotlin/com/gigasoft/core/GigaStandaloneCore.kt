package com.gigasoft.core

import com.gigasoft.api.GigaLogger
import com.gigasoft.runtime.GigaRuntime
import com.gigasoft.runtime.PluginRuntimeProfile
import com.gigasoft.runtime.ReloadReport
import com.gigasoft.runtime.RuntimeDiagnostics
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class StandaloneCoreConfig(
    val pluginsDirectory: Path,
    val dataDirectory: Path,
    val tickPeriodMillis: Long = 50L
)

class GigaStandaloneCore(
    private val config: StandaloneCoreConfig,
    private val logger: GigaLogger = GigaLogger { println("[GigaCore] $it") }
) {
    private val running = AtomicBoolean(false)
    private lateinit var runtime: GigaRuntime
    private lateinit var scheduler: ScheduledExecutorService

    fun start() {
        if (!running.compareAndSet(false, true)) return
        Files.createDirectories(config.pluginsDirectory)
        Files.createDirectories(config.dataDirectory)

        runtime = GigaRuntime(
            pluginsDirectory = config.pluginsDirectory,
            dataDirectory = config.dataDirectory,
            rootLogger = logger
        )
        scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "gigasoft-core-tick").apply { isDaemon = true }
        }

        runtime.scanAndLoad()
        scheduler.scheduleAtFixedRate(
            { tick() },
            1L,
            config.tickPeriodMillis,
            TimeUnit.MILLISECONDS
        )
        logger.info("Standalone core started")
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        scheduler.shutdownNow()
        runtime.loadedPlugins().forEach { runtime.unload(it.manifest.id) }
        logger.info("Standalone core stopped")
    }

    fun plugins(): List<String> {
        return runtime.loadedPlugins().map { "${it.manifest.id}@${it.manifest.version}" }
    }

    fun reload(pluginId: String): ReloadReport = runtime.reloadWithReport(pluginId)

    fun reloadAll(): ReloadReport = runtime.reloadAllWithReport()

    fun profile(pluginId: String): PluginRuntimeProfile? = runtime.profile(pluginId)

    fun doctor(): RuntimeDiagnostics = runtime.diagnostics()

    fun loadNewPlugins(): Int = runtime.scanAndLoad().size

    private fun tick() {
        runtime.loadedPlugins().forEach { plugin ->
            plugin.context.registry.systems().toSortedMap().forEach { (systemId, system) ->
                val started = System.nanoTime()
                var success = true
                try {
                    system.onTick(plugin.context)
                } catch (t: Throwable) {
                    success = false
                    logger.info("System ${plugin.manifest.id}:$systemId failed: ${t.message}")
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
    }
}
