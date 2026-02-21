package com.gigasoft.runtime

import com.gigasoft.api.EventBus
import com.gigasoft.api.GigaCommandPostExecuteEvent
import com.gigasoft.api.GigaCommandPreExecuteEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RuntimeMessagingTest {
    @Test
    fun `register rejects duplicate command ids`() {
        val registry = RuntimeCommandRegistry()
        registry.register("ping", "first") { _, _, _ -> "pong" }

        assertFailsWith<IllegalArgumentException> {
            registry.register("PING", "second") { _, _, _ -> "pong2" }
        }
    }

    @Test
    fun `register rejects blank command ids`() {
        val registry = RuntimeCommandRegistry()
        assertFailsWith<IllegalArgumentException> {
            registry.register("   ", "bad") { _, _, _ -> "x" }
        }
    }

    @Test
    fun `registerOrReplace replaces existing handler and unregister removes it`() {
        val registry = RuntimeCommandRegistry()
        registry.register("ping", "first") { _, _, _ -> "one" }
        registry.registerOrReplace("ping", "second") { _, _, _ -> "two" }

        val response = registry.execute(
            ctx = fakeContext(),
            sender = "tester",
            commandLine = "ping"
        )
        assertEquals("two", response)
        assertTrue(registry.unregister("ping"))
        assertEquals("Unknown command: ping", registry.execute(fakeContext(), "tester", "ping"))
    }

    @Test
    fun `registerAlias resolves and executes target command`() {
        val registry = RuntimeCommandRegistry()
        registry.register("ping", "first") { _, _, _ -> "pong" }
        assertTrue(registry.registerAlias("p", "ping"))

        val response = registry.execute(fakeContext(), "tester", "p")
        assertEquals("pong", response)
        assertEquals("ping", registry.resolve("p"))
    }

    @Test
    fun `unregister command removes linked aliases`() {
        val registry = RuntimeCommandRegistry()
        registry.register("ping", "first") { _, _, _ -> "pong" }
        assertTrue(registry.registerAlias("p", "ping"))

        assertTrue(registry.unregister("ping"))
        assertEquals(null, registry.resolve("p"))
        assertEquals("Unknown command: p", registry.execute(fakeContext(), "tester", "p"))
    }

    @Test
    fun `registerAlias rejects collisions with command ids`() {
        val registry = RuntimeCommandRegistry()
        registry.register("ping", "first") { _, _, _ -> "pong" }
        registry.register("p", "other") { _, _, _ -> "alt" }

        assertFailsWith<IllegalArgumentException> {
            registry.registerAlias("p", "ping")
        }
    }

    @Test
    fun `registerAlias rejects aliasing unknown command`() {
        val registry = RuntimeCommandRegistry()
        assertFailsWith<IllegalArgumentException> {
            registry.registerAlias("p", "ping")
        }
    }

    @Test
    fun `command pre event can cancel execution`() {
        val bus = RuntimeEventBus(mode = EventDispatchMode.EXACT)
        val registry = RuntimeCommandRegistry(pluginId = "demo")
        registry.register("ping", "first") { _, _, _ -> "pong" }
        bus.subscribe(GigaCommandPreExecuteEvent::class.java) {
            it.cancelled = true
            it.cancelReason = "blocked by policy"
        }

        val response = registry.execute(fakeContext(events = bus), "tester", "ping")
        assertEquals("blocked by policy", response)
    }

    @Test
    fun `command post event is published with success`() {
        val bus = RuntimeEventBus(mode = EventDispatchMode.EXACT)
        val registry = RuntimeCommandRegistry(pluginId = "demo")
        registry.register("ping", "first") { _, _, _ -> "pong" }
        var post: GigaCommandPostExecuteEvent? = null
        bus.subscribe(GigaCommandPostExecuteEvent::class.java) { post = it }

        val response = registry.execute(fakeContext(events = bus), "tester", "ping")
        assertEquals("pong", response)
        assertEquals(true, post?.success)
        assertEquals("ping", post?.command)
        assertEquals("demo", post?.pluginId)
    }

    @Test
    fun `polymorphic event mode dispatches superclass listeners`() {
        val bus = RuntimeEventBus(mode = EventDispatchMode.POLYMORPHIC)
        var called = 0
        bus.subscribe(Number::class.java) { called++ }
        bus.publish(5)
        assertEquals(1, called)
    }

    @Test
    fun `exact event mode does not dispatch superclass listeners`() {
        val bus = RuntimeEventBus(mode = EventDispatchMode.EXACT)
        var called = 0
        bus.subscribe(Number::class.java) { called++ }
        bus.publish(5)
        assertEquals(0, called)
    }

    @Test
    fun `event bus unsubscribe detaches listener`() {
        val bus = RuntimeEventBus(mode = EventDispatchMode.EXACT)
        var called = 0
        val listener: (Int) -> Unit = { called++ }
        bus.subscribe(Int::class.javaObjectType, listener)
        assertTrue(bus.unsubscribe(Int::class.javaObjectType, listener))
        bus.publish(5)
        assertEquals(0, called)
    }

    @Test
    fun `hybrid event mode dispatches exact listeners before supertypes`() {
        val bus = RuntimeEventBus(mode = EventDispatchMode.HYBRID)
        val calls = mutableListOf<String>()
        bus.subscribe(Number::class.java) { calls += "number" }
        bus.subscribe(Int::class.javaObjectType) { calls += "int" }
        bus.subscribe(Any::class.java) { calls += "any" }

        bus.publish(5)
        assertEquals(listOf("int", "number", "any"), calls)
    }

    private fun fakeContext(events: EventBus = RuntimeEventBus()): com.gigasoft.api.PluginContext {
        return object : com.gigasoft.api.PluginContext {
            override val manifest = com.gigasoft.api.PluginManifest("test", "test", "1.0.0", "main")
            override val logger = com.gigasoft.api.GigaLogger {}
            override val scheduler = object : com.gigasoft.api.Scheduler {
                override fun repeating(taskId: String, periodTicks: Int, block: () -> Unit) {}
                override fun once(taskId: String, delayTicks: Int, block: () -> Unit) {}
                override fun cancel(taskId: String) {}
                override fun clear() {}
            }
            override val registry = object : com.gigasoft.api.RegistryFacade {
                override fun registerItem(definition: com.gigasoft.api.ItemDefinition) {}
                override fun registerBlock(definition: com.gigasoft.api.BlockDefinition) {}
                override fun registerRecipe(definition: com.gigasoft.api.RecipeDefinition) {}
                override fun registerMachine(definition: com.gigasoft.api.MachineDefinition) {}
                override fun registerTexture(definition: com.gigasoft.api.TextureDefinition) {}
                override fun registerModel(definition: com.gigasoft.api.ModelDefinition) {}
                override fun registerSystem(id: String, system: com.gigasoft.api.TickSystem) {}
                override fun items(): List<com.gigasoft.api.ItemDefinition> = emptyList()
                override fun blocks(): List<com.gigasoft.api.BlockDefinition> = emptyList()
                override fun recipes(): List<com.gigasoft.api.RecipeDefinition> = emptyList()
                override fun machines(): List<com.gigasoft.api.MachineDefinition> = emptyList()
                override fun textures(): List<com.gigasoft.api.TextureDefinition> = emptyList()
                override fun models(): List<com.gigasoft.api.ModelDefinition> = emptyList()
                override fun systems(): Map<String, com.gigasoft.api.TickSystem> = emptyMap()
            }
            override val adapters = object : com.gigasoft.api.ModAdapterRegistry {
                override fun register(adapter: com.gigasoft.api.ModAdapter) {}
                override fun list(): List<com.gigasoft.api.ModAdapter> = emptyList()
                override fun find(id: String): com.gigasoft.api.ModAdapter? = null
                override fun invoke(adapterId: String, invocation: com.gigasoft.api.AdapterInvocation) =
                    com.gigasoft.api.AdapterResponse(success = false)
            }
            override val storage = object : com.gigasoft.api.StorageProvider {
                override fun <T : Any> store(key: String, type: Class<T>, version: Int): com.gigasoft.api.PersistentStore<T> {
                    throw UnsupportedOperationException()
                }
            }
            override val commands = object : com.gigasoft.api.CommandRegistry {
                override fun register(
                    command: String,
                    description: String,
                    action: (ctx: com.gigasoft.api.PluginContext, sender: String, args: List<String>) -> String
                ) {
                }
            }
            override val events: EventBus = events
        }
    }
}
