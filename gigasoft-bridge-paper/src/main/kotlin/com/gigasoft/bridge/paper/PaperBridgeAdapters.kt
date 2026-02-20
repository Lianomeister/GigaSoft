package com.gigasoft.bridge.paper

import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Entity
import org.bukkit.inventory.Inventory

// Minimal bridge DTOs for V1 so DSL-facing code can remain decoupled from Paper internals.
data class WorldRef(val name: String)
data class EntityRef(val uuid: String, val type: String)
data class InventoryRef(val size: Int)

object PaperBridgeAdapters {
    fun world(world: World): WorldRef = WorldRef(world.name)
    fun entity(entity: Entity): EntityRef = EntityRef(entity.uniqueId.toString(), entity.type.name)
    fun inventory(inventory: Inventory): InventoryRef = InventoryRef(inventory.size)
    fun location(location: Location): Map<String, Double> = mapOf(
        "x" to location.x,
        "y" to location.y,
        "z" to location.z
    )
}
