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
    const val ENTITY_READ = "host.entity.read"
    const val ENTITY_SPAWN = "host.entity.spawn"
    const val INVENTORY_READ = "host.inventory.read"
    const val INVENTORY_WRITE = "host.inventory.write"
    const val PLAYER_READ = "host.player.read"
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

data class GigaInventoryChangeEvent(
    val owner: String,
    val slot: Int,
    val itemId: String
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
