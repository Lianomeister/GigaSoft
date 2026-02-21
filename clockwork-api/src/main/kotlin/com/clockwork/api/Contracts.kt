package com.clockwork.api

interface GigaPlugin {
    fun onEnable(ctx: PluginContext)
    fun onDisable(ctx: PluginContext)
    fun onReload(ctx: PluginContext) = onDisable(ctx).also { onEnable(ctx) }
}

interface PluginContext {
    val manifest: PluginManifest
    val logger: GigaLogger
    val scheduler: Scheduler
    val registry: RegistryFacade
    val adapters: ModAdapterRegistry
    val storage: StorageProvider
    val commands: CommandRegistry
    val events: EventBus
    val network: PluginNetwork
        get() = PluginNetwork.unavailable()
    val ui: PluginUi
        get() = PluginUi.unavailable()
    val host: HostAccess
        get() = HostAccess.unavailable()
}

data class PluginChannelSpec(
    val id: String,
    val schemaVersion: Int = 1,
    val maxInFlight: Int = 64,
    val maxMessagesPerMinute: Int = 600,
    val maxPayloadEntries: Int = 64,
    val maxPayloadTotalChars: Int = 8192
)

data class PluginMessage(
    val channel: String,
    val schemaVersion: Int = 1,
    val payload: Map<String, String> = emptyMap(),
    val traceId: String? = null,
    val sourcePluginId: String? = null
)

enum class PluginMessageStatus {
    ACCEPTED,
    CHANNEL_NOT_FOUND,
    SCHEMA_MISMATCH,
    PAYLOAD_INVALID,
    BACKPRESSURE,
    QUOTA_EXCEEDED,
    DENIED
}

data class PluginMessageResult(
    val status: PluginMessageStatus,
    val deliveredSubscribers: Int = 0,
    val reason: String? = null
)

data class PluginChannelStats(
    val channelId: String,
    val schemaVersion: Int,
    val inFlight: Int,
    val accepted: Long,
    val rejected: Long,
    val droppedBackpressure: Long,
    val droppedQuota: Long
)

interface PluginNetwork {
    fun registerChannel(spec: PluginChannelSpec): Boolean
    fun listChannels(): List<PluginChannelSpec>
    fun subscribe(channel: String, listener: (PluginMessage) -> Unit)
    fun unsubscribe(channel: String, listener: (PluginMessage) -> Unit): Boolean = false
    fun send(channel: String, message: PluginMessage): PluginMessageResult
    fun channelStats(channel: String): PluginChannelStats? = null

    companion object {
        fun unavailable(): PluginNetwork = object : PluginNetwork {
            override fun registerChannel(spec: PluginChannelSpec): Boolean = false
            override fun listChannels(): List<PluginChannelSpec> = emptyList()
            override fun subscribe(channel: String, listener: (PluginMessage) -> Unit) {}
            override fun send(channel: String, message: PluginMessage): PluginMessageResult {
                return PluginMessageResult(
                    status = PluginMessageStatus.DENIED,
                    reason = "Plugin network is unavailable"
                )
            }
        }
    }
}

enum class UiLevel {
    INFO,
    SUCCESS,
    WARNING,
    ERROR
}

data class UiNotice(
    val title: String = "",
    val message: String,
    val level: UiLevel = UiLevel.INFO,
    val durationMillis: Long = 3_000L
)

data class UiMenuItem(
    val id: String,
    val label: String,
    val description: String = "",
    val enabled: Boolean = true
)

data class UiMenu(
    val id: String,
    val title: String,
    val items: List<UiMenuItem>
)

enum class UiDialogFieldType {
    TEXT,
    NUMBER,
    TOGGLE,
    SELECT
}

data class UiDialogField(
    val id: String,
    val label: String,
    val type: UiDialogFieldType = UiDialogFieldType.TEXT,
    val required: Boolean = true,
    val options: List<String> = emptyList(),
    val placeholder: String = ""
)

data class UiDialog(
    val id: String,
    val title: String,
    val fields: List<UiDialogField>
)

