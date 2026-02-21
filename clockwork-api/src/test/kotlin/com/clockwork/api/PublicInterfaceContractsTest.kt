package com.clockwork.api

import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PublicInterfaceContractsTest {
    @Test
    fun `unavailable implementations provide safe defaults`() {
        val net = PluginNetwork.unavailable()
        assertFalse(net.registerChannel(PluginChannelSpec("demo")))
        assertTrue(net.listChannels().isEmpty())
        assertEquals(PluginMessageStatus.DENIED, net.send("demo", PluginMessage("demo")).status)

        val ui = PluginUi.unavailable()
        assertFalse(ui.notify("Alex", UiNotice(message = "hello")))
        assertFalse(ui.actionBar("Alex", "x"))
        assertFalse(ui.openMenu("Alex", UiMenu("m", "Menu", emptyList())))
        assertFalse(ui.openDialog("Alex", UiDialog("d", "Dialog", emptyList())))
        assertFalse(ui.close("Alex"))

        val host = HostAccess.unavailable()
        assertNull(host.lookupPlayer("Alex"))
        assertFalse(host.isPlayerOp("Alex"))
        assertTrue(host.permissionsOfPlayer("Alex").isEmpty())
        assertFalse(host.playerHasPermission("Alex", "demo"))
        assertTrue(host.listWorlds().isEmpty())
        assertTrue(host.listEntities().isEmpty())
        assertTrue(host.worldDataOrEmpty("world").isEmpty())
        assertTrue(host.entityDataOrEmpty("entity").isEmpty())
    }

    @Test
    fun `event bus default contracts are deterministic`() {
        val bus = object : EventBus {
            override fun <T : Any> subscribe(eventType: Class<T>, listener: (T) -> Unit) {}
            override fun publish(event: Any) {}
        }
        assertFalse(bus.setTracingEnabled(true))
        assertEquals(0L, bus.eventTraceSnapshot().totalEvents)
        assertEquals(Unit, bus.publishAsync("event").get())
    }

    @Test
    fun `command registry default contracts are stable`() {
        val registry = object : CommandRegistry {
            override fun registerSpec(
                spec: CommandSpec,
                middleware: List<CommandMiddlewareBinding>,
                completion: CommandCompletionContract?,
                completionAsync: CommandCompletionAsyncContract?,
                policy: CommandPolicyProfile?,
                action: (CommandInvocationContext) -> CommandResult
            ) {}
        }
        assertFalse(registry.unregister("demo"))
        assertFalse(registry.registerAlias("d", "demo"))
        assertFalse(registry.unregisterAlias("d"))
        assertNull(registry.resolve("d"))
        assertTrue(registry.registeredCommands().isEmpty())
        assertNull(registry.commandTelemetry("demo"))
        assertTrue(registry.commandTelemetry().isEmpty())
    }

    @Test
    fun `registry facade default asset contracts are stable`() {
        val facade = object : RegistryFacade {
            override fun registerItem(definition: ItemDefinition) {}
            override fun registerBlock(definition: BlockDefinition) {}
            override fun registerRecipe(definition: RecipeDefinition) {}
            override fun registerMachine(definition: MachineDefinition) {}
            override fun registerTexture(definition: TextureDefinition) {}
            override fun registerModel(definition: ModelDefinition) {}
            override fun registerSystem(id: String, system: TickSystem) {}
            override fun items(): List<ItemDefinition> = emptyList()
            override fun blocks(): List<BlockDefinition> = emptyList()
            override fun recipes(): List<RecipeDefinition> = emptyList()
            override fun machines(): List<MachineDefinition> = emptyList()
            override fun textures(): List<TextureDefinition> = emptyList()
            override fun models(): List<ModelDefinition> = emptyList()
            override fun systems(): Map<String, TickSystem> = emptyMap()
        }

        val validation = facade.validateAssets()
        assertTrue(validation.valid)
        val bundle = facade.buildResourcePackBundle()
        assertEquals("unknown", bundle.pluginId)
    }

    @Test
    fun `functional interfaces are invokable`() {
        val logger: GigaLogger = GigaLogger { }
        logger.info("ok")

        val system: TickSystem = TickSystem { }
        val ctx = object : PluginContext {
            override val manifest: PluginManifest = PluginManifest("t", "t", "1", "main")
            override val logger: GigaLogger = GigaLogger { }
            override val scheduler: Scheduler = object : Scheduler {
                override fun repeating(taskId: String, periodTicks: Int, block: () -> Unit) {}
                override fun once(taskId: String, delayTicks: Int, block: () -> Unit) {}
                override fun cancel(taskId: String) {}
                override fun clear() {}
            }
            override val registry: RegistryFacade = object : RegistryFacade {
                override fun registerItem(definition: ItemDefinition) {}
                override fun registerBlock(definition: BlockDefinition) {}
                override fun registerRecipe(definition: RecipeDefinition) {}
                override fun registerMachine(definition: MachineDefinition) {}
                override fun registerTexture(definition: TextureDefinition) {}
                override fun registerModel(definition: ModelDefinition) {}
                override fun registerSystem(id: String, system: TickSystem) {}
                override fun items(): List<ItemDefinition> = emptyList()
                override fun blocks(): List<BlockDefinition> = emptyList()
                override fun recipes(): List<RecipeDefinition> = emptyList()
                override fun machines(): List<MachineDefinition> = emptyList()
                override fun textures(): List<TextureDefinition> = emptyList()
                override fun models(): List<ModelDefinition> = emptyList()
                override fun systems(): Map<String, TickSystem> = emptyMap()
            }
            override val adapters: ModAdapterRegistry = object : ModAdapterRegistry {
                override fun register(adapter: ModAdapter) {}
                override fun list(): List<ModAdapter> = emptyList()
                override fun find(id: String): ModAdapter? = null
                override fun invoke(adapterId: String, invocation: AdapterInvocation): AdapterResponse {
                    return AdapterResponse(success = false)
                }
            }
            override val storage: StorageProvider = object : StorageProvider {
                override fun <T : Any> store(key: String, type: Class<T>, version: Int): PersistentStore<T> {
                    return object : PersistentStore<T> {
                        override fun load(): T? = null
                        override fun save(value: T) {}
                        override fun migrate(fromVersion: Int, migration: (T) -> T) {}
                    }
                }
            }
            override val commands: CommandRegistry = object : CommandRegistry {
                override fun registerSpec(
                    spec: CommandSpec,
                    middleware: List<CommandMiddlewareBinding>,
                    completion: CommandCompletionContract?,
                    completionAsync: CommandCompletionAsyncContract?,
                    policy: CommandPolicyProfile?,
                    action: (CommandInvocationContext) -> CommandResult
                ) {}
            }
            override val events: EventBus = object : EventBus {
                override fun <T : Any> subscribe(eventType: Class<T>, listener: (T) -> Unit) {}
                override fun publish(event: Any) {}
                override fun publishAsync(event: Any): CompletableFuture<Unit> = CompletableFuture.completedFuture(Unit)
            }
        }
        system.onTick(ctx)
    }
}

