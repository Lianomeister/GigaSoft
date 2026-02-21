package com.gigasoft.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
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
    fun `event bus subscribeOnce only receives first event`() {
        val bus = RuntimeLikeEventBus()
        var calls = 0
        bus.subscribeOnce<GigaTickEvent> { calls += it.tick.toInt() }

        bus.publish(GigaTickEvent(2))
        bus.publish(GigaTickEvent(3))

        assertEquals(2, calls)
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
    fun `command result helpers render typed responses`() {
        val commands = RecordingCommandRegistry()
        commands.registerResult("safe") { _, _ ->
            CommandResult.ok("done", code = "OK")
        }
        commands.registerOrReplaceResult("safe") { _, _ ->
            CommandResult.error("denied", code = "E_PERMISSION")
        }

        val response = commands.invoke("safe", sender = "alice", args = emptyList())
        assertEquals("[E_PERMISSION] denied", response)
    }

    @Test
    fun `command alias helpers register and resolve aliases`() {
        val commands = RecordingCommandRegistry()
        commands.registerWithAliases(
            command = "hello",
            aliases = listOf("hi", "hey")
        ) { sender, _ -> "hello:$sender" }

        assertEquals("hello:alice", commands.invoke("hi", sender = "alice", args = emptyList()))
        assertEquals("hello:alice", commands.invoke("hey", sender = "alice", args = emptyList()))
        assertEquals("hello", commands.resolve("hi"))
    }

    @Test
    fun `validated command helper short-circuits with error response`() {
        val commands = RecordingCommandRegistry()
        commands.registerValidated(
            command = "sum",
            validator = { _, args ->
                if (args.size < 2) CommandResult.error("need at least 2 args", code = "E_ARGS") else null
            }
        ) { _, _, args ->
            val total = args.map(String::toInt).sum()
            CommandResult.ok(total.toString())
        }

        assertEquals("[E_ARGS] need at least 2 args", commands.invoke("sum", sender = "alice", args = listOf("1")))
        assertEquals("3", commands.invoke("sum", sender = "alice", args = listOf("1", "2")))
    }

    @Test
    fun `command result render uses message when no code is set`() {
        assertEquals("pong", CommandResult.ok("pong").render())
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

    @Test
    fun `adapter payload helpers parse supported primitive types`() {
        val invocation = AdapterInvocation(
            action = "test",
            payload = mapOf(
                "name" to "  alpha  ",
                "count" to "42",
                "size" to "  9000000000 ",
                "ratio" to "1.25",
                "enabled" to "YES",
                "flag" to "0",
                "tags" to "alpha, beta ,,gamma",
                "mode" to "safe",
                "cfg.world.name" to "demo",
                "cfg.world.seed" to "42"
            )
        )

        assertEquals("  alpha  ", invocation.payloadString("name"))
        assertEquals("alpha", invocation.payloadTrimmed("name"))
        assertEquals(42, invocation.payloadInt("count"))
        assertEquals(9_000_000_000L, invocation.payloadLong("size"))
        assertEquals(1.25, invocation.payloadDouble("ratio"))
        assertEquals(true, invocation.payloadBool("enabled"))
        assertEquals(false, invocation.payloadBool("flag"))
        assertEquals(listOf("alpha", "beta", "gamma"), invocation.payloadCsv("tags"))
        assertEquals(TestMode.SAFE, invocation.payloadEnum<TestMode>("mode"))
        assertEquals(
            mapOf(
                "world.name" to "demo",
                "world.seed" to "42"
            ),
            invocation.payloadByPrefix("cfg.")
        )
    }

    @Test
    fun `adapter payload helpers return null for invalid optional values`() {
        val invocation = AdapterInvocation(
            action = "test",
            payload = mapOf(
                "count" to "NaN",
                "enabled" to "maybe"
            )
        )
        assertNull(invocation.payloadInt("count"))
        assertNull(invocation.payloadBool("enabled"))
        assertNull(invocation.payloadString("missing"))
    }

    @Test
    fun `adapter payload required throws for missing or blank values`() {
        val invocation = AdapterInvocation(
            action = "test",
            payload = mapOf(
                "name" to "  "
            )
        )
        assertFailsWith<IllegalArgumentException> { invocation.payloadRequired("name") }
        assertFailsWith<IllegalArgumentException> { invocation.payloadRequired("missing") }
        assertFailsWith<IllegalArgumentException> { invocation.payloadIntRequired("name") }
    }

    @Test
    fun `adapter payload required primitive helpers enforce valid values`() {
        val invocation = AdapterInvocation(
            action = "test",
            payload = mapOf(
                "count" to "5",
                "longValue" to "12",
                "ratio" to "1.5",
                "enabled" to "true",
                "broken" to "x",
                "timeout" to "2m",
                "kv" to "a=1; b = 2 ; invalid ; c=3",
                "strictMode" to "FAST"
            )
        )
        assertEquals(5, invocation.payloadIntRequired("count"))
        assertEquals(12L, invocation.payloadLongRequired("longValue"))
        assertEquals(1.5, invocation.payloadDoubleRequired("ratio"))
        assertEquals(true, invocation.payloadBoolRequired("enabled"))
        assertEquals(120_000L, invocation.payloadDurationMillis("timeout"))
        assertEquals(mapOf("a" to "1", "b" to "2", "c" to "3"), invocation.payloadMap("kv"))
        assertEquals(TestMode.FAST, invocation.payloadEnumRequired<TestMode>("strictMode"))
        assertFailsWith<IllegalArgumentException> { invocation.payloadBoolRequired("broken") }
        assertFailsWith<IllegalArgumentException> { invocation.payloadEnumRequired<TestMode>("broken") }
    }

    private data class TestPayload(val value: String)
    private enum class TestMode {
        SAFE,
        FAST
    }

    private class RuntimeLikeEventBus : EventBus {
        private val listeners = linkedMapOf<Class<*>, MutableList<ListenerEntry>>()

        @Suppress("UNCHECKED_CAST")
        override fun <T : Any> subscribe(eventType: Class<T>, listener: (T) -> Unit) {
            val bucket = listeners.getOrPut(eventType) { mutableListOf() }
            bucket += ListenerEntry(
                raw = listener as (Any) -> Unit,
                callback = { event -> listener(event as T) }
            )
        }

        override fun <T : Any> unsubscribe(eventType: Class<T>, listener: (T) -> Unit): Boolean {
            val bucket = listeners[eventType] ?: return false
            val before = bucket.size
            bucket.removeIf { it.raw === listener || it.raw == listener }
            return before != bucket.size
        }

        override fun publish(event: Any) {
            listeners[event::class.java]?.toList()?.forEach { it.callback(event) }
        }

        private data class ListenerEntry(
            val raw: (Any) -> Unit,
            val callback: (Any) -> Unit
        )
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
        private val aliases = linkedMapOf<String, String>()

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

        override fun registerAlias(alias: String, command: String): Boolean {
            if (!handlers.containsKey(command)) return false
            if (handlers.containsKey(alias)) return false
            val existed = aliases.putIfAbsent(alias, command)
            return existed == null
        }

        override fun unregister(command: String): Boolean {
            val removed = handlers.remove(command) != null
            if (removed) {
                aliases.entries.removeIf { (_, target) -> target == command }
            }
            return removed
        }

        override fun unregisterAlias(alias: String): Boolean = aliases.remove(alias) != null

        override fun resolve(commandOrAlias: String): String? {
            return when {
                handlers.containsKey(commandOrAlias) -> commandOrAlias
                else -> aliases[commandOrAlias]
            }
        }

        fun invoke(command: String, sender: String, args: List<String>): String {
            val key = resolve(command) ?: command
            val action = handlers[key] ?: return "missing"
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
            override val storage: StorageProvider = RecordingStorageProvider()
            override val commands: CommandRegistry = RecordingCommandRegistry()
            override val events: EventBus = RuntimeLikeEventBus()
        }
    }
}
