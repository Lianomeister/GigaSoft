package com.clockwork.core

import com.clockwork.api.AdapterInvocation
import com.clockwork.api.AdapterResponse
import com.clockwork.api.CommandSender
import com.clockwork.api.GigaLogger
import com.clockwork.api.GigaEntitySpawnEvent
import com.clockwork.api.GigaInventoryChangeEvent
import com.clockwork.api.GigaBlockChangeEvent
import com.clockwork.api.GigaBlockDataChangeEvent
import com.clockwork.api.GigaEntityDataChangeEvent
import com.clockwork.api.GigaWorldDataChangeEvent
import com.clockwork.api.GigaWorldWeatherChangeEvent
import com.clockwork.api.GigaPlayerJoinEvent
import com.clockwork.api.GigaPlayerLeaveEvent
import com.clockwork.api.GigaPlayerMessageEvent
import com.clockwork.api.GigaPlayerMoveEvent
import com.clockwork.api.GigaPlayerMovePreEvent
import com.clockwork.api.GigaPlayerMovePostEvent
import com.clockwork.api.GigaPlayerGameModeChangeEvent
import com.clockwork.api.GigaPlayerKickEvent
import com.clockwork.api.GigaPlayerOpChangeEvent
import com.clockwork.api.GigaPlayerPermissionChangeEvent
import com.clockwork.api.GigaPlayerStatusChangeEvent
import com.clockwork.api.GigaPlayerEffectChangeEvent
import com.clockwork.api.GigaPlayerTeleportEvent
import com.clockwork.api.GigaTickEvent
import com.clockwork.api.GigaWorldTimeChangeEvent
import com.clockwork.api.GigaEntityRemoveEvent
import com.clockwork.api.GigaWorldCreatedEvent
import com.clockwork.api.GigaEntitySpawnPreEvent
import com.clockwork.api.GigaEntitySpawnPostEvent
import com.clockwork.api.GigaBlockBreakPreEvent
import com.clockwork.api.GigaBlockBreakPostEvent
import com.clockwork.api.EventBus
import com.clockwork.api.HostAccess
import com.clockwork.api.HostEntitySnapshot
import com.clockwork.api.HostBlockSnapshot
import com.clockwork.api.HostHttpResponse
import com.clockwork.api.HostLocationRef
import com.clockwork.api.HostMutationBatch
import com.clockwork.api.HostMutationBatchResult
import com.clockwork.api.HostMutationType
import com.clockwork.api.HostMutationOp
import com.clockwork.api.HostPlayerSnapshot
import com.clockwork.api.HostPluginInstallResult
import com.clockwork.api.HostPlayerStatusSnapshot
import com.clockwork.api.HostWorldSnapshot
import com.clockwork.api.PluginContext
import com.clockwork.api.TickSystem
import com.clockwork.host.api.HostBridgeAdapters
import com.clockwork.runtime.AdapterSecurityConfig
import com.clockwork.runtime.EventDispatchMode
import com.clockwork.runtime.FaultBudgetEscalationPolicy
import com.clockwork.runtime.FaultBudgetStage
import com.clockwork.runtime.FaultBudgetPolicy
import com.clockwork.runtime.GigaRuntime
import com.clockwork.runtime.PluginRuntimeProfile
import com.clockwork.runtime.ReloadReport
import com.clockwork.runtime.ReloadStatus
import com.clockwork.runtime.RuntimeCommandRegistry
import com.clockwork.runtime.RuntimeDiagnostics
import com.clockwork.runtime.RuntimeRegistry
import com.clockwork.runtime.SystemIsolationSnapshot
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.ExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

data class StandaloneCoreConfig(
    val pluginsDirectory: Path,
    val dataDirectory: Path,
    val tickPeriodMillis: Long = 50L,
    val serverName: String = "Clockwork Standalone",
    val serverVersion: String = "1.5.0",
    val defaultWorld: String = "world",
    val maxPlayers: Int = 0,
    val maxWorlds: Int = 0,
    val maxEntities: Int = 0,
    val autoSaveEveryTicks: Long = 200L,
    val systemIsolationFailureThreshold: Int = 5,
    val systemIsolationBaseCooldownTicks: Long = 40L,
    val systemIsolationMaxCooldownTicks: Long = 800L,
    val perPluginTickBudgetNanos: Long = 5_000_000L,
    val chunkViewDistance: Int = 4,
    val maxChunkLoadsPerTick: Int = 128,
    val maxLoadedChunksPerWorld: Int = 4096,
    val runtimeSchedulerWorkerThreads: Int = 1,
    val adapterSecurity: AdapterSecurityConfig = AdapterSecurityConfig(),
    val faultBudgetPolicy: FaultBudgetPolicy = FaultBudgetPolicy(),
    val faultBudgetEscalationPolicy: FaultBudgetEscalationPolicy = FaultBudgetEscalationPolicy(),
    val eventDispatchMode: EventDispatchMode = EventDispatchMode.EXACT
) {
    init {
        require(defaultWorld.trim().isNotBlank()) { "defaultWorld must not be blank" }
        require(maxPlayers >= 0) { "maxPlayers must be >= 0" }
        require(maxWorlds >= 0) { "maxWorlds must be >= 0" }
        require(maxEntities >= 0) { "maxEntities must be >= 0" }
        require(runtimeSchedulerWorkerThreads > 0) { "runtimeSchedulerWorkerThreads must be > 0" }
    }
}

data class AdapterDescriptor(
    val id: String,
    val name: String,
    val version: String,
    val capabilities: Set<String>
)

data class StandaloneCoreStatus(
    val running: Boolean,
    val uptimeMillis: Long,
    val tickCount: Long,
    val averageTickDurationNanos: Long,
    val lastTickDurationNanos: Long,
    val tickFailures: Long,
    val averageQueueDrainNanos: Long,
    val averageWorldTickNanos: Long,
    val averageEventPublishNanos: Long,
    val averageSystemsNanos: Long,
    val averageTickJitterNanos: Long,
    val maxTickJitterNanos: Long,
    val tickOverruns: Long,
    val pluginBudgetExhaustions: Long,
    val faultBudgetWarnTicks: Long,
    val faultBudgetThrottleTicks: Long,
    val faultBudgetIsolateTicks: Long,
    val loadedPlugins: Int,
    val onlinePlayers: Int,
    val worlds: Int,
    val entities: Int,
    val queuedMutations: Int
)

data class PluginSyncReport(
    val loadedNewPlugins: Int,
    val changedPluginsDetected: Int,
    val reloadedPlugins: List<String>,
    val reloadStatus: ReloadStatus,
    val reason: String? = null
)

