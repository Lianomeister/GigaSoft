package com.gigasoft.runtime

import com.gigasoft.api.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class RuntimeRegistry(private val pluginId: String) : RegistryFacade {
    private val items = CopyOnWriteArrayList<ItemDefinition>()
    private val blocks = CopyOnWriteArrayList<BlockDefinition>()
    private val recipes = CopyOnWriteArrayList<RecipeDefinition>()
    private val machines = CopyOnWriteArrayList<MachineDefinition>()
    private val systems = ConcurrentHashMap<String, TickSystem>()

    override fun registerItem(definition: ItemDefinition) {
        ensureUnique(items.map { it.id }, definition.id, "item")
        items += definition
    }

    override fun registerBlock(definition: BlockDefinition) {
        ensureUnique(blocks.map { it.id }, definition.id, "block")
        blocks += definition
    }

    override fun registerRecipe(definition: RecipeDefinition) {
        ensureUnique(recipes.map { it.id }, definition.id, "recipe")
        recipes += definition
    }

    override fun registerMachine(definition: MachineDefinition) {
        ensureUnique(machines.map { it.id }, definition.id, "machine")
        machines += definition
    }

    override fun registerSystem(id: String, system: TickSystem) {
        val existing = systems.putIfAbsent(id, system)
        require(existing == null) { "Duplicate system id '$id' in plugin '$pluginId'" }
    }

    override fun items(): List<ItemDefinition> = items.toList()
    override fun blocks(): List<BlockDefinition> = blocks.toList()
    override fun recipes(): List<RecipeDefinition> = recipes.toList()
    override fun machines(): List<MachineDefinition> = machines.toList()
    override fun systems(): Map<String, TickSystem> = systems.toMap()

    private fun ensureUnique(existing: List<String>, id: String, kind: String) {
        require(id !in existing) { "Duplicate $kind id '$id' in plugin '$pluginId'" }
    }
}
