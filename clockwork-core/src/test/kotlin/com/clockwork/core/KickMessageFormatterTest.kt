package com.clockwork.core

import kotlin.test.Test
import kotlin.test.assertEquals

class KickMessageFormatterTest {
    @Test
    fun `blank reason falls back to cause-aware default`() {
        assertEquals(
            "Connection timed out.",
            KickMessageFormatter.format(reason = "  ", cause = "timeout")
        )
        assertEquals(
            "Server is full. Please try again in a moment.",
            KickMessageFormatter.format(reason = "", cause = "capacity")
        )
    }

    @Test
    fun `known reason code maps to user-friendly message`() {
        assertEquals(
            "Session is no longer valid. Please reconnect.",
            KickMessageFormatter.format(reason = "UNAUTHORIZED", cause = "auth")
        )
        assertEquals(
            "Too many actions in a short time. Please slow down.",
            KickMessageFormatter.format(reason = "RATE_LIMIT", cause = "rate-limit")
        )
        assertEquals(
            "Disconnected: one or more client mods are not allowed on this server.",
            KickMessageFormatter.format(reason = "MOD_BANNED", cause = "security")
        )
    }

    @Test
    fun `reason code can include detail payload`() {
        assertEquals(
            "Server is full. Please try again in a moment. (120/120)",
            KickMessageFormatter.format(reason = "SERVER_FULL|120/120", cause = "capacity")
        )
        assertEquals(
            "Connection timed out. (read timeout 30s)",
            KickMessageFormatter.format(reason = "TIMEOUT: read timeout 30s", cause = "timeout")
        )
    }

    @Test
    fun `custom reason text is preserved`() {
        val raw = "Manual moderation action by Admin."
        assertEquals(raw, KickMessageFormatter.format(reason = raw, cause = "moderation"))
    }
}
