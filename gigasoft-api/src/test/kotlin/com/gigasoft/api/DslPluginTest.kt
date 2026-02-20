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

            override fun registerItem(definition: ItemDefinition) { itemIds += definition.id }
            override fun registerBlock(definition: BlockDefinition) {}
            override fun registerRecipe(definition: RecipeDefinition) {}
            override fun registerMachine(definition: MachineDefinition) {}
            override fun registerSystem(id: String, system: TickSystem) { systemIds += id }
            override fun items(): List<ItemDefinition> = emptyList()
            override fun blocks(): List<BlockDefinition> = emptyList()
            override fun recipes(): List<RecipeDefinition> = emptyList()
            override fun machines(): List<MachineDefinition> = emptyList()
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
            systems { system("tick") {} }
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

        plugin.onEnable(
            RuntimelessContext(registry, adapters)
        )

        assertEquals(listOf("gear"), registry.itemIds)
        assertEquals(listOf("tick"), registry.systemIds)
        assertEquals(listOf("bridge"), adapters.list().map { it.id })
        assertTrue(adapters.invoke("bridge", AdapterInvocation("ping")).success)
    }

    private class RuntimelessContext(
        private val registryFacade: RegistryFacade,
        private val adapterRegistry: ModAdapterRegistry
    ) : PluginContext {
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
        override val commands: CommandRegistry = object : CommandRegistry {
            override fun register(
                command: String,
                description: String,
                action: (ctx: PluginContext, sender: String, args: List<String>) -> String
            ) {
            }
        }
        override val events: EventBus = object : EventBus {
            override fun <T : Any> subscribe(eventType: Class<T>, listener: (T) -> Unit) {}
            override fun publish(event: Any) {}
        }
    }
}
