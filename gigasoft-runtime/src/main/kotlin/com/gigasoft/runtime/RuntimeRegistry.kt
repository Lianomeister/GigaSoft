package com.gigasoft.runtime

import com.gigasoft.api.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

class RuntimeRegistry(private val pluginId: String) : RegistryFacade {
    private val items = CopyOnWriteArrayList<ItemDefinition>()
    private val blocks = CopyOnWriteArrayList<BlockDefinition>()
    private val recipes = CopyOnWriteArrayList<RecipeDefinition>()
    private val machines = CopyOnWriteArrayList<MachineDefinition>()
    private val textures = CopyOnWriteArrayList<TextureDefinition>()
    private val models = CopyOnWriteArrayList<ModelDefinition>()
    private val systems = ConcurrentHashMap<String, TickSystem>()
    private val itemIds = ConcurrentHashMap.newKeySet<String>()
    private val blockIds = ConcurrentHashMap.newKeySet<String>()
    private val recipeIds = ConcurrentHashMap.newKeySet<String>()
    private val machineIds = ConcurrentHashMap.newKeySet<String>()
    private val textureIds = ConcurrentHashMap.newKeySet<String>()
    private val modelIds = ConcurrentHashMap.newKeySet<String>()
    @Volatile
    private var systemsSnapshotCache: List<Pair<String, TickSystem>> = emptyList()
    @Volatile
    private var systemsSnapshotDirty = true
    private val systemsVersionCounter = AtomicLong(0)

    override fun registerItem(definition: ItemDefinition) {
        ensureUnique(itemIds, definition.id, "item")
        items += definition
    }

    override fun registerBlock(definition: BlockDefinition) {
        ensureUnique(blockIds, definition.id, "block")
        blocks += definition
    }

    override fun registerRecipe(definition: RecipeDefinition) {
        ensureUnique(recipeIds, definition.id, "recipe")
        recipes += definition
    }

    override fun registerMachine(definition: MachineDefinition) {
        ensureUnique(machineIds, definition.id, "machine")
        machines += definition
    }

    override fun registerTexture(definition: TextureDefinition) {
        ensureUnique(textureIds, definition.id, "texture")
        textures += definition
    }

    override fun registerModel(definition: ModelDefinition) {
        ensureUnique(modelIds, definition.id, "model")
        models += definition
    }

    override fun registerSystem(id: String, system: TickSystem) {
        val existing = systems.putIfAbsent(id, system)
        require(existing == null) { "Duplicate system id '$id' in plugin '$pluginId'" }
        systemsVersionCounter.incrementAndGet()
        systemsSnapshotDirty = true
    }

    override fun items(): List<ItemDefinition> = items.toList()
    override fun blocks(): List<BlockDefinition> = blocks.toList()
    override fun recipes(): List<RecipeDefinition> = recipes.toList()
    override fun machines(): List<MachineDefinition> = machines.toList()
    override fun textures(): List<TextureDefinition> = textures.toList()
    override fun models(): List<ModelDefinition> = models.toList()
    override fun systems(): Map<String, TickSystem> = systems.toMap()
    fun systemsView(): Set<Map.Entry<String, TickSystem>> = systems.entries
    fun systemsVersion(): Long = systemsVersionCounter.get()
    fun systemsSnapshot(): List<Pair<String, TickSystem>> {
        if (!systemsSnapshotDirty) return systemsSnapshotCache
        synchronized(this) {
            if (!systemsSnapshotDirty) return systemsSnapshotCache
            systemsSnapshotCache = systems.entries.map { it.key to it.value }
            systemsSnapshotDirty = false
            return systemsSnapshotCache
        }
    }

    private fun ensureUnique(existing: MutableSet<String>, id: String, kind: String) {
        require(existing.add(id)) { "Duplicate $kind id '$id' in plugin '$pluginId'" }
    }
}
