package com.gigasoft.core

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class StandalonePlayer(
    val uuid: String,
    val name: String,
    val world: String,
    val x: Double,
    val y: Double,
    val z: Double
)

data class StandaloneWorld(
    val name: String,
    val seed: Long,
    val time: Long
)

data class StandaloneEntity(
    val uuid: String,
    val type: String,
    val world: String,
    val x: Double,
    val y: Double,
    val z: Double
)

data class StandaloneInventory(
    val owner: String,
    val size: Int,
    val slots: Map<Int, String>
)

class StandaloneHostState(
    defaultWorld: String = "world"
) {
    private val worlds = ConcurrentHashMap<String, StandaloneWorld>()
    private val entities = ConcurrentHashMap<String, StandaloneEntity>()
    private val worldEntityCounts = ConcurrentHashMap<String, Int>()
    private val playersByName = ConcurrentHashMap<String, StandalonePlayer>()
    private val inventoriesByOwner = ConcurrentHashMap<String, MutableMap<Int, String>>()
    @Volatile
    private var worldsCache: List<StandaloneWorld> = emptyList()
    @Volatile
    private var worldsDirty = true
    @Volatile
    private var playersCache: List<StandalonePlayer> = emptyList()
    @Volatile
    private var playersDirty = true
    @Volatile
    private var entitiesAllCache: List<StandaloneEntity> = emptyList()
    @Volatile
    private var entitiesAllDirty = true
    private val entitiesWorldCache = ConcurrentHashMap<String, List<StandaloneEntity>>()

    init {
        createWorld(defaultWorld)
    }

    fun worldCount(): Int = worlds.size

    fun worlds(): List<StandaloneWorld> {
        if (!worldsDirty) return worldsCache
        synchronized(this) {
            if (!worldsDirty) return worldsCache
            worldsCache = worlds.values.sortedBy { it.name.lowercase() }
            worldsDirty = false
            return worldsCache
        }
    }

    fun onlinePlayerCount(): Int = playersByName.size

    fun players(): List<StandalonePlayer> {
        if (!playersDirty) return playersCache
        synchronized(this) {
            if (!playersDirty) return playersCache
            playersCache = playersByName.values.sortedBy { it.name.lowercase() }
            playersDirty = false
            return playersCache
        }
    }

    fun entities(world: String? = null): List<StandaloneEntity> {
        val all = entities.values
        return if (world.isNullOrBlank()) {
            if (!entitiesAllDirty) return entitiesAllCache
            synchronized(this) {
                if (!entitiesAllDirty) return entitiesAllCache
                entitiesAllCache = all.sortedWith(compareBy({ it.world.lowercase() }, { it.type.lowercase() }, { it.uuid }))
                entitiesAllDirty = false
                return entitiesAllCache
            }
        } else {
            val key = world.lowercase()
            entitiesWorldCache[key]?.let { return it }
            val sorted = all.filter { it.world.equals(world, ignoreCase = true) }
                .sortedWith(compareBy({ it.type.lowercase() }, { it.uuid }))
            entitiesWorldCache[key] = sorted
            sorted
        }
    }

    fun entityCount(): Int = entities.size

    fun entityCount(world: String): Int {
        if (world.isBlank()) return 0
        return worldEntityCounts[world.lowercase()] ?: 0
    }

    fun joinPlayer(
        name: String,
        world: String,
        x: Double,
        y: Double,
        z: Double
    ): StandalonePlayer {
        createWorld(world)
        val player = StandalonePlayer(
            uuid = UUID.randomUUID().toString(),
            name = name,
            world = world,
            x = x,
            y = y,
            z = z
        )
        playersByName[name.lowercase()] = player
        playersDirty = true
        inventoriesByOwner.computeIfAbsent(name.lowercase()) { mutableMapOf() }
        entities[player.uuid] = StandaloneEntity(
            uuid = player.uuid,
            type = "player",
            world = world,
            x = x,
            y = y,
            z = z
        )
        incrementWorldEntityCount(world)
        entitiesAllDirty = true
        entitiesWorldCache.remove(world.lowercase())
        return player
    }

    fun leavePlayer(name: String): StandalonePlayer? {
        val key = name.lowercase()
        val player = playersByName.remove(key)
        if (player != null) {
            entities.remove(player.uuid)
            decrementWorldEntityCount(player.world)
            playersDirty = true
            entitiesAllDirty = true
            entitiesWorldCache.remove(player.world.lowercase())
        }
        return player
    }

    fun findPlayer(name: String): StandalonePlayer? {
        return playersByName[name.lowercase()]
    }

    fun movePlayer(
        name: String,
        x: Double,
        y: Double,
        z: Double,
        world: String?
    ): StandalonePlayer? {
        val key = name.lowercase()
        val current = playersByName[key] ?: return null
        val nextWorld = world ?: current.world
        createWorld(nextWorld)
        val moved = current.copy(world = nextWorld, x = x, y = y, z = z)
        playersByName[key] = moved
        playersDirty = true
        entities[current.uuid]?.let { entity ->
            entities[current.uuid] = entity.copy(world = nextWorld, x = x, y = y, z = z)
        }
        if (!current.world.equals(nextWorld, ignoreCase = true)) {
            decrementWorldEntityCount(current.world)
            incrementWorldEntityCount(nextWorld)
            entitiesWorldCache.remove(current.world.lowercase())
            entitiesWorldCache.remove(nextWorld.lowercase())
        }
        entitiesAllDirty = true
        return moved
    }

    fun createWorld(name: String, seed: Long = 0L): StandaloneWorld {
        val key = name.lowercase()
        val existing = worlds[key]
        val world = if (existing != null) {
            existing
        } else {
            val created = StandaloneWorld(name = name, seed = seed, time = 0L)
            val previous = worlds.putIfAbsent(key, created)
            worldsDirty = true
            previous ?: created
        }
        worldEntityCounts.putIfAbsent(key, 0)
        return world
    }

    fun tickWorlds() {
        worlds.replaceAll { _, world -> world.copy(time = world.time + 1L) }
        worldsDirty = true
    }

    fun spawnEntity(
        type: String,
        world: String,
        x: Double,
        y: Double,
        z: Double
    ): StandaloneEntity {
        createWorld(world)
        val entity = StandaloneEntity(
            uuid = UUID.randomUUID().toString(),
            type = type,
            world = world,
            x = x,
            y = y,
            z = z
        )
        entities[entity.uuid] = entity
        incrementWorldEntityCount(world)
        entitiesAllDirty = true
        entitiesWorldCache.remove(world.lowercase())
        return entity
    }

    fun inventory(owner: String): StandaloneInventory? {
        val key = owner.lowercase()
        val slots = inventoriesByOwner[key] ?: return null
        return StandaloneInventory(
            owner = owner,
            size = 36,
            slots = slots.toSortedMap()
        )
    }

    fun setInventoryItem(owner: String, slot: Int, itemId: String): Boolean {
        if (slot !in 0..35) return false
        val key = owner.lowercase()
        if (!playersByName.containsKey(key)) return false
        val slots = inventoriesByOwner.computeIfAbsent(key) { mutableMapOf() }
        if (itemId.equals("air", ignoreCase = true) || itemId.equals("empty", ignoreCase = true)) {
            slots.remove(slot)
        } else {
            slots[slot] = itemId
        }
        return true
    }

    fun snapshot(): StandaloneHostSnapshot {
        return StandaloneHostSnapshot(
            worlds = worlds(),
            players = players(),
            entities = entities(),
            inventories = inventoriesByOwner.entries.associate { (owner, slots) ->
                owner to slots.toMap()
            }
        )
    }

    fun restore(snapshot: StandaloneHostSnapshot) {
        worlds.clear()
        entities.clear()
        worldEntityCounts.clear()
        playersByName.clear()
        inventoriesByOwner.clear()
        worldsDirty = true
        playersDirty = true
        entitiesAllDirty = true
        entitiesWorldCache.clear()

        snapshot.worlds.forEach { world ->
            worlds[world.name.lowercase()] = world
        }
        snapshot.players.forEach { player ->
            playersByName[player.name.lowercase()] = player
        }
        snapshot.entities.forEach { entity ->
            entities[entity.uuid] = entity
            incrementWorldEntityCount(entity.world)
        }
        snapshot.inventories.forEach { (owner, slots) ->
            inventoriesByOwner[owner.lowercase()] = slots.toMutableMap()
        }

        if (worlds.isEmpty()) {
            createWorld("world")
        }
    }

    private fun incrementWorldEntityCount(world: String) {
        val key = world.lowercase()
        worldEntityCounts.compute(key) { _, current -> (current ?: 0) + 1 }
    }

    private fun decrementWorldEntityCount(world: String) {
        val key = world.lowercase()
        worldEntityCounts.compute(key) { _, current ->
            val next = (current ?: 0) - 1
            if (next <= 0) 0 else next
        }
    }
}
