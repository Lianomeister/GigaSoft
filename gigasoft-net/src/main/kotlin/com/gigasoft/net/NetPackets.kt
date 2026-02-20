package com.gigasoft.net

data class SessionRequestPacket(
    val protocol: String = "gigasoft-standalone-net",
    val version: Int = 1,
    val requestId: String? = null,
    val action: String,
    val payload: Map<String, String> = emptyMap()
)

data class SessionResponsePacket(
    val protocol: String = "gigasoft-standalone-net",
    val version: Int = 1,
    val requestId: String? = null,
    val success: Boolean,
    val code: String,
    val message: String,
    val payload: Map<String, String> = emptyMap()
)
