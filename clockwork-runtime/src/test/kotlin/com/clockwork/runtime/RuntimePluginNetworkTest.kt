package com.clockwork.runtime

import com.clockwork.api.PluginChannelSpec
import com.clockwork.api.PluginMessage
import com.clockwork.api.PluginMessageStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RuntimePluginNetworkTest {
    @Test
    fun `network channel send and subscribe works across plugins`() {
        val hub = RuntimePluginNetworkHub()
        val a = hub.viewFor("plugin-a")
        val b = hub.viewFor("plugin-b")
        val calls = mutableListOf<String>()
        assertTrue(
            a.registerChannel(
                PluginChannelSpec(
                    id = "demo:chat",
                    schemaVersion = 1,
                    maxInFlight = 8,
                    maxMessagesPerMinute = 10
                )
            )
        )
        b.subscribe("demo:chat") { msg ->
            calls += msg.payload["text"].orEmpty()
        }

        val result = a.send(
            "demo:chat",
            PluginMessage(
                channel = "demo:chat",
                schemaVersion = 1,
                payload = mapOf("text" to "hello")
            )
        )
        assertEquals(PluginMessageStatus.ACCEPTED, result.status)
        assertEquals(1, result.deliveredSubscribers)
        assertEquals(listOf("hello"), calls)
    }

    @Test
    fun `network channel rejects schema mismatch`() {
        val hub = RuntimePluginNetworkHub()
        val a = hub.viewFor("plugin-a")
        a.registerChannel(PluginChannelSpec(id = "demo:schema", schemaVersion = 2))
        val result = a.send(
            "demo:schema",
            PluginMessage(
                channel = "demo:schema",
                schemaVersion = 1,
                payload = mapOf("k" to "v")
            )
        )
        assertEquals(PluginMessageStatus.SCHEMA_MISMATCH, result.status)
    }

    @Test
    fun `network channel enforces plugin quota`() {
        val hub = RuntimePluginNetworkHub()
        val a = hub.viewFor("plugin-a")
        val b = hub.viewFor("plugin-b")
        a.registerChannel(PluginChannelSpec(id = "demo:quota", maxMessagesPerMinute = 1))
        b.subscribe("demo:quota") { }

        val first = a.send("demo:quota", PluginMessage(channel = "demo:quota", payload = mapOf("x" to "1")))
        val second = a.send("demo:quota", PluginMessage(channel = "demo:quota", payload = mapOf("x" to "2")))
        assertEquals(PluginMessageStatus.ACCEPTED, first.status)
        assertEquals(PluginMessageStatus.QUOTA_EXCEEDED, second.status)

        val stats = a.channelStats("demo:quota")
        assertEquals(1L, stats?.droppedQuota)
    }
}

