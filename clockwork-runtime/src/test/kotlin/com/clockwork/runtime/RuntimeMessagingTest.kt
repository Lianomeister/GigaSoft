package com.clockwork.runtime

import com.clockwork.api.CommandSender
import com.clockwork.api.CommandSenderType
import com.clockwork.api.CommandSpec
import com.clockwork.api.EventBus
import com.clockwork.api.EventPriority
import com.clockwork.api.EventSubscriptionOptions
import com.clockwork.api.GigaCommandPostExecuteEvent
import com.clockwork.api.GigaCommandPreExecuteEvent
import com.clockwork.api.PluginContext
import com.clockwork.api.registerSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RuntimeMessagingTest {
    @Test
    fun `registerSpec rejects duplicate command ids`() {
        val registry = RuntimeCommandRegistry()
        registry.registerSpec(CommandSpec(command = "ping")) { com.clockwork.api.CommandResult.ok("pong") }

        assertFailsWith<IllegalArgumentException> {
            registry.registerSpec(CommandSpec(command = "PING")) { com.clockwork.api.CommandResult.ok("pong2") }
        }
    }

    @Test
    fun `registerSpec supports alias resolution`() {
        val registry = RuntimeCommandRegistry()
        registry.registerSpec(CommandSpec(command = "ping", aliases = listOf("p"))) {
            com.clockwork.api.CommandResult.ok("pong")
        }

        val response = registry.execute(fakeContext(), console(), "p")
        assertEquals("pong", response)
        assertEquals("ping", registry.resolve("p"))
    }

    @Test
    fun `typed sender is exposed through invocation context`() {
        val registry = RuntimeCommandRegistry()
        registry.registerSpec(CommandSpec(command = "whoami")) { inv ->
            com.clockwork.api.CommandResult.ok("${inv.sender.type}:${inv.sender.id}")
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
            com.clockwork.api.CommandResult.ok("sub=${inv.spec.command}")
        }

        val response = registry.execute(fakeContext(), console(), "plugin error")
        assertEquals("sub=error", response)
    }

    @Test
    fun `command pre event can cancel execution with rich response`() {
        val bus = RuntimeEventBus(mode = EventDispatchMode.EXACT)
        val registry = RuntimeCommandRegistry(pluginId = "demo")
        registry.registerSpec(CommandSpec(command = "ping")) { com.clockwork.api.CommandResult.ok("pong") }
        bus.subscribe(GigaCommandPreExecuteEvent::class.java) {
            it.cancelled = true
            it.overrideResponse = com.clockwork.api.CommandResult.error("blocked", code = "E_BLOCKED")
        }

        val response = registry.execute(fakeContext(events = bus), console(), "ping")
        assertTrue(response.startsWith("[E_BLOCKED]"))
    }

    @Test
    fun `command telemetry captures percentiles and top errors`() {
        val registry = RuntimeCommandRegistry(pluginId = "demo")
        registry.registerSpec(CommandSpec(command = "maybe")) { inv ->
            if (inv.rawArgs.firstOrNull() == "ok") {
                com.clockwork.api.CommandResult.ok("ok")
            } else {
                com.clockwork.api.CommandResult.error("bad", code = "E_BAD")
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
        registry.registerSpec(CommandSpec(command = "ping")) { com.clockwork.api.CommandResult.ok("pong") }
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
            override val manifest = com.clockwork.api.PluginManifest("test", "test", "1.0.0", "main")
            override val logger = com.clockwork.api.GigaLogger {}
            override val scheduler = object : com.clockwork.api.Scheduler {
                override fun repeating(taskId: String, periodTicks: Int, block: () -> Unit) {}
                override fun once(taskId: String, delayTicks: Int, block: () -> Unit) {}
                override fun cancel(taskId: String) {}
                override fun clear() {}
            }
            override val registry = object : com.clockwork.api.RegistryFacade {
                override fun registerItem(definition: com.clockwork.api.ItemDefinition) {}
                override fun registerBlock(definition: com.clockwork.api.BlockDefinition) {}
                override fun registerRecipe(definition: com.clockwork.api.RecipeDefinition) {}
                override fun registerMachine(definition: com.clockwork.api.MachineDefinition) {}
                override fun registerTexture(definition: com.clockwork.api.TextureDefinition) {}
                override fun registerModel(definition: com.clockwork.api.ModelDefinition) {}
                override fun registerSystem(id: String, system: com.clockwork.api.TickSystem) {}
                override fun items(): List<com.clockwork.api.ItemDefinition> = emptyList()
                override fun blocks(): List<com.clockwork.api.BlockDefinition> = emptyList()
                override fun recipes(): List<com.clockwork.api.RecipeDefinition> = emptyList()
                override fun machines(): List<com.clockwork.api.MachineDefinition> = emptyList()
                override fun textures(): List<com.clockwork.api.TextureDefinition> = emptyList()
                override fun models(): List<com.clockwork.api.ModelDefinition> = emptyList()
                override fun systems(): Map<String, com.clockwork.api.TickSystem> = emptyMap()
            }
            override val adapters = object : com.clockwork.api.ModAdapterRegistry {
                override fun register(adapter: com.clockwork.api.ModAdapter) {}
                override fun list(): List<com.clockwork.api.ModAdapter> = emptyList()
                override fun find(id: String): com.clockwork.api.ModAdapter? = null
                override fun invoke(adapterId: String, invocation: com.clockwork.api.AdapterInvocation) =
                    com.clockwork.api.AdapterResponse(success = false)
            }
            override val storage = object : com.clockwork.api.StorageProvider {
                override fun <T : Any> store(key: String, type: Class<T>, version: Int): com.clockwork.api.PersistentStore<T> {
                    throw UnsupportedOperationException()
                }
            }
            override val commands = object : com.clockwork.api.CommandRegistry {
                override fun registerSpec(
                    spec: com.clockwork.api.CommandSpec,
                    middleware: List<com.clockwork.api.CommandMiddlewareBinding>,
                    completion: com.clockwork.api.CommandCompletionContract?,
                    completionAsync: com.clockwork.api.CommandCompletionAsyncContract?,
                    policy: com.clockwork.api.CommandPolicyProfile?,
                    action: (com.clockwork.api.CommandInvocationContext) -> com.clockwork.api.CommandResult
                ) {
                }
            }
            override val events: EventBus = events
        }
    }
}
