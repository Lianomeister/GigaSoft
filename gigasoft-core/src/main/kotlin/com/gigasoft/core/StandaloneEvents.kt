package com.gigasoft.core

data class StandaloneTickEvent(
    val tick: Long
)

data class StandalonePlayerJoinEvent(
    val player: StandalonePlayer
)

data class StandalonePlayerLeaveEvent(
    val player: StandalonePlayer
)

data class StandalonePlayerMoveEvent(
    val previous: StandalonePlayer,
    val current: StandalonePlayer
)

data class StandaloneWorldCreatedEvent(
    val world: StandaloneWorld
)

data class StandaloneEntitySpawnEvent(
    val entity: StandaloneEntity
)

data class StandaloneInventoryChangeEvent(
    val owner: String,
    val slot: Int,
    val itemId: String
)
