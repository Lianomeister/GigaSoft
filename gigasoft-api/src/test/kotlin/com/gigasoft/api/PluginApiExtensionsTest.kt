package com.gigasoft.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PluginApiExtensionsTest {
    @Test
    fun `event bus reified subscribe dispatches typed event`() {
        val bus = RuntimeLikeEventBus()
        var calls = 0
        bus.subscribe<GigaTickEvent> { calls += it.tick.toInt() }

        bus.publish(GigaTickEvent(2))
        bus.publish(GigaTickEvent(3))

        assertEquals(5, calls)
    }

    @Test
    fun `storage reified store resolves class automatically`() {
        val storage = RecordingStorageProvider()
        val store = storage.store<TestPayload>("plugin:data", version = 2)
        store.save(TestPayload("ok"))

        assertEquals("plugin:data", storage.lastKey)
        assertEquals(TestPayload::class.java, storage.lastType)
        assertEquals(2, storage.lastVersion)
        assertEquals(TestPayload("ok"), store.load())
    }

    @Test
    fun `command helpers adapt simplified action signature`() {
        val commands = RecordingCommandRegistry()
        commands.register("hello") { sender, args -> "$sender:${args.joinToString(",")}" }
        commands.registerOrReplace("hello") { sender, _ -> "$sender:replaced" }

        val response = commands.invoke("hello", sender = "alice", args = listOf("x"))
        assertEquals("alice:replaced", response)
    }

    @Test
    fun `permission helpers evaluate declared permissions`() {
        val ctx = contextWithPermissions(listOf(HostPermissions.SERVER_READ, "x.y.z"))
        assertTrue(ctx.hasPermission("host.server.read"))
        assertTrue(ctx.hasPermission("HOST.SERVER.READ"))
        assertFalse(ctx.hasPermission("host.world.read"))
        assertFailsWith<IllegalArgumentException> { ctx.requirePermission("host.world.read") }
        ctx.requirePermission("x.y.z")
    }

    private data class TestPayload(val value: String)

    private class RuntimeLikeEventBus : EventBus {
        private val listeners = linkedMapOf<Class<*>, MutableList<(Any) -> Unit>>()

        @Suppress("UNCHECKED_CAST")
        override fun <T : Any> subscribe(eventType: Class<T>, listener: (T) -> Unit) {
            val bucket = listeners.getOrPut(eventType) { mutableListOf() }
            bucket += { event -> listener(event as T) }
        }

        override fun publish(event: Any) {
            listeners[event::class.java]?.forEach { it(event) }
        }
    }

    private class RecordingStorageProvider : StorageProvider {
        var lastKey: String? = null
        var lastType: Class<*>? = null
        var lastVersion: Int? = null
        private val stores = linkedMapOf<String, Any>()

        @Suppress("UNCHECKED_CAST")
        override fun <T : Any> store(key: String, type: Class<T>, version: Int): PersistentStore<T> {
            lastKey = key
            lastType = type
            lastVersion = version
            return object : PersistentStore<T> {
                override fun load(): T? = stores[key] as? T
                override fun save(value: T) {
                    stores[key] = value
                }
                override fun migrate(fromVersion: Int, migration: (T) -> T) {
                    val current = load() ?: return
                    save(migration(current))
                }
            }
        }
    }

    private class RecordingCommandRegistry : CommandRegistry {
        private val handlers = linkedMapOf<String, (PluginContext, String, List<String>) -> String>()

        override fun register(
            command: String,
            description: String,
            action: (ctx: PluginContext, sender: String, args: List<String>) -> String
        ) {
            handlers.putIfAbsent(command, action)
        }

        override fun registerOrReplace(
            command: String,
            description: String,
            action: (ctx: PluginContext, sender: String, args: List<String>) -> String
        ) {
            handlers[command] = action
        }

        fun invoke(command: String, sender: String, args: List<String>): String {
            val action = handlers[command] ?: return "missing"
            return action(
                object : PluginContext {
                    override val manifest: PluginManifest = PluginManifest("test", "test", "1", "main")
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
                        override fun registerSystem(id: String, system: TickSystem) {}
                        override fun items(): List<ItemDefinition> = emptyList()
                        override fun blocks(): List<BlockDefinition> = emptyList()
                        override fun recipes(): List<RecipeDefinition> = emptyList()
                        override fun machines(): List<MachineDefinition> = emptyList()
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
                    override val storage: StorageProvider = RecordingStorageProvider()
                    override val commands: CommandRegistry = this@RecordingCommandRegistry
                    override val events: EventBus = RuntimeLikeEventBus()
                },
                sender,
                args
            )
        }
    }

    private fun contextWithPermissions(permissions: List<String>): PluginContext {
        return object : PluginContext {
            override val manifest: PluginManifest = PluginManifest(
                id = "test",
                name = "test",
                version = "1.0.0",
                main = "main",
                permissions = permissions
            )
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
                override fun registerSystem(id: String, system: TickSystem) {}
                override fun items(): List<ItemDefinition> = emptyList()
                override fun blocks(): List<BlockDefinition> = emptyList()
                override fun recipes(): List<RecipeDefinition> = emptyList()
                override fun machines(): List<MachineDefinition> = emptyList()
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
            override val storage: StorageProvider = RecordingStorageProvider()
            override val commands: CommandRegistry = RecordingCommandRegistry()
            override val events: EventBus = RuntimeLikeEventBus()
        }
    }
}