interface PluginUi {
    fun notify(player: String, notice: UiNotice): Boolean
    fun actionBar(player: String, message: String, durationTicks: Int = 40): Boolean = false
    fun openMenu(player: String, menu: UiMenu): Boolean = false
    fun openDialog(player: String, dialog: UiDialog): Boolean = false
    fun close(player: String): Boolean = false

    companion object {
        fun unavailable(): PluginUi = object : PluginUi {
            override fun notify(player: String, notice: UiNotice): Boolean = false
        }
    }
}

interface HostAccess {
    fun serverInfo(): HostServerSnapshot?
    fun broadcast(message: String): Boolean
    fun findPlayer(name: String): HostPlayerSnapshot?
    fun sendPlayerMessage(name: String, message: String): Boolean = false
    fun kickPlayer(name: String, reason: String = "Kicked by host"): Boolean = false
    fun playerIsOp(name: String): Boolean? = null
    fun setPlayerOp(name: String, op: Boolean): Boolean = false
    fun playerPermissions(name: String): Set<String>? = null
    fun hasPlayerPermission(name: String, permission: String): Boolean? = null
    fun grantPlayerPermission(name: String, permission: String): Boolean = false
    fun revokePlayerPermission(name: String, permission: String): Boolean = false
    fun worlds(): List<HostWorldSnapshot>
    fun entities(world: String? = null): List<HostEntitySnapshot>
    fun spawnEntity(type: String, location: HostLocationRef): HostEntitySnapshot?
    fun playerInventory(name: String): HostInventorySnapshot?
    fun setPlayerInventoryItem(name: String, slot: Int, itemId: String): Boolean
    fun createWorld(name: String, seed: Long = 0L): HostWorldSnapshot? = null
    fun worldTime(name: String): Long? = null
    fun setWorldTime(name: String, time: Long): Boolean = false
    fun worldData(name: String): Map<String, String>? = null
    fun setWorldData(name: String, data: Map<String, String>): Map<String, String>? = null
    fun worldWeather(name: String): String? = null
    fun setWorldWeather(name: String, weather: String): Boolean = false
    fun findEntity(uuid: String): HostEntitySnapshot? = null
    fun removeEntity(uuid: String): Boolean = false
    fun entityData(uuid: String): Map<String, String>? = null
    fun setEntityData(uuid: String, data: Map<String, String>): Map<String, String>? = null
    fun movePlayer(name: String, location: HostLocationRef): HostPlayerSnapshot? = null
    fun playerGameMode(name: String): String? = null
    fun setPlayerGameMode(name: String, gameMode: String): Boolean = false
    fun playerStatus(name: String): HostPlayerStatusSnapshot? = null
    fun setPlayerStatus(name: String, status: HostPlayerStatusSnapshot): HostPlayerStatusSnapshot? = null
    fun addPlayerEffect(name: String, effectId: String, durationTicks: Int, amplifier: Int = 0): Boolean = false
    fun removePlayerEffect(name: String, effectId: String): Boolean = false
    fun inventoryItem(name: String, slot: Int): String? = null
    fun givePlayerItem(name: String, itemId: String, count: Int = 1): Int = 0
    fun blockAt(world: String, x: Int, y: Int, z: Int): HostBlockSnapshot? = null
    fun setBlock(world: String, x: Int, y: Int, z: Int, blockId: String): HostBlockSnapshot? = null
    fun breakBlock(world: String, x: Int, y: Int, z: Int, dropLoot: Boolean = true): Boolean = false
    fun blockData(world: String, x: Int, y: Int, z: Int): Map<String, String>? = null
    fun setBlockData(world: String, x: Int, y: Int, z: Int, data: Map<String, String>): Map<String, String>? = null
    fun applyMutationBatch(batch: HostMutationBatch): HostMutationBatchResult {
        return HostMutationBatchResult(
            batchId = batch.id,
            success = false,
            appliedOperations = 0,
            rolledBack = false,
            error = "Host mutation batch is not supported"
        )
    }

