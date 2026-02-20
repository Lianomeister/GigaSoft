package com.gigasoft.net

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StandaloneNetServerTest {
    private val mapper = ObjectMapper().registerKotlinModule()

    @Test
    fun `json auth grants roles and enforces admin actions`() {
        val handler = TestHandler()
        val server = StandaloneNetServer(
            config = StandaloneNetConfig(
                host = "127.0.0.1",
                port = 0,
                authRequired = true,
                sharedSecret = "player-secret",
                adminSecret = "admin-secret",
                sessionTtlSeconds = 30
            ),
            logger = {},
            handler = handler
        )
        server.start()
        try {
            val port = waitForPort(server)
            Socket("127.0.0.1", port).use { socket ->
                val reader = socket.getInputStream().bufferedReader()
                val writer = socket.getOutputStream().bufferedWriter()

                val hs = sendJson(reader, writer, action = "handshake", requestId = "1")
                assertTrue(hs.success)

                val authPlayer = sendJson(
                    reader,
                    writer,
                    action = "auth",
                    requestId = "2",
                    payload = mapOf("secret" to "player-secret")
                )
                assertEquals("player", authPlayer.payload["role"])
                val sid = authPlayer.payload["sessionId"].orEmpty()
                assertTrue(sid.isNotBlank())

                val forbidden = sendJson(
                    reader,
                    writer,
                    action = "world.create",
                    requestId = "3",
                    payload = mapOf("sessionId" to sid, "name" to "w1")
                )
                assertTrue(!forbidden.success)
                assertEquals("FORBIDDEN", forbidden.code)

                val authAdmin = sendJson(
                    reader,
                    writer,
                    action = "auth",
                    requestId = "4",
                    payload = mapOf("secret" to "admin-secret")
                )
                assertEquals("admin", authAdmin.payload["role"])
                val adminSid = authAdmin.payload["sessionId"].orEmpty()

                val created = sendJson(
                    reader,
                    writer,
                    action = "world.create",
                    requestId = "5",
                    payload = mapOf("sessionId" to adminSid, "name" to "w1", "seed" to "42")
                )
                assertTrue(created.success)
                assertEquals(1, handler.worldCreateCalls)
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun `session expires and refresh extends ttl`() {
        val handler = TestHandler()
        val server = StandaloneNetServer(
            config = StandaloneNetConfig(
                host = "127.0.0.1",
                port = 0,
                authRequired = true,
                sharedSecret = "player-secret",
                sessionTtlSeconds = 1
            ),
            logger = {},
            handler = handler
        )
        server.start()
        try {
            val port = waitForPort(server)
            Socket("127.0.0.1", port).use { socket ->
                val reader = socket.getInputStream().bufferedReader()
                val writer = socket.getOutputStream().bufferedWriter()

                val auth = sendJson(
                    reader,
                    writer,
                    action = "auth",
                    requestId = "1",
                    payload = mapOf("secret" to "player-secret")
                )
                val sid = auth.payload["sessionId"].orEmpty()
                assertTrue(sid.isNotBlank())

                Thread.sleep(1200)
                val expired = sendJson(
                    reader,
                    writer,
                    action = "whoami",
                    requestId = "2",
                    payload = mapOf("sessionId" to sid)
                )
                assertTrue(!expired.success)
                assertEquals("SESSION_EXPIRED", expired.code)

                val reauth = sendJson(
                    reader,
                    writer,
                    action = "auth",
                    requestId = "3",
                    payload = mapOf("secret" to "player-secret")
                )
                val sid2 = reauth.payload["sessionId"].orEmpty()
                val refreshed = sendJson(
                    reader,
                    writer,
                    action = "refresh",
                    requestId = "4",
                    payload = mapOf("sessionId" to sid2)
                )
                assertTrue(refreshed.success)
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun `framed transport supports negotiate and auth`() {
        val handler = TestHandler()
        val server = StandaloneNetServer(
            config = StandaloneNetConfig(
                host = "127.0.0.1",
                port = 0,
                authRequired = true,
                sharedSecret = "player-secret",
                maxFrameBytes = 64 * 1024
            ),
            logger = {},
            handler = handler
        )
        server.start()
        try {
            val port = waitForPort(server)
            Socket("127.0.0.1", port).use { socket ->
                val input = DataInputStream(socket.getInputStream())
                val output = DataOutputStream(socket.getOutputStream())

                val negotiate = sendFrame(
                    input,
                    output,
                    SessionRequestPacket(
                        action = "negotiate",
                        requestId = "n1",
                        payload = mapOf("minVersion" to "1", "maxVersion" to "1")
                    )
                )
                assertTrue(negotiate.success)
                assertEquals("NEGOTIATED", negotiate.code)

                val handshake = sendFrame(
                    input,
                    output,
                    SessionRequestPacket(action = "handshake", requestId = "n2")
                )
                assertTrue(handshake.success)

                val auth = sendFrame(
                    input,
                    output,
                    SessionRequestPacket(
                        action = "auth",
                        requestId = "n3",
                        payload = mapOf("secret" to "player-secret")
                    )
                )
                assertTrue(auth.success)
                assertEquals("player", auth.payload["role"])
            }
        } finally {
            server.stop()
        }
    }

    private fun waitForPort(server: StandaloneNetServer): Int {
        repeat(100) {
            val port = server.boundPort()
            if (port > 0) return port
            Thread.sleep(10)
        }
        error("Server did not bind port")
    }

    private fun sendJson(
        reader: java.io.BufferedReader,
        writer: java.io.BufferedWriter,
        action: String,
        requestId: String,
        payload: Map<String, String> = emptyMap()
    ): SessionResponsePacket {
        val request = SessionRequestPacket(
            requestId = requestId,
            action = action,
            payload = payload
        )
        writer.write(mapper.writeValueAsString(request))
        writer.newLine()
        writer.flush()
        val line = reader.readLine()
        return mapper.readValue(line, SessionResponsePacket::class.java)
    }

    private fun sendFrame(
        input: DataInputStream,
        output: DataOutputStream,
        request: SessionRequestPacket
    ): SessionResponsePacket {
        val payload = mapper.writeValueAsBytes(request)
        output.writeInt(payload.size)
        output.write(payload)
        output.flush()

        val len = input.readInt()
        val bytes = ByteArray(len)
        input.readFully(bytes)
        return mapper.readValue(bytes, SessionResponsePacket::class.java)
    }

    private class TestHandler : StandaloneSessionHandler {
        var worldCreateCalls: Int = 0

        override fun join(name: String, world: String, x: Double, y: Double, z: Double): SessionActionResult {
            return SessionActionResult(true, "JOINED", "ok")
        }

        override fun leave(name: String): SessionActionResult {
            return SessionActionResult(true, "LEFT", "ok")
        }

        override fun move(name: String, x: Double, y: Double, z: Double, world: String?): SessionActionResult {
            return SessionActionResult(true, "MOVED", "ok")
        }

        override fun lookup(name: String): SessionActionResult {
            return SessionActionResult(true, "FOUND", "ok")
        }

        override fun who(name: String?): SessionActionResult {
            return SessionActionResult(true, "WHOAMI", name ?: "anonymous")
        }

        override fun worldCreate(name: String, seed: Long): SessionActionResult {
            worldCreateCalls += 1
            return SessionActionResult(true, "WORLD_CREATED", "ok")
        }

        override fun entitySpawn(type: String, world: String, x: Double, y: Double, z: Double): SessionActionResult {
            return SessionActionResult(true, "ENTITY_SPAWNED", "ok")
        }

        override fun inventorySet(owner: String, slot: Int, itemId: String): SessionActionResult {
            return SessionActionResult(true, "INVENTORY_UPDATED", "ok")
        }
    }
}
