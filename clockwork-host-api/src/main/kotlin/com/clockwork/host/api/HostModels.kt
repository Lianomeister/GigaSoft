package com.clockwork.host.api

data class HostWorldRef(
    val name: String
)

data class HostWorldSnapshot(
    val name: String,
    val entityCount: Int
)

data class HostEntityRef(
    val uuid: String,
    val type: String
)

data class HostEntitySnapshot(
    val uuid: String,
    val type: String,
    val location: HostLocationRef
)

data class HostInventoryRef(
    val size: Int
)

data class HostInventorySnapshot(
    val owner: String,
    val size: Int,
    val nonEmptySlots: Int
)

data class HostBlockSnapshot(
    val world: String,
    val x: Int,
    val y: Int,
    val z: Int,
    val blockId: String
)

data class HostLocationRef(
    val world: String,
    val x: Double,
    val y: Double,
    val z: Double
)

data class HostPlayerSnapshot(
    val uuid: String,
    val name: String,
    val location: HostLocationRef
)

data class HostPlayerStatusSnapshot(
    val health: Double,
    val maxHealth: Double,
    val foodLevel: Int,
    val saturation: Double,
    val experienceLevel: Int,
    val experienceProgress: Double,
    val effects: Map<String, Int> = emptyMap()
)

data class HostServerSnapshot(
    val name: String,
    val version: String,
    val platformVersion: String? = null,
    val onlinePlayers: Int,
    val maxPlayers: Int,
    val worldCount: Int
)