    companion object {
        fun unavailable(): HostAccess = object : HostAccess {
            override fun serverInfo(): HostServerSnapshot? = null
            override fun broadcast(message: String): Boolean = false
            override fun findPlayer(name: String): HostPlayerSnapshot? = null
            override fun worlds(): List<HostWorldSnapshot> = emptyList()
            override fun entities(world: String?): List<HostEntitySnapshot> = emptyList()
            override fun spawnEntity(type: String, location: HostLocationRef): HostEntitySnapshot? = null
            override fun playerInventory(name: String): HostInventorySnapshot? = null
            override fun setPlayerInventoryItem(name: String, slot: Int, itemId: String): Boolean = false
        }
    }
}

object HostPermissions {
    const val SERVER_READ = "host.server.read"
    const val SERVER_BROADCAST = "host.server.broadcast"
    const val WORLD_READ = "host.world.read"
    const val WORLD_WRITE = "host.world.write"
    const val WORLD_DATA_READ = "host.world.data.read"
    const val WORLD_DATA_WRITE = "host.world.data.write"
    const val WORLD_WEATHER_READ = "host.world.weather.read"
    const val WORLD_WEATHER_WRITE = "host.world.weather.write"
    const val ENTITY_READ = "host.entity.read"
    const val ENTITY_SPAWN = "host.entity.spawn"
    const val ENTITY_REMOVE = "host.entity.remove"
    const val ENTITY_DATA_READ = "host.entity.data.read"
    const val ENTITY_DATA_WRITE = "host.entity.data.write"
    const val INVENTORY_READ = "host.inventory.read"
    const val INVENTORY_WRITE = "host.inventory.write"
    const val PLAYER_READ = "host.player.read"
    const val PLAYER_MESSAGE = "host.player.message"
    const val PLAYER_KICK = "host.player.kick"
    const val PLAYER_OP_READ = "host.player.op.read"
    const val PLAYER_OP_WRITE = "host.player.op.write"
    const val PLAYER_PERMISSION_READ = "host.player.permission.read"
    const val PLAYER_PERMISSION_WRITE = "host.player.permission.write"
    const val PLAYER_MOVE = "host.player.move"
    const val PLAYER_GAMEMODE_READ = "host.player.gamemode.read"
    const val PLAYER_GAMEMODE_WRITE = "host.player.gamemode.write"
    const val PLAYER_STATUS_READ = "host.player.status.read"
    const val PLAYER_STATUS_WRITE = "host.player.status.write"
    const val PLAYER_EFFECT_WRITE = "host.player.effect.write"
    const val BLOCK_READ = "host.block.read"
    const val BLOCK_WRITE = "host.block.write"
    const val BLOCK_DATA_READ = "host.block.data.read"
    const val BLOCK_DATA_WRITE = "host.block.data.write"
    const val MUTATION_BATCH = "host.mutation.batch"
}

enum class HostMutationType {
    CREATE_WORLD,
    SET_WORLD_TIME,
    SET_WORLD_DATA,
    SET_WORLD_WEATHER,
    SPAWN_ENTITY,
    REMOVE_ENTITY,
    SET_PLAYER_INVENTORY_ITEM,
    GIVE_PLAYER_ITEM,
    MOVE_PLAYER,
    SET_PLAYER_GAMEMODE,
    ADD_PLAYER_EFFECT,
    REMOVE_PLAYER_EFFECT,
    SET_BLOCK,
    BREAK_BLOCK,
    SET_BLOCK_DATA
}

data class HostMutationOp(
    val type: HostMutationType,
    val target: String = "",
    val world: String? = null,
    val x: Double? = null,
    val y: Double? = null,
    val z: Double? = null,
    val intValue: Int? = null,
    val longValue: Long? = null,
    val boolValue: Boolean? = null,
    val stringValue: String? = null,
    val data: Map<String, String> = emptyMap()
)

data class HostMutationBatch(
    val id: String,
    val operations: List<HostMutationOp>,
    val rollbackReason: String = "rollback"
)

