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
}

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