class GigaStandaloneCore(
    private val config: StandaloneCoreConfig,
    private val logger: GigaLogger = GigaLogger { println("[GigaCore] $it") }
) {
    private val running = AtomicBoolean(false)
    private val startedAtMillis = AtomicLong(0)
    private val coreThreadId = AtomicLong(-1L)
    private val tickCounter = AtomicLong(0)
    private val tickFailureCounter = AtomicLong(0)
    private val tickTotalDurationNanos = AtomicLong(0)
    private val lastTickDurationNanos = AtomicLong(0)
    private val tickQueueDrainNanos = AtomicLong(0)
    private val tickWorldNanos = AtomicLong(0)
    private val tickEventNanos = AtomicLong(0)
    private val tickSystemsNanos = AtomicLong(0)
    private val previousTickStartNanos = AtomicLong(0L)
    private val tickJitterTotalNanos = AtomicLong(0L)
    private val tickJitterMaxNanos = AtomicLong(0L)
    private val tickOverrunCounter = AtomicLong(0L)
    private val pluginBudgetExhaustionCounter = AtomicLong(0L)
    private val faultBudgetWarnTickCounter = AtomicLong(0L)
    private val faultBudgetThrottleTickCounter = AtomicLong(0L)
    private val faultBudgetIsolateTickCounter = AtomicLong(0L)
    private val commandQueue = ConcurrentLinkedQueue<() -> Unit>()
    private val systemIsolation = SystemFaultIsolationController(
        SystemIsolationPolicy(
            failureThreshold = config.systemIsolationFailureThreshold,
            baseCooldownTicks = config.systemIsolationBaseCooldownTicks,
            maxCooldownTicks = config.systemIsolationMaxCooldownTicks
        )
    )
    private val hostState = StandaloneHostState(
        defaultWorld = config.defaultWorld.trim(),
        chunkViewDistance = config.chunkViewDistance,
        maxChunkLoadsPerTick = config.maxChunkLoadsPerTick,
        maxLoadedChunksPerWorld = config.maxLoadedChunksPerWorld
    )
    private val statePersistence = StandaloneStatePersistence(config.dataDirectory.resolve("standalone-state.json"))
    @Volatile
    private var tickPlugins: List<TickPluginSnapshot> = emptyList()
    private lateinit var runtime: GigaRuntime
    private lateinit var scheduler: ScheduledExecutorService
    private val hostBridge = StandaloneHostBridge(
        serverName = config.serverName,
        serverVersion = config.serverVersion,
        maxPlayers = config.maxPlayers,
        logger = logger,
        hostState = hostState
    )
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()
    private val maxPluginDownloadBytes = 64L * 1024L * 1024L
    private val defaultPluginSeedMarker = config.dataDirectory.resolve("bootstrap/default-plugins-seeded.marker")
    private val bundledDefaultPlugins = listOf(
        "default-plugins/clockwork-plugin-browser.jar"
    )
    private data class TickPluginSnapshot(
        val pluginId: String,
        val context: PluginContext,
        val events: EventBus,
        val runtimeRegistry: RuntimeRegistry?,
        val systemsVersion: Long,
        val systems: List<Pair<String, TickSystem>>
    )

    fun start() {
        if (!running.compareAndSet(false, true)) return
        startedAtMillis.set(System.currentTimeMillis())
        tickCounter.set(0L)
        tickFailureCounter.set(0L)
        tickTotalDurationNanos.set(0L)
        lastTickDurationNanos.set(0L)
        tickQueueDrainNanos.set(0L)
        tickWorldNanos.set(0L)
        tickEventNanos.set(0L)
        tickSystemsNanos.set(0L)
        previousTickStartNanos.set(0L)
        tickJitterTotalNanos.set(0L)
        tickJitterMaxNanos.set(0L)
        tickOverrunCounter.set(0L)
        pluginBudgetExhaustionCounter.set(0L)
        faultBudgetWarnTickCounter.set(0L)
        faultBudgetThrottleTickCounter.set(0L)
        faultBudgetIsolateTickCounter.set(0L)
        commandQueue.clear()
        systemIsolation.clear()

        Files.createDirectories(config.pluginsDirectory)
        Files.createDirectories(config.dataDirectory)
        ensureBundledDefaultPlugins()
        loadState()

        runtime = GigaRuntime(
            pluginsDirectory = config.pluginsDirectory,
            dataDirectory = config.dataDirectory,
            schedulerWorkerThreads = config.runtimeSchedulerWorkerThreads,
            adapterSecurity = config.adapterSecurity,
            faultBudgetPolicy = config.faultBudgetPolicy,
            faultBudgetEscalationPolicy = config.faultBudgetEscalationPolicy,
            eventDispatchMode = config.eventDispatchMode,
            hostAccess = runtimeHostAccess(),
            rootLogger = logger
        )

        scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(
                {
                    coreThreadId.set(Thread.currentThread().threadId())
                    runnable.run()
                },
                "clockwork-core-tick"
            ).apply { isDaemon = true }
        }

        installStandaloneBridgeAdapters(runtime.scanAndLoad())
        refreshTickPluginsSnapshot()
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
        saveState()
        runtime.loadedPluginsView().map { it.manifest.id }.forEach { runtime.unload(it) }
        tickPlugins = emptyList()
        startedAtMillis.set(0L)
        coreThreadId.set(-1L)
        logger.info("Standalone core stopped")
    }

    fun plugins(): List<String> {
        return if (this::runtime.isInitialized) {
            runtime.loadedPlugins().map { "${it.manifest.id}@${it.manifest.version}" }
        } else {
            emptyList()
        }
    }

    fun reload(pluginId: String): ReloadReport = mutate(timeoutMillis = 30_000L) {
        ensureRuntimeInitialized()
        val report = runtime.reloadWithReport(pluginId)
        installStandaloneBridgeAdapters(runtime.loadedPlugins().filter { it.manifest.id in report.reloadedPlugins.toSet() })
        refreshTickPluginsSnapshot()
        report
    }

    fun reloadAll(): ReloadReport = mutate(timeoutMillis = 30_000L) {
        ensureRuntimeInitialized()
        val report = runtime.reloadAllWithReport()
        installStandaloneBridgeAdapters(runtime.loadedPlugins().filter { it.manifest.id in report.reloadedPlugins.toSet() })
        refreshTickPluginsSnapshot()
        report
    }

    fun profile(pluginId: String): PluginRuntimeProfile? = mutate(timeoutMillis = 10_000L) {
        ensureRuntimeInitialized()
        runtime.profile(pluginId)
    }

    fun doctor(): RuntimeDiagnostics = mutate(timeoutMillis = 10_000L) {
        ensureRuntimeInitialized()
        runtime.diagnostics()
    }

    fun loadNewPlugins(): Int = mutate(timeoutMillis = 30_000L) {
        ensureRuntimeInitialized()
        val loadedPlugins = runtime.scanAndLoad()
        installStandaloneBridgeAdapters(loadedPlugins)
        if (loadedPlugins.isNotEmpty()) {
            refreshTickPluginsSnapshot()
        }
        loadedPlugins.size
    }

    fun syncPlugins(): PluginSyncReport = mutate(timeoutMillis = 30_000L) {
        ensureRuntimeInitialized()
        val loadedPlugins = runtime.scanAndLoad()
        if (loadedPlugins.isNotEmpty()) {
            installStandaloneBridgeAdapters(loadedPlugins)
        }

        val changedReload = runtime.reloadChangedWithReport()
        if (changedReload.reloadedPlugins.isNotEmpty()) {
            installStandaloneBridgeAdapters(
                runtime.loadedPlugins().filter { it.manifest.id in changedReload.reloadedPlugins.toSet() }
            )
        }
        if (loadedPlugins.isNotEmpty() || changedReload.reloadedPlugins.isNotEmpty()) {
            refreshTickPluginsSnapshot()
        }

        PluginSyncReport(
            loadedNewPlugins = loadedPlugins.size,
            changedPluginsDetected = changedReload.affectedPlugins.size,
            reloadedPlugins = changedReload.reloadedPlugins,
            reloadStatus = changedReload.status,
            reason = changedReload.reason
        )
    }

    fun installPluginFromUrl(
        url: String,
        fileName: String? = null,
        loadNow: Boolean = true
    ): HostPluginInstallResult = mutate(timeoutMillis = 30_000L) {
        ensureRuntimeInitialized()
        val trimmedUrl = url.trim()
        if (trimmedUrl.isEmpty()) {
            return@mutate HostPluginInstallResult(success = false, message = "URL must not be blank")
        }
        val uri = try {
            URI(trimmedUrl)
        } catch (_: Throwable) {
            return@mutate HostPluginInstallResult(success = false, message = "Invalid URL")
        }
        if (!uri.isAbsolute || (uri.scheme != "https" && uri.scheme != "http")) {
            return@mutate HostPluginInstallResult(success = false, message = "Only http/https URLs are supported")
        }
        val response = httpGetBytesInternal(
            url = trimmedUrl,
            connectTimeoutMillis = 5_000,
            readTimeoutMillis = 20_000,
            maxBodyBytes = maxPluginDownloadBytes.toInt()
        )
        if (!response.success) {
            return@mutate HostPluginInstallResult(
                success = false,
                loaded = false,
                message = response.error ?: "Download failed"
            )
        }
        if (response.body.isEmpty()) {
            return@mutate HostPluginInstallResult(success = false, loaded = false, message = "Downloaded file is empty")
        }
        if (response.body.size.toLong() > maxPluginDownloadBytes) {
            return@mutate HostPluginInstallResult(
                success = false,
                loaded = false,
                message = "Downloaded plugin exceeds ${maxPluginDownloadBytes / (1024 * 1024)}MB limit"
            )
        }

        val targetName = resolvePluginFileName(uri, fileName)
        val target = config.pluginsDirectory.resolve(targetName).normalize()
        if (!target.startsWith(config.pluginsDirectory.toAbsolutePath().normalize())) {
            return@mutate HostPluginInstallResult(success = false, loaded = false, message = "Unsafe plugin file path")
        }
        Files.createDirectories(config.pluginsDirectory)
        Files.write(target, response.body)

        val loadedPlugin = if (loadNow) {
            val loadedNow = runtime.scanAndLoad()
            if (loadedNow.isNotEmpty()) {
                installStandaloneBridgeAdapters(loadedNow)
                refreshTickPluginsSnapshot()
            }
            loadedNow.firstOrNull { it.sourceJarPath.fileName.toString().equals(targetName, ignoreCase = true) }
        } else {
            null
        }

        HostPluginInstallResult(
            success = true,
            pluginId = loadedPlugin?.manifest?.id,
            filePath = target.toString(),
            loaded = loadedPlugin != null,
            message = if (loadedPlugin != null) "Plugin installed and loaded" else "Plugin installed (load pending scan)"
        )
    }

    fun run(pluginId: String, sender: CommandSender, commandLine: String): String {
        ensureRuntimeInitialized()
        val plugin = runtime.loadedPlugin(pluginId)
            ?: return "Unknown plugin: $pluginId"
        val registry = plugin.context.commands as? RuntimeCommandRegistry
            ?: return "Plugin command registry unavailable"
        return registry.execute(plugin.context, sender, commandLine)
    }

    fun adapters(pluginId: String): List<AdapterDescriptor> {
        ensureRuntimeInitialized()
        val plugin = runtime.loadedPlugin(pluginId) ?: return emptyList()
        return plugin.context.adapters.list().map { adapter ->
            AdapterDescriptor(
                id = adapter.id,
                name = adapter.name,
                version = adapter.version,
                capabilities = adapter.capabilities
            )
        }.sortedBy { it.id }
    }

    fun invokeAdapter(
        pluginId: String,
        adapterId: String,
        action: String,
        payload: Map<String, String>
    ): AdapterResponse {
        ensureRuntimeInitialized()
        val plugin = runtime.loadedPlugin(pluginId)
            ?: return AdapterResponse(success = false, message = "Unknown plugin: $pluginId")
        return plugin.context.adapters.invoke(adapterId, AdapterInvocation(action, payload))
    }

    fun applyMutationBatch(batch: HostMutationBatch, cause: String = "plugin"): HostMutationBatchResult = mutate(timeoutMillis = 30_000L) {
        if (batch.id.trim().isEmpty()) {
            return@mutate HostMutationBatchResult(
                batchId = batch.id,
                success = false,
                appliedOperations = 0,
                rolledBack = false,
                error = "Batch id must not be blank"
            )
        }
        val before = hostState.snapshot()
        var applied = 0
        return@mutate try {
            batch.operations.forEachIndexed { index, op ->
                val ok = applyMutationOp(op, cause)
                if (!ok) {
                    error("Operation #$index (${op.type}) failed")
                }
                applied++
            }
            HostMutationBatchResult(
                batchId = batch.id,
                success = true,
                appliedOperations = applied,
                rolledBack = false
            )
        } catch (t: Throwable) {
            hostState.restore(before)
            HostMutationBatchResult(
                batchId = batch.id,
                success = false,
                appliedOperations = applied,
                rolledBack = true,
                error = t.message ?: t.javaClass.simpleName
            )
        }
    }

    fun players(): List<StandalonePlayer> = hostState.players()
    fun worlds(): List<StandaloneWorld> = hostState.worlds()
    fun entities(world: String? = null): List<StandaloneEntity> = hostState.entities(world)

    fun joinPlayer(
        name: String,
        world: String = config.defaultWorld,
        x: Double = 0.0,
        y: Double = 64.0,
        z: Double = 0.0
    ): StandalonePlayer = mutate {
        val existing = hostState.findPlayer(name)
        if (existing == null) {
            if (config.maxPlayers > 0 && hostState.onlinePlayerCount() >= config.maxPlayers) {
                throw StandaloneCapacityException(
                    code = "SERVER_FULL",
                    message = "Server is full (${hostState.onlinePlayerCount()}/${config.maxPlayers} players)"
                )
            }
            if (config.maxEntities > 0 && hostState.entityCount() >= config.maxEntities) {
                throw StandaloneCapacityException(
                    code = "ENTITY_LIMIT_REACHED",
                    message = "Entity limit reached (${hostState.entityCount()}/${config.maxEntities})"
                )
            }
        }
        ensureWorldExistsAndPublish(world, 0L)
        val player = hostState.joinPlayer(name, world, x, y, z)
        publishEvent(StandalonePlayerJoinEvent(player))
        publishEvent(GigaPlayerJoinEvent(player.toHostSnapshot()))
        player
    }

    fun leavePlayer(name: String): StandalonePlayer? = mutate {
        val player = hostState.leavePlayer(name) ?: return@mutate null
        publishEvent(StandalonePlayerLeaveEvent(player))
        publishEvent(GigaPlayerLeaveEvent(player.toHostSnapshot()))
        player
    }

    fun sendPlayerMessage(name: String, message: String, cause: String = "plugin"): Boolean = mutate {
        val player = hostState.findPlayer(name) ?: return@mutate false
        val text = message.trim()
        if (text.isEmpty()) return@mutate false
        logger.info("[message:${player.name}] $text")
        publishEvent(GigaPlayerMessageEvent(player.toHostSnapshot(), text, cause))
        true
    }

    fun kickPlayer(name: String, reason: String = "Kicked by host", cause: String = "plugin"): StandalonePlayer? = mutate {
        val player = hostState.leavePlayer(name) ?: return@mutate null
        val text = KickMessageFormatter.format(reason = reason, cause = cause)
        publishEvent(StandalonePlayerLeaveEvent(player))
        publishEvent(GigaPlayerLeaveEvent(player.toHostSnapshot()))
        publishEvent(GigaPlayerKickEvent(player.toHostSnapshot(), text, cause))
        player
    }

    fun playerIsOp(name: String): Boolean? = hostState.playerIsOp(name)

    fun setPlayerOp(name: String, op: Boolean, cause: String = "plugin"): Boolean = mutate {
        val player = hostState.findPlayer(name) ?: return@mutate false
        val previous = hostState.playerIsOp(name) ?: return@mutate false
        val updated = hostState.setPlayerOp(name, op) ?: return@mutate false
        if (previous != updated) {
            publishEvent(
                GigaPlayerOpChangeEvent(
                    player = player.toHostSnapshot(),
                    previousOp = previous,
                    currentOp = updated,
                    cause = cause
                )
            )
        }
        true
    }

    fun playerPermissions(name: String): Set<String>? = hostState.playerPermissions(name)

    fun hasPlayerPermission(name: String, permission: String): Boolean? = hostState.hasPlayerPermission(name, permission)

    fun grantPlayerPermission(name: String, permission: String, cause: String = "plugin"): Boolean = mutate {
        val player = hostState.findPlayer(name) ?: return@mutate false
        val granted = hostState.grantPlayerPermission(name, permission)
        if (!granted) return@mutate false
        publishEvent(
            GigaPlayerPermissionChangeEvent(
                player = player.toHostSnapshot(),
                permission = permission.trim(),
                granted = true,
                cause = cause
            )
        )
        true
    }

    fun revokePlayerPermission(name: String, permission: String, cause: String = "plugin"): Boolean = mutate {
        val player = hostState.findPlayer(name) ?: return@mutate false
        val revoked = hostState.revokePlayerPermission(name, permission)
        if (!revoked) return@mutate false
        publishEvent(
            GigaPlayerPermissionChangeEvent(
                player = player.toHostSnapshot(),
                permission = permission.trim(),
                granted = false,
                cause = cause
            )
        )
        true
    }

    fun movePlayer(
        name: String,
        x: Double,
        y: Double,
        z: Double,
        world: String? = null
    ): StandalonePlayer? = movePlayerWithCause(name, x, y, z, world, cause = "command")

    fun movePlayerWithCause(
        name: String,
        x: Double,
        y: Double,
        z: Double,
        world: String? = null,
        cause: String = "plugin"
    ): StandalonePlayer? = mutate {
        val previous = hostState.findPlayer(name) ?: return@mutate null
        val started = System.nanoTime()
        val pre = GigaPlayerMovePreEvent(
            player = previous.toHostSnapshot(),
            targetWorld = world?.trim().orEmpty().ifEmpty { previous.world },
            targetX = x,
            targetY = y,
            targetZ = z,
            cause = cause
        )
        publishEvent(pre)
        if (pre.cancelled) {
            publishEvent(
                GigaPlayerMovePostEvent(
                    player = previous.toHostSnapshot(),
                    previous = previous.toHostSnapshot(),
                    current = null,
                    targetWorld = pre.targetWorld,
                    targetX = pre.targetX,
                    targetY = pre.targetY,
                    targetZ = pre.targetZ,
                    cause = pre.cause,
                    success = false,
                    cancelled = true,
                    durationNanos = System.nanoTime() - started,
                    error = pre.cancelReason ?: "cancelled"
                )
            )
            return@mutate null
        }
        if (pre.targetWorld.isNotBlank()) {
            ensureWorldExistsAndPublish(pre.targetWorld, 0L)
        }
        val moved = hostState.movePlayer(
            name = name,
            x = pre.targetX,
            y = pre.targetY,
            z = pre.targetZ,
            world = pre.targetWorld
        )
        if (moved == null) {
            publishEvent(
                GigaPlayerMovePostEvent(
                    player = previous.toHostSnapshot(),
                    previous = previous.toHostSnapshot(),
                    current = null,
                    targetWorld = pre.targetWorld,
                    targetX = pre.targetX,
                    targetY = pre.targetY,
                    targetZ = pre.targetZ,
                    cause = pre.cause,
                    success = false,
                    cancelled = false,
                    durationNanos = System.nanoTime() - started,
                    error = "move_failed"
                )
            )
            return@mutate null
        }
        publishEvent(StandalonePlayerMoveEvent(previous, moved))
        publishEvent(GigaPlayerMoveEvent(previous.toHostSnapshot(), moved.toHostSnapshot()))
        publishEvent(GigaPlayerTeleportEvent(previous.toHostSnapshot(), moved.toHostSnapshot(), pre.cause))
        publishEvent(
            GigaPlayerMovePostEvent(
                player = moved.toHostSnapshot(),
                previous = previous.toHostSnapshot(),
                current = moved.toHostSnapshot(),
                targetWorld = pre.targetWorld,
                targetX = pre.targetX,
                targetY = pre.targetY,
                targetZ = pre.targetZ,
                cause = pre.cause,
                success = true,
                cancelled = false,
                durationNanos = System.nanoTime() - started
            )
        )
        moved
    }

    fun createWorld(name: String, seed: Long = 0L): StandaloneWorld = mutate {
        ensureWorldExistsAndPublish(name, seed)
    }

    fun spawnEntity(
        type: String,
        world: String,
        x: Double,
        y: Double,
        z: Double
    ): StandaloneEntity = mutate {
        val started = System.nanoTime()
        val pre = GigaEntitySpawnPreEvent(
            entityType = type,
            world = world,
            x = x,
            y = y,
            z = z,
            cause = "plugin"
        )
        publishEvent(pre)
        if (pre.cancelled) {
            val reason = pre.cancelReason ?: "cancelled"
            publishEvent(
                GigaEntitySpawnPostEvent(
                    entityType = pre.entityType,
                    world = pre.world,
                    x = pre.x,
                    y = pre.y,
                    z = pre.z,
                    cause = pre.cause,
                    entity = null,
                    success = false,
                    cancelled = true,
                    durationNanos = System.nanoTime() - started,
                    error = reason
                )
            )
            error("Entity spawn cancelled: $reason")
        }
        if (config.maxEntities > 0 && hostState.entityCount() >= config.maxEntities) {
            throw StandaloneCapacityException(
                code = "ENTITY_LIMIT_REACHED",
                message = "Entity limit reached (${hostState.entityCount()}/${config.maxEntities})"
            )
        }
        ensureWorldExistsAndPublish(pre.world, 0L)
        val entity = hostState.spawnEntity(pre.entityType, pre.world, pre.x, pre.y, pre.z)
        publishEvent(StandaloneEntitySpawnEvent(entity))
        val entitySnapshot = entity.toHostSnapshot()
        publishEvent(GigaEntitySpawnEvent(entitySnapshot))
        publishEvent(
            GigaEntitySpawnPostEvent(
                entityType = pre.entityType,
                world = pre.world,
                x = pre.x,
                y = pre.y,
                z = pre.z,
                cause = pre.cause,
                entity = entitySnapshot,
                success = true,
                cancelled = false,
                durationNanos = System.nanoTime() - started
            )
        )
        entity
    }

    fun inventory(owner: String): StandaloneInventory? = hostState.inventory(owner)

    fun setInventoryItem(owner: String, slot: Int, itemId: String): Boolean = mutate {
        val updated = hostState.setInventoryItem(owner, slot, itemId)
        if (updated) {
            publishEvent(StandaloneInventoryChangeEvent(owner, slot, itemId))
            publishEvent(GigaInventoryChangeEvent(owner, slot, itemId))
        }
        updated
    }

    fun worldTime(name: String): Long? = hostState.worldTime(name)

    fun setWorldTime(name: String, time: Long): Boolean = mutate {
        val previous = hostState.worldTime(name) ?: return@mutate false
        val updated = hostState.setWorldTime(name, time) ?: return@mutate false
        if (previous != updated.time) {
            publishEvent(GigaWorldTimeChangeEvent(world = updated.name, previousTime = previous, currentTime = updated.time))
        }
        true
    }

    fun worldData(name: String): Map<String, String>? = hostState.worldData(name)

    fun setWorldData(name: String, data: Map<String, String>, cause: String = "plugin"): Map<String, String>? = mutate {
        val previous = hostState.worldData(name) ?: return@mutate null
        val updated = hostState.setWorldData(name, data) ?: return@mutate null
        if (previous != updated) {
            publishEvent(
                GigaWorldDataChangeEvent(
                    world = name,
                    previousData = previous,
                    currentData = updated,
                    cause = cause
                )
            )
        }
        updated
    }

    fun worldWeather(name: String): String? = hostState.worldWeather(name)

    fun setWorldWeather(name: String, weather: String, cause: String = "plugin"): Boolean = mutate {
        val previous = hostState.worldWeather(name) ?: return@mutate false
        val updated = hostState.setWorldWeather(name, weather) ?: return@mutate false
        if (!previous.equals(updated, ignoreCase = true)) {
            publishEvent(
                GigaWorldWeatherChangeEvent(
                    world = name,
                    previousWeather = previous,
                    currentWeather = updated,
                    cause = cause
                )
            )
        }
        true
    }

    fun findEntity(uuid: String): StandaloneEntity? = hostState.findEntity(uuid)

    fun removeEntity(uuid: String, reason: String = "plugin"): StandaloneEntity? = mutate {
        val removed = hostState.removeEntity(uuid) ?: return@mutate null
        publishEvent(GigaEntityRemoveEvent(entity = removed.toHostSnapshot(), reason = reason))
        removed
    }

    fun entityData(uuid: String): Map<String, String>? = hostState.entityData(uuid)

    fun setEntityData(uuid: String, data: Map<String, String>, cause: String = "plugin"): Map<String, String>? = mutate {
        val entity = hostState.findEntity(uuid) ?: return@mutate null
        val previous = hostState.entityData(uuid) ?: return@mutate null
        val updated = hostState.setEntityData(uuid, data) ?: return@mutate null
        if (previous != updated) {
            publishEvent(
                GigaEntityDataChangeEvent(
                    entity = entity.toHostSnapshot(),
                    previousData = previous,
                    currentData = updated,
                    cause = cause
                )
            )
        }
        updated
    }

    fun inventoryItem(owner: String, slot: Int): String? = hostState.inventoryItem(owner, slot)

    fun givePlayerItem(owner: String, itemId: String, count: Int = 1): Int = mutate {
        hostState.givePlayerItem(owner, itemId, count)
    }

    fun playerGameMode(name: String): String? = hostState.playerGameMode(name)

    fun setPlayerGameMode(name: String, gameMode: String, cause: String = "plugin"): String? = mutate {
        val player = hostState.findPlayer(name) ?: return@mutate null
        val previous = hostState.playerGameMode(name) ?: return@mutate null
        val updated = hostState.setPlayerGameMode(name, gameMode) ?: return@mutate null
        if (!previous.equals(updated, ignoreCase = true)) {
            publishEvent(
                GigaPlayerGameModeChangeEvent(
                    player = player.toHostSnapshot(),
                    previousGameMode = previous,
                    currentGameMode = updated,
                    cause = cause
                )
            )
        }
        updated
    }

    fun playerStatus(name: String): StandalonePlayerStatus? = hostState.playerStatus(name)

    fun setPlayerStatus(name: String, status: StandalonePlayerStatus, cause: String = "plugin"): StandalonePlayerStatus? = mutate {
        val player = hostState.findPlayer(name) ?: return@mutate null
        val previous = hostState.playerStatus(name) ?: return@mutate null
        val updated = hostState.setPlayerStatus(name, status) ?: return@mutate null
        if (previous != updated) {
            publishEvent(
                GigaPlayerStatusChangeEvent(
                    player = player.toHostSnapshot(),
                    previous = previous.toApiSnapshot(),
                    current = updated.toApiSnapshot(),
                    cause = cause
                )
            )
        }
        updated
    }

    fun addPlayerEffect(
        name: String,
        effectId: String,
        durationTicks: Int,
        amplifier: Int = 0,
        cause: String = "plugin"
    ): Boolean = mutate {
        val player = hostState.findPlayer(name) ?: return@mutate false
        val previous = hostState.playerStatus(name) ?: return@mutate false
        val previousDuration = previous.effects[effectId]
        val changed = hostState.addPlayerEffect(name, effectId, durationTicks, amplifier)
        if (!changed) return@mutate false
        val current = hostState.playerStatus(name) ?: return@mutate false
        val currentDuration = current.effects[effectId]
        publishEvent(
            GigaPlayerEffectChangeEvent(
                player = player.toHostSnapshot(),
                effectId = effectId,
                previousDurationTicks = previousDuration,
                currentDurationTicks = currentDuration,
                cause = cause
            )
        )
        true
    }

    fun removePlayerEffect(name: String, effectId: String, cause: String = "plugin"): Boolean = mutate {
        val player = hostState.findPlayer(name) ?: return@mutate false
        val previous = hostState.playerStatus(name) ?: return@mutate false
        val previousDuration = previous.effects[effectId] ?: return@mutate false
        val changed = hostState.removePlayerEffect(name, effectId)
        if (!changed) return@mutate false
        publishEvent(
            GigaPlayerEffectChangeEvent(
                player = player.toHostSnapshot(),
                effectId = effectId,
                previousDurationTicks = previousDuration,
                currentDurationTicks = null,
                cause = cause
            )
        )
        true
    }

    fun blockAt(world: String, x: Int, y: Int, z: Int): StandaloneBlock? = hostState.blockAt(world, x, y, z)

    fun setBlock(world: String, x: Int, y: Int, z: Int, blockId: String, cause: String = "plugin"): StandaloneBlock? = mutate {
        val previous = hostState.blockAt(world, x, y, z)
        val updated = hostState.setBlock(world, x, y, z, blockId) ?: return@mutate null
        publishEvent(
            GigaBlockChangeEvent(
                world = updated.world,
                x = updated.x,
                y = updated.y,
                z = updated.z,
                previousBlockId = previous?.blockId,
                currentBlockId = updated.blockId,
                cause = cause
            )
        )
        updated
    }

    fun breakBlock(world: String, x: Int, y: Int, z: Int, dropLoot: Boolean = true, cause: String = "plugin"): Boolean = mutate {
        val started = System.nanoTime()
        val pre = GigaBlockBreakPreEvent(
            world = world,
            x = x,
            y = y,
            z = z,
            dropLoot = dropLoot,
            cause = cause
        )
        publishEvent(pre)
        val previous = hostState.blockAt(pre.world, pre.x, pre.y, pre.z)
        if (pre.cancelled) {
            publishEvent(
                GigaBlockBreakPostEvent(
                    world = pre.world,
                    x = pre.x,
                    y = pre.y,
                    z = pre.z,
                    dropLoot = pre.dropLoot,
                    cause = pre.cause,
                    previousBlockId = previous?.blockId,
                    success = false,
                    cancelled = true,
                    durationNanos = System.nanoTime() - started,
                    error = pre.cancelReason ?: "cancelled"
                )
            )
            return@mutate false
        }
        if (previous == null) {
            publishEvent(
                GigaBlockBreakPostEvent(
                    world = pre.world,
                    x = pre.x,
                    y = pre.y,
                    z = pre.z,
                    dropLoot = pre.dropLoot,
                    cause = pre.cause,
                    previousBlockId = null,
                    success = false,
                    cancelled = false,
                    durationNanos = System.nanoTime() - started,
                    error = "block_not_found"
                )
            )
            return@mutate false
        }
        val removed = hostState.breakBlock(pre.world, pre.x, pre.y, pre.z) ?: return@mutate false
        publishEvent(
            GigaBlockChangeEvent(
                world = removed.world,
                x = removed.x,
                y = removed.y,
                z = removed.z,
                previousBlockId = previous.blockId,
                currentBlockId = null,
                cause = if (pre.dropLoot) "${pre.cause}:drop" else pre.cause
            )
        )
        publishEvent(
            GigaBlockBreakPostEvent(
                world = removed.world,
                x = removed.x,
                y = removed.y,
                z = removed.z,
                dropLoot = pre.dropLoot,
                cause = pre.cause,
                previousBlockId = previous.blockId,
                success = true,
                cancelled = false,
                durationNanos = System.nanoTime() - started
            )
        )
        true
    }

    fun blockData(world: String, x: Int, y: Int, z: Int): Map<String, String>? = hostState.blockData(world, x, y, z)

    fun setBlockData(
        world: String,
        x: Int,
        y: Int,
        z: Int,
        data: Map<String, String>,
        cause: String = "plugin"
    ): Map<String, String>? = mutate {
        val previous = hostState.blockData(world, x, y, z) ?: return@mutate null
        val updated = hostState.setBlockData(world, x, y, z, data) ?: return@mutate null
        if (previous != updated) {
            publishEvent(
                GigaBlockDataChangeEvent(
                    world = world,
                    x = x,
                    y = y,
                    z = z,
                    previousData = previous,
                    currentData = updated,
                    cause = cause
                )
            )
        }
        updated
    }

    fun saveState() {
        mutate {
            runCatching {
                statePersistence.save(hostState.snapshot())
            }.onFailure {
                logger.info("Failed saving standalone state: ${it.message}")
            }
        }
    }

    fun loadState() {
        mutate {
            runCatching {
                val loaded = statePersistence.loadWithReport() ?: return@runCatching
                hostState.restore(loaded.snapshot)
                if (loaded.report.warnings.isNotEmpty()) {
                    loaded.report.warnings.forEach { warning ->
                        logger.info("State load warning: $warning")
                    }
                }
                if (loaded.report.migrated) {
                    logger.info(
                        "State migrated from schema ${loaded.report.originalSchemaVersion} " +
                            "to ${loaded.report.targetSchemaVersion} (${loaded.report.appliedSteps.joinToString()})"
                    )
                    statePersistence.save(
                        snapshot = loaded.snapshot,
                        migrationHistory = loaded.report.appliedSteps
                    )
                }
            }.onFailure {
                logger.info("Failed loading standalone state: ${it.message}")
            }
        }
    }

    fun status(): StandaloneCoreStatus {
        val ticks = tickCounter.get()
        val average = if (ticks <= 0L) 0L else tickTotalDurationNanos.get() / ticks
        val loadedPlugins = tickPlugins.size
        return StandaloneCoreStatus(
            running = running.get(),
            uptimeMillis = if (startedAtMillis.get() == 0L) 0L else (System.currentTimeMillis() - startedAtMillis.get()),
            tickCount = ticks,
            averageTickDurationNanos = average,
            lastTickDurationNanos = lastTickDurationNanos.get(),
            tickFailures = tickFailureCounter.get(),
            averageQueueDrainNanos = if (ticks <= 0L) 0L else tickQueueDrainNanos.get() / ticks,
            averageWorldTickNanos = if (ticks <= 0L) 0L else tickWorldNanos.get() / ticks,
            averageEventPublishNanos = if (ticks <= 0L) 0L else tickEventNanos.get() / ticks,
            averageSystemsNanos = if (ticks <= 0L) 0L else tickSystemsNanos.get() / ticks,
            averageTickJitterNanos = if (ticks <= 0L) 0L else tickJitterTotalNanos.get() / ticks,
            maxTickJitterNanos = tickJitterMaxNanos.get(),
            tickOverruns = tickOverrunCounter.get(),
            pluginBudgetExhaustions = pluginBudgetExhaustionCounter.get(),
            faultBudgetWarnTicks = faultBudgetWarnTickCounter.get(),
            faultBudgetThrottleTicks = faultBudgetThrottleTickCounter.get(),
            faultBudgetIsolateTicks = faultBudgetIsolateTickCounter.get(),
            loadedPlugins = loadedPlugins,
            onlinePlayers = hostState.onlinePlayerCount(),
            worlds = hostState.worldCount(),
            entities = hostState.entityCount(),
            queuedMutations = commandQueue.size
        )
    }

    private fun tick() {
        val tickStarted = System.nanoTime()
        val previousStart = previousTickStartNanos.getAndSet(tickStarted)
        if (previousStart > 0L) {
            val expectedInterval = config.tickPeriodMillis.coerceAtLeast(1L) * 1_000_000L
            val actualInterval = tickStarted - previousStart
            val jitter = kotlin.math.abs(actualInterval - expectedInterval)
            tickJitterTotalNanos.addAndGet(jitter)
            tickJitterMaxNanos.updateAndGet { current -> if (jitter > current) jitter else current }
        }
        var tickFailure = false
        val beforeQueue1 = System.nanoTime()
        drainCommandQueue()
        tickQueueDrainNanos.addAndGet(System.nanoTime() - beforeQueue1)
        maybeRefreshTickPluginsSnapshot()
        val beforeWorld = System.nanoTime()
        hostState.tickWorlds()
        tickWorldNanos.addAndGet(System.nanoTime() - beforeWorld)
        val tick = tickCounter.incrementAndGet()
        val beforeEvents = System.nanoTime()
        publishTickEvents(tick)
        tickEventNanos.addAndGet(System.nanoTime() - beforeEvents)
        val beforeSystems = System.nanoTime()
        for (entry in tickPlugins) {
            val faultBudgetStage = runtime.faultBudgetStage(entry.pluginId)
            when (faultBudgetStage) {
                FaultBudgetStage.WARN -> faultBudgetWarnTickCounter.incrementAndGet()
                FaultBudgetStage.THROTTLE -> faultBudgetThrottleTickCounter.incrementAndGet()
                FaultBudgetStage.ISOLATE -> faultBudgetIsolateTickCounter.incrementAndGet()
                FaultBudgetStage.NORMAL -> {}
            }
            if (faultBudgetStage == FaultBudgetStage.ISOLATE) {
                runtime.recordPluginFault(entry.pluginId, "fault-budget:isolate")
                logger.info("Plugin ${entry.pluginId} isolated by fault-budget escalation for this tick")
                continue
            }
            var pluginSpentNanos = 0L
            var budgetExhausted = false
            var budgetFaultRecorded = false
            val effectiveBudget = when (faultBudgetStage) {
                FaultBudgetStage.THROTTLE -> {
                    val throttled = (config.perPluginTickBudgetNanos * config.faultBudgetEscalationPolicy.throttleBudgetMultiplier).toLong()
                    throttled.coerceAtLeast(1L)
                }
                else -> config.perPluginTickBudgetNanos.coerceAtLeast(1L)
            }
            for ((systemId, system) in entry.systems) {
                if (pluginSpentNanos >= effectiveBudget) {
                    budgetExhausted = true
                    if (!budgetFaultRecorded) {
                        runtime.recordPluginFault(
                            pluginId = entry.pluginId,
                            source = "budget:tick"
                        )
                        budgetFaultRecorded = true
                    }
                    break
                }
                if (!systemIsolation.shouldRun(entry.pluginId, systemId, tick)) {
                    runtime.recordSystemIsolation(
                        pluginId = entry.pluginId,
                        snapshot = systemIsolationSnapshot(entry.pluginId, systemId, tick)
                    )
                    continue
                }
                val started = System.nanoTime()
                var success = true
                try {
                    system.onTick(entry.context)
                    systemIsolation.onSuccess(entry.pluginId, systemId)
                } catch (t: Throwable) {
                    success = false
                    tickFailure = true
                    val error = t.message ?: t.javaClass.simpleName
                    runtime.recordPluginFault(
                        pluginId = entry.pluginId,
                        source = "system:$systemId"
                    )
                    val cooldown = systemIsolation.onFailure(
                        pluginId = entry.pluginId,
                        systemId = systemId,
                        tick = tick,
                        error = error
                    )
                    if (cooldown > 0L) {
                        logger.info(
                            "System ${entry.pluginId}:$systemId isolated for $cooldown ticks after repeated failures (lastError=$error)"
                        )
                    } else {
                        logger.info("System ${entry.pluginId}:$systemId failed: $error")
                    }
                } finally {
                    val elapsed = System.nanoTime() - started
                    pluginSpentNanos += elapsed
                    if (pluginSpentNanos > effectiveBudget) {
                        budgetExhausted = true
                        if (!budgetFaultRecorded) {
                            runtime.recordPluginFault(
                                pluginId = entry.pluginId,
                                source = "budget:tick"
                            )
                            budgetFaultRecorded = true
                        }
                    }
                    runtime.recordSystemTick(
                        pluginId = entry.pluginId,
                        systemId = systemId,
                        durationNanos = elapsed,
                        success = success
                    )
                    runtime.recordSystemIsolation(
                        pluginId = entry.pluginId,
                        snapshot = systemIsolationSnapshot(entry.pluginId, systemId, tick)
                    )
                }
            }
            if (budgetExhausted) {
                pluginBudgetExhaustionCounter.incrementAndGet()
                logger.info("Plugin ${entry.pluginId} exhausted per-plugin tick budget (${effectiveBudget}ns, stage=$faultBudgetStage); remaining systems skipped for this tick")
            }
        }
        tickSystemsNanos.addAndGet(System.nanoTime() - beforeSystems)
        val beforeQueue2 = System.nanoTime()
        drainCommandQueue()
        tickQueueDrainNanos.addAndGet(System.nanoTime() - beforeQueue2)

        val duration = System.nanoTime() - tickStarted
        val tickBudgetNanos = config.tickPeriodMillis.coerceAtLeast(1L) * 1_000_000L
        if (duration > tickBudgetNanos) {
            tickOverrunCounter.incrementAndGet()
        }
        lastTickDurationNanos.set(duration)
        tickTotalDurationNanos.addAndGet(duration)
        if (tickFailure) {
            tickFailureCounter.incrementAndGet()
        }
        if (config.autoSaveEveryTicks > 0L && tick % config.autoSaveEveryTicks == 0L) {
            saveState()
        }
    }

    fun deterministicExecutionOrder(): Map<String, List<String>> {
        return tickPlugins
            .sortedBy { it.pluginId.lowercase() }
            .associate { entry ->
                entry.pluginId to entry.systems.map { it.first }
            }
            .toSortedMap()
    }

    private fun publishTickEvents(tick: Long) {
        if (!this::runtime.isInitialized) return
        val plugins = tickPlugins
        if (plugins.isEmpty()) return
        val standaloneEvent = StandaloneTickEvent(tick)
        val apiEvent = GigaTickEvent(tick)
        for (entry in plugins) {
            try {
                entry.events.publish(standaloneEvent)
                entry.events.publish(apiEvent)
            } catch (_: Throwable) {
                // Event delivery is best effort and must not break core loop.
            }
        }
    }

    private fun drainCommandQueue() {
        while (true) {
            val task = commandQueue.poll() ?: break
            try {
                task()
            } catch (t: Throwable) {
                logger.info("Mutation queue task failed: ${t.message}")
            }
        }
    }

    private fun publishEvent(event: Any) {
        if (!this::runtime.isInitialized) return
        val plugins = tickPlugins
        if (plugins.isEmpty()) return
        for (entry in plugins) {
            try {
                entry.events.publish(event)
            } catch (_: Throwable) {
                // Event delivery is best effort and must not break core loop.
            }
        }
    }

    private fun runtimeHostAccess(): HostAccess {
        return object : HostAccess {
            override fun serverInfo() = hostBridge.serverInfo().toApi()
            override fun broadcast(message: String): Boolean = runCatching { hostBridge.broadcast(message) }.isSuccess
            override fun findPlayer(name: String): HostPlayerSnapshot? = this@GigaStandaloneCore.findPlayer(name)
            override fun sendPlayerMessage(name: String, message: String): Boolean {
                return this@GigaStandaloneCore.sendPlayerMessage(name, message, cause = "plugin")
            }
            override fun kickPlayer(name: String, reason: String): Boolean {
                return this@GigaStandaloneCore.kickPlayer(name, reason, cause = "plugin") != null
            }
            override fun playerIsOp(name: String): Boolean? = this@GigaStandaloneCore.playerIsOp(name)
            override fun setPlayerOp(name: String, op: Boolean): Boolean {
                return this@GigaStandaloneCore.setPlayerOp(name, op, cause = "plugin")
            }
            override fun playerPermissions(name: String): Set<String>? = this@GigaStandaloneCore.playerPermissions(name)
            override fun hasPlayerPermission(name: String, permission: String): Boolean? {
                return this@GigaStandaloneCore.hasPlayerPermission(name, permission)
            }
            override fun grantPlayerPermission(name: String, permission: String): Boolean {
                return this@GigaStandaloneCore.grantPlayerPermission(name, permission, cause = "plugin")
            }
            override fun revokePlayerPermission(name: String, permission: String): Boolean {
                return this@GigaStandaloneCore.revokePlayerPermission(name, permission, cause = "plugin")
            }
            override fun worlds(): List<HostWorldSnapshot> = this@GigaStandaloneCore.worlds().map { it.toHostSnapshot() }
            override fun entities(world: String?): List<HostEntitySnapshot> = this@GigaStandaloneCore.entities(world).map { it.toHostSnapshot() }
            override fun spawnEntity(type: String, location: HostLocationRef): HostEntitySnapshot? {
                return runCatching {
                    this@GigaStandaloneCore.spawnEntity(type, location.world, location.x, location.y, location.z).toHostSnapshot()
                }.getOrNull()
            }
            override fun playerInventory(name: String) = hostBridge.playerInventory(name).toApi()
            override fun setPlayerInventoryItem(name: String, slot: Int, itemId: String): Boolean {
                return this@GigaStandaloneCore.setInventoryItem(name, slot, itemId)
            }
            override fun createWorld(name: String, seed: Long): HostWorldSnapshot? {
                return this@GigaStandaloneCore.createWorld(name, seed).toHostSnapshot()
            }
            override fun worldTime(name: String): Long? = this@GigaStandaloneCore.worldTime(name)
            override fun setWorldTime(name: String, time: Long): Boolean = this@GigaStandaloneCore.setWorldTime(name, time)
            override fun worldData(name: String): Map<String, String>? = this@GigaStandaloneCore.worldData(name)
            override fun setWorldData(name: String, data: Map<String, String>): Map<String, String>? {
                return this@GigaStandaloneCore.setWorldData(name, data, cause = "plugin")
            }
            override fun worldWeather(name: String): String? = this@GigaStandaloneCore.worldWeather(name)
            override fun setWorldWeather(name: String, weather: String): Boolean {
                return this@GigaStandaloneCore.setWorldWeather(name, weather, cause = "plugin")
            }
            override fun findEntity(uuid: String): HostEntitySnapshot? = this@GigaStandaloneCore.findEntity(uuid)?.toHostSnapshot()
            override fun removeEntity(uuid: String): Boolean = this@GigaStandaloneCore.removeEntity(uuid) != null
            override fun entityData(uuid: String): Map<String, String>? = this@GigaStandaloneCore.entityData(uuid)
            override fun setEntityData(uuid: String, data: Map<String, String>): Map<String, String>? {
                return this@GigaStandaloneCore.setEntityData(uuid, data, cause = "plugin")
            }
            override fun movePlayer(name: String, location: HostLocationRef): HostPlayerSnapshot? {
                return this@GigaStandaloneCore.movePlayerWithCause(
                    name = name,
                    x = location.x,
                    y = location.y,
                    z = location.z,
                    world = location.world,
                    cause = "plugin"
                )?.toHostSnapshot()
            }
            override fun inventoryItem(name: String, slot: Int): String? = this@GigaStandaloneCore.inventoryItem(name, slot)
            override fun givePlayerItem(name: String, itemId: String, count: Int): Int {
                return this@GigaStandaloneCore.givePlayerItem(name, itemId, count)
            }
            override fun playerGameMode(name: String): String? {
                return this@GigaStandaloneCore.playerGameMode(name)
            }
            override fun setPlayerGameMode(name: String, gameMode: String): Boolean {
                return this@GigaStandaloneCore.setPlayerGameMode(name, gameMode, cause = "plugin") != null
            }
            override fun playerStatus(name: String): HostPlayerStatusSnapshot? {
                return this@GigaStandaloneCore.playerStatus(name)?.toApiSnapshot()
            }
            override fun setPlayerStatus(name: String, status: HostPlayerStatusSnapshot): HostPlayerStatusSnapshot? {
                return this@GigaStandaloneCore.setPlayerStatus(name, status.toStandalone(), cause = "plugin")?.toApiSnapshot()
            }
            override fun addPlayerEffect(name: String, effectId: String, durationTicks: Int, amplifier: Int): Boolean {
                return this@GigaStandaloneCore.addPlayerEffect(name, effectId, durationTicks, amplifier, cause = "plugin")
            }
            override fun removePlayerEffect(name: String, effectId: String): Boolean {
                return this@GigaStandaloneCore.removePlayerEffect(name, effectId, cause = "plugin")
            }
            override fun blockAt(world: String, x: Int, y: Int, z: Int): HostBlockSnapshot? {
                return this@GigaStandaloneCore.blockAt(world, x, y, z)?.toHostSnapshot()
            }
            override fun setBlock(world: String, x: Int, y: Int, z: Int, blockId: String): HostBlockSnapshot? {
                return this@GigaStandaloneCore.setBlock(world, x, y, z, blockId, cause = "plugin")?.toHostSnapshot()
            }
            override fun breakBlock(world: String, x: Int, y: Int, z: Int, dropLoot: Boolean): Boolean {
                return this@GigaStandaloneCore.breakBlock(world, x, y, z, dropLoot, cause = "plugin")
            }
            override fun blockData(world: String, x: Int, y: Int, z: Int): Map<String, String>? {
                return this@GigaStandaloneCore.blockData(world, x, y, z)
            }
            override fun setBlockData(world: String, x: Int, y: Int, z: Int, data: Map<String, String>): Map<String, String>? {
                return this@GigaStandaloneCore.setBlockData(world, x, y, z, data, cause = "plugin")
            }
            override fun httpGet(
                url: String,
                connectTimeoutMillis: Int,
                readTimeoutMillis: Int,
                maxBodyChars: Int
            ): HostHttpResponse? {
                return this@GigaStandaloneCore.httpGetInternal(url, connectTimeoutMillis, readTimeoutMillis, maxBodyChars)
            }
            override fun installPluginFromUrl(url: String, fileName: String?, loadNow: Boolean): HostPluginInstallResult {
                return this@GigaStandaloneCore.installPluginFromUrl(
                    url = url,
                    fileName = fileName,
                    loadNow = loadNow
                )
            }
            override fun applyMutationBatch(batch: HostMutationBatch): HostMutationBatchResult {
                return this@GigaStandaloneCore.applyMutationBatch(batch, cause = "plugin")
            }
        }
    }

    private fun applyMutationOp(op: HostMutationOp, cause: String): Boolean {
        return when (op.type) {
            HostMutationType.CREATE_WORLD -> {
                val worldName = op.target.trim()
                if (worldName.isEmpty()) false else createWorld(worldName, op.longValue ?: 0L).name.equals(worldName, ignoreCase = true)
            }
            HostMutationType.SET_WORLD_TIME -> {
                val worldName = op.target.trim()
                val time = op.longValue ?: return false
                if (worldName.isEmpty()) false else setWorldTime(worldName, time)
            }
            HostMutationType.SET_WORLD_DATA -> {
                val worldName = op.target.trim()
                if (worldName.isEmpty()) false else setWorldData(worldName, op.data, cause = cause) != null
            }
            HostMutationType.SET_WORLD_WEATHER -> {
                val worldName = op.target.trim()
                val weather = op.stringValue?.trim().orEmpty()
                if (worldName.isEmpty() || weather.isEmpty()) false else setWorldWeather(worldName, weather, cause = cause)
            }
            HostMutationType.SPAWN_ENTITY -> {
                val type = op.target.trim()
                val world = op.world?.trim().orEmpty()
                val x = op.x ?: return false
                val y = op.y ?: return false
                val z = op.z ?: return false
                if (type.isEmpty() || world.isEmpty()) false else runCatching { spawnEntity(type, world, x, y, z) }.isSuccess
            }
            HostMutationType.REMOVE_ENTITY -> {
                val uuid = op.target.trim()
                if (uuid.isEmpty()) false else removeEntity(uuid, reason = cause) != null
            }
            HostMutationType.SET_PLAYER_INVENTORY_ITEM -> {
                val player = op.target.trim()
                val slot = op.intValue ?: return false
                val item = op.stringValue?.trim().orEmpty()
                if (player.isEmpty() || item.isEmpty()) false else setInventoryItem(player, slot, item)
            }
            HostMutationType.GIVE_PLAYER_ITEM -> {
                val player = op.target.trim()
                val item = op.stringValue?.trim().orEmpty()
                val count = op.intValue ?: 1
                if (player.isEmpty() || item.isEmpty()) false else givePlayerItem(player, item, count) > 0
            }
            HostMutationType.MOVE_PLAYER -> {
                val player = op.target.trim()
                val world = op.world?.trim()
                val x = op.x ?: return false
                val y = op.y ?: return false
                val z = op.z ?: return false
                if (player.isEmpty()) false else movePlayerWithCause(player, x, y, z, world = world, cause = cause) != null
            }
            HostMutationType.SET_PLAYER_GAMEMODE -> {
                val player = op.target.trim()
                val gameMode = op.stringValue?.trim().orEmpty()
                if (player.isEmpty() || gameMode.isEmpty()) false else setPlayerGameMode(player, gameMode, cause = cause) != null
            }
            HostMutationType.ADD_PLAYER_EFFECT -> {
                val player = op.target.trim()
                val effectId = op.stringValue?.trim().orEmpty()
                val duration = op.intValue ?: return false
                val amplifier = op.longValue?.toInt() ?: 0
                if (player.isEmpty() || effectId.isEmpty()) false else addPlayerEffect(player, effectId, duration, amplifier, cause = cause)
            }
            HostMutationType.REMOVE_PLAYER_EFFECT -> {
                val player = op.target.trim()
                val effectId = op.stringValue?.trim().orEmpty()
                if (player.isEmpty() || effectId.isEmpty()) false else removePlayerEffect(player, effectId, cause = cause)
            }
            HostMutationType.SET_BLOCK -> {
                val world = op.world?.trim().orEmpty()
                val blockId = op.stringValue?.trim().orEmpty()
                val x = op.x?.toInt() ?: return false
                val y = op.y?.toInt() ?: return false
                val z = op.z?.toInt() ?: return false
                if (world.isEmpty() || blockId.isEmpty()) false else setBlock(world, x, y, z, blockId, cause = cause) != null
            }
            HostMutationType.BREAK_BLOCK -> {
                val world = op.world?.trim().orEmpty()
                val x = op.x?.toInt() ?: return false
                val y = op.y?.toInt() ?: return false
                val z = op.z?.toInt() ?: return false
                if (world.isEmpty()) false else breakBlock(world, x, y, z, dropLoot = op.boolValue ?: true, cause = cause)
            }
            HostMutationType.SET_BLOCK_DATA -> {
                val world = op.world?.trim().orEmpty()
                val x = op.x?.toInt() ?: return false
                val y = op.y?.toInt() ?: return false
                val z = op.z?.toInt() ?: return false
                if (world.isEmpty()) false else setBlockData(world, x, y, z, op.data, cause = cause) != null
            }
        }
    }

    private fun ensureBundledDefaultPlugins() {
        if (Files.exists(defaultPluginSeedMarker)) return
        Files.createDirectories(config.pluginsDirectory)
        Files.createDirectories(defaultPluginSeedMarker.parent)

        var seededAny = false
        bundledDefaultPlugins.forEach { resourcePath ->
            val resourceName = resourcePath.substringAfterLast('/')
            val target = config.pluginsDirectory.resolve(resourceName).normalize()
            if (!target.startsWith(config.pluginsDirectory.toAbsolutePath().normalize())) return@forEach
            if (Files.exists(target)) return@forEach
            javaClass.classLoader.getResourceAsStream(resourcePath)?.use { input ->
                Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING)
                seededAny = true
                logger.info("Seeded default plugin: ${target.fileName}")
            }
        }

        if (seededAny || !Files.exists(defaultPluginSeedMarker)) {
            Files.writeString(defaultPluginSeedMarker, "seeded=${System.currentTimeMillis()}\n")
        }
    }

    private fun resolvePluginFileName(uri: URI, explicitName: String?): String {
        val candidate = explicitName?.trim().orEmpty()
        val chosen = if (candidate.isNotEmpty()) {
            candidate
        } else {
            val fromPath = uri.path?.substringAfterLast('/')?.trim().orEmpty()
            if (fromPath.isNotEmpty()) fromPath else "plugin-${System.currentTimeMillis()}.jar"
        }
        val cleaned = chosen.replace("\\", "_").replace("/", "_")
        return if (cleaned.endsWith(".jar", ignoreCase = true)) cleaned else "$cleaned.jar"
    }

    private data class HttpBytesResult(
        val success: Boolean,
        val statusCode: Int,
        val body: ByteArray,
        val headers: Map<String, String>,
        val error: String? = null
    )

    private fun httpGetBytesInternal(
        url: String,
        connectTimeoutMillis: Int,
        readTimeoutMillis: Int,
        maxBodyBytes: Int
    ): HttpBytesResult {
        if (maxBodyBytes <= 0) {
            return HttpBytesResult(success = false, statusCode = 0, body = ByteArray(0), headers = emptyMap(), error = "maxBodyBytes must be > 0")
        }
        val uri = try {
            URI(url.trim())
        } catch (_: Throwable) {
            return HttpBytesResult(success = false, statusCode = 0, body = ByteArray(0), headers = emptyMap(), error = "Invalid URL")
        }
        if (!uri.isAbsolute || (uri.scheme != "https" && uri.scheme != "http")) {
            return HttpBytesResult(success = false, statusCode = 0, body = ByteArray(0), headers = emptyMap(), error = "Only http/https URLs are supported")
        }
        return try {
            val request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMillis(readTimeoutMillis.toLong().coerceAtLeast(1L)))
                .GET()
                .build()
            val client = if (connectTimeoutMillis > 0) {
                HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofMillis(connectTimeoutMillis.toLong()))
                    .build()
            } else {
                httpClient
            }
            val response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())
            val headers = response.headers().map().mapValues { it.value.joinToString(",") }
            val bytes = response.body() ?: ByteArray(0)
            if (bytes.size > maxBodyBytes) {
                HttpBytesResult(
                    success = false,
                    statusCode = response.statusCode(),
                    body = ByteArray(0),
                    headers = headers,
                    error = "Response body exceeds limit ($maxBodyBytes bytes)"
                )
            } else {
                HttpBytesResult(
                    success = response.statusCode() in 200..299,
                    statusCode = response.statusCode(),
                    body = bytes,
                    headers = headers,
                    error = if (response.statusCode() in 200..299) null else "HTTP ${response.statusCode()}"
                )
            }
        } catch (t: Throwable) {
            HttpBytesResult(success = false, statusCode = 0, body = ByteArray(0), headers = emptyMap(), error = t.message ?: t.javaClass.simpleName)
        }
    }

    private fun httpGetInternal(
        url: String,
        connectTimeoutMillis: Int,
        readTimeoutMillis: Int,
        maxBodyChars: Int
    ): HostHttpResponse {
        val bytesResult = httpGetBytesInternal(
            url = url,
            connectTimeoutMillis = connectTimeoutMillis,
            readTimeoutMillis = readTimeoutMillis,
            maxBodyBytes = maxBodyChars.coerceAtLeast(1) * 4
        )
        if (!bytesResult.success) {
            return HostHttpResponse(
                success = false,
                statusCode = bytesResult.statusCode,
                body = "",
                headers = bytesResult.headers,
                error = bytesResult.error
            )
        }
        val decoded = bytesResult.body.toString(Charsets.UTF_8)
        val capped = if (decoded.length > maxBodyChars) decoded.take(maxBodyChars) else decoded
        return HostHttpResponse(
            success = true,
            statusCode = bytesResult.statusCode,
            body = capped,
            headers = bytesResult.headers
        )
    }

    private fun installStandaloneBridgeAdapters(plugins: List<com.clockwork.runtime.LoadedPlugin>) {
        plugins.forEach { plugin ->
            try {
                HostBridgeAdapters.registerDefaults(
                    pluginId = plugin.manifest.id,
                    registry = plugin.context.adapters,
                    hostBridge = hostBridge,
                    logger = logger,
                    bridgeName = "Standalone",
                    grantedPermissions = plugin.manifest.permissions.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                )
            } catch (t: Throwable) {
                logger.info("Failed installing standalone bridge adapters for ${plugin.manifest.id}: ${t.message}")
            }
        }
    }

    private fun ensureRuntimeInitialized() {
        check(this::runtime.isInitialized) { "Standalone core is not started" }
    }

    private fun refreshTickPluginsSnapshot() {
        val refreshed = runtime.loadedPluginsView()
            .sortedBy { it.manifest.id.lowercase() }
            .map { plugin ->
                val runtimeRegistry = plugin.context.registry as? RuntimeRegistry
                val systems = (runtimeRegistry?.systemsSnapshot()
                    ?: plugin.context.registry.systems().map { it.key to it.value })
                    .sortedBy { it.first.lowercase() }
                TickPluginSnapshot(
                    pluginId = plugin.manifest.id,
                    context = plugin.context,
                    events = plugin.context.events,
                    runtimeRegistry = runtimeRegistry,
                    systemsVersion = runtimeRegistry?.systemsVersion() ?: -1L,
                    systems = systems
                )
            }
        tickPlugins = refreshed
        pruneSystemIsolationState(refreshed)
    }

    private fun systemIsolationSnapshot(pluginId: String, systemId: String, tick: Long): SystemIsolationSnapshot {
        return systemIsolation.snapshot(pluginId = pluginId, systemId = systemId, tick = tick)
    }

    private fun pruneSystemIsolationState(snapshots: List<TickPluginSnapshot>) {
        val activeSystems = snapshots
            .flatMap { entry -> entry.systems.map { (systemId, _) -> SystemKey(entry.pluginId, systemId) } }
            .toSet()
        val removed = systemIsolation.pruneTo(activeSystems)
        removed.forEach { key ->
            runtime.removeSystemIsolation(pluginId = key.pluginId, systemId = key.systemId)
        }
    }

    private fun maybeRefreshTickPluginsSnapshot() {
        for (entry in tickPlugins) {
            val registry = entry.runtimeRegistry ?: continue
            if (registry.systemsVersion() != entry.systemsVersion) {
                refreshTickPluginsSnapshot()
                return
            }
        }
    }

    private fun isOnCoreThread(): Boolean {
        return Thread.currentThread().threadId() == coreThreadId.get()
    }

    private fun <T> mutate(timeoutMillis: Long = 5_000L, block: () -> T): T {
        if (!running.get() || !this::scheduler.isInitialized || isOnCoreThread()) {
            return block()
        }
        val future = CompletableFuture<T>()
        commandQueue.add {
            try {
                future.complete(block())
            } catch (t: Throwable) {
                future.completeExceptionally(t)
            }
        }
        return try {
            future.get(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (e: ExecutionException) {
            val cause = e.cause
            if (cause is RuntimeException) throw cause
            if (cause != null) throw IllegalStateException(cause.message ?: "Mutation failed", cause)
            throw IllegalStateException(e.message ?: "Mutation failed", e)
        } catch (_: TimeoutException) {
            throw IllegalStateException("Timed out waiting for core mutation execution (${timeoutMillis}ms)")
        }
    }

    private fun ensureWorldExistsAndPublish(name: String, seed: Long): StandaloneWorld {
        if (!hostState.hasWorld(name) && config.maxWorlds > 0 && hostState.worldCount() >= config.maxWorlds) {
            throw StandaloneCapacityException(
                code = "WORLD_LIMIT_REACHED",
                message = "World limit reached (${hostState.worldCount()}/${config.maxWorlds})"
            )
        }
        val result = hostState.createWorldWithStatus(name, seed)
        if (result.created) {
            publishEvent(StandaloneWorldCreatedEvent(result.world))
            publishEvent(
                GigaWorldCreatedEvent(
                    HostWorldSnapshot(
                        name = result.world.name,
                        entityCount = hostState.entityCount(result.world.name)
                    )
                )
            )
        }
        return result.world
    }

    private fun StandalonePlayer.toHostSnapshot(): HostPlayerSnapshot {
        return HostPlayerSnapshot(
            uuid = uuid,
            name = name,
            location = HostLocationRef(
                world = world,
                x = x,
                y = y,
                z = z
            )
        )
    }

    private fun StandaloneEntity.toHostSnapshot(): HostEntitySnapshot {
        return HostEntitySnapshot(
            uuid = uuid,
            type = type,
            location = HostLocationRef(
                world = world,
                x = x,
                y = y,
                z = z
            )
        )
    }

    private fun StandaloneWorld.toHostSnapshot(): HostWorldSnapshot {
        return HostWorldSnapshot(
            name = name,
            entityCount = hostState.entityCount(name)
        )
    }

    private fun StandaloneBlock.toHostSnapshot(): HostBlockSnapshot {
        return HostBlockSnapshot(
            world = world,
            x = x,
            y = y,
            z = z,
            blockId = blockId
        )
    }

    private fun StandalonePlayerStatus.toApiSnapshot(): HostPlayerStatusSnapshot {
        return HostPlayerStatusSnapshot(
            health = health,
            maxHealth = maxHealth,
            foodLevel = foodLevel,
            saturation = saturation,
            experienceLevel = experienceLevel,
            experienceProgress = experienceProgress,
            effects = effects
        )
    }

    private fun HostPlayerStatusSnapshot.toStandalone(): StandalonePlayerStatus {
        return StandalonePlayerStatus(
            health = health,
            maxHealth = maxHealth,
            foodLevel = foodLevel,
            saturation = saturation,
            experienceLevel = experienceLevel,
            experienceProgress = experienceProgress,
            effects = effects
        )
    }

    private fun com.clockwork.host.api.HostServerSnapshot.toApi(): com.clockwork.api.HostServerSnapshot {
        return com.clockwork.api.HostServerSnapshot(
            name = name,
            version = version,
            platformVersion = platformVersion,
            onlinePlayers = onlinePlayers,
            maxPlayers = maxPlayers,
            worldCount = worldCount
        )
    }

    private fun com.clockwork.host.api.HostInventorySnapshot?.toApi(): com.clockwork.api.HostInventorySnapshot? {
        val value = this ?: return null
        return com.clockwork.api.HostInventorySnapshot(
            owner = value.owner,
            size = value.size,
            nonEmptySlots = value.nonEmptySlots
        )
    }

    private fun findPlayer(name: String): HostPlayerSnapshot? {
        return hostState.findPlayer(name)?.toHostSnapshot()
    }
}



