package com.clockwork.net

data class SessionActionResult(
    val success: Boolean,
    val code: String,
    val message: String,
    val payload: Map<String, String> = emptyMap()
)

data class SessionJoinContext(
    val clientBrand: String? = null,
    val clientMods: Set<String> = emptySet()
)

interface StandaloneSessionHandler {
    fun join(name: String, world: String, x: Double, y: Double, z: Double): SessionActionResult
    fun joinWithContext(
        name: String,
        world: String,
        x: Double,
        y: Double,
        z: Double,
        context: SessionJoinContext
    ): SessionActionResult = join(name, world, x, y, z)
    fun leave(name: String): SessionActionResult
    fun move(name: String, x: Double, y: Double, z: Double, world: String?): SessionActionResult
    fun lookup(name: String): SessionActionResult
    fun who(name: String?): SessionActionResult
    fun worldCreate(name: String, seed: Long): SessionActionResult
    fun entitySpawn(type: String, world: String, x: Double, y: Double, z: Double): SessionActionResult
    fun inventorySet(owner: String, slot: Int, itemId: String): SessionActionResult
}
