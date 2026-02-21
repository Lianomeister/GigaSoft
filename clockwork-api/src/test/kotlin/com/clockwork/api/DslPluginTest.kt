package com.clockwork.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DslPluginTest {
    @Test
    fun `dsl registers items systems and adapters`() {
        val registry = object : RegistryFacade {
            val itemIds = mutableListOf<String>()
            val systemIds = mutableListOf<String>()
            val textureIds = mutableListOf<String>()
            val models = mutableListOf<ModelDefinition>()

            override fun registerItem(definition: ItemDefinition) { itemIds += definition.id }
            override fun registerBlock(definition: BlockDefinition) {}
            override fun registerRecipe(definition: RecipeDefinition) {}
            override fun registerMachine(definition: MachineDefinition) {}
            override fun registerTexture(definition: TextureDefinition) { textureIds += definition.id }
            override fun registerModel(definition: ModelDefinition) { models += definition }
            override fun registerSystem(id: String, system: TickSystem) { systemIds += id }
            override fun items(): List<ItemDefinition> = emptyList()
            override fun blocks(): List<BlockDefinition> = emptyList()
            override fun recipes(): List<RecipeDefinition> = emptyList()
            override fun machines(): List<MachineDefinition> = emptyList()
            override fun textures(): List<TextureDefinition> = emptyList()
            override fun models(): List<ModelDefinition> = emptyList()
            override fun systems(): Map<String, TickSystem> = emptyMap()
        }

        val adapters = object : ModAdapterRegistry {
            private val map = linkedMapOf<String, ModAdapter>()
            override fun register(adapter: ModAdapter) {
                map[adapter.id] = adapter
            }

            override fun list(): List<ModAdapter> = map.values.toList()
            override fun find(id: String): ModAdapter? = map[id]
            override fun invoke(adapterId: String, invocation: AdapterInvocation): AdapterResponse {
                return map[adapterId]?.invoke(invocation)
                    ?: AdapterResponse(success = false, message = "missing")
            }
        }

        val plugin = gigaPlugin("test") {
            items { item("gear", "Gear") }
            textures { texture("gear_base", "assets/demo/textures/item/gear.png") }
            models {
                model(
                    id = "gear_model",
                    geometryPath = "assets/demo/models/item/gear.json",
                    textures = mapOf("layer0" to "gear_base"),
                    material = "cutout",
                    doubleSided = true,
                    scale = 1.25,
                    bounds = ModelBounds(0.0, 0.0, 0.0, 1.0, 1.0, 1.0),
                    lods = listOf(
                        ModelLod(distance = 24.0, geometryPath = "assets/demo/models/item/gear_lod1.json")
                    ),
                    animations = mapOf("spin" to "assets/demo/animations/gear_spin.json")
                )
            }
            systems { system("tick") {} }
            commands {
                command(
                    spec = CommandSpec(
                        command = "demo",
                        aliases = listOf("d")
                    )
                ) { sender, _ ->
                    CommandResult.ok("ok:${sender.id}")
                }
                command(
                    spec = CommandSpec(
                        command = "math",
                        aliases = listOf("m"),
                        argsSchema = listOf(
                            CommandArgSpec(
                                name = "mode",
                                type = CommandArgType.ENUM,
                                enumValues = listOf("SAFE", "FAST")
                            ),
                            CommandArgSpec(name = "value", type = CommandArgType.INT)
                        ),
                        help = "Adds two numbers."
                    ),
                    middleware = listOf(
                        authMiddleware { null },
                        validationMiddleware { null },
                        auditMiddleware { _, _ -> }
                    )
                ) { _, parsed ->
                    val value = parsed.int("value") ?: 0
                    val factor = if (parsed.enum("mode").equals("FAST", ignoreCase = true)) 2 else 1
                    CommandResult.ok((value * factor).toString())
                }
            }
            adapters {
                adapter(
                    id = "bridge",
                    name = "BridgeAdapter",
                    capabilities = setOf("craft", "transfer")
                ) { invocation ->
                    AdapterResponse(success = invocation.action == "ping")
                }
            }
        }

        val ctx = RuntimelessContext(registry, adapters)
        plugin.onEnable(ctx)

        assertEquals(listOf("gear"), registry.itemIds)
        assertEquals(listOf("gear_base"), registry.textureIds)
        assertEquals(listOf("gear_model"), registry.models.map { it.id })
        assertEquals(listOf("tick"), registry.systemIds)
        assertEquals(listOf("bridge"), adapters.list().map { it.id })
        assertTrue(adapters.invoke("bridge", AdapterInvocation("ping")).success)
        assertEquals("cutout", registry.models.first().material)
        assertEquals(true, registry.models.first().doubleSided)
        assertEquals(1.25, registry.models.first().scale)
        assertEquals(1, registry.models.first().lods.size)
        assertEquals(1, registry.models.first().animations.size)
        assertTrue(ctx.eventsSeen.any { it is GigaTextureRegisteredEvent })
        assertTrue(ctx.eventsSeen.any { it is GigaModelRegisteredEvent })
        assertEquals("ok:alice", ctx.commandRegistry.invoke("d", "alice", emptyList()))
        assertEquals("8", ctx.commandRegistry.invoke("m", "alice", listOf("FAST", "4")))
        val completions = CommandCompletionCatalog.suggest(
            commandOrAlias = "m",
            ctx = ctx,
            sender = CommandSender.player("alice"),
            args = listOf("")
        )
        assertEquals(listOf("SAFE", "FAST"), completions.map { it.value })
        CommandCompletionCatalog.unregister("math")
        CommandCompletionCatalog.unregister("m")
    }

    private class RuntimelessContext(
        private val registryFacade: RegistryFacade,
        private val adapterRegistry: ModAdapterRegistry
    ) : PluginContext {
        val eventsSeen = mutableListOf<Any>()
        val commandRegistry = RecordingCommands()
        override val manifest: PluginManifest = PluginManifest("test", "test", "1", "main")
        override val logger: GigaLogger = GigaLogger { }
        override val scheduler: Scheduler = object : Scheduler {
            override fun repeating(taskId: String, periodTicks: Int, block: () -> Unit) {}
            override fun once(taskId: String, delayTicks: Int, block: () -> Unit) {}
            override fun cancel(taskId: String) {}
            override fun clear() {}
        }
        override val registry: RegistryFacade = registryFacade
        override val adapters: ModAdapterRegistry = adapterRegistry
        override val storage: StorageProvider = object : StorageProvider {
            override fun <T : Any> store(key: String, type: Class<T>, version: Int): PersistentStore<T> {
                error("not required")
            }
        }
        override val commands: CommandRegistry = commandRegistry
        override val events: EventBus = object : EventBus {
            override fun <T : Any> subscribe(eventType: Class<T>, listener: (T) -> Unit) {}
            override fun publish(event: Any) { eventsSeen += event }
        }
    }

    private class RecordingCommands : CommandRegistry {
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
            aliases[alias] = command
            return true
        }

        override fun resolve(commandOrAlias: String): String? {
            if (handlers.containsKey(commandOrAlias)) return commandOrAlias
            return aliases[commandOrAlias]
        }

        fun invoke(commandOrAlias: String, sender: String, args: List<String>): String {
            val key = resolve(commandOrAlias.trim().lowercase()) ?: return "missing"
            val entry = handlers[key] ?: return "missing"
            val route = resolveCommandRoute(entry.spec, args)
            val routedSpec = route.spec
            val routedArgs = if (route.consumedArgs <= 0) args else args.drop(route.consumedArgs)
            val parsed = parseCommandArgs(routedSpec, routedArgs)
            parsed.error?.let { return it.render() }
            val ctx = object : PluginContext {
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
                override val storage: StorageProvider = object : StorageProvider {
                    override fun <T : Any> store(key: String, type: Class<T>, version: Int): PersistentStore<T> {
                        error("not required")
                    }
                }
                override val commands: CommandRegistry = this@RecordingCommands
                override val events: EventBus = object : EventBus {
                    override fun <T : Any> subscribe(eventType: Class<T>, listener: (T) -> Unit) {}
                    override fun publish(event: Any) {}
                }
            }
            val invocation = CommandInvocationContext(
                pluginContext = ctx,
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
}
