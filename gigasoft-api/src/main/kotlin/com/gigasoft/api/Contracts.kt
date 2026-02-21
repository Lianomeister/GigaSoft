package com.gigasoft.api

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
    val host: HostAccess
        get() = HostAccess.unavailable()
}

interface HostAccess {
    fun serverInfo(): HostServerSnapshot?
    fun broadcast(message: String): Boolean
    fun findPlayer(name: String): HostPlayerSnapshot?
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
    fun inventoryItem(name: String, slot: Int): String? = null
    fun givePlayerItem(name: String, itemId: String, count: Int = 1): Int = 0
    fun blockAt(world: String, x: Int, y: Int, z: Int): HostBlockSnapshot? = null
    fun setBlock(world: String, x: Int, y: Int, z: Int, blockId: String): HostBlockSnapshot? = null
    fun breakBlock(world: String, x: Int, y: Int, z: Int, dropLoot: Boolean = true): Boolean = false
    fun blockData(world: String, x: Int, y: Int, z: Int): Map<String, String>? = null
    fun setBlockData(world: String, x: Int, y: Int, z: Int, data: Map<String, String>): Map<String, String>? = null

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
    const val PLAYER_MOVE = "host.player.move"
    const val BLOCK_READ = "host.block.read"
    const val BLOCK_WRITE = "host.block.write"
    const val BLOCK_DATA_READ = "host.block.data.read"
    const val BLOCK_DATA_WRITE = "host.block.data.write"
}

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

data class GigaWorldCreatedEvent(
    val world: HostWorldSnapshot
)

data class GigaEntitySpawnEvent(
    val entity: HostEntitySnapshot
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

data class GigaBlockChangeEvent(
    val world: String,
    val x: Int,
    val y: Int,
    val z: Int,
    val previousBlockId: String?,
    val currentBlockId: String?,
    val cause: String = "plugin"
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
    fun registerSystem(id: String, system: TickSystem)

    fun items(): List<ItemDefinition>
    fun blocks(): List<BlockDefinition>
    fun recipes(): List<RecipeDefinition>
    fun machines(): List<MachineDefinition>
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
    fun register(
        command: String,
        description: String = "",
        action: (ctx: PluginContext, sender: String, args: List<String>) -> String
    )

    fun registerOrReplace(
        command: String,
        description: String = "",
        action: (ctx: PluginContext, sender: String, args: List<String>) -> String
    ) {
        register(command, description, action)
    }

    fun unregister(command: String): Boolean = false
}

data class CommandResult(
    val success: Boolean,
    val message: String,
    val code: String? = null
) {
    companion object {
        fun ok(message: String, code: String? = null): CommandResult {
            return CommandResult(success = true, message = message, code = code)
        }

        fun error(message: String, code: String? = null): CommandResult {
            return CommandResult(success = false, message = message, code = code)
        }
    }
}

interface EventBus {
    fun <T : Any> subscribe(eventType: Class<T>, listener: (T) -> Unit)
    fun publish(event: Any)
}

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
