package com.clockwork.standalone

import com.fasterxml.jackson.databind.ObjectMapper
import com.clockwork.core.GigaStandaloneCore
import com.clockwork.core.StandaloneCoreConfig
import com.clockwork.core.StandaloneCapacityException
import com.clockwork.net.SessionActionResult
import com.clockwork.net.StandaloneNetConfig
import com.clockwork.net.StandaloneNetServer
import com.clockwork.net.StandaloneSessionHandler
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.net.Socket
import java.net.SocketException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class StandaloneNetSessionIntegrationTest {
    private val mapper = ObjectMapper()

    @Test
    fun `net session supports handshake auth hello move and lookup against core`() {
        val root = Files.createTempDirectory("clockwork-standalone-net-it")
        val core = GigaStandaloneCore(
            config = StandaloneCoreConfig(
                pluginsDirectory = root.resolve("plugins"),
                dataDirectory = root.resolve("data"),
                tickPeriodMillis = 1L,
                autoSaveEveryTicks = 0L
            ),
            logger = {}
        )
        core.start()
        val net = StandaloneNetServer(
            config = StandaloneNetConfig(
                host = "127.0.0.1",
                port = 0,
                authRequired = true,
                sharedSecret = "player-secret",
                sessionTtlSeconds = 60
            ),
            logger = {},
            handler = coreHandler(core)
        )
        net.start()
        try {
            val port = waitForPort(net)
            Socket("127.0.0.1", port).use { socket ->
                val reader = socket.getInputStream().bufferedReader()
                val writer = socket.getOutputStream().bufferedWriter()

                val hs = sendJson(reader, writer, "handshake", "1")
                assertTrue(hs.success)
                assertEquals("HANDSHAKE_OK", hs.code)

                val auth = sendJson(
                    reader,
                    writer,
                    "auth",
                    "2",
                    payload = mapOf("secret" to "player-secret")
                )
                assertTrue(auth.success)
                val sid = auth.payload["sessionId"].orEmpty()
                assertTrue(sid.isNotBlank())

                val hello = sendJson(
                    reader,
                    writer,
                    "hello",
                    "3",
                    payload = mapOf(
                        "sessionId" to sid,
                        "name" to "Alex",
                        "world" to "world",
                        "x" to "0",
                        "y" to "64",
                        "z" to "0"
                    )
                )
                assertTrue(hello.success)
                assertEquals("JOINED", hello.code)

                val move = sendJson(
                    reader,
                    writer,
                    "move",
                    "4",
                    payload = mapOf(
                        "sessionId" to sid,
                        "x" to "4",
                        "y" to "65",
                        "z" to "6"
                    )
                )
                assertTrue(move.success)
                assertEquals("MOVED", move.code)

                val lookup = sendJson(
                    reader,
                    writer,
                    "lookup",
                    "5",
                    payload = mapOf("sessionId" to sid, "name" to "Alex")
                )
                assertTrue(lookup.success)
                assertEquals("FOUND", lookup.code)
                assertEquals("4.0", lookup.payload["x"])
                assertEquals("65.0", lookup.payload["y"])
                assertEquals("6.0", lookup.payload["z"])
            }
        } finally {
            net.stop()
            core.stop()
        }
    }

    @Test
    fun `net hello returns server full when max players reached`() {
        val root = Files.createTempDirectory("clockwork-standalone-net-full-it")
        val core = GigaStandaloneCore(
            config = StandaloneCoreConfig(
                pluginsDirectory = root.resolve("plugins"),
                dataDirectory = root.resolve("data"),
                maxPlayers = 1,
                tickPeriodMillis = 1L,
                autoSaveEveryTicks = 0L
            ),
            logger = {}
        )
        core.start()
        val net = StandaloneNetServer(
            config = StandaloneNetConfig(
                host = "127.0.0.1",
                port = 0,
                authRequired = true,
                sharedSecret = "player-secret",
                sessionTtlSeconds = 60
            ),
            logger = {},
            handler = coreHandler(core)
        )
        net.start()
        try {
            val port = waitForPort(net)
            Socket("127.0.0.1", port).use { socket ->
                val reader = socket.getInputStream().bufferedReader()
                val writer = socket.getOutputStream().bufferedWriter()

                val auth = sendJson(reader, writer, "auth", "1", payload = mapOf("secret" to "player-secret"))
                assertTrue(auth.success)
                val sid = auth.payload["sessionId"].orEmpty()
                assertTrue(sid.isNotBlank())

                val hello1 = sendJson(
                    reader,
                    writer,
                    "hello",
                    "2",
                    payload = mapOf("sessionId" to sid, "name" to "Alex", "world" to "world")
                )
                assertTrue(hello1.success)
                assertEquals("JOINED", hello1.code)

                val hello2 = sendJson(
                    reader,
                    writer,
                    "hello",
                    "3",
                    payload = mapOf("sessionId" to sid, "name" to "Steve", "world" to "world")
                )
                assertTrue(!hello2.success)
                assertEquals("SERVER_FULL", hello2.code)
            }
        } finally {
            net.stop()
            core.stop()
        }
    }

    @Test
    fun `net session enforces admin role for world entity and inventory actions`() {
        val root = Files.createTempDirectory("clockwork-standalone-net-admin-it")
        val core = GigaStandaloneCore(
            config = StandaloneCoreConfig(
                pluginsDirectory = root.resolve("plugins"),
                dataDirectory = root.resolve("data"),
                tickPeriodMillis = 1L,
                autoSaveEveryTicks = 0L
            ),
            logger = {}
        )
        core.start()
        val net = StandaloneNetServer(
            config = StandaloneNetConfig(
                host = "127.0.0.1",
                port = 0,
                authRequired = true,
                sharedSecret = "player-secret",
                adminSecret = "admin-secret",
                sessionTtlSeconds = 60
            ),
            logger = {},
            handler = coreHandler(core)
        )
        net.start()
        try {
            val port = waitForPort(net)
            Socket("127.0.0.1", port).use { socket ->
                val reader = socket.getInputStream().bufferedReader()
                val writer = socket.getOutputStream().bufferedWriter()

                val playerAuth = sendJson(
                    reader,
                    writer,
                    "auth",
                    "1",
                    payload = mapOf("secret" to "player-secret")
                )
                assertTrue(playerAuth.success)
                assertEquals("player", playerAuth.payload["role"])
                val playerSid = playerAuth.payload["sessionId"].orEmpty()
                assertTrue(playerSid.isNotBlank())

                val forbidden = sendJson(
                    reader,
                    writer,
                    "world.create",
                    "2",
                    payload = mapOf("sessionId" to playerSid, "name" to "admin-world")
                )
                assertTrue(!forbidden.success)
                assertEquals("FORBIDDEN", forbidden.code)

                val adminAuth = sendJson(
                    reader,
                    writer,
                    "auth",
                    "3",
                    payload = mapOf("secret" to "admin-secret")
                )
                assertTrue(adminAuth.success)
                assertEquals("admin", adminAuth.payload["role"])
                val adminSid = adminAuth.payload["sessionId"].orEmpty()
                assertTrue(adminSid.isNotBlank())

                val worldCreate = sendJson(
                    reader,
                    writer,
                    "world.create",
                    "4",
                    payload = mapOf("sessionId" to adminSid, "name" to "admin-world", "seed" to "7")
                )
                assertTrue(worldCreate.success)
                assertEquals("WORLD_CREATED", worldCreate.code)

                val entitySpawn = sendJson(
                    reader,
                    writer,
                    "entity.spawn",
                    "5",
                    payload = mapOf(
                        "sessionId" to adminSid,
                        "type" to "zombie",
                        "world" to "admin-world",
                        "x" to "1",
                        "y" to "65",
                        "z" to "2"
                    )
                )
                assertTrue(entitySpawn.success)
                assertEquals("ENTITY_SPAWNED", entitySpawn.code)

                val hello = sendJson(
                    reader,
                    writer,
                    "hello",
                    "6",
                    payload = mapOf(
                        "sessionId" to adminSid,
                        "name" to "Alex",
                        "world" to "admin-world",
                        "x" to "0",
                        "y" to "64",
                        "z" to "0"
                    )
                )
                assertTrue(hello.success)

                val inventorySet = sendJson(
                    reader,
                    writer,
                    "inventory.set",
                    "7",
                    payload = mapOf(
                        "sessionId" to adminSid,
                        "owner" to "Alex",
                        "slot" to "1",
                        "itemId" to "stone"
                    )
                )
                assertTrue(inventorySet.success)
                assertEquals("INVENTORY_UPDATED", inventorySet.code)

                assertTrue(core.worlds().any { it.name == "admin-world" })
                assertTrue(core.entities("admin-world").isNotEmpty())
                assertEquals("stone", core.inventory("Alex")?.slots?.get(1))
            }
        } finally {
            net.stop()
            core.stop()
        }
    }

    @Test
    fun `net session supports refresh revoke and expiration lifecycle`() {
        val root = Files.createTempDirectory("clockwork-standalone-net-session-it")
        val core = GigaStandaloneCore(
            config = StandaloneCoreConfig(
                pluginsDirectory = root.resolve("plugins"),
                dataDirectory = root.resolve("data"),
                tickPeriodMillis = 1L,
                autoSaveEveryTicks = 0L
            ),
            logger = {}
        )
        core.start()
        val net = StandaloneNetServer(
            config = StandaloneNetConfig(
                host = "127.0.0.1",
                port = 0,
                authRequired = true,
                sharedSecret = "player-secret",
                sessionTtlSeconds = 1
            ),
            logger = {},
            handler = coreHandler(core)
        )
        net.start()
        try {
            val port = waitForPort(net)
            Socket("127.0.0.1", port).use { socket ->
                val reader = socket.getInputStream().bufferedReader()
                val writer = socket.getOutputStream().bufferedWriter()

                val auth = sendJson(
                    reader,
                    writer,
                    "auth",
                    "1",
                    payload = mapOf("secret" to "player-secret")
                )
                assertTrue(auth.success)
                val sid = auth.payload["sessionId"].orEmpty()
                assertTrue(sid.isNotBlank())

                val refreshed = sendJson(
                    reader,
                    writer,
                    "refresh",
                    "2",
                    payload = mapOf("sessionId" to sid)
                )
                assertTrue(refreshed.success)
                assertEquals("REFRESHED", refreshed.code)

                val whoami = sendJson(
                    reader,
                    writer,
                    "whoami",
                    "3",
                    payload = mapOf("sessionId" to sid)
                )
                assertTrue(whoami.success)

                val revoked = sendJson(
                    reader,
                    writer,
                    "revoke",
                    "4",
                    payload = mapOf("sessionId" to sid)
                )
                assertTrue(revoked.success)
                assertEquals("REVOKED", revoked.code)

                val invalidAfterRevoke = sendJson(
                    reader,
                    writer,
                    "whoami",
                    "5",
                    payload = mapOf("sessionId" to sid)
                )
                assertTrue(!invalidAfterRevoke.success)
                assertEquals("UNAUTHORIZED", invalidAfterRevoke.code)

                val auth2 = sendJson(
                    reader,
                    writer,
                    "auth",
                    "6",
                    payload = mapOf("secret" to "player-secret")
                )
                assertTrue(auth2.success)
                val sid2 = auth2.payload["sessionId"].orEmpty()
                assertTrue(sid2.isNotBlank())

                Thread.sleep(1200)
                val expired = sendJson(
                    reader,
                    writer,
                    "whoami",
                    "7",
                    payload = mapOf("sessionId" to sid2)
                )
                assertTrue(!expired.success)
                assertEquals("SESSION_EXPIRED", expired.code)
            }
        } finally {
            net.stop()
            core.stop()
        }
    }

    @Test
    fun `framed transport supports negotiate handshake and auth lifecycle`() {
        val root = Files.createTempDirectory("clockwork-standalone-net-framed-it")
        val core = GigaStandaloneCore(
            config = StandaloneCoreConfig(
                pluginsDirectory = root.resolve("plugins"),
                dataDirectory = root.resolve("data"),
                tickPeriodMillis = 1L,
                autoSaveEveryTicks = 0L
            ),
            logger = {}
        )
        core.start()
        val net = StandaloneNetServer(
            config = StandaloneNetConfig(
                host = "127.0.0.1",
                port = 0,
                authRequired = true,
                sharedSecret = "player-secret",
                sessionTtlSeconds = 60,
                maxFrameBytes = 64 * 1024
            ),
            logger = {},
            handler = coreHandler(core)
        )
        net.start()
        try {
            val port = waitForPort(net)
            Socket("127.0.0.1", port).use { socket ->
                val input = DataInputStream(socket.getInputStream())
                val output = DataOutputStream(socket.getOutputStream())

                val negotiated = sendFrame(
                    input = input,
                    output = output,
                    request = mapOf(
                        "protocol" to "clockwork-standalone-net",
                        "version" to 1,
                        "requestId" to "f1",
                        "action" to "negotiate",
                        "payload" to mapOf("minVersion" to "1", "maxVersion" to "1")
                    )
                )
                assertTrue(negotiated.success)
                assertEquals("NEGOTIATED", negotiated.code)
                assertEquals("1", negotiated.payload["selectedVersion"])

                val handshake = sendFrame(
                    input = input,
                    output = output,
                    request = mapOf(
                        "protocol" to "clockwork-standalone-net",
                        "version" to 1,
                        "requestId" to "f2",
                        "action" to "handshake",
                        "payload" to emptyMap<String, String>()
                    )
                )
                assertTrue(handshake.success)
                assertEquals("HANDSHAKE_OK", handshake.code)

                val auth = sendFrame(
                    input = input,
                    output = output,
                    request = mapOf(
                        "protocol" to "clockwork-standalone-net",
                        "version" to 1,
                        "requestId" to "f3",
                        "action" to "auth",
                        "payload" to mapOf("secret" to "player-secret")
                    )
                )
                assertTrue(auth.success)
                assertEquals("player", auth.payload["role"])
                assertTrue(auth.payload["sessionId"].orEmpty().isNotBlank())
            }
        } finally {
            net.stop()
            core.stop()
        }
    }

    @Test
    fun `framed transport rejects oversize frames`() {
        val root = Files.createTempDirectory("clockwork-standalone-net-framed-limit-it")
        val core = GigaStandaloneCore(
            config = StandaloneCoreConfig(
                pluginsDirectory = root.resolve("plugins"),
                dataDirectory = root.resolve("data"),
                tickPeriodMillis = 1L,
                autoSaveEveryTicks = 0L
            ),
            logger = {}
        )
        core.start()
        val net = StandaloneNetServer(
            config = StandaloneNetConfig(
                host = "127.0.0.1",
                port = 0,
                authRequired = false,
                maxFrameBytes = 128
            ),
            logger = {},
            handler = coreHandler(core)
        )
        net.start()
        try {
            val port = waitForPort(net)
            Socket("127.0.0.1", port).use { socket ->
                val input = DataInputStream(socket.getInputStream())
                val output = DataOutputStream(socket.getOutputStream())

                val oversizedPayload = ByteArray(512) { 'A'.code.toByte() }
                output.writeInt(oversizedPayload.size)
                output.write(oversizedPayload)
                output.flush()

                val thrown = assertFails {
                    input.readInt()
                }
                assertTrue(
                    thrown is EOFException || thrown is SocketException,
                    "Expected EOF/SocketException but got ${thrown::class.java.name}"
                )
            }
        } finally {
            net.stop()
            core.stop()
        }
    }

    @Test
    fun `text transport rejects oversize lines`() {
        val root = Files.createTempDirectory("clockwork-standalone-net-text-limit-it")
        val core = GigaStandaloneCore(
            config = StandaloneCoreConfig(
                pluginsDirectory = root.resolve("plugins"),
                dataDirectory = root.resolve("data"),
                tickPeriodMillis = 1L,
                autoSaveEveryTicks = 0L
            ),
            logger = {}
        )
        core.start()
        val net = StandaloneNetServer(
            config = StandaloneNetConfig(
                host = "127.0.0.1",
                port = 0,
                authRequired = false,
                maxTextLineBytes = 64
            ),
            logger = {},
            handler = coreHandler(core)
        )
        net.start()
        try {
            val port = waitForPort(net)
            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 1_000
                val reader = socket.getInputStream().bufferedReader()
                val writer = socket.getOutputStream().bufferedWriter()

                writer.write("Z".repeat(512))
                writer.newLine()
                writer.flush()

                assertEquals(null, reader.readLine())
            }
        } finally {
            net.stop()
            core.stop()
        }
    }

    private fun coreHandler(core: GigaStandaloneCore): StandaloneSessionHandler {
        return object : StandaloneSessionHandler {
            override fun join(name: String, world: String, x: Double, y: Double, z: Double): SessionActionResult {
                val player = try {
                    core.joinPlayer(name, world, x, y, z)
                } catch (limit: StandaloneCapacityException) {
                    return SessionActionResult(false, limit.code, limit.message ?: "capacity limit reached")
                }
                return SessionActionResult(
                    success = true,
                    code = "JOINED",
                    message = "joined ${player.name}",
                    payload = mapOf(
                        "name" to player.name,
                        "uuid" to player.uuid,
                        "world" to player.world,
                        "x" to player.x.toString(),
                        "y" to player.y.toString(),
                        "z" to player.z.toString()
                    )
                )
            }

            override fun leave(name: String): SessionActionResult {
                val left = core.leavePlayer(name)
                return if (left == null) {
                    SessionActionResult(false, "NOT_FOUND", "player '$name' not online")
                } else {
                    SessionActionResult(true, "LEFT", "left ${left.name}")
                }
            }

            override fun move(name: String, x: Double, y: Double, z: Double, world: String?): SessionActionResult {
                val moved = core.movePlayer(name, x, y, z, world)
                return if (moved == null) {
                    SessionActionResult(false, "NOT_FOUND", "player '$name' not online")
                } else {
                    SessionActionResult(
                        success = true,
                        code = "MOVED",
                        message = "moved ${moved.name}",
                        payload = mapOf(
                            "name" to moved.name,
                            "world" to moved.world,
                            "x" to moved.x.toString(),
                            "y" to moved.y.toString(),
                            "z" to moved.z.toString()
                        )
                    )
                }
            }

            override fun lookup(name: String): SessionActionResult {
                val player = core.players().find { it.name.equals(name, ignoreCase = true) }
                return if (player == null) {
                    SessionActionResult(false, "NOT_FOUND", "player '$name' not online")
                } else {
                    SessionActionResult(
                        success = true,
                        code = "FOUND",
                        message = "found ${player.name}",
                        payload = mapOf(
                            "name" to player.name,
                            "uuid" to player.uuid,
                            "world" to player.world,
                            "x" to player.x.toString(),
                            "y" to player.y.toString(),
                            "z" to player.z.toString()
                        )
                    )
                }
            }

            override fun who(name: String?): SessionActionResult {
                return if (name.isNullOrBlank()) {
                    SessionActionResult(true, "WHOAMI", "anonymous")
                } else {
                    lookup(name)
                }
            }

            override fun worldCreate(name: String, seed: Long): SessionActionResult {
                val world = try {
                    core.createWorld(name, seed)
                } catch (limit: StandaloneCapacityException) {
                    return SessionActionResult(false, limit.code, limit.message ?: "capacity limit reached")
                }
                return SessionActionResult(true, "WORLD_CREATED", "world created ${world.name}")
            }

            override fun entitySpawn(type: String, world: String, x: Double, y: Double, z: Double): SessionActionResult {
                val entity = try {
                    core.spawnEntity(type, world, x, y, z)
                } catch (limit: StandaloneCapacityException) {
                    return SessionActionResult(false, limit.code, limit.message ?: "capacity limit reached")
                }
                return SessionActionResult(true, "ENTITY_SPAWNED", "spawned ${entity.type}")
            }

            override fun inventorySet(owner: String, slot: Int, itemId: String): SessionActionResult {
                val ok = core.setInventoryItem(owner, slot, itemId)
                return if (ok) {
                    SessionActionResult(true, "INVENTORY_UPDATED", "inventory updated")
                } else {
                    SessionActionResult(false, "INVENTORY_UPDATE_FAILED", "inventory update failed")
                }
            }
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
    ): ResponseView {
        val request = mapOf(
            "protocol" to "clockwork-standalone-net",
            "version" to 1,
            "requestId" to requestId,
            "action" to action,
            "payload" to payload
        )
        writer.write(mapper.writeValueAsString(request))
        writer.newLine()
        writer.flush()
        val line = reader.readLine()
        val decoded = mapper.readValue(line, Map::class.java)
        return ResponseView.from(decoded)
    }

    private fun sendFrame(
        input: DataInputStream,
        output: DataOutputStream,
        request: Map<String, Any>
    ): ResponseView {
        val encoded = mapper.writeValueAsBytes(request)
        output.writeInt(encoded.size)
        output.write(encoded)
        output.flush()

        val responseLen = input.readInt()
        val responseBytes = ByteArray(responseLen)
        input.readFully(responseBytes)
        val decoded = mapper.readValue(responseBytes, Map::class.java)
        return ResponseView.from(decoded)
    }

    private data class ResponseView(
        val success: Boolean,
        val code: String,
        val payload: Map<String, String>
    ) {
        companion object {
            fun from(raw: Map<*, *>): ResponseView {
                val success = raw["success"] as? Boolean ?: false
                val code = raw["code"]?.toString().orEmpty()
                val payload = (raw["payload"] as? Map<*, *>).orEmpty()
                    .entries
                    .associate { (k, v) -> k.toString() to v.toString() }
                return ResponseView(success = success, code = code, payload = payload)
            }
        }
    }
}
