package com.gigasoft.runtime

import com.gigasoft.api.CommandSender
import com.gigasoft.api.CommandSenderType
import com.gigasoft.api.CommandSpec
import com.gigasoft.api.EventBus
import com.gigasoft.api.EventPriority
import com.gigasoft.api.EventSubscriptionOptions
import com.gigasoft.api.GigaCommandPostExecuteEvent
import com.gigasoft.api.GigaCommandPreExecuteEvent
import com.gigasoft.api.PluginContext
import com.gigasoft.api.registerSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RuntimeMessagingTest {
    @Test
    fun `registerSpec rejects duplicate command ids`() {
        val registry = RuntimeCommandRegistry()
        registry.registerSpec(CommandSpec(command = "ping")) { com.gigasoft.api.CommandResult.ok("pong") }

        assertFailsWith<IllegalArgumentException> {
            registry.registerSpec(CommandSpec(command = "PING")) { com.gigasoft.api.CommandResult.ok("pong2") }
        }
    }

    @Test
    fun `registerSpec supports alias resolution`() {
        val registry = RuntimeCommandRegistry()
        registry.registerSpec(CommandSpec(command = "ping", aliases = listOf("p"))) {
            com.gigasoft.api.CommandResult.ok("pong")
        }

        val response = registry.execute(fakeContext(), console(), "p")
        assertEquals("pong", response)
        assertEquals("ping", registry.resolve("p"))
    }

    @Test
    fun `typed sender is exposed through invocation context`() {
        val registry = RuntimeCommandRegistry()
        registry.registerSpec(CommandSpec(command = "whoami")) { inv ->
            com.gigasoft.api.CommandResult.ok("${inv.sender.type}:${inv.sender.id}")
        }

        val response = registry.execute(fakeContext(), CommandSender.player("Alex"), "whoami")
        assertEquals("PLAYER:Alex", response)
    }

    @Test
    fun `subcommands route to nested spec`() {
        val registry = RuntimeCommandRegistry()
        registry.registerSpec(
            CommandSpec(
                command = "plugin",
                subcommands = listOf(
                    CommandSpec(command = "error")
                )
            )
        ) { inv ->
            com.gigasoft.api.CommandResult.ok("sub=${inv.spec.command}")
        }

        val response = registry.execute(fakeContext(), console(), "plugin error")
        assertEquals("sub=error", response)
    }

    @Test
    fun `command pre event can cancel execution with rich response`() {
        val bus = RuntimeEventBus(mode = EventDispatchMode.EXACT)
        val registry = RuntimeCommandRegistry(pluginId = "demo")
        registry.registerSpec(CommandSpec(command = "ping")) { com.gigasoft.api.CommandResult.ok("pong") }
        bus.subscribe(GigaCommandPreExecuteEvent::class.java) {
            it.cancelled = true
            it.overrideResponse = com.gigasoft.api.CommandResult.error("blocked", code = "E_BLOCKED")
        }

        val response = registry.execute(fakeContext(events = bus), console(), "ping")
        assertTrue(response.startsWith("[E_BLOCKED]"))
    }

    @Test
    fun `command telemetry captures percentiles and top errors`() {
        val registry = RuntimeCommandRegistry(pluginId = "demo")
        registry.registerSpec(CommandSpec(command = "maybe")) { inv ->
            if (inv.rawArgs.firstOrNull() == "ok") {
                com.gigasoft.api.CommandResult.ok("ok")
            } else {
                com.gigasoft.api.CommandResult.error("bad", code = "E_BAD")
            }
        }

        registry.execute(fakeContext(), console(), "maybe ok")
        registry.execute(fakeContext(), console(), "maybe nope")
        registry.execute(fakeContext(), console(), "maybe nope")

        val snapshot = registry.commandTelemetry("maybe")
        assertNotNull(snapshot)
        assertEquals(3, snapshot.totalRuns)
        assertEquals(2, snapshot.failures)
        assertTrue(snapshot.p95Nanos >= snapshot.p50Nanos)
        assertEquals("E_BAD", snapshot.topErrors.first().code)
    }

    @Test
    fun `command post event is published with typed response`() {
        val bus = RuntimeEventBus(mode = EventDispatchMode.EXACT)
        val registry = RuntimeCommandRegistry(pluginId = "demo")
        registry.registerSpec(CommandSpec(command = "ping")) { com.gigasoft.api.CommandResult.ok("pong") }
        var post: GigaCommandPostExecuteEvent? = null
        bus.subscribe(GigaCommandPostExecuteEvent::class.java) { post = it }

        val response = registry.execute(fakeContext(events = bus), console(), "ping")
        assertEquals("pong", response)
        assertEquals(true, post?.success)
        assertEquals("pong", post?.response?.message)
        assertEquals(CommandSenderType.CONSOLE, post?.sender?.type)
    }

    @Test
    fun `event bus honors listener priority`() {
        val bus = RuntimeEventBus(mode = EventDispatchMode.EXACT)
        val calls = mutableListOf<String>()
        bus.subscribe(
            String::class.java,
            EventSubscriptionOptions(priority = EventPriority.LOW)
        ) { calls += "low" }
        bus.subscribe(
            String::class.java,
            EventSubscriptionOptions(priority = EventPriority.HIGHEST)
        ) { calls += "highest" }
        bus.publish("ping")
        assertEquals(listOf("highest", "low"), calls)
    }

    private fun console(): CommandSender = CommandSender(id = "console", type = CommandSenderType.CONSOLE)

    private fun fakeContext(events: EventBus = RuntimeEventBus()): PluginContext {
        return object : PluginContext {
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
                override fun registerSpec(
                    spec: com.gigasoft.api.CommandSpec,
                    middleware: List<com.gigasoft.api.CommandMiddlewareBinding>,
                    completion: com.gigasoft.api.CommandCompletionContract?,
                    completionAsync: com.gigasoft.api.CommandCompletionAsyncContract?,
                    policy: com.gigasoft.api.CommandPolicyProfile?,
                    action: (com.gigasoft.api.CommandInvocationContext) -> com.gigasoft.api.CommandResult
                ) {
                }
            }
            override val events: EventBus = events
        }
    }
}
