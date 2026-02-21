package com.gigasoft.core

import java.util.UUID
import java.util.TreeMap
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

data class StandaloneBlock(
    val world: String,
    val x: Int,
    val y: Int,
    val z: Int,
    val blockId: String
)

data class StandaloneBlockData(
    val world: String,
    val x: Int,
    val y: Int,
    val z: Int,
    val data: Map<String, String>
)

class StandaloneHostState(
    defaultWorld: String = "world"
) {
    companion object {
        private val CASE_INSENSITIVE = String.CASE_INSENSITIVE_ORDER
        private val WORLD_COMPARATOR = Comparator<StandaloneWorld> { a, b ->
            CASE_INSENSITIVE.compare(a.name, b.name)
        }
        private val PLAYER_COMPARATOR = Comparator<StandalonePlayer> { a, b ->
            CASE_INSENSITIVE.compare(a.name, b.name)
        }
        private val ENTITY_ALL_COMPARATOR = Comparator<StandaloneEntity> { a, b ->
            val worldOrder = CASE_INSENSITIVE.compare(a.world, b.world)
            if (worldOrder != 0) return@Comparator worldOrder
            val typeOrder = CASE_INSENSITIVE.compare(a.type, b.type)
            if (typeOrder != 0) return@Comparator typeOrder
            a.uuid.compareTo(b.uuid)
        }
        private val ENTITY_WORLD_COMPARATOR = Comparator<StandaloneEntity> { a, b ->
            val typeOrder = CASE_INSENSITIVE.compare(a.type, b.type)
            if (typeOrder != 0) return@Comparator typeOrder
            a.uuid.compareTo(b.uuid)
        }
    }

    data class WorldCreateResult(
        val world: StandaloneWorld,
        val created: Boolean
    )

    private val stateLock = Any()
    private val worlds = LinkedHashMap<String, StandaloneWorld>()
    private val entities = LinkedHashMap<String, StandaloneEntity>()
    private val entityData = LinkedHashMap<String, MutableMap<String, String>>()
    private val worldEntityCounts = LinkedHashMap<String, Int>()
    private val playersByName = LinkedHashMap<String, StandalonePlayer>()
    private val inventoriesByOwner = LinkedHashMap<String, MutableMap<Int, String>>()
    private val blocks = LinkedHashMap<String, StandaloneBlock>()
    private val blockData = LinkedHashMap<String, MutableMap<String, String>>()
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

    fun worldCount(): Int = synchronized(stateLock) { worlds.size }

    fun worlds(): List<StandaloneWorld> {
        if (!worldsDirty) return worldsCache
        synchronized(stateLock) {
            if (!worldsDirty) return worldsCache
            worldsCache = worlds.values.sortedWith(WORLD_COMPARATOR)
            worldsDirty = false
            return worldsCache
        }
    }

    fun onlinePlayerCount(): Int = synchronized(stateLock) { playersByName.size }

    fun players(): List<StandalonePlayer> {
        if (!playersDirty) return playersCache
        synchronized(stateLock) {
            if (!playersDirty) return playersCache
            playersCache = playersByName.values.sortedWith(PLAYER_COMPARATOR)
            playersDirty = false
            return playersCache
        }
    }

    fun entities(world: String? = null): List<StandaloneEntity> {
        return if (world.isNullOrBlank()) {
            if (!entitiesAllDirty) return entitiesAllCache
            synchronized(stateLock) {
                if (!entitiesAllDirty) return entitiesAllCache
                entitiesAllCache = entities.values.sortedWith(ENTITY_ALL_COMPARATOR)
                entitiesAllDirty = false
                return entitiesAllCache
            }
        } else {
            val worldName = requiredText(world, "world")
            val key = canonicalKey(worldName)
            entitiesWorldCache[key]?.let { return it }
            val sorted = synchronized(stateLock) {
                entities.values.filter { it.world.equals(worldName, ignoreCase = true) }
                    .sortedWith(ENTITY_WORLD_COMPARATOR)
            }
            entitiesWorldCache[key] = sorted
            sorted
        }
    }

    fun findEntity(uuid: String): StandaloneEntity? {
        val entityId = uuid.trim()
        if (entityId.isEmpty()) return null
        return synchronized(stateLock) { entities[entityId] }
    }

    fun removeEntity(uuid: String): StandaloneEntity? {
        val entityId = uuid.trim()
        if (entityId.isEmpty()) return null
        return synchronized(stateLock) {
            val removed = entities.remove(entityId) ?: return@synchronized null
            entityData.remove(entityId)
            decrementWorldEntityCountUnsafe(removed.world)
            entitiesWorldCache.remove(canonicalKey(removed.world))
            entitiesAllDirty = true
            if (removed.type.equals("player", ignoreCase = true)) {
                val player = playersByName.values.firstOrNull { it.uuid == removed.uuid }
                if (player != null) {
                    playersByName.remove(canonicalKey(player.name))
                    playersDirty = true
                    inventoriesByOwner.remove(canonicalKey(player.name))
                }
            }
            removed
        }
    }

    fun entityCount(): Int = synchronized(stateLock) { entities.size }

    fun entityCount(world: String): Int {
        val worldName = world.trim()
        if (worldName.isEmpty()) return 0
        return synchronized(stateLock) { worldEntityCounts[canonicalKey(worldName)] ?: 0 }
    }

    fun hasWorld(name: String): Boolean {
        val worldName = name.trim()
        if (worldName.isEmpty()) return false
        return synchronized(stateLock) { worlds.containsKey(canonicalKey(worldName)) }
    }

    fun joinPlayer(
        name: String,
        world: String,
        x: Double,
        y: Double,
        z: Double
    ): StandalonePlayer {
        val playerName = requiredText(name, "name")
        return synchronized(stateLock) {
            val worldResult = createWorldWithStatusUnsafe(world, seed = 0L)
            val key = canonicalKey(playerName)
            val previous = playersByName[key]
            if (previous != null) {
                val oldEntity = entities.remove(previous.uuid)
                if (oldEntity != null) {
                    entityData.remove(oldEntity.uuid)
                    decrementWorldEntityCountUnsafe(oldEntity.world)
                    entitiesWorldCache.remove(canonicalKey(oldEntity.world))
                }
            }
            val nextPlayer = StandalonePlayer(
                uuid = previous?.uuid ?: UUID.randomUUID().toString(),
                name = playerName,
                world = worldResult.world.name,
                x = x,
                y = y,
                z = z
            )
            playersByName[key] = nextPlayer
            playersDirty = true
            inventoriesByOwner.computeIfAbsent(key) { mutableMapOf() }
            entities[nextPlayer.uuid] = StandaloneEntity(
                uuid = nextPlayer.uuid,
                type = "player",
                world = nextPlayer.world,
                x = x,
                y = y,
                z = z
            )
            incrementWorldEntityCountUnsafe(nextPlayer.world)
            entitiesAllDirty = true
            entitiesWorldCache.remove(canonicalKey(nextPlayer.world))
            nextPlayer
        }
    }

    fun leavePlayer(name: String): StandalonePlayer? {
        val playerName = name.trim()
        if (playerName.isEmpty()) return null
        return synchronized(stateLock) {
            val player = playersByName.remove(canonicalKey(playerName))
            if (player != null) {
                val removed = entities.remove(player.uuid)
                if (removed != null) {
                    decrementWorldEntityCountUnsafe(removed.world)
                    entitiesWorldCache.remove(canonicalKey(removed.world))
                }
                playersDirty = true
                entitiesAllDirty = true
            }
            player
        }
    }

    fun findPlayer(name: String): StandalonePlayer? {
        val playerName = name.trim()
        if (playerName.isEmpty()) return null
        return synchronized(stateLock) { playersByName[canonicalKey(playerName)] }
    }

    fun movePlayer(
        name: String,
        x: Double,
        y: Double,
        z: Double,
        world: String?
    ): StandalonePlayer? {
        val playerName = name.trim()
        if (playerName.isEmpty()) return null
        return synchronized(stateLock) {
            val key = canonicalKey(playerName)
            val current = playersByName[key] ?: return@synchronized null
            val nextWorld = if (world.isNullOrBlank()) {
                current.world
            } else {
                createWorldWithStatusUnsafe(world, seed = 0L).world.name
            }
            val moved = current.copy(world = nextWorld, x = x, y = y, z = z)
            playersByName[key] = moved
            playersDirty = true
            val previousEntity = entities[current.uuid]
            if (previousEntity != null) {
                if (!previousEntity.world.equals(nextWorld, ignoreCase = true)) {
                    decrementWorldEntityCountUnsafe(previousEntity.world)
                    incrementWorldEntityCountUnsafe(nextWorld)
                    entitiesWorldCache.remove(canonicalKey(previousEntity.world))
                    entitiesWorldCache.remove(canonicalKey(nextWorld))
                } else {
                    entitiesWorldCache.remove(canonicalKey(nextWorld))
                }
                entities[current.uuid] = previousEntity.copy(world = nextWorld, x = x, y = y, z = z)
            } else {
                entities[current.uuid] = StandaloneEntity(
                    uuid = current.uuid,
                    type = "player",
                    world = nextWorld,
                    x = x,
                    y = y,
                    z = z
                )
                incrementWorldEntityCountUnsafe(nextWorld)
                entitiesWorldCache.remove(canonicalKey(nextWorld))
            }
            entitiesAllDirty = true
            moved
        }
    }

    fun createWorld(name: String, seed: Long = 0L): StandaloneWorld {
        return createWorldWithStatus(name, seed).world
    }

    fun createWorldWithStatus(name: String, seed: Long = 0L): WorldCreateResult {
        return synchronized(stateLock) {
            createWorldWithStatusUnsafe(name, seed)
        }
    }

    fun worldTime(name: String): Long? {
        val worldName = name.trim()
        if (worldName.isEmpty()) return null
        return synchronized(stateLock) {
            worlds[canonicalKey(worldName)]?.time
        }
    }

    fun setWorldTime(name: String, time: Long): StandaloneWorld? {
        if (time < 0L) return null
        val worldName = name.trim()
        if (worldName.isEmpty()) return null
        return synchronized(stateLock) {
            val key = canonicalKey(worldName)
            val current = worlds[key] ?: return@synchronized null
            val updated = current.copy(time = time)
            worlds[key] = updated
            worldsDirty = true
            updated
        }
    }

    fun tickWorlds() {
        synchronized(stateLock) {
            for ((key, world) in worlds) {
                worlds[key] = world.copy(time = world.time + 1L)
            }
            worldsDirty = true
        }
    }

    fun spawnEntity(
        type: String,
        world: String,
        x: Double,
        y: Double,
        z: Double
    ): StandaloneEntity {
        val entityType = requiredText(type, "type")
        return synchronized(stateLock) {
            val worldResult = createWorldWithStatusUnsafe(world, seed = 0L)
            val entity = StandaloneEntity(
                uuid = UUID.randomUUID().toString(),
                type = entityType,
                world = worldResult.world.name,
                x = x,
                y = y,
                z = z
            )
            val previous = entities.put(entity.uuid, entity)
            if (previous != null) {
                entityData.remove(previous.uuid)
                decrementWorldEntityCountUnsafe(previous.world)
                entitiesWorldCache.remove(canonicalKey(previous.world))
            }
            incrementWorldEntityCountUnsafe(entity.world)
            entitiesAllDirty = true
            entitiesWorldCache.remove(canonicalKey(entity.world))
            entity
        }
    }

    fun inventory(owner: String): StandaloneInventory? {
        val ownerName = owner.trim()
        if (ownerName.isEmpty()) return null
        return synchronized(stateLock) {
            val key = canonicalKey(ownerName)
            val slots = inventoriesByOwner[key] ?: return@synchronized null
            val canonicalOwner = playersByName[key]?.name ?: ownerName
            StandaloneInventory(
                owner = canonicalOwner,
                size = 36,
                slots = slots.toSortedMap()
            )
        }
    }

    fun inventoryItem(owner: String, slot: Int): String? {
        if (slot !in 0..35) return null
        val ownerName = owner.trim()
        if (ownerName.isEmpty()) return null
        return synchronized(stateLock) {
            inventoriesByOwner[canonicalKey(ownerName)]?.get(slot)
        }
    }

    fun setInventoryItem(owner: String, slot: Int, itemId: String): Boolean {
        if (slot !in 0..35) return false
        val ownerName = owner.trim()
        if (ownerName.isEmpty()) return false
        val value = itemId.trim()
        if (value.isEmpty()) return false
        return synchronized(stateLock) {
            val key = canonicalKey(ownerName)
            if (!playersByName.containsKey(key)) return@synchronized false
            val slots = inventoriesByOwner.computeIfAbsent(key) { mutableMapOf() }
            if (value.equals("air", ignoreCase = true) || value.equals("empty", ignoreCase = true)) {
                slots.remove(slot)
            } else {
                slots[slot] = value
            }
            true
        }
    }

    fun givePlayerItem(owner: String, itemId: String, count: Int): Int {
        if (count <= 0) return 0
        val ownerName = owner.trim()
        if (ownerName.isEmpty()) return 0
        val value = itemId.trim()
        if (value.isEmpty() || value.equals("air", ignoreCase = true) || value.equals("empty", ignoreCase = true)) return 0
        return synchronized(stateLock) {
            val key = canonicalKey(ownerName)
            if (!playersByName.containsKey(key)) return@synchronized 0
            val slots = inventoriesByOwner.computeIfAbsent(key) { mutableMapOf() }
            var inserted = 0
            repeat(count) {
                val freeSlot = firstFreeInventorySlotUnsafe(slots) ?: return@repeat
                slots[freeSlot] = value
                inserted += 1
            }
            inserted
        }
    }

    fun snapshot(): StandaloneHostSnapshot {
        return synchronized(stateLock) {
            StandaloneHostSnapshot(
                worlds = worlds.values.sortedWith(WORLD_COMPARATOR),
                players = playersByName.values.sortedWith(PLAYER_COMPARATOR),
                entities = entities.values.sortedWith(ENTITY_ALL_COMPARATOR),
                entityData = entityData
                    .filterValues { it.isNotEmpty() }
                    .toSortedMap()
                    .mapValues { (_, values) -> values.toSortedMap() },
                inventories = inventoriesByOwner.toSortedMap().mapValues { (_, slots) -> slots.toSortedMap() },
                blocks = blocks.values.sortedWith(
                    compareBy<StandaloneBlock, String>(String.CASE_INSENSITIVE_ORDER) { it.world }
                        .thenBy { it.y }
                        .thenBy { it.z }
                        .thenBy { it.x }
                ),
                blockData = blockData.entries
                    .filter { it.value.isNotEmpty() }
                    .mapNotNull { (key, values) ->
                        val parts = key.split(":")
                        if (parts.size != 4) return@mapNotNull null
                        val world = parts[0]
                        val x = parts[1].toIntOrNull() ?: return@mapNotNull null
                        val y = parts[2].toIntOrNull() ?: return@mapNotNull null
                        val z = parts[3].toIntOrNull() ?: return@mapNotNull null
                        StandaloneBlockData(
                            world = world,
                            x = x,
                            y = y,
                            z = z,
                            data = values.toSortedMap()
                        )
                    }
            )
        }
    }

    fun restore(snapshot: StandaloneHostSnapshot) {
        synchronized(stateLock) {
            worlds.clear()
            entities.clear()
            entityData.clear()
            worldEntityCounts.clear()
            playersByName.clear()
            inventoriesByOwner.clear()
            blocks.clear()
            blockData.clear()

            snapshot.worlds.forEach { world ->
                val name = world.name.trim()
                if (name.isNotEmpty()) {
                    val key = canonicalKey(name)
                    if (!worlds.containsKey(key)) {
                        worlds[key] = world.copy(name = name)
                    }
                }
            }

            snapshot.players.forEach { player ->
                val name = player.name.trim()
                val uuid = player.uuid.trim()
                if (name.isEmpty() || uuid.isEmpty()) return@forEach
                val world = createWorldWithStatusUnsafe(player.world, seed = 0L).world.name
                val key = canonicalKey(name)
                if (!playersByName.containsKey(key)) {
                    playersByName[key] = player.copy(name = name, uuid = uuid, world = world)
                }
            }

            snapshot.entities.forEach { entity ->
                val uuid = entity.uuid.trim()
                val type = entity.type.trim()
                if (uuid.isEmpty() || type.isEmpty()) return@forEach
                if (entities.containsKey(uuid)) return@forEach
                val world = createWorldWithStatusUnsafe(entity.world, seed = 0L).world.name
                entities[uuid] = entity.copy(uuid = uuid, type = type, world = world)
            }

            snapshot.entityData.forEach { (uuidRaw, values) ->
                val uuid = uuidRaw.trim()
                if (uuid.isEmpty()) return@forEach
                if (!entities.containsKey(uuid)) return@forEach
                val sanitized = linkedMapOf<String, String>()
                values.forEach { (k, v) ->
                    val keyName = k.trim()
                    val value = v.trim()
                    if (keyName.isNotEmpty() && value.isNotEmpty()) {
                        sanitized[keyName] = value
                    }
                }
                if (sanitized.isNotEmpty()) {
                    entityData[uuid] = sanitized
                }
            }

            playersByName.values.forEach { player ->
                entities[player.uuid] = StandaloneEntity(
                    uuid = player.uuid,
                    type = "player",
                    world = player.world,
                    x = player.x,
                    y = player.y,
                    z = player.z
                )
                entityData.remove(player.uuid)
            }

            snapshot.inventories.forEach { (owner, slots) ->
                val ownerName = owner.trim()
                if (ownerName.isEmpty()) return@forEach
                val ownerKey = canonicalKey(ownerName)
                if (!playersByName.containsKey(ownerKey)) return@forEach
                val sanitized = TreeMap<Int, String>()
                slots.forEach { (slot, itemId) ->
                    val value = itemId.trim()
                    if (slot in 0..35 && value.isNotEmpty() && !value.equals("air", ignoreCase = true) && !value.equals("empty", ignoreCase = true)) {
                        sanitized[slot] = value
                    }
                }
                inventoriesByOwner[ownerKey] = sanitized.toMutableMap()
            }

            snapshot.blocks.forEach { block ->
                val worldName = block.world.trim()
                val blockId = block.blockId.trim()
                if (worldName.isEmpty() || blockId.isEmpty()) return@forEach
                if (blockId.equals("air", ignoreCase = true) || blockId.equals("empty", ignoreCase = true)) return@forEach
                val resolvedWorld = createWorldWithStatusUnsafe(worldName, seed = 0L).world.name
                val resolved = block.copy(world = resolvedWorld, blockId = blockId)
                blocks[blockKey(resolved.world, resolved.x, resolved.y, resolved.z)] = resolved
            }

            snapshot.blockData.forEach { entry ->
                val worldName = entry.world.trim()
                if (worldName.isEmpty()) return@forEach
                val key = blockKey(worldName, entry.x, entry.y, entry.z)
                if (!blocks.containsKey(key)) return@forEach
                val sanitized = linkedMapOf<String, String>()
                entry.data.forEach { (k, v) ->
                    val keyName = k.trim()
                    val value = v.trim()
                    if (keyName.isNotEmpty() && value.isNotEmpty()) {
                        sanitized[keyName] = value
                    }
                }
                if (sanitized.isNotEmpty()) {
                    blockData[key] = sanitized
                }
            }

            if (worlds.isEmpty()) {
                createWorldWithStatusUnsafe("world", 0L)
            }

            rebuildWorldEntityCountsUnsafe()
            worldsDirty = true
            playersDirty = true
            entitiesAllDirty = true
            entitiesWorldCache.clear()
        }
    }

    private fun createWorldWithStatusUnsafe(name: String, seed: Long): WorldCreateResult {
        val worldName = requiredText(name, "world")
        val key = canonicalKey(worldName)
        val existing = worlds[key]
        if (existing != null) {
            worldEntityCounts.putIfAbsent(key, 0)
            return WorldCreateResult(world = existing, created = false)
        }
        val created = StandaloneWorld(name = worldName, seed = seed, time = 0L)
        worlds[key] = created
        worldEntityCounts.putIfAbsent(key, 0)
        worldsDirty = true
        return WorldCreateResult(world = created, created = true)
    }

    private fun incrementWorldEntityCountUnsafe(world: String) {
        val key = canonicalKey(world)
        val current = worldEntityCounts[key] ?: 0
        worldEntityCounts[key] = current + 1
    }

    private fun decrementWorldEntityCountUnsafe(world: String) {
        val key = canonicalKey(world)
        val current = worldEntityCounts[key] ?: 0
        worldEntityCounts[key] = if (current <= 1) 0 else current - 1
    }

    private fun rebuildWorldEntityCountsUnsafe() {
        worldEntityCounts.clear()
        worlds.keys.forEach { worldEntityCounts[it] = 0 }
        entities.values.forEach { entity ->
            val key = canonicalKey(entity.world)
            worldEntityCounts[key] = (worldEntityCounts[key] ?: 0) + 1
        }
    }

    private fun canonicalKey(value: String): String = value.trim().lowercase()

    private fun requiredText(value: String, field: String): String {
        val trimmed = value.trim()
        require(trimmed.isNotEmpty()) { "$field must not be blank" }
        return trimmed
    }

    fun blockAt(world: String, x: Int, y: Int, z: Int): StandaloneBlock? {
        val worldName = world.trim()
        if (worldName.isEmpty()) return null
        return synchronized(stateLock) {
            blocks[blockKey(worldName, x, y, z)]
        }
    }

    fun setBlock(world: String, x: Int, y: Int, z: Int, blockId: String): StandaloneBlock? {
        val worldName = requiredText(world, "world")
        val block = requiredText(blockId, "blockId")
        if (block.equals("air", ignoreCase = true) || block.equals("empty", ignoreCase = true)) {
            return breakBlock(worldName, x, y, z)
        }
        return synchronized(stateLock) {
            val resolvedWorld = createWorldWithStatusUnsafe(worldName, seed = 0L).world.name
            val next = StandaloneBlock(
                world = resolvedWorld,
                x = x,
                y = y,
                z = z,
                blockId = block
            )
            val key = blockKey(resolvedWorld, x, y, z)
            val previous = blocks[key]
            blocks[key] = next
            if (previous != null && !previous.blockId.equals(next.blockId, ignoreCase = true)) {
                blockData.remove(key)
            }
            next
        }
    }

    fun breakBlock(world: String, x: Int, y: Int, z: Int): StandaloneBlock? {
        val worldName = world.trim()
        if (worldName.isEmpty()) return null
        return synchronized(stateLock) {
            val key = blockKey(worldName, x, y, z)
            blockData.remove(key)
            blocks.remove(key)
        }
    }

    fun blockData(world: String, x: Int, y: Int, z: Int): Map<String, String>? {
        val worldName = world.trim()
        if (worldName.isEmpty()) return null
        return synchronized(stateLock) {
            val key = blockKey(worldName, x, y, z)
            if (!blocks.containsKey(key)) return@synchronized null
            blockData[key]?.toMap() ?: emptyMap()
        }
    }

    fun setBlockData(world: String, x: Int, y: Int, z: Int, data: Map<String, String>): Map<String, String>? {
        val worldName = world.trim()
        if (worldName.isEmpty()) return null
        return synchronized(stateLock) {
            val key = blockKey(worldName, x, y, z)
            if (!blocks.containsKey(key)) return@synchronized null
            val sanitized = linkedMapOf<String, String>()
            data.forEach { (k, v) ->
                val keyName = k.trim()
                val value = v.trim()
                if (keyName.isNotEmpty() && value.isNotEmpty()) {
                    sanitized[keyName] = value
                }
            }
            if (sanitized.isEmpty()) {
                blockData.remove(key)
                return@synchronized emptyMap()
            }
            blockData[key] = sanitized
            sanitized.toMap()
        }
    }

    fun entityData(uuid: String): Map<String, String>? {
        val entityId = uuid.trim()
        if (entityId.isEmpty()) return null
        return synchronized(stateLock) {
            if (!entities.containsKey(entityId)) return@synchronized null
            entityData[entityId]?.toMap() ?: emptyMap()
        }
    }

    fun setEntityData(uuid: String, data: Map<String, String>): Map<String, String>? {
        val entityId = uuid.trim()
        if (entityId.isEmpty()) return null
        return synchronized(stateLock) {
            if (!entities.containsKey(entityId)) return@synchronized null
            val entity = entities[entityId]
            if (entity != null && entity.type.equals("player", ignoreCase = true)) {
                return@synchronized null
            }
            val sanitized = linkedMapOf<String, String>()
            data.forEach { (k, v) ->
                val keyName = k.trim()
                val value = v.trim()
                if (keyName.isNotEmpty() && value.isNotEmpty()) {
                    sanitized[keyName] = value
                }
            }
            if (sanitized.isEmpty()) {
                entityData.remove(entityId)
                return@synchronized emptyMap()
            }
            entityData[entityId] = sanitized
            sanitized.toMap()
        }
    }

    private fun firstFreeInventorySlotUnsafe(slots: Map<Int, String>): Int? {
        for (slot in 0..35) {
            if (!slots.containsKey(slot)) return slot
        }
        return null
    }

    private fun blockKey(world: String, x: Int, y: Int, z: Int): String {
        return "${canonicalKey(world)}:$x:$y:$z"
    }
}
