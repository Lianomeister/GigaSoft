package com.gigasoft.core

import com.gigasoft.api.AdapterInvocation
import com.gigasoft.api.AdapterResponse
import com.gigasoft.api.GigaLogger
import com.gigasoft.api.GigaEntitySpawnEvent
import com.gigasoft.api.GigaInventoryChangeEvent
import com.gigasoft.api.GigaBlockChangeEvent
import com.gigasoft.api.GigaBlockDataChangeEvent
import com.gigasoft.api.GigaEntityDataChangeEvent
import com.gigasoft.api.GigaPlayerJoinEvent
import com.gigasoft.api.GigaPlayerLeaveEvent
import com.gigasoft.api.GigaPlayerMoveEvent
import com.gigasoft.api.GigaPlayerTeleportEvent
import com.gigasoft.api.GigaTickEvent
import com.gigasoft.api.GigaWorldTimeChangeEvent
import com.gigasoft.api.GigaEntityRemoveEvent
import com.gigasoft.api.GigaWorldCreatedEvent
import com.gigasoft.api.EventBus
import com.gigasoft.api.HostAccess
import com.gigasoft.api.HostEntitySnapshot
import com.gigasoft.api.HostBlockSnapshot
import com.gigasoft.api.HostLocationRef
import com.gigasoft.api.HostPlayerSnapshot
import com.gigasoft.api.HostWorldSnapshot
import com.gigasoft.api.PluginContext
import com.gigasoft.api.TickSystem
import com.gigasoft.host.api.HostBridgeAdapters
import com.gigasoft.runtime.AdapterSecurityConfig
import com.gigasoft.runtime.EventDispatchMode
import com.gigasoft.runtime.GigaRuntime
import com.gigasoft.runtime.PluginRuntimeProfile
import com.gigasoft.runtime.ReloadReport
import com.gigasoft.runtime.RuntimeCommandRegistry
import com.gigasoft.runtime.RuntimeDiagnostics
import com.gigasoft.runtime.RuntimeRegistry
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

