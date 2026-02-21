package com.clockwork.core

internal object KickMessageFormatter {
    fun format(reason: String, cause: String): String {
        val raw = reason.trim()
        if (raw.isEmpty()) {
            return fromCause(cause.trim())
        }
        val parsed = parseCodeAndDetail(raw)
        val mapped = fromCode(parsed.code)
        return when {
            mapped == null -> raw
            parsed.detail.isBlank() -> mapped
            else -> "$mapped (${parsed.detail})"
        }
    }

    private fun parseCodeAndDetail(raw: String): ParsedReason {
        val pipeIndex = raw.indexOf('|')
        if (pipeIndex > 0) {
            return ParsedReason(
                code = raw.substring(0, pipeIndex).trim(),
                detail = raw.substring(pipeIndex + 1).trim()
            )
        }
        val colonIndex = raw.indexOf(':')
        if (colonIndex > 0) {
            val left = raw.substring(0, colonIndex).trim()
            if (looksLikeCode(left)) {
                return ParsedReason(
                    code = left,
                    detail = raw.substring(colonIndex + 1).trim()
                )
            }
        }
        return ParsedReason(code = raw, detail = "")
    }

    private fun fromCause(cause: String): String {
        return when (cause.lowercase()) {
            "timeout", "network-timeout", "session-timeout" -> "Connection timed out."
            "security", "auth", "forbidden" -> "Disconnected by server security policy."
            "rate-limit", "spam" -> "Too many actions in a short time. Please slow down."
            "capacity", "server-full" -> "Server is full. Please try again in a moment."
            "maintenance", "restart", "shutdown" -> "Server is restarting for maintenance."
            else -> "Kicked by host."
        }
    }

    private fun fromCode(code: String): String? {
        return when (code.trim().uppercase()) {
            "SERVER_FULL", "FULL" -> "Server is full. Please try again in a moment."
            "AUTH_FAILED", "UNAUTHORIZED", "INVALID_SESSION", "SESSION_EXPIRED" -> {
                "Session is no longer valid. Please reconnect."
            }
            "RATE_LIMIT", "SPAM", "TOO_MANY_REQUESTS" -> "Too many actions in a short time. Please slow down."
            "TIMEOUT", "TIMED_OUT", "AFK_TIMEOUT" -> "Connection timed out."
            "CHEAT_DETECTED", "SECURITY_VIOLATION", "FORBIDDEN" -> "Disconnected by server security policy."
            "MOD_BANNED", "BANNED_MOD" -> "Disconnected: one or more client mods are not allowed on this server."
            "MAINTENANCE", "RESTART", "SHUTDOWN" -> "Server is restarting for maintenance."
            else -> null
        }
    }

    private fun looksLikeCode(value: String): Boolean {
        if (value.isBlank()) return false
        return value.all { it.isUpperCase() || it.isDigit() || it == '_' || it == '-' }
    }

    private data class ParsedReason(
        val code: String,
        val detail: String
    )
}
