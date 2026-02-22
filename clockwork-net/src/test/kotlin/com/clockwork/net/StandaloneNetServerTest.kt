package com.clockwork.net

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.sun.net.httpserver.HttpServer
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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

    @Test
    fun `invalid json request returns structured bad request`() {
        val server = StandaloneNetServer(
            config = StandaloneNetConfig(
                host = "127.0.0.1",
                port = 0,
                authRequired = false
            ),
            logger = {},
            handler = TestHandler()
        )
        server.start()
        try {
            val port = waitForPort(server)
            Socket("127.0.0.1", port).use { socket ->
                val reader = socket.getInputStream().bufferedReader()
                val writer = socket.getOutputStream().bufferedWriter()

                writer.write("{\"protocol\":\"clockwork-standalone-net\",\"version\":1,\"action\":")
                writer.newLine()
                writer.flush()

                val responseLine = reader.readLine()
                val response = mapper.readValue(responseLine, SessionResponsePacket::class.java)
                assertTrue(!response.success)
                assertEquals("BAD_REQUEST", response.code)
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun `oversized text line closes client connection`() {
        val server = StandaloneNetServer(
            config = StandaloneNetConfig(
                host = "127.0.0.1",
                port = 0,
                authRequired = false,
                maxTextLineBytes = 256
            ),
            logger = {},
            handler = TestHandler()
        )
        server.start()
        try {
            val port = waitForPort(server)
            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 1_000
                val reader = socket.getInputStream().bufferedReader()
                val writer = socket.getOutputStream().bufferedWriter()
                writer.write("A".repeat(4096))
                writer.newLine()
                writer.flush()
                assertNull(reader.readLine())
            }

            Socket("127.0.0.1", port).use { socket ->
                val reader = socket.getInputStream().bufferedReader()
                val writer = socket.getOutputStream().bufferedWriter()
                val ping = sendJson(reader, writer, action = "ping", requestId = "p1")
                assertTrue(ping.success)
                assertEquals("PONG", ping.code)
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun `json payload limits reject abuse payload`() {
        val server = StandaloneNetServer(
            config = StandaloneNetConfig(
                host = "127.0.0.1",
                port = 0,
                authRequired = false,
                maxJsonPayloadEntries = 2
            ),
            logger = {},
            handler = TestHandler()
        )
        server.start()
        try {
            val port = waitForPort(server)
            Socket("127.0.0.1", port).use { socket ->
                val reader = socket.getInputStream().bufferedReader()
                val writer = socket.getOutputStream().bufferedWriter()
                val payload = mapOf("a" to "1", "b" to "2", "c" to "3")
                val response = sendJson(reader, writer, "ping", "abuse-1", payload)
                assertTrue(!response.success)
                assertEquals("PAYLOAD_LIMIT", response.code)
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun `per connection request limit throttles abuse burst`() {
        val server = StandaloneNetServer(
            config = StandaloneNetConfig(
                host = "127.0.0.1",
                port = 0,
                authRequired = false,
                maxRequestsPerMinutePerConnection = 3,
                maxRequestsPerMinutePerIp = 1_000
            ),
            logger = {},
            handler = TestHandler()
        )
        server.start()
        try {
            val port = waitForPort(server)
            Socket("127.0.0.1", port).use { socket ->
                val reader = socket.getInputStream().bufferedReader()
                val writer = socket.getOutputStream().bufferedWriter()
                repeat(3) { idx ->
                    val ok = sendJson(reader, writer, "ping", "ok-$idx")
                    assertTrue(ok.success)
                }
                val denied = sendJson(reader, writer, "ping", "deny")
                assertTrue(!denied.success)
                assertEquals("RATE_LIMIT", denied.code)
                assertNull(reader.readLine())
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun `per ip concurrent session limit rejects excess clients`() {
        val server = StandaloneNetServer(
            config = StandaloneNetConfig(
                host = "127.0.0.1",
                port = 0,
                authRequired = false,
                maxConcurrentSessions = 8,
                maxSessionsPerIp = 1
            ),
            logger = {},
            handler = TestHandler()
        )
        server.start()
        try {
            val port = waitForPort(server)
            Socket("127.0.0.1", port).use { socket1 ->
                val reader1 = socket1.getInputStream().bufferedReader()
                val writer1 = socket1.getOutputStream().bufferedWriter()
                val ping1 = sendJson(reader1, writer1, "ping", "hold")
                assertTrue(ping1.success)

                Socket("127.0.0.1", port).use { socket2 ->
                    socket2.soTimeout = 1_000
                    val reader2 = socket2.getInputStream().bufferedReader()
                    val writer2 = socket2.getOutputStream().bufferedWriter()
                    val result = runCatching {
                        writer2.write("""{"protocol":"clockwork-standalone-net","version":1,"requestId":"x","action":"ping","payload":{}}""")
                        writer2.newLine()
                        writer2.flush()
                        reader2.readLine()
                    }
                    if (result.isSuccess) {
                        assertNull(result.getOrNull())
                    } else {
                        assertTrue(result.exceptionOrNull() is SocketException)
                    }
                }
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun `hello forwards client mod metadata to session handler`() {
        val handler = TestHandler()
        val server = StandaloneNetServer(
            config = StandaloneNetConfig(
                host = "127.0.0.1",
                port = 0,
                authRequired = true,
                sharedSecret = "player-secret"
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
                    requestId = "m1",
                    payload = mapOf("secret" to "player-secret")
                )
                assertTrue(auth.success)
                val sid = auth.payload["sessionId"].orEmpty()

                val hello = sendJson(
                    reader,
                    writer,
                    action = "hello",
                    requestId = "m2",
                    payload = mapOf(
                        "sessionId" to sid,
                        "name" to "Alex",
                        "clientBrand" to "forge",
                        "clientMods" to "gamma, minimap|sodium"
                    )
                )
                assertTrue(hello.success)
                val joinContext = handler.lastJoinContext
                assertEquals("forge", joinContext?.clientBrand)
                assertEquals(setOf("gamma", "minimap", "sodium"), joinContext?.clientMods)
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun `global concurrent session limit rejects excess clients`() {
        val server = StandaloneNetServer(
            config = StandaloneNetConfig(
                host = "127.0.0.1",
                port = 0,
                authRequired = false,
                maxConcurrentSessions = 1,
                maxSessionsPerIp = 8
            ),
            logger = {},
            handler = TestHandler()
        )
        server.start()
        try {
            val port = waitForPort(server)
            Socket("127.0.0.1", port).use { socket1 ->
                val reader1 = socket1.getInputStream().bufferedReader()
                val writer1 = socket1.getOutputStream().bufferedWriter()
                val ping1 = sendJson(reader1, writer1, "ping", "hold-global")
                assertTrue(ping1.success)

                Socket("127.0.0.1", port).use { socket2 ->
                    socket2.soTimeout = 1_000
                    val reader2 = socket2.getInputStream().bufferedReader()
                    val writer2 = socket2.getOutputStream().bufferedWriter()
                    val result = runCatching {
                        writer2.write("""{"protocol":"clockwork-standalone-net","version":1,"requestId":"x2","action":"ping","payload":{}}""")
                        writer2.newLine()
                        writer2.flush()
                        reader2.readLine()
                    }
                    if (result.isSuccess) {
                        assertNull(result.getOrNull())
                    } else {
                        assertTrue(result.exceptionOrNull() is SocketException)
                    }
                }
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun `minecraft status gets api-port response when bridge is disabled`() {
        val server = StandaloneNetServer(
            config = StandaloneNetConfig(
                host = "127.0.0.1",
                port = 0,
                minecraftBridgeEnabled = false
            ),
            logger = {},
            handler = TestHandler()
        )
        server.start()
        try {
            val port = waitForPort(server)
            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 3_000
                val input = DataInputStream(socket.getInputStream())
                val output = DataOutputStream(socket.getOutputStream())
                sendMcHandshake(output, host = "127.0.0.1", port = port, nextState = 1)
                sendMcStatusRequest(output)
                val packet = readMcPacket(input)
                assertEquals(0, packet.packetId)
                assertTrue(packet.stringPayload.contains("Clockwork"))
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun `minecraft status is proxied to upstream when bridge is enabled`() {
        val upstream = ServerSocket(0)
        val upstreamReady = CountDownLatch(1)
        val upstreamDone = CountDownLatch(1)
        val upstreamPort = upstream.localPort
        var upstreamError: Throwable? = null
        val upstreamThread = Thread({
            try {
                upstreamReady.countDown()
                upstream.accept().use { client ->
                    val inData = DataInputStream(client.getInputStream())
                    val outData = DataOutputStream(client.getOutputStream())
                    val handshake = readMcPacket(inData)
                    assertEquals(0, handshake.packetId)
                    val statusReq = readMcPacket(inData)
                    assertEquals(0, statusReq.packetId)
                    sendMcStatusResponse(outData, """{"description":{"text":"UPSTREAM_OK"}}""")
                }
            } catch (t: Throwable) {
                upstreamError = t
            } finally {
                upstreamDone.countDown()
            }
        }, "test-upstream").apply { isDaemon = true }
        upstreamThread.start()

        val server = StandaloneNetServer(
            config = StandaloneNetConfig(
                host = "127.0.0.1",
                port = 0,
                minecraftBridgeEnabled = true,
                minecraftBridgeHost = "127.0.0.1",
                minecraftBridgePort = upstreamPort
            ),
            logger = {},
            handler = TestHandler()
        )
        server.start()
        try {
            assertTrue(upstreamReady.await(2, TimeUnit.SECONDS))
            val port = waitForPort(server)
            Socket("127.0.0.1", port).use { socket ->
                val input = DataInputStream(socket.getInputStream())
                val output = DataOutputStream(socket.getOutputStream())
                sendMcHandshake(output, host = "localhost", port = port, nextState = 1)
                sendMcStatusRequest(output)
                val packet = readMcPacket(input)
                assertEquals(0, packet.packetId)
                assertTrue(packet.stringPayload.contains("UPSTREAM_OK"))
            }
            assertTrue(upstreamDone.await(2, TimeUnit.SECONDS))
            assertNull(upstreamError)
        } finally {
            server.stop()
            runCatching { upstream.close() }
        }
    }

    @Test
    fun `minecraft login gets disconnect when bridge upstream is unavailable`() {
        val unavailablePort = ServerSocket(0).use { it.localPort }
        val server = StandaloneNetServer(
            config = StandaloneNetConfig(
                host = "127.0.0.1",
                port = 0,
                minecraftBridgeEnabled = true,
                minecraftBridgeHost = "127.0.0.1",
                minecraftBridgePort = unavailablePort
            ),
            logger = {},
            handler = TestHandler()
        )
        server.start()
        try {
            val port = waitForPort(server)
            Socket("127.0.0.1", port).use { socket ->
                val input = DataInputStream(socket.getInputStream())
                val output = DataOutputStream(socket.getOutputStream())
                sendMcHandshake(output, host = "localhost", port = port, nextState = 2)
                val packet = readMcPacket(input)
                assertEquals(0, packet.packetId)
                assertTrue(packet.stringPayload.contains("Minecraft bridge unavailable"))
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun `minecraft native offline mode emits login success`() {
        val server = StandaloneNetServer(
            config = StandaloneNetConfig(
                host = "127.0.0.1",
                port = 0,
                minecraftMode = "native-offline",
                minecraftBridgeEnabled = false,
                minecraftSupportedProtocolVersion = 774
            ),
            logger = {},
            handler = TestHandler()
        )
        server.start()
        try {
            val port = waitForPort(server)
            Socket("127.0.0.1", port).use { socket ->
                val input = DataInputStream(socket.getInputStream())
                val output = DataOutputStream(socket.getOutputStream())
                sendMcHandshake(output, host = "localhost", port = port, nextState = 2, protocolVersion = 774)
                sendMcLoginStart(output, "Alex")
                val loginSuccess = readMcPacket(input)
                assertEquals(2, loginSuccess.packetId)
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun `minecraft native offline mode completes login configuration and enters play`() {
        val server = StandaloneNetServer(
            config = StandaloneNetConfig(
                host = "127.0.0.1",
                port = 0,
                minecraftMode = "native-offline",
                minecraftBridgeEnabled = false,
                minecraftSupportedProtocolVersion = 774
            ),
            logger = {},
            handler = TestHandler()
        )
        server.start()
        try {
            val port = waitForPort(server)
            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 4_000
                val input = DataInputStream(socket.getInputStream())
                val output = DataOutputStream(socket.getOutputStream())
                sendMcHandshake(output, host = "localhost", port = port, nextState = 2, protocolVersion = 774)
                sendMcLoginStart(output, "Alex")

                val loginSuccess = readMcPacket(input)
                assertEquals(0x02, loginSuccess.packetId)

                sendMcPacketById(output, 0x03) // login acknowledged

                val configPacketIds = mutableListOf<Int>()
                while (true) {
                    val packet = readMcPacket(input)
                    configPacketIds += packet.packetId
                    if (packet.packetId == 0x03) break // finish_configuration
                }
                assertTrue(configPacketIds.contains(0x0C)) // feature_flags
                assertTrue(configPacketIds.contains(0x07)) // registry_data
                assertTrue(configPacketIds.contains(0x0D)) // tags
                assertEquals(0x03, configPacketIds.last())

                sendMcPacketById(output, 0x03) // finish_configuration ack

                val playPacketIds = mutableListOf<Int>()
                repeat(5) {
                    val packet = readMcPacket(input)
                    playPacketIds += packet.packetId
                }
                assertTrue(playPacketIds.contains(0x30)) // play login
                assertTrue(playPacketIds.contains(0x5C)) // update view position
                assertTrue(playPacketIds.contains(0x5F)) // spawn position
                assertTrue(playPacketIds.contains(0x3E)) // abilities
                assertTrue(playPacketIds.contains(0x46)) // position
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun `minecraft native online mode completes encrypted login with local session verify`() {
        val sessionServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        sessionServer.createContext("/session/minecraft/hasJoined") { exchange ->
            val body = """{"id":"11111111111111111111111111111111","name":"Alex","properties":[]}"""
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        sessionServer.start()

        val server = StandaloneNetServer(
            config = StandaloneNetConfig(
                host = "127.0.0.1",
                port = 0,
                minecraftMode = "native-online",
                minecraftBridgeEnabled = false,
                minecraftSupportedProtocolVersion = 774,
                minecraftOnlineSessionServerUrl = "http://127.0.0.1:${sessionServer.address.port}/session/minecraft/hasJoined",
                minecraftOnlineAuthTimeoutMillis = 3_000
            ),
            logger = {},
            handler = TestHandler()
        )
        server.start()
        try {
            val port = waitForPort(server)
            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 4_000
                val rawIn = DataInputStream(socket.getInputStream())
                val rawOut = DataOutputStream(socket.getOutputStream())
                sendMcHandshake(rawOut, host = "localhost", port = port, nextState = 2, protocolVersion = 774)
                sendMcLoginStart(rawOut, "Alex")

                val encryptionReq = readMcPacket(rawIn)
                assertEquals(0x01, encryptionReq.packetId)
                val parsedReq = parseMcEncryptionRequest(encryptionReq.payload)

                val sharedSecret = ByteArray(16) { idx -> (idx + 1).toByte() }
                val publicKey = KeyFactory.getInstance("RSA")
                    .generatePublic(X509EncodedKeySpec(parsedReq.publicKey))
                val rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding").apply {
                    init(Cipher.ENCRYPT_MODE, publicKey)
                }
                val encSecret = rsa.doFinal(sharedSecret)
                val encToken = rsa.doFinal(parsedReq.verifyToken)
                sendMcEncryptionResponse(rawOut, encSecret, encToken)

                val decryptCipher = Cipher.getInstance("AES/CFB8/NoPadding").apply {
                    init(Cipher.DECRYPT_MODE, SecretKeySpec(sharedSecret, "AES"), IvParameterSpec(sharedSecret))
                }
                val encryptCipher = Cipher.getInstance("AES/CFB8/NoPadding").apply {
                    init(Cipher.ENCRYPT_MODE, SecretKeySpec(sharedSecret, "AES"), IvParameterSpec(sharedSecret))
                }
                val encIn = DataInputStream(CipherInputStream(socket.getInputStream(), decryptCipher))
                val encOut = DataOutputStream(CipherOutputStream(socket.getOutputStream(), encryptCipher))

                val loginSuccess = readMcPacket(encIn)
                assertEquals(0x02, loginSuccess.packetId)
                sendMcPacketById(encOut, 0x03) // login acknowledged
            }
        } finally {
            server.stop()
            sessionServer.stop(0)
        }
    }

    @Test
    fun `minecraft handshake session is cleaned up and does not block next connection`() {
        val server = StandaloneNetServer(
            config = StandaloneNetConfig(
                host = "127.0.0.1",
                port = 0,
                minecraftBridgeEnabled = false,
                maxConcurrentSessions = 8,
                maxSessionsPerIp = 1
            ),
            logger = {},
            handler = TestHandler()
        )
        server.start()
        try {
            val port = waitForPort(server)
            Socket("127.0.0.1", port).use { socket ->
                val input = DataInputStream(socket.getInputStream())
                val output = DataOutputStream(socket.getOutputStream())
                sendMcHandshake(output, host = "127.0.0.1", port = port, nextState = 1)
                sendMcStatusRequest(output)
                val packet = readMcPacket(input)
                assertEquals(0, packet.packetId)
                assertTrue(packet.stringPayload.contains("Clockwork"))
            }

            Socket("127.0.0.1", port).use { socket ->
                val reader = socket.getInputStream().bufferedReader()
                val writer = socket.getOutputStream().bufferedWriter()
                val ping = sendJson(reader, writer, action = "ping", requestId = "after-mc")
                assertTrue(ping.success)
                assertEquals("PONG", ping.code)
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun `native offline vanilla-like probe reaches play bootstrap when enabled`() {
        org.junit.jupiter.api.Assumptions.assumeTrue(
            System.getenv("CLOCKWORK_RUN_VANILLA_PROBE") == "1",
            "set CLOCKWORK_RUN_VANILLA_PROBE=1 to run this integration probe"
        )
        val server = StandaloneNetServer(
            config = StandaloneNetConfig(
                host = "127.0.0.1",
                port = 0,
                minecraftMode = "native-offline",
                minecraftBridgeEnabled = false,
                minecraftSupportedProtocolVersion = 774
            ),
            logger = {},
            handler = TestHandler()
        )
        server.start()
        try {
            val port = waitForPort(server)
            val cmd = listOf(
                "npx",
                "--yes",
                "--package",
                "minecraft-protocol",
                "node",
                "scripts/native_vanilla_probe.js",
                "127.0.0.1",
                port.toString(),
                "1.21.11"
            )
            val process = try {
                ProcessBuilder(cmd)
                    .directory(File(System.getProperty("user.dir")))
                    .redirectErrorStream(true)
                    .start()
            } catch (_: Exception) {
                org.junit.jupiter.api.Assumptions.assumeTrue(false, "npx is not available on this machine")
                return
            }
            val output = process.inputStream.bufferedReader().readText()
            val finished = process.waitFor(90, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
            }
            assertTrue(finished, "probe timed out")
            assertEquals(0, process.exitValue(), "probe failed:\n$output")
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

    private data class McPacket(
        val packetId: Int,
        val payload: ByteArray,
        val stringPayload: String
    )

    private data class McEncryptionRequest(
        val publicKey: ByteArray,
        val verifyToken: ByteArray
    )

    private fun sendMcHandshake(output: DataOutputStream, host: String, port: Int, nextState: Int, protocolVersion: Int = 767) {
        val payload = mutableListOf<Byte>()
        payload += encodeVarInt(0).toList()
        payload += encodeVarInt(protocolVersion).toList()
        val hostBytes = host.toByteArray(Charsets.UTF_8)
        payload += encodeVarInt(hostBytes.size).toList()
        payload += hostBytes.toList()
        payload += byteArrayOf(((port ushr 8) and 0xFF).toByte(), (port and 0xFF).toByte()).toList()
        payload += encodeVarInt(nextState).toList()
        writeMcRawPacket(output, payload.toByteArray())
    }

    private fun sendMcStatusRequest(output: DataOutputStream) {
        writeMcRawPacket(output, encodeVarInt(0))
    }

    private fun sendMcLoginStart(output: DataOutputStream, playerName: String) {
        val nameBytes = playerName.toByteArray(Charsets.UTF_8)
        val payload = mutableListOf<Byte>()
        payload += encodeVarInt(0).toList()
        payload += encodeVarInt(nameBytes.size).toList()
        payload += nameBytes.toList()
        val uuid = java.util.UUID.nameUUIDFromBytes("OfflinePlayer:$playerName".toByteArray(Charsets.UTF_8))
        val uuidBytes = ByteArray(16)
        var msb = uuid.mostSignificantBits
        var lsb = uuid.leastSignificantBits
        for (i in 7 downTo 0) {
            uuidBytes[i] = (msb and 0xFFL).toByte()
            msb = msb ushr 8
        }
        for (i in 15 downTo 8) {
            uuidBytes[i] = (lsb and 0xFFL).toByte()
            lsb = lsb ushr 8
        }
        payload += uuidBytes.toList()
        writeMcRawPacket(output, payload.toByteArray())
    }

    private fun sendMcPacketById(output: DataOutputStream, packetId: Int) {
        writeMcRawPacket(output, encodeVarInt(packetId))
    }

    private fun sendMcEncryptionResponse(output: DataOutputStream, encryptedSecret: ByteArray, encryptedVerifyToken: ByteArray) {
        val payload = mutableListOf<Byte>()
        payload += encodeVarInt(0x01).toList()
        payload += encodeVarInt(encryptedSecret.size).toList()
        payload += encryptedSecret.toList()
        payload += encodeVarInt(encryptedVerifyToken.size).toList()
        payload += encryptedVerifyToken.toList()
        writeMcRawPacket(output, payload.toByteArray())
    }

    private fun sendMcStatusResponse(output: DataOutputStream, json: String) {
        val jsonBytes = json.toByteArray(Charsets.UTF_8)
        val payload = mutableListOf<Byte>()
        payload += encodeVarInt(0).toList()
        payload += encodeVarInt(jsonBytes.size).toList()
        payload += jsonBytes.toList()
        writeMcRawPacket(output, payload.toByteArray())
    }

    private fun writeMcRawPacket(output: DataOutputStream, payload: ByteArray) {
        output.write(encodeVarInt(payload.size))
        output.write(payload)
        output.flush()
    }

    private fun readMcPacket(input: DataInputStream): McPacket {
        val frameLen = readVarInt(input)
        val frame = ByteArray(frameLen)
        input.readFully(frame)
        val frameIn = DataInputStream(frame.inputStream())
        val packetId = readVarInt(frameIn)
        val payload = frame.copyOfRange(varIntSize(packetId), frame.size)
        val payloadIn = DataInputStream(payload.inputStream())
        val stringPayload = runCatching {
            val strLen = readVarInt(payloadIn)
            val strBytes = ByteArray(strLen)
            payloadIn.readFully(strBytes)
            String(strBytes, Charsets.UTF_8)
        }.getOrDefault("")
        return McPacket(packetId = packetId, payload = payload, stringPayload = stringPayload)
    }

    private fun parseMcEncryptionRequest(payload: ByteArray): McEncryptionRequest {
        var idx = 0
        fun readVarInt(): Int {
            var numRead = 0
            var result = 0
            while (numRead < 5) {
                val raw = payload[idx].toInt() and 0xFF
                idx += 1
                val value = raw and 0x7F
                result = result or (value shl (7 * numRead))
                numRead++
                if ((raw and 0x80) == 0) return result
            }
            error("invalid varint in encryption request")
        }
        fun readString(): String {
            val len = readVarInt()
            val out = String(payload, idx, len, StandardCharsets.UTF_8)
            idx += len
            return out
        }
        fun readByteArray(): ByteArray {
            val len = readVarInt()
            val out = payload.copyOfRange(idx, idx + len)
            idx += len
            return out
        }

        readString() // serverId
        val publicKey = readByteArray()
        val verifyToken = readByteArray()
        // shouldAuthenticate bool
        return McEncryptionRequest(publicKey = publicKey, verifyToken = verifyToken)
    }

    private fun readVarInt(input: DataInputStream): Int {
        var numRead = 0
        var result = 0
        while (numRead < 5) {
            val read = input.read()
            require(read >= 0) { "Unexpected EOF while reading VarInt" }
            val value = read and 0x7F
            result = result or (value shl (7 * numRead))
            numRead++
            if ((read and 0x80) == 0) return result
        }
        error("VarInt too big")
    }

    private fun encodeVarInt(value: Int): ByteArray {
        var current = value
        val out = ArrayList<Byte>(5)
        do {
            var temp = current and 0x7F
            current = current ushr 7
            if (current != 0) temp = temp or 0x80
            out += temp.toByte()
        } while (current != 0)
        return out.toByteArray()
    }

    private fun varIntSize(value: Int): Int = encodeVarInt(value).size

    private class TestHandler : StandaloneSessionHandler {
        var worldCreateCalls: Int = 0
        var lastJoinContext: SessionJoinContext? = null

        override fun joinWithContext(
            name: String,
            world: String,
            x: Double,
            y: Double,
            z: Double,
            context: SessionJoinContext
        ): SessionActionResult {
            lastJoinContext = context
            return join(name, world, x, y, z)
        }

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