data class HostMutationBatchResult(
    val batchId: String,
    val success: Boolean,
    val appliedOperations: Int,
    val rolledBack: Boolean,
    val error: String? = null
)

data class HostLocationRef(
    val world: String,
    val x: Double,
    val y: Double,
    val z: Double
)

data class HostPlayerSnapshot(
    val uuid: String,
    val name: String,
    val location: HostLocationRef
)

data class HostPlayerStatusSnapshot(
    val health: Double,
    val maxHealth: Double,
    val foodLevel: Int,
    val saturation: Double,
    val experienceLevel: Int,
    val experienceProgress: Double,
    val effects: Map<String, Int> = emptyMap()
)

data class HostWorldSnapshot(
    val name: String,
    val entityCount: Int
)

data class HostEntitySnapshot(
    val uuid: String,
    val type: String,
    val location: HostLocationRef
)

data class HostInventorySnapshot(
    val owner: String,
    val size: Int,
    val nonEmptySlots: Int
)

data class HostBlockSnapshot(
    val world: String,
    val x: Int,
    val y: Int,
    val z: Int,
    val blockId: String
)

data class HostServerSnapshot(
    val name: String,
    val version: String,
    val platformVersion: String? = null,
    val onlinePlayers: Int,
    val maxPlayers: Int,
    val worldCount: Int
)

data class GigaTickEvent(
    val tick: Long
)

data class GigaPlayerJoinEvent(
    val player: HostPlayerSnapshot
)

data class GigaPlayerLeaveEvent(
    val player: HostPlayerSnapshot
)

data class GigaPlayerMoveEvent(
    val previous: HostPlayerSnapshot,
    val current: HostPlayerSnapshot
)

class GigaPlayerMovePreEvent(
    val player: HostPlayerSnapshot,
    var targetWorld: String,
    var targetX: Double,
    var targetY: Double,
    var targetZ: Double,
    var cause: String = "plugin"
) {
    var cancelled: Boolean = false
    var cancelReason: String? = null
}

data class GigaPlayerMovePostEvent(
    val player: HostPlayerSnapshot,
    val previous: HostPlayerSnapshot,
    val current: HostPlayerSnapshot?,
    val targetWorld: String,
    val targetX: Double,
    val targetY: Double,
    val targetZ: Double,
    val cause: String,
    val success: Boolean,
    val cancelled: Boolean,
    val durationNanos: Long,
    val error: String? = null
)

data class GigaWorldCreatedEvent(
    val world: HostWorldSnapshot
)

data class GigaEntitySpawnEvent(
    val entity: HostEntitySnapshot
)

class GigaEntitySpawnPreEvent(
    var entityType: String,
    var world: String,
    var x: Double,
    var y: Double,
    var z: Double,
    var cause: String = "plugin"
) {
    var cancelled: Boolean = false
    var cancelReason: String? = null
}

data class GigaEntitySpawnPostEvent(
    val entityType: String,
    val world: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val cause: String,
    val entity: HostEntitySnapshot?,
    val success: Boolean,
    val cancelled: Boolean,
    val durationNanos: Long,
    val error: String? = null
)

data class GigaEntityRemoveEvent(
    val entity: HostEntitySnapshot,
    val reason: String = "plugin"
)

data class GigaInventoryChangeEvent(
    val owner: String,
    val slot: Int,
    val itemId: String
)

data class GigaPlayerTeleportEvent(
    val previous: HostPlayerSnapshot,
    val current: HostPlayerSnapshot,
    val cause: String = "plugin"
)

data class GigaPlayerGameModeChangeEvent(
    val player: HostPlayerSnapshot,
    val previousGameMode: String,
    val currentGameMode: String,
    val cause: String = "plugin"
)

data class GigaPlayerMessageEvent(
    val player: HostPlayerSnapshot,
    val message: String,
    val cause: String = "plugin"
)