data class StandaloneCoreConfig(
    val pluginsDirectory: Path,
    val dataDirectory: Path,
    val tickPeriodMillis: Long = 50L,
    val serverName: String = "GigaSoft Standalone",
    val serverVersion: String = "1.1.0-SNAPSHOT",
    val maxPlayers: Int = 0,
    val autoSaveEveryTicks: Long = 200L,
    val adapterSecurity: AdapterSecurityConfig = AdapterSecurityConfig(),
    val eventDispatchMode: EventDispatchMode = EventDispatchMode.EXACT
)

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
    val loadedPlugins: Int,
    val onlinePlayers: Int,
    val worlds: Int,
    val entities: Int,
    val queuedMutations: Int
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
    private val commandQueue = ConcurrentLinkedQueue<() -> Unit>()
    private val hostState = StandaloneHostState()
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
        commandQueue.clear()

        Files.createDirectories(config.pluginsDirectory)
        Files.createDirectories(config.dataDirectory)
        loadState()

        runtime = GigaRuntime(
            pluginsDirectory = config.pluginsDirectory,
            dataDirectory = config.dataDirectory,
            adapterSecurity = config.adapterSecurity,
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
                "gigasoft-core-tick"
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

    fun reload(pluginId: String): ReloadReport = mutate {
        ensureRuntimeInitialized()
        val report = runtime.reloadWithReport(pluginId)
        installStandaloneBridgeAdapters(runtime.loadedPlugins().filter { it.manifest.id in report.reloadedPlugins.toSet() })
        refreshTickPluginsSnapshot()
        report
    }

    fun reloadAll(): ReloadReport = mutate {
        ensureRuntimeInitialized()
        val report = runtime.reloadAllWithReport()
        installStandaloneBridgeAdapters(runtime.loadedPlugins().filter { it.manifest.id in report.reloadedPlugins.toSet() })
        refreshTickPluginsSnapshot()
        report
    }

    fun profile(pluginId: String): PluginRuntimeProfile? {
        ensureRuntimeInitialized()
        return runtime.profile(pluginId)
    }

    fun doctor(): RuntimeDiagnostics {
        ensureRuntimeInitialized()
        return runtime.diagnostics()
    }

    fun loadNewPlugins(): Int = mutate {
        ensureRuntimeInitialized()
        val loadedPlugins = runtime.scanAndLoad()
        installStandaloneBridgeAdapters(loadedPlugins)
        if (loadedPlugins.isNotEmpty()) {
            refreshTickPluginsSnapshot()
        }
        loadedPlugins.size
    }

    fun run(pluginId: String, sender: String, commandLine: String): String {
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

    fun players(): List<StandalonePlayer> = hostState.players()
    fun worlds(): List<StandaloneWorld> = hostState.worlds()
    fun entities(world: String? = null): List<StandaloneEntity> = hostState.entities(world)

    fun joinPlayer(
        name: String,
        world: String = "world",
        x: Double = 0.0,
        y: Double = 64.0,
        z: Double = 0.0
    ): StandalonePlayer = mutate {
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
        if (!world.isNullOrBlank()) {
            ensureWorldExistsAndPublish(world, 0L)
        }
        val previous = hostState.findPlayer(name) ?: return@mutate null
        val moved = hostState.movePlayer(name, x, y, z, world) ?: return@mutate null
        publishEvent(StandalonePlayerMoveEvent(previous, moved))
        publishEvent(GigaPlayerMoveEvent(previous.toHostSnapshot(), moved.toHostSnapshot()))
        publishEvent(GigaPlayerTeleportEvent(previous.toHostSnapshot(), moved.toHostSnapshot(), cause))
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
        ensureWorldExistsAndPublish(world, 0L)
        val entity = hostState.spawnEntity(type, world, x, y, z)
        publishEvent(StandaloneEntitySpawnEvent(entity))
        publishEvent(
            GigaEntitySpawnEvent(
                HostEntitySnapshot(
                    uuid = entity.uuid,
                    type = entity.type,
                    location = HostLocationRef(
                        world = entity.world,
                        x = entity.x,
                        y = entity.y,
                        z = entity.z
                    )
                )
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
        val previous = hostState.blockAt(world, x, y, z) ?: return@mutate false
        val removed = hostState.breakBlock(world, x, y, z) ?: return@mutate false
        publishEvent(
            GigaBlockChangeEvent(
                world = removed.world,
                x = removed.x,
                y = removed.y,
                z = removed.z,
                previousBlockId = previous.blockId,
                currentBlockId = null,
                cause = if (dropLoot) "$cause:drop" else cause
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
                val snapshot = statePersistence.load() ?: return@runCatching
                hostState.restore(snapshot)
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
            loadedPlugins = loadedPlugins,
            onlinePlayers = hostState.onlinePlayerCount(),
            worlds = hostState.worldCount(),
            entities = hostState.entityCount(),
            queuedMutations = commandQueue.size
        )
    }

    private fun tick() {
        val tickStarted = System.nanoTime()
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
            for ((systemId, system) in entry.systems) {
                val started = System.nanoTime()
                var success = true
                try {
                    system.onTick(entry.context)
                } catch (t: Throwable) {
                    success = false
                    tickFailure = true
                    logger.info("System ${entry.pluginId}:$systemId failed: ${t.message}")
                } finally {
                    runtime.recordSystemTick(
                        pluginId = entry.pluginId,
                        systemId = systemId,
                        durationNanos = System.nanoTime() - started,
                        success = success
                    )
                }
            }
        }
        tickSystemsNanos.addAndGet(System.nanoTime() - beforeSystems)
        val beforeQueue2 = System.nanoTime()
        drainCommandQueue()
        tickQueueDrainNanos.addAndGet(System.nanoTime() - beforeQueue2)

        val duration = System.nanoTime() - tickStarted
        lastTickDurationNanos.set(duration)
        tickTotalDurationNanos.addAndGet(duration)
        if (tickFailure) {
            tickFailureCounter.incrementAndGet()
        }
        if (config.autoSaveEveryTicks > 0L && tick % config.autoSaveEveryTicks == 0L) {
            saveState()
        }
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
            override fun worlds(): List<HostWorldSnapshot> = this@GigaStandaloneCore.worlds().map { it.toHostSnapshot() }
            override fun entities(world: String?): List<HostEntitySnapshot> = this@GigaStandaloneCore.entities(world).map { it.toHostSnapshot() }
            override fun spawnEntity(type: String, location: HostLocationRef): HostEntitySnapshot? {
                return this@GigaStandaloneCore.spawnEntity(type, location.world, location.x, location.y, location.z).toHostSnapshot()
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
        }
    }

    private fun installStandaloneBridgeAdapters(plugins: List<com.gigasoft.runtime.LoadedPlugin>) {
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
        tickPlugins = runtime.loadedPluginsView()
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

    private fun <T> mutate(block: () -> T): T {
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
            future.get(5, TimeUnit.SECONDS)
        } catch (_: TimeoutException) {
            throw IllegalStateException("Timed out waiting for core mutation execution")
        }
    }

    private fun ensureWorldExistsAndPublish(name: String, seed: Long): StandaloneWorld {
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

    private fun com.gigasoft.host.api.HostServerSnapshot.toApi(): com.gigasoft.api.HostServerSnapshot {
        return com.gigasoft.api.HostServerSnapshot(
            name = name,
            version = version,
            platformVersion = platformVersion,
            onlinePlayers = onlinePlayers,
            maxPlayers = maxPlayers,
            worldCount = worldCount
        )
    }

    private fun com.gigasoft.host.api.HostInventorySnapshot?.toApi(): com.gigasoft.api.HostInventorySnapshot? {
        val value = this ?: return null
        return com.gigasoft.api.HostInventorySnapshot(
            owner = value.owner,
            size = value.size,
            nonEmptySlots = value.nonEmptySlots
        )
    }

    private fun findPlayer(name: String): HostPlayerSnapshot? {
        return hostState.findPlayer(name)?.toHostSnapshot()
    }
}
