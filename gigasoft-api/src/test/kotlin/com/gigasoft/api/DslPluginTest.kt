package com.gigasoft.api

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
                    name = "demo",
                    description = "Demo command",
                    aliases = listOf("d")
                ) { _, sender, _ -> "ok:$sender" }
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
            aliases[alias] = command
            return true
        }

        override fun resolve(commandOrAlias: String): String? {
            if (handlers.containsKey(commandOrAlias)) return commandOrAlias
            return aliases[commandOrAlias]
        }

        fun invoke(commandOrAlias: String, sender: String, args: List<String>): String {
            val key = resolve(commandOrAlias) ?: return "missing"
            val action = handlers[key] ?: return "missing"
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
            return action(ctx, sender, args)
        }
    }
}