data class GigaUiNoticeEvent(
    val player: HostPlayerSnapshot?,
    val title: String,
    val message: String,
    val level: UiLevel,
    val durationMillis: Long
)

data class GigaUiActionBarEvent(
    val player: HostPlayerSnapshot?,
    val message: String,
    val durationTicks: Int
)

data class GigaUiMenuOpenEvent(
    val player: HostPlayerSnapshot?,
    val menuId: String,
    val title: String,
    val itemCount: Int
)

data class GigaUiDialogOpenEvent(
    val player: HostPlayerSnapshot?,
    val dialogId: String,
    val title: String,
    val fieldCount: Int
)

data class GigaPlayerKickEvent(
    val player: HostPlayerSnapshot,
    val reason: String,
    val cause: String = "plugin"
)

data class GigaPlayerOpChangeEvent(
    val player: HostPlayerSnapshot,
    val previousOp: Boolean,
    val currentOp: Boolean,
    val cause: String = "plugin"
)

data class GigaPlayerPermissionChangeEvent(
    val player: HostPlayerSnapshot,
    val permission: String,
    val granted: Boolean,
    val cause: String = "plugin"
)

data class GigaWorldTimeChangeEvent(
    val world: String,
    val previousTime: Long,
    val currentTime: Long
)

data class GigaWorldDataChangeEvent(
    val world: String,
    val previousData: Map<String, String>,
    val currentData: Map<String, String>,
    val cause: String = "plugin"
)

data class GigaWorldWeatherChangeEvent(
    val world: String,
    val previousWeather: String,
    val currentWeather: String,
    val cause: String = "plugin"
)

data class GigaPlayerStatusChangeEvent(
    val player: HostPlayerSnapshot,
    val previous: HostPlayerStatusSnapshot,
    val current: HostPlayerStatusSnapshot,
    val cause: String = "plugin"
)

data class GigaPlayerEffectChangeEvent(
    val player: HostPlayerSnapshot,
    val effectId: String,
    val previousDurationTicks: Int?,
    val currentDurationTicks: Int?,
    val cause: String = "plugin"
)

data class GigaBlockChangeEvent(
    val world: String,
    val x: Int,
    val y: Int,
    val z: Int,
    val previousBlockId: String?,
    val currentBlockId: String?,
    val cause: String = "plugin"
)

class GigaBlockBreakPreEvent(
    var world: String,
    var x: Int,
    var y: Int,
    var z: Int,
    var dropLoot: Boolean = true,
    var cause: String = "plugin"
) {
    var cancelled: Boolean = false
    var cancelReason: String? = null
}

data class GigaBlockBreakPostEvent(
    val world: String,
    val x: Int,
    val y: Int,
    val z: Int,
    val dropLoot: Boolean,
    val cause: String,
    val previousBlockId: String?,
    val success: Boolean,
    val cancelled: Boolean,
    val durationNanos: Long,
    val error: String? = null
)

data class GigaBlockDataChangeEvent(
    val world: String,
    val x: Int,
    val y: Int,
    val z: Int,
    val previousData: Map<String, String>,
    val currentData: Map<String, String>,
    val cause: String = "plugin"
)

data class GigaEntityDataChangeEvent(
    val entity: HostEntitySnapshot,
    val previousData: Map<String, String>,
    val currentData: Map<String, String>,
    val cause: String = "plugin"
)

data class GigaTextureRegisteredEvent(
    val texture: TextureDefinition
)

data class GigaModelRegisteredEvent(
    val model: ModelDefinition
)

data class GigaAnimationRegisteredEvent(
    val animation: AnimationDefinition
)

data class GigaSoundRegisteredEvent(
    val sound: SoundDefinition
)

data class GigaResourcePackBundleEvent(
    val bundle: ResourcePackBundle,
    val validation: AssetValidationResult
)

class GigaCommandPreExecuteEvent(
    val pluginId: String,
    val command: String,
    val sender: CommandSender,
    val args: List<String>,
    val rawCommandLine: String
) {
    var cancelled: Boolean = false
    var cancelReason: String? = null
    var overrideResponse: CommandResult? = null
}

