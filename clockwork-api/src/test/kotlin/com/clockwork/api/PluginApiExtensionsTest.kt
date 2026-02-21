package com.clockwork.api

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
    fun `event bus publishAsync helper returns completed future`() {
        val bus = RuntimeLikeEventBus()
        var calls = 0
        bus.subscribe<GigaTickEvent> { calls++ }

        bus.publishAsyncUnit(GigaTickEvent(1)).get()

        assertEquals(1, calls)
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
    fun `plugin context state helpers simplify load save and update`() {
        val storage = RecordingStorageProvider()
        val ctx = contextWithPermissions(emptyList(), storage = storage)

        val initial = ctx.loadOrDefault<TestPayload>("state:test") { TestPayload("init") }
        assertEquals(TestPayload("init"), initial)

        ctx.saveState("state:test", TestPayload("saved"))
        assertEquals(TestPayload("saved"), ctx.store<TestPayload>("state:test").load())

        val updated = ctx.updateState("state:test", default = { TestPayload("default") }) { current ->
            current.copy(value = "${current.value}-updated")
        }
        assertEquals(TestPayload("saved-updated"), updated)
        assertEquals(TestPayload("saved-updated"), ctx.store<TestPayload>("state:test").load())
    }

    @Test
    fun `register alias helper enforces registration and resolves aliases`() {
        val commands = RecordingCommandRegistry()
        commands.registerSpec(CommandSpec(command = "hello")) { inv ->
            CommandResult.ok("hello:${inv.sender.id}")
        }
        commands.registerAliasOrThrow("hi", "hello")
        commands.registerAliasOrThrow("hey", "hello")

        assertEquals("hello:alice", commands.invoke("hi", sender = "alice", args = emptyList()))
        assertEquals("hello:alice", commands.invoke("hey", sender = "alice", args = emptyList()))
        assertEquals("hello", commands.resolve("hi"))
    }

    @Test
    fun `command spec parses typed args and provides auto help`() {
        val commands = RecordingCommandRegistry()
        val spec = CommandSpec(
            command = "sum",
            description = "Adds two numbers",
            argsSchema = listOf(
                CommandArgSpec("a", CommandArgType.INT),
                CommandArgSpec("b", CommandArgType.INT)
            )
        )
        commands.registerSpec(spec) { _, parsed ->
            CommandResult.ok((parsed.int("a")!! + parsed.int("b")!!).toString())
        }

        assertEquals("7", commands.invoke("sum", "alice", listOf("3", "4")))
        assertTrue(commands.invoke("sum", "alice", listOf("--help")).contains("Usage: sum <a> <b>"))
        assertTrue(commands.invoke("sum", "alice", listOf("x", "4")).startsWith("[E_ARGS]"))
    }

    @Test
    fun `command spec middleware chain is deterministic`() {
        val commands = RecordingCommandRegistry()
        val calls = mutableListOf<String>()
        val spec = CommandSpec(command = "guarded")
        commands.registerSpec(
            spec = spec,
            middleware = listOf(
                validationMiddleware { calls += "validation"; null },
                auditMiddleware { _, _ -> calls += "audit" },
                authMiddleware { calls += "auth"; null }
            )
        ) {
            calls += "action"
            CommandResult.ok("ok")
        }

        assertEquals("ok", commands.invoke("guarded", "alice", emptyList()))
        assertEquals(listOf("auth", "validation", "action", "audit"), calls)
    }

    @Test
    fun `command spec supports cooldown and rate limit`() {
        val commands = RecordingCommandRegistry()
        var now = 1_000L
        commands.registerSpec(
            spec = CommandSpec(command = "cool", cooldownMillis = 100L),
            clockMillis = { now }
        ) { _, _ ->
            CommandResult.ok("ok")
        }
        assertEquals("ok", commands.invoke("cool", "alice", emptyList()))
        assertTrue(commands.invoke("cool", "alice", emptyList()).startsWith("[E_COOLDOWN]"))
        now += 150L
        assertEquals("ok", commands.invoke("cool", "alice", emptyList()))

        now = 5_000L
        commands.registerSpec(
            spec = CommandSpec(command = "rate", rateLimitPerMinute = 2),
            clockMillis = { now }
        ) { _, _ ->
            CommandResult.ok("ok")
        }
        assertEquals("ok", commands.invoke("rate", "alice", emptyList()))
        assertEquals("ok", commands.invoke("rate", "alice", emptyList()))
        assertTrue(commands.invoke("rate", "alice", emptyList()).startsWith("[E_RATE_LIMIT]"))
        now += 61_000L
        assertEquals("ok", commands.invoke("rate", "alice", emptyList()))
    }

    @Test
    fun `command completion catalog exposes schema based completions`() {
        val commands = RecordingCommandRegistry()
        val spec = CommandSpec(
            command = "mode",
            aliases = listOf("m"),
            argsSchema = listOf(CommandArgSpec("kind", type = CommandArgType.ENUM, enumValues = listOf("SAFE", "FAST")))
        )
        commands.registerSpec(spec) { _, _ -> CommandResult.ok("ok") }
        val ctx = contextWithPermissions(emptyList())

        val completions = CommandCompletionCatalog.suggest("m", ctx, CommandSender.player("alice"), listOf(""))
        assertEquals(listOf("SAFE", "FAST"), completions.map { it.value })

        CommandCompletionCatalog.unregister("mode")
        CommandCompletionCatalog.unregister("m")
    }

    @Test
    fun `command result render uses message when no code is set`() {
        assertEquals("pong", CommandResult.ok("pong").render())
    }

    @Test
    fun `command parsed args required primitives enforce typed access`() {
        val spec = CommandSpec(
            command = "typed",
            argsSchema = listOf(
                CommandArgSpec("i", CommandArgType.INT),
                CommandArgSpec("l", CommandArgType.LONG),
                CommandArgSpec("d", CommandArgType.DOUBLE),
                CommandArgSpec("b", CommandArgType.BOOLEAN)
            )
        )
        val parsed = parseCommandArgs(spec, listOf("4", "9", "1.5", "true")).parsed
        assertEquals(4, parsed.requiredInt("i"))
        assertEquals(9L, parsed.requiredLong("l"))
        assertEquals(1.5, parsed.requiredDouble("d"))
        assertTrue(parsed.requiredBoolean("b"))
        assertFailsWith<IllegalArgumentException> { parsed.requiredInt("missing") }
    }

    @Test
    fun `command parsed args duration helper parses suffix formats`() {
        val spec = CommandSpec(
            command = "timer",
            argsSchema = listOf(
                CommandArgSpec("a", CommandArgType.STRING),
                CommandArgSpec("b", CommandArgType.STRING),
                CommandArgSpec("c", CommandArgType.STRING),
                CommandArgSpec("d", CommandArgType.STRING)
            )
        )
        val parsed = parseCommandArgs(spec, listOf("1500", "2s", "3m", "1h")).parsed
        assertEquals(1_500L, parsed.durationMillis("a"))
        assertEquals(2_000L, parsed.durationMillis("b"))
        assertEquals(180_000L, parsed.durationMillis("c"))
        assertEquals(3_600_000L, parsed.requiredDurationMillis("d"))
        assertNull(parsed.durationMillis("missing"))
    }

    @Test
    fun `player key helper normalizes sender and strings`() {
        assertEquals("alex", "  Alex ".playerKey())
        assertEquals("player_one", CommandSender.player(" Player_One ").playerKey())
    }

    @Test
    fun `plugin kv helper simplifies string and numeric state`() {
        val ctx = contextWithPermissions(emptyList())
        val kv = ctx.pluginKv(namespace = "bridged", version = 1)

        assertNull(kv.get("missing"))
        kv.put("mode", "paper")
        kv.putBoolean("enabled", true)
        kv.putInt("checks", 3)
        assertEquals("paper", kv.get("mode"))
        assertEquals(true, kv.getBoolean("enabled"))
        assertEquals(3, kv.getInt("checks"))
        assertEquals(5L, kv.increment("checks.total", delta = 5L, initial = 0L))
        assertEquals(7L, kv.increment("checks.total", delta = 2L, initial = 0L))
        assertTrue(kv.remove("mode"))
        assertFalse(kv.remove("mode"))
    }

    @Test
    fun `bridge version helper returns expected compatibility grade`() {
        assertEquals(
            BridgeCompatibilityGrade.BELOW_MINIMUM,
            assessBridgeVersion("1.20.1", minimumVersion = "1.20.2", recommendedVersion = "1.21.1")
        )
        assertEquals(
            BridgeCompatibilityGrade.SUPPORTED,
            assessBridgeVersion("1.20.4", minimumVersion = "1.20.2", recommendedVersion = "1.21.1")
        )
        assertEquals(
            BridgeCompatibilityGrade.RECOMMENDED,
            assessBridgeVersion("1.21.1", minimumVersion = "1.20.2", recommendedVersion = "1.21.1")
        )
        assertEquals(
            BridgeCompatibilityGrade.UNKNOWN,
            assessBridgeVersion("snapshot-build", minimumVersion = "1.20.2", recommendedVersion = "1.21.1")
        )
        assertEquals("below-minimum", BridgeCompatibilityGrade.BELOW_MINIMUM.label())
    }

    @Test
    fun `bridge runtime detection maps server snapshot tokens`() {
        val runtime = HostServerSnapshot(
            name = "Paper",
            version = "1.21.1-R0.1-SNAPSHOT",
            platformVersion = "git-Paper-120",
            onlinePlayers = 5,
            maxPlayers = 100,
            worldCount = 3
        ).detectBridgeRuntime()
        assertEquals(BridgeRuntime.PAPER, runtime.runtime)
        assertTrue(runtime.token.contains("paper"))
    }

    @Test
    fun `movement distance helpers compute 3d horizontal and vertical distances`() {
        val before = HostPlayerSnapshot(
            uuid = "u1",
            name = "Alex",
            location = HostLocationRef(world = "world", x = 10.0, y = 64.0, z = 10.0)
        )
        val after = before.copy(
            location = HostLocationRef(world = "world", x = 13.0, y = 66.0, z = 14.0)
        )
        val event = GigaPlayerMoveEvent(previous = before, current = after)

        assertEquals(5.385164807134504, event.distance3d())
        assertEquals(5.0, event.horizontalDistance())
        assertEquals(2.0, event.verticalDistanceAbs())
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
    fun `host access aliases provide deterministic non-null defaults`() {
        val host = HostAccess.unavailable()
        assertNull(host.lookupPlayer("Alex"))
        assertFalse(host.isPlayerOp("Alex"))
        assertTrue(host.permissionsOfPlayer("Alex").isEmpty())
        assertFalse(host.playerHasPermission("Alex", "plugin.debug"))
        assertTrue(host.listWorlds().isEmpty())
        assertTrue(host.listEntities().isEmpty())
        assertTrue(host.worldDataOrEmpty("world").isEmpty())
        assertTrue(host.entityDataOrEmpty("entity").isEmpty())
    }

    @Test
    fun `ui helpers delegate to plugin ui contract`() {
        val calls = mutableListOf<String>()
        val ui = object : PluginUi {
            override fun notify(player: String, notice: UiNotice): Boolean {
                calls += "notify:${player}:${notice.level}:${notice.title}:${notice.message}"
                return true
            }

            override fun actionBar(player: String, message: String, durationTicks: Int): Boolean {
                calls += "actionbar:${player}:${durationTicks}:${message}"
                return true
            }

            override fun openMenu(player: String, menu: UiMenu): Boolean {
                calls += "menu:${player}:${menu.id}"
                return true
            }

            override fun openDialog(player: String, dialog: UiDialog): Boolean {
                calls += "dialog:${player}:${dialog.id}"
                return true
            }

            override fun close(player: String): Boolean {
                calls += "close:$player"
                return true
            }
        }
        val ctx = contextWithPermissions(emptyList(), ui = ui)

        assertTrue(ctx.notifyInfo("Alex", "hello", "Title"))
        assertTrue(ctx.notifySuccess("Alex", "done"))
        assertTrue(ctx.notify("Alex", UiLevel.WARNING, "careful", "Warn", durationMillis = 1_500L))
        assertTrue(ctx.actionBar("Alex", "Heads up", durationTicks = 30))
        assertTrue(
            ctx.showMenu(
                "Alex",
                UiMenu(
                    id = "main",
                    title = "Main",
                    items = listOf(UiMenuItem(id = "play", label = "Play"))
                )
            )
        )
        assertTrue(
            ctx.showDialog(
                "Alex",
                UiDialog(
                    id = "settings",
                    title = "Settings",
                    fields = listOf(UiDialogField(id = "volume", label = "Volume"))
                )
            )
        )
        assertTrue(ctx.closeUi("Alex"))

        assertTrue(calls.any { it.startsWith("notify:Alex:INFO:Title:hello") })
        assertTrue(calls.any { it.startsWith("notify:Alex:SUCCESS::done") })
        assertTrue(calls.any { it.startsWith("notify:Alex:WARNING:Warn:careful") })
        assertTrue(calls.any { it == "actionbar:Alex:30:Heads up" })
        assertTrue(calls.any { it == "menu:Alex:main" })
        assertTrue(calls.any { it == "dialog:Alex:settings" })
        assertTrue(calls.any { it == "close:Alex" })
    }

    @Test
    fun `broadcast notice helper formats level prefix and delegates to host`() {
        val broadcasts = mutableListOf<String>()
        val ctx = object : PluginContext {
            override val manifest: PluginManifest = PluginManifest("test", "test", "1.0.0", "main")
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
            override val host: HostAccess = object : HostAccess by HostAccess.unavailable() {
                override fun broadcast(message: String): Boolean {
                    broadcasts += message
                    return true
                }
            }
        }

        assertTrue(ctx.broadcastNotice("hello", level = UiLevel.SUCCESS, title = "Demo"))
        assertEquals("[OK] Demo: hello", broadcasts.single())
    }

    @Test
    fun `asset helpers delegate to registry bundle and validation contracts`() {
        val registry = object : RegistryFacade {
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
            override fun textures(): List<TextureDefinition> = listOf(TextureDefinition("gear", "assets/demo/textures/item/gear.png"))
            override fun models(): List<ModelDefinition> = listOf(ModelDefinition("gear_model", geometryPath = "assets/demo/models/item/gear.json"))
            override fun systems(): Map<String, TickSystem> = emptyMap()
            override fun validateAssets(options: ResourcePackBundleOptions): AssetValidationResult {
                return AssetValidationResult(
                    valid = true,
                    issues = emptyList()
                )
            }
            override fun buildResourcePackBundle(options: ResourcePackBundleOptions): ResourcePackBundle {
                return ResourcePackBundle(
                    pluginId = "demo",
                    textures = textures(),
                    models = models()
                )
            }
        }
        val ctx = object : PluginContext {
            override val manifest = PluginManifest("demo", "demo", "1.0.0", "main")
            override val logger = GigaLogger { }
            override val scheduler = object : Scheduler {
                override fun repeating(taskId: String, periodTicks: Int, block: () -> Unit) {}
                override fun once(taskId: String, delayTicks: Int, block: () -> Unit) {}
                override fun cancel(taskId: String) {}
                override fun clear() {}
            }
            override val registry: RegistryFacade = registry
            override val adapters: ModAdapterRegistry = object : ModAdapterRegistry {
                override fun register(adapter: ModAdapter) {}
                override fun list(): List<ModAdapter> = emptyList()
                override fun find(id: String): ModAdapter? = null
                override fun invoke(adapterId: String, invocation: AdapterInvocation): AdapterResponse {
                    return AdapterResponse(false)
                }
            }
            override val storage: StorageProvider = RecordingStorageProvider()
            override val commands: CommandRegistry = RecordingCommandRegistry()
            override val events: EventBus = RuntimeLikeEventBus()
        }

        val validation = ctx.validateAssets()
        val bundle = ctx.buildResourcePackBundle()
        assertTrue(validation.valid)
        assertEquals("demo", bundle.pluginId)
        assertEquals(1, bundle.textures.size)
        assertEquals(1, bundle.models.size)
    }

    @Test
    fun `host mutation batch helper triggers rollback callback`() {
        val ctx = object : PluginContext {
            override val manifest: PluginManifest = PluginManifest("test", "test", "1.0.0", "main")
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
            override val host: HostAccess = object : HostAccess by HostAccess.unavailable() {
                override fun applyMutationBatch(batch: HostMutationBatch): HostMutationBatchResult {
                    return HostMutationBatchResult(
                        batchId = batch.id,
                        success = false,
                        appliedOperations = 1,
                        rolledBack = true,
                        error = "rolled back"
                    )
                }
            }
        }

        var callbackCalled = false
        val result = ctx.applyHostMutationBatch(
            HostMutationBatch(
                id = "tx-1",
                operations = listOf(HostMutationOp(type = HostMutationType.SET_WORLD_TIME, target = "world", longValue = 1L))
            )
        ) {
            callbackCalled = true
        }

        assertFalse(result.success)
        assertTrue(result.rolledBack)
        assertTrue(callbackCalled)
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
        private data class Entry(
            val spec: CommandSpec,
            val middleware: List<CommandMiddlewareBinding>,
            val action: (CommandInvocationContext) -> CommandResult
        )

        private val handlers = linkedMapOf<String, Entry>()
        private val aliases = linkedMapOf<String, String>()

        override fun registerSpec(
            spec: CommandSpec,
            middleware: List<CommandMiddlewareBinding>,
            completion: CommandCompletionContract?,
            completionAsync: CommandCompletionAsyncContract?,
            policy: CommandPolicyProfile?,
            action: (CommandInvocationContext) -> CommandResult
        ) {
            val key = spec.command.trim().lowercase()
            require(key.isNotBlank()) { "Command id must not be blank" }
            require(!handlers.containsKey(key)) { "Duplicate command '$key'" }
            handlers[key] = Entry(
                spec = spec.copy(command = key),
                middleware = middleware,
                action = action
            )
            spec.aliases.forEach { alias ->
                aliases.putIfAbsent(alias.trim().lowercase(), key)
            }
            val completionProvider = completion ?: CommandCompletionContract { _, _, commandSpec, args ->
                commandSpec.defaultCompletions(args)
            }
            CommandCompletionCatalog.register(
                command = key,
                provider = completionProvider,
                providerAsync = completionAsync,
                spec = spec
            )
            spec.aliases.forEach { alias ->
                CommandCompletionCatalog.register(
                    command = alias,
                    provider = completionProvider,
                    providerAsync = completionAsync,
                    spec = spec
                )
            }
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
            val key = resolve(command.trim().lowercase()) ?: command.trim().lowercase()
            val entry = handlers[key] ?: return "missing"
            val route = resolveCommandRoute(entry.spec, args)
            val routedSpec = route.spec
            val routedArgs = if (route.consumedArgs <= 0) args else args.drop(route.consumedArgs)
            if (routedArgs.isNotEmpty() && (routedArgs[0].equals("help", ignoreCase = true) || routedArgs[0] == "--help")) {
                return CommandResult.ok(routedSpec.helpText()).render()
            }
            val parsed = parseCommandArgs(routedSpec, routedArgs)
            parsed.error?.let { return it.render() }
            val invocation = CommandInvocationContext(
                pluginContext = object : PluginContext {
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
                sender = CommandSender.player(sender),
                rawArgs = routedArgs,
                spec = routedSpec,
                parsedArgs = parsed.parsed
            )
            val orderedMiddleware = entry.middleware.sortedWith(
                compareBy<CommandMiddlewareBinding> { it.phase.ordinal }
                    .thenBy { it.order }
                    .thenBy { it.id }
            )
            var index = -1
            fun executeNext(): CommandResult {
                index++
                if (index < orderedMiddleware.size) {
                    return orderedMiddleware[index].middleware.invoke(invocation, ::executeNext)
                }
                return entry.action(invocation)
            }
            return executeNext().render()
        }
    }

    private fun contextWithPermissions(
        permissions: List<String>,
        ui: PluginUi = PluginUi.unavailable(),
        storage: StorageProvider = RecordingStorageProvider()
    ): PluginContext {
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
            override val storage: StorageProvider = storage
            override val commands: CommandRegistry = RecordingCommandRegistry()
            override val ui: PluginUi = ui
            override val events: EventBus = RuntimeLikeEventBus()
        }
    }
}
