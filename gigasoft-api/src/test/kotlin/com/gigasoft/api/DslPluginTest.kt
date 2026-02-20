package com.gigasoft.api

import kotlin.test.Test
import kotlin.test.assertEquals

class DslPluginTest {
    @Test
    fun `dsl registers items and systems`() {
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

        val plugin = gigaPlugin("test") {
            items { item("gear", "Gear") }
            systems { system("tick") {} }
        }

        plugin.onEnable(
            RuntimelessContext(registry)
        )

        assertEquals(listOf("gear"), registry.itemIds)
        assertEquals(listOf("tick"), registry.systemIds)
    }

    private class RuntimelessContext(private val registryFacade: RegistryFacade) : PluginContext {
        override val manifest: PluginManifest = PluginManifest("test", "test", "1", "main")
        override val logger: GigaLogger = GigaLogger { }
        override val scheduler: Scheduler = object : Scheduler {
            override fun repeating(taskId: String, periodTicks: Int, block: () -> Unit) {}
            override fun once(taskId: String, delayTicks: Int, block: () -> Unit) {}
            override fun cancel(taskId: String) {}
            override fun clear() {}
        }
        override val registry: RegistryFacade = registryFacade
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