data class GigaCommandPostExecuteEvent(
    val pluginId: String,
    val command: String,
    val sender: CommandSender,
    val args: List<String>,
    val rawCommandLine: String,
    val response: CommandResult,
    val success: Boolean,
    val durationNanos: Long,
    val error: CommandError? = null
)

class GigaAdapterPreInvokeEvent(
    val pluginId: String,
    val adapterId: String,
    val action: String,
    val payload: Map<String, String>
) {
    var cancelled: Boolean = false
    var cancelReason: String? = null
    var overrideResponse: AdapterResponse? = null
}

data class GigaAdapterPostInvokeEvent(
    val pluginId: String,
    val adapterId: String,
    val action: String,
    val payload: Map<String, String>,
    val response: AdapterResponse,
    val outcome: String,
    val durationNanos: Long
)

fun interface GigaLogger {
    fun info(message: String)
}

interface Scheduler {
    fun repeating(taskId: String, periodTicks: Int, block: () -> Unit)
    fun once(taskId: String, delayTicks: Int, block: () -> Unit)
    fun cancel(taskId: String)
    fun clear()
}

interface RegistryFacade {
    fun registerItem(definition: ItemDefinition)
    fun registerBlock(definition: BlockDefinition)
    fun registerRecipe(definition: RecipeDefinition)
    fun registerMachine(definition: MachineDefinition)
    fun registerTexture(definition: TextureDefinition)
    fun registerModel(definition: ModelDefinition)
    fun registerAnimation(definition: AnimationDefinition) {
        throw UnsupportedOperationException("Animations are not supported by this runtime")
    }
    fun registerSound(definition: SoundDefinition) {
        throw UnsupportedOperationException("Sounds are not supported by this runtime")
    }
    fun registerSystem(id: String, system: TickSystem)

    fun items(): List<ItemDefinition>
    fun blocks(): List<BlockDefinition>
    fun recipes(): List<RecipeDefinition>
    fun machines(): List<MachineDefinition>
    fun textures(): List<TextureDefinition>
    fun models(): List<ModelDefinition>
    fun animations(): List<AnimationDefinition> = emptyList()
    fun sounds(): List<SoundDefinition> = emptyList()
    fun validateAssets(options: ResourcePackBundleOptions = ResourcePackBundleOptions()): AssetValidationResult {
        return AssetValidationResult(valid = true)
    }
    fun buildResourcePackBundle(options: ResourcePackBundleOptions = ResourcePackBundleOptions()): ResourcePackBundle {
        return ResourcePackBundle(
            pluginId = "unknown",
            textures = textures(),
            models = models(),
            animations = animations(),
            sounds = sounds()
        )
    }
    fun systems(): Map<String, TickSystem>
}

fun interface TickSystem {
    fun onTick(ctx: PluginContext)
}

interface MachineBehavior {
    fun onTick(state: MachineState, ctx: PluginContext)
    fun onInteract(state: MachineState, interaction: InteractionContext, ctx: PluginContext) {}
    fun onStateChange(previous: MachineState, next: MachineState, ctx: PluginContext) {}
}

interface StorageProvider {
    fun <T : Any> store(key: String, type: Class<T>, version: Int = 1): PersistentStore<T>
}

interface PersistentStore<T : Any> {
    fun load(): T?
    fun save(value: T)
    fun migrate(fromVersion: Int, migration: (T) -> T)
}

interface CommandRegistry {
    fun registerSpec(
        spec: CommandSpec,
        middleware: List<CommandMiddlewareBinding> = emptyList(),
        completion: CommandCompletionContract? = null,
        completionAsync: CommandCompletionAsyncContract? = null,
        policy: CommandPolicyProfile? = null,
        action: (CommandInvocationContext) -> CommandResult
    )

    fun registerOrReplaceSpec(
        spec: CommandSpec,
        middleware: List<CommandMiddlewareBinding> = emptyList(),
        completion: CommandCompletionContract? = null,
        completionAsync: CommandCompletionAsyncContract? = null,
        policy: CommandPolicyProfile? = null,
        action: (CommandInvocationContext) -> CommandResult
    ) {
        registerSpec(spec, middleware, completion, completionAsync, policy, action)
    }

    fun unregister(command: String): Boolean = false
    fun registerAlias(alias: String, command: String): Boolean = false
    fun unregisterAlias(alias: String): Boolean = false
    fun resolve(commandOrAlias: String): String? = null
    fun registeredCommands(): Map<String, CommandSpec> = emptyMap()
    fun commandTelemetry(commandOrAlias: String): CommandTelemetrySnapshot? = null
    fun commandTelemetry(): Map<String, CommandTelemetrySnapshot> = emptyMap()
}

data class CommandResult(
    val success: Boolean,
    val message: String,
    val code: String? = null,
    val error: CommandError? = null
) {
    companion object {
        fun ok(message: String, code: String? = null): CommandResult {
            return CommandResult(success = true, message = message, code = code, error = null)
        }

        fun error(
            message: String,
            code: String? = null,
            field: String? = null,
            hint: String? = null
        ): CommandResult {
            val normalizedCode = code?.trim()?.takeIf { it.isNotEmpty() } ?: "E_COMMAND"
            return CommandResult(
                success = false,
                message = message,
                code = normalizedCode,
                error = CommandError(
                    code = normalizedCode,
                    field = field?.trim()?.takeIf { it.isNotEmpty() },
                    hint = hint?.trim()?.takeIf { it.isNotEmpty() }
                )
            )
        }
    }
}

interface EventBus {
    fun <T : Any> subscribe(eventType: Class<T>, listener: (T) -> Unit)
    fun <T : Any> subscribe(
        eventType: Class<T>,
        options: EventSubscriptionOptions,
        listener: (T) -> Unit
    ) {
        subscribe(eventType, listener)
    }
    fun <T : Any> unsubscribe(eventType: Class<T>, listener: (T) -> Unit): Boolean = false
    fun publish(event: Any)
    fun publishAsync(event: Any): java.util.concurrent.CompletableFuture<Unit> {
        publish(event)
        return java.util.concurrent.CompletableFuture.completedFuture(Unit)
    }
    fun setTracingEnabled(enabled: Boolean): Boolean = false
    fun eventTraceSnapshot(): EventTraceSnapshot = EventTraceSnapshot()
    fun resetEventTrace() {}
}

enum class EventPriority {
    HIGHEST,
    HIGH,
    NORMAL,
    LOW,
    LOWEST
}

data class EventSubscriptionOptions(
    val priority: EventPriority = EventPriority.NORMAL,
    val ignoreCancelled: Boolean = false,
    val mainThreadOnly: Boolean = false
)

data class EventTypeTraceSnapshot(
    val eventType: String,
    val events: Long,
    val listenerCalls: Long,
    val errors: Long,
    val averageNanos: Long,
    val maxNanos: Long,
    val lastDurationNanos: Long,
    val lastThread: String? = null
)

data class EventTraceSnapshot(
    val enabled: Boolean = false,
    val totalEvents: Long = 0L,
    val totalListenerCalls: Long = 0L,
    val totalErrors: Long = 0L,
    val eventTypes: List<EventTypeTraceSnapshot> = emptyList()
)

data class AdapterInvocation(
    val action: String,
    val payload: Map<String, String> = emptyMap()
)

data class AdapterResponse(
    val success: Boolean,
    val payload: Map<String, String> = emptyMap(),
    val message: String? = null
)

interface ModAdapter {
    val id: String
    val name: String
    val version: String
    val capabilities: Set<String>

    fun invoke(invocation: AdapterInvocation): AdapterResponse
}

interface ModAdapterRegistry {
    fun register(adapter: ModAdapter)
    fun list(): List<ModAdapter>
    fun find(id: String): ModAdapter?
    fun invoke(adapterId: String, invocation: AdapterInvocation): AdapterResponse
}
