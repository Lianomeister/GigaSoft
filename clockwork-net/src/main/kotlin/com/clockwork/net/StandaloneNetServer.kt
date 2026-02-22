package com.clockwork.net

import com.clockwork.api.GigaLogger
import java.io.BufferedWriter
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.nio.charset.StandardCharsets
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.Base64
import java.util.HashMap
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

data class StandaloneNetConfig(
    val host: String = "0.0.0.0",
    val port: Int = 25570,
    val authRequired: Boolean = false,
    val sharedSecret: String? = null,
    val adminSecret: String? = null,
    val sessionTtlSeconds: Long = 1800L,
    val maxFrameBytes: Int = 1_048_576,
    val maxTextLineBytes: Int = 16_384,
    val readTimeoutMillis: Int = 30_000,
    val maxConcurrentSessions: Int = 256,
    val maxSessionsPerIp: Int = 32,
    val maxRequestsPerMinutePerConnection: Int = 6_000,
    val maxRequestsPerMinutePerIp: Int = 20_000,
    val maxJsonPayloadEntries: Int = 64,
    val maxJsonPayloadKeyChars: Int = 64,
    val maxJsonPayloadValueChars: Int = 1_024,
    val maxJsonPayloadTotalChars: Int = 8_192,
    val workerThreads: Int = maxOf(4, Runtime.getRuntime().availableProcessors()),
    val workerQueueCapacity: Int = 2_048,
    val auditLogEnabled: Boolean = true,
    val textFlushEveryResponses: Int = 1,
    val frameFlushEveryResponses: Int = 1,
    val minecraftBridgeEnabled: Boolean = false,
    val minecraftBridgeHost: String = "127.0.0.1",
    val minecraftBridgePort: Int = 25565,
    val minecraftBridgeConnectTimeoutMillis: Int = 5_000,
    val minecraftBridgeStreamBufferBytes: Int = 65_536,
    val minecraftBridgeSocketBufferBytes: Int = 262_144,
    val minecraftMode: String = "bridge",
    val minecraftSupportedProtocolVersion: Int = 774,
    val minecraftStatusDescription: String = "Clockwork Native 1.21.11",
    val minecraftOnlineSessionServerUrl: String = "https://sessionserver.mojang.com/session/minecraft/hasJoined",
    val minecraftOnlineAuthTimeoutMillis: Int = 7_000
) {
    init {
        require(maxTextLineBytes > 0) { "maxTextLineBytes must be > 0" }
        require(readTimeoutMillis >= 0) { "readTimeoutMillis must be >= 0" }
        require(maxConcurrentSessions > 0) { "maxConcurrentSessions must be > 0" }
        require(maxSessionsPerIp > 0) { "maxSessionsPerIp must be > 0" }
        require(maxRequestsPerMinutePerConnection > 0) { "maxRequestsPerMinutePerConnection must be > 0" }
        require(maxRequestsPerMinutePerIp > 0) { "maxRequestsPerMinutePerIp must be > 0" }
        require(maxJsonPayloadEntries > 0) { "maxJsonPayloadEntries must be > 0" }
        require(maxJsonPayloadKeyChars > 0) { "maxJsonPayloadKeyChars must be > 0" }
        require(maxJsonPayloadValueChars > 0) { "maxJsonPayloadValueChars must be > 0" }
        require(maxJsonPayloadTotalChars > 0) { "maxJsonPayloadTotalChars must be > 0" }
        require(workerThreads > 0) { "workerThreads must be > 0" }
        require(workerQueueCapacity > 0) { "workerQueueCapacity must be > 0" }
        require(textFlushEveryResponses > 0) { "textFlushEveryResponses must be > 0" }
        require(frameFlushEveryResponses > 0) { "frameFlushEveryResponses must be > 0" }
        require(minecraftBridgePort in 1..65535) { "minecraftBridgePort must be between 1 and 65535" }
        require(minecraftBridgeConnectTimeoutMillis in 100..60_000) {
            "minecraftBridgeConnectTimeoutMillis must be between 100 and 60000"
        }
        require(minecraftBridgeStreamBufferBytes in 4_096..1_048_576) {
            "minecraftBridgeStreamBufferBytes must be between 4096 and 1048576"
        }
        require(minecraftBridgeSocketBufferBytes in 16_384..4_194_304) {
            "minecraftBridgeSocketBufferBytes must be between 16384 and 4194304"
        }
        require(minecraftMode.lowercase() in setOf("bridge", "native-offline", "native-online")) {
            "minecraftMode must be one of: bridge, native-offline, native-online"
        }
        require(minecraftSupportedProtocolVersion > 0) { "minecraftSupportedProtocolVersion must be > 0" }
        require(minecraftStatusDescription.isNotBlank()) { "minecraftStatusDescription must not be blank" }
        require(minecraftOnlineSessionServerUrl.isNotBlank()) { "minecraftOnlineSessionServerUrl must not be blank" }
        require(minecraftOnlineAuthTimeoutMillis in 500..60_000) { "minecraftOnlineAuthTimeoutMillis must be between 500 and 60000" }
    }
}

data class NetActionMetricSnapshot(
    val count: Long,
    val failures: Long,
    val totalNanos: Long,
    val maxNanos: Long
) {
    val averageNanos: Long
        get() = if (count <= 0L) 0L else totalNanos / count
}

data class StandaloneNetMetricsSnapshot(
    val totalRequests: Long,
    val jsonRequests: Long,
    val legacyRequests: Long,
    val averageRequestNanos: Long,
    val actionMetrics: Map<String, NetActionMetricSnapshot>,
    val minecraftBridge: MinecraftBridgeMetricsSnapshot
)

data class MinecraftBridgeMetricsSnapshot(
    val handshakesDetected: Long,
    val proxiedSessions: Long,
    val activeProxiedSessions: Int,
    val bytesClientToUpstream: Long,
    val bytesUpstreamToClient: Long,
    val connectFailures: Long,
    val averageProxySessionNanos: Long,
    val maxProxySessionNanos: Long,
    val nativeLoginAttempts: Long,
    val nativeLoginRejected: Long
)

class StandaloneNetServer(
    private val config: StandaloneNetConfig,
    private val logger: GigaLogger,
    private val handler: StandaloneSessionHandler
) {
    companion object {
        private const val ROLE_GUEST = 0
        private const val ROLE_PLAYER = 1
        private const val ROLE_ADMIN = 2
        private const val MC_STATE_LOGIN_SUCCESS_PACKET = 0x02
        private const val MC_STATE_LOGIN_ACK_PACKET = 0x03
        private const val MC_STATE_LOGIN_ENCRYPTION_REQUEST_PACKET = 0x01
        private const val MC_STATE_LOGIN_ENCRYPTION_RESPONSE_PACKET = 0x01
        private const val MC_STATE_CONFIG_FINISH_PACKET = 0x03
        private const val MC_STATE_CONFIG_FINISH_ACK_PACKET = 0x03
        private const val MC_STATE_PLAY_KEEPALIVE_PACKET = 0x2B
        private const val MC_STATE_PLAY_KEEPALIVE_RESPONSE_PACKET = 0x1B
        private const val MC_NATIVE_KEEPALIVE_INTERVAL_MILLIS = 5_000L
    }

    private val codec = NetPacketCodec()
    private val httpClient = HttpClient.newBuilder().build()
    private val onlineKeyPair: KeyPair by lazy {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(1024)
        generator.generateKeyPair()
    }
    private val nativePacketTemplates = loadNativePacketTemplates()
    private val mcStatusPayloadBridge = encodeMcString(
        """{"version":{"name":"Clockwork API + Bridge","protocol":${config.minecraftSupportedProtocolVersion}},"players":{"max":0,"online":0,"sample":[]},"description":{"text":"Clockwork API + Bridge"}}"""
    )
    private val mcStatusPayloadNativeOffline = encodeMcString(
        """{"version":{"name":"${config.minecraftStatusDescription} (offline)","protocol":${config.minecraftSupportedProtocolVersion}},"players":{"max":0,"online":0,"sample":[]},"description":{"text":"${config.minecraftStatusDescription} (offline)"}}"""
    )
    private val mcStatusPayloadNativeOnline = encodeMcString(
        """{"version":{"name":"${config.minecraftStatusDescription} (online auth)","protocol":${config.minecraftSupportedProtocolVersion}},"players":{"max":0,"online":0,"sample":[]},"description":{"text":"${config.minecraftStatusDescription} (online auth)"}}"""
    )
    private val handshakePayload = mapOf(
        "authRequired" to config.authRequired.toString(),
        "authMode" to if (config.sharedSecret.isNullOrBlank() && config.adminSecret.isNullOrBlank()) "none" else "secret",
        "sessionTtlSeconds" to config.sessionTtlSeconds.toString(),
        "maxFrameBytes" to config.maxFrameBytes.toString(),
        "maxTextLineBytes" to config.maxTextLineBytes.toString(),
        "maxRequestsPerMinutePerConnection" to config.maxRequestsPerMinutePerConnection.toString(),
        "maxRequestsPerMinutePerIp" to config.maxRequestsPerMinutePerIp.toString(),
        "roles" to "guest,player,admin"
    )
    private val negotiatedPayload = mapOf("selectedVersion" to "1")
    private val rateLimitJsonResponse = codec.encodeResponseParts(
        requestId = null,
        success = false,
        code = "RATE_LIMIT",
        message = "Rate limit exceeded",
        payload = emptyMap()
    )
    private val running = AtomicBoolean(false)
    private val totalRequests = AtomicLong(0)
    private val jsonRequests = AtomicLong(0)
    private val legacyRequests = AtomicLong(0)
    private val totalRequestNanos = AtomicLong(0)
    private val actionMetrics = ConcurrentHashMap<String, MutableActionMetric>()
    private val sessions = ConcurrentHashMap.newKeySet<Socket>()
    private val activeSessions = AtomicInteger(0)
    private val sessionsPerIp = ConcurrentHashMap<String, AtomicInteger>()
    private val requestsPerIp = ConcurrentHashMap<String, RateWindow>()
    private val invalidFrameAuditByIp = ConcurrentHashMap<String, AtomicLong>()
    private val minecraftHandshakesDetected = AtomicLong(0)
    private val minecraftProxiedSessions = AtomicLong(0)
    private val minecraftActiveProxiedSessions = AtomicInteger(0)
    private val minecraftBytesClientToUpstream = AtomicLong(0)
    private val minecraftBytesUpstreamToClient = AtomicLong(0)
    private val minecraftBridgeConnectFailures = AtomicLong(0)
    private val minecraftProxyTotalNanos = AtomicLong(0)
    private val minecraftProxyMaxNanos = AtomicLong(0)
    private val minecraftNativeLoginAttempts = AtomicLong(0)
    private val minecraftNativeLoginRejected = AtomicLong(0)
    private val acceptor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "clockwork-net-accept").apply { isDaemon = true }
    }
    private val workers = ThreadPoolExecutor(
        config.workerThreads,
        config.workerThreads,
        30L,
        TimeUnit.SECONDS,
        LinkedBlockingQueue(config.workerQueueCapacity),
        { r -> Thread(r, "clockwork-net-worker").apply { isDaemon = true } }
    )
    @Volatile
    private var boundPortValue: Int = -1
    private var serverSocket: ServerSocket? = null

    private data class RateWindow(
        val calls: ArrayDeque<Long> = ArrayDeque(),
        var size: Int = 0
    )

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val socket = ServerSocket()
        socket.bind(InetSocketAddress(config.host, config.port))
        serverSocket = socket
        boundPortValue = socket.localPort
        logger.info("Standalone net listening on ${config.host}:$boundPortValue")
        acceptor.submit {
            while (running.get()) {
                val client = runCatching { socket.accept() }.getOrNull() ?: continue
                val remoteIp = client.inetAddress?.hostAddress ?: "unknown"
                if (!allowConnection(client, remoteIp)) {
                    continue
                }
                try {
                    sessions += client
                    workers.execute { handleClient(client, remoteIp) }
                } catch (_: RejectedExecutionException) {
                    sessions.remove(client)
                    releaseConnection(remoteIp)
                    recordActionMetric("transport.worker_saturated", 0L, false)
                    audit("worker_saturated", remoteIp, null, "worker queue saturated")
                    runCatching { client.close() }
                }
            }
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { serverSocket?.close() }
        sessions.forEach { runCatching { it.close() } }
        sessions.clear()
        activeSessions.set(0)
        boundPortValue = -1
        acceptor.shutdownNow()
        workers.shutdownNow()
        logger.info("Standalone net stopped")
    }

    fun boundPort(): Int = boundPortValue

    private data class SessionContext(
        val connectionId: String,
        val sessionId: String?,
        val authenticated: Boolean,
        val currentName: String?,
        val role: String,
        val roleLevel: Int,
        val sessionExpiresAtMillis: Long?
    )

    private class MutableActionMetric {
        private val count = AtomicLong(0)
        private val failures = AtomicLong(0)
        private val totalNanos = AtomicLong(0)
        private val maxNanos = AtomicLong(0)

        fun record(durationNanos: Long, success: Boolean) {
            count.incrementAndGet()
            if (!success) failures.incrementAndGet()
            totalNanos.addAndGet(durationNanos)
            maxNanos.updateAndGet { current -> if (durationNanos > current) durationNanos else current }
        }

        fun snapshot(): NetActionMetricSnapshot {
            return NetActionMetricSnapshot(
                count = count.get(),
                failures = failures.get(),
                totalNanos = totalNanos.get(),
                maxNanos = maxNanos.get()
            )
        }
    }
    private class PayloadReader(private val payload: Map<String, String>) {
        fun text(key: String): String = payload[key].orEmpty()
        fun trimmed(key: String): String = text(key).trim()
        fun trimmedOrNull(key: String): String? = payload[key]?.trim()?.takeIf { it.isNotEmpty() }
        fun int(key: String): Int? = payload[key]?.toIntOrNull()
        fun long(key: String): Long? = payload[key]?.toLongOrNull()
        fun double(key: String): Double? = payload[key]?.toDoubleOrNull()
        fun worldOrDefault(key: String, fallback: String): String {
            val value = payload[key]?.trim().orEmpty()
            return if (value.isEmpty()) fallback else value
        }
        fun worldOrNull(key: String): String? = payload[key]?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun handleClient(socket: Socket, remoteIp: String) {
        socket.tcpNoDelay = true
        if (config.readTimeoutMillis > 0) {
            socket.soTimeout = config.readTimeoutMillis
        }
        var context = SessionContext(
            connectionId = UUID.randomUUID().toString(),
            sessionId = null,
            authenticated = false,
            currentName = null,
            role = "guest",
            roleLevel = ROLE_GUEST,
            sessionExpiresAtMillis = null
        )
        val remote = socket.remoteSocketAddress.toString()
        val connectionWindow = RateWindow()
        var sessionVisible = false
        fun markSessionVisible() {
            if (!sessionVisible) {
                sessionVisible = true
                logger.info("Session connected: $remote")
            }
        }
        try {
            val rawIn = socket.getInputStream()
            val rawOut = socket.getOutputStream()
            val input = PushbackInputStream(rawIn, 16_384)
            var first = input.read()
            if (first < 0) return

            val isTextLead = first == '{'.code ||
                (first in 'A'.code..'Z'.code) ||
                (first in 'a'.code..'z'.code)

            if (!isTextLead) {
                input.unread(first)
                if (tryHandleMinecraftClient(
                        socket = socket,
                        input = input,
                        rawOut = rawOut,
                        remoteIp = remoteIp,
                        connectionId = context.connectionId
                    )
                ) {
                    return
                }
                first = input.read()
                if (first < 0) return
            }

            val isText = first == '{'.code ||
                (first in 'A'.code..'Z'.code) ||
                (first in 'a'.code..'z'.code)

            if (isText) {
                input.unread(first)
                rawOut.bufferedWriter().use { writer ->
                    var pendingTextResponses = 0
                    while (running.get()) {
                        val line = readTextLine(input) ?: break
                        markSessionVisible()
                        if (line.oversize) {
                            recordActionMetric("transport.text_oversize", 0L, false)
                            audit("text_oversize", remoteIp, context.connectionId, "text request exceeded maxTextLineBytes")
                            break
                        }
                        if (!allowRequest(remoteIp, connectionWindow)) {
                            val response = rateLimitResponse(line.value)
                            writeLine(writer, response, true)
                            audit("rate_limit", remoteIp, context.connectionId, "request rate limit exceeded")
                            break
                        }
                        val result = processLine(line.value, context)
                        context = result.context
                        pendingTextResponses += 1
                        val flushNow = result.response == "BYE" || pendingTextResponses >= config.textFlushEveryResponses
                        writeLine(writer, result.response, flushNow)
                        if (flushNow) pendingTextResponses = 0
                        if (result.response == "BYE") break
                    }
                }
            } else {
                val dataIn = DataInputStream(input)
                val dataOut = DataOutputStream(rawOut)
                var pendingFrames = 0
                var nextFrame = readFrame(dataIn, first)
                while (running.get() && nextFrame != null) {
                    markSessionVisible()
                    if (!allowRequest(remoteIp, connectionWindow)) {
                        val response = rateLimitResponse(nextFrame)
                        writeFrame(dataOut, response, true)
                        audit("rate_limit", remoteIp, context.connectionId, "request rate limit exceeded")
                        break
                    }
                    val result = processLine(nextFrame, context)
                    context = result.context
                    pendingFrames += 1
                    val flushNow = result.response == "BYE" || pendingFrames >= config.frameFlushEveryResponses
                    writeFrame(dataOut, result.response, flushNow)
                    if (flushNow) pendingFrames = 0
                    nextFrame = readFrame(dataIn, null)
                }
            }
        } catch (_: SocketTimeoutException) {
            recordActionMetric("transport.timeout", 0L, false)
            audit("timeout", remoteIp, context.connectionId, "socket timeout")
        } catch (_: IllegalStateException) {
            recordActionMetric("transport.invalid_frame", 0L, false)
            audit("invalid_frame", remoteIp, context.connectionId, "invalid frame size")
        } catch (_: Exception) {
            recordActionMetric("transport.error", 0L, false)
            audit("transport_error", remoteIp, context.connectionId, "unhandled transport exception")
        } finally {
            sessions.remove(socket)
            releaseConnection(remoteIp)
            runCatching { socket.close() }
            if (sessionVisible) {
                logger.info("Session disconnected: $remote")
            }
        }
    }

    private data class McHandshake(
        val protocolVersion: Int,
        val host: String,
        val port: Int,
        val nextState: Int
    )

    private data class McLoginStart(
        val name: String,
        val uuid: UUID
    )

    private data class McEncryptionResponse(
        val encryptedSharedSecret: ByteArray,
        val encryptedVerifyToken: ByteArray
    )

    private data class MojangProfile(
        val uuid: UUID,
        val name: String
    )

    private fun tryHandleMinecraftClient(
        socket: Socket,
        input: PushbackInputStream,
        rawOut: OutputStream,
        remoteIp: String,
        connectionId: String
    ): Boolean {
        val probe = ByteArray(8_320)
        var probeSize = 0
        var probeOverflow = false
        fun readProbeByte(): Int {
            val next = input.read()
            if (next >= 0) {
                if (probeSize >= probe.size) {
                    probeOverflow = true
                } else {
                    probe[probeSize] = next.toByte()
                    probeSize += 1
                }
            }
            return next
        }
        fun restoreProbeAndReturnFalse(): Boolean {
            if (probeSize > 0) {
                input.unread(probe, 0, probeSize)
            }
            return false
        }

        val frameLength = readVarInt { readProbeByte() } ?: return restoreProbeAndReturnFalse()
        if (frameLength <= 0 || frameLength > 8192) return restoreProbeAndReturnFalse()
        val frame = ByteArray(frameLength)
        var frameRead = 0
        while (frameRead < frame.size) {
            val next = readProbeByte()
            if (next < 0) return restoreProbeAndReturnFalse()
            frame[frameRead] = next.toByte()
            frameRead += 1
        }
        if (probeOverflow) return restoreProbeAndReturnFalse()
        val handshake = parseMcHandshake(frame) ?: return restoreProbeAndReturnFalse()

        audit(
            kind = "minecraft_handshake",
            remoteIp = remoteIp,
            connectionId = connectionId,
            detail = "host=${handshake.host} port=${handshake.port} protocol=${handshake.protocolVersion} nextState=${handshake.nextState}"
        )
        minecraftHandshakesDetected.incrementAndGet()

        val mode = config.minecraftMode.lowercase()
        if (mode == "bridge" && config.minecraftBridgeEnabled) {
            val initialBytes = if (probeSize > 0) probe.copyOf(probeSize) else ByteArray(0)
            return proxyMinecraft(socket, input, rawOut, initialBytes, remoteIp, connectionId)
        }

        if (handshake.nextState == 1) {
            respondMinecraftStatus(input, rawOut)
        } else {
            if (handshake.protocolVersion != config.minecraftSupportedProtocolVersion) {
                minecraftNativeLoginRejected.incrementAndGet()
                respondMinecraftDisconnect(
                    rawOut,
                    "Unsupported protocol ${handshake.protocolVersion}. Expected ${config.minecraftSupportedProtocolVersion} (Minecraft 1.21.11)."
                )
                return true
            }
            when (mode) {
                "native-offline" -> handleNativeLogin(socket, input, rawOut, remoteIp, connectionId, onlineMode = false)
                "native-online" -> handleNativeLogin(socket, input, rawOut, remoteIp, connectionId, onlineMode = true)
                else -> respondMinecraftDisconnect(rawOut)
            }
        }
        return true
    }

    private fun handleNativeLogin(
        socket: Socket,
        input: PushbackInputStream,
        rawOut: OutputStream,
        remoteIp: String,
        connectionId: String,
        onlineMode: Boolean
    ) {
        minecraftNativeLoginAttempts.incrementAndGet()
        val maybeLoginLen = readVarInt(input)
        if (maybeLoginLen == null || maybeLoginLen <= 0 || maybeLoginLen > 8_192) {
            minecraftNativeLoginRejected.incrementAndGet()
            respondMinecraftDisconnect(rawOut, "Invalid login frame.")
            return
        }
        val loginFrame = ByteArray(maybeLoginLen)
        if (!readFully(input, loginFrame)) {
            minecraftNativeLoginRejected.incrementAndGet()
            respondMinecraftDisconnect(rawOut, "Incomplete login frame.")
            return
        }
        val loginStart = parseMcLoginStart(loginFrame)
        if (loginStart == null) {
            minecraftNativeLoginRejected.incrementAndGet()
            respondMinecraftDisconnect(rawOut, "Malformed login start.")
            return
        }
        audit(
            kind = "minecraft_native_login_start",
            remoteIp = remoteIp,
            connectionId = connectionId,
            detail = "name=${loginStart.name} mode=${if (onlineMode) "online" else "offline"}"
        )
        if (onlineMode) {
            runNativeOnlineSession(socket, input, rawOut, loginStart, remoteIp, connectionId)
        } else {
            runNativeOfflineSession(socket, input, rawOut, loginStart, remoteIp, connectionId)
        }
    }

    private fun runNativeOfflineSession(
        socket: Socket,
        input: PushbackInputStream,
        rawOut: OutputStream,
        loginStart: McLoginStart,
        remoteIp: String,
        connectionId: String
    ) {
        runCatching { socket.soTimeout = 0 }
        val offlineUuid = UUID.nameUUIDFromBytes("OfflinePlayer:${loginStart.name}".toByteArray(StandardCharsets.UTF_8))
        sendNativeLoginSuccess(rawOut, offlineUuid, loginStart.name)
        runNativePostLoginSession(
            input = input,
            out = rawOut,
            profileUuid = offlineUuid,
            profileName = loginStart.name,
            remoteIp = remoteIp,
            connectionId = connectionId
        )
    }

    private fun runNativeOnlineSession(
        socket: Socket,
        input: PushbackInputStream,
        rawOut: OutputStream,
        loginStart: McLoginStart,
        remoteIp: String,
        connectionId: String
    ) {
        val verifyToken = ByteArray(4).also { java.security.SecureRandom().nextBytes(it) }
        val publicKey = onlineKeyPair.public.encoded
        val encryptionPayload = ByteArrayOutputStream(64 + publicKey.size)
        encryptionPayload.write(encodeMcString(""))
        encryptionPayload.write(encodeVarInt(publicKey.size))
        encryptionPayload.write(publicKey)
        encryptionPayload.write(encodeVarInt(verifyToken.size))
        encryptionPayload.write(verifyToken)
        encryptionPayload.write(1) // shouldAuthenticate=true
        writeMcPacket(rawOut, MC_STATE_LOGIN_ENCRYPTION_REQUEST_PACKET, encryptionPayload.toByteArray())

        val responseLen = readVarInt(input)
        if (responseLen == null || responseLen <= 0 || responseLen > 16_384) {
            minecraftNativeLoginRejected.incrementAndGet()
            respondMinecraftDisconnect(rawOut, "Invalid encryption response frame.")
            return
        }
        val responseFrame = ByteArray(responseLen)
        if (!readFully(input, responseFrame)) {
            minecraftNativeLoginRejected.incrementAndGet()
            respondMinecraftDisconnect(rawOut, "Incomplete encryption response.")
            return
        }
        val response = parseMcEncryptionResponse(responseFrame)
        if (response == null) {
            minecraftNativeLoginRejected.incrementAndGet()
            respondMinecraftDisconnect(rawOut, "Malformed encryption response.")
            return
        }
        val sharedSecret = decryptRsa(response.encryptedSharedSecret)
        val token = decryptRsa(response.encryptedVerifyToken)
        if (sharedSecret == null || token == null || !token.contentEquals(verifyToken) || sharedSecret.size != 16) {
            minecraftNativeLoginRejected.incrementAndGet()
            respondMinecraftDisconnect(rawOut, "Online authentication challenge failed.")
            return
        }
        val serverHash = computeMinecraftServerHash("", sharedSecret, publicKey)
        val profile = verifyMojangJoin(loginStart.name, serverHash)
        if (profile == null) {
            minecraftNativeLoginRejected.incrementAndGet()
            respondMinecraftDisconnect(rawOut, "Online authentication failed (session verify).")
            return
        }

        val decryptCipher = Cipher.getInstance("AES/CFB8/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(sharedSecret, "AES"), IvParameterSpec(sharedSecret))
        }
        val encryptCipher = Cipher.getInstance("AES/CFB8/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(sharedSecret, "AES"), IvParameterSpec(sharedSecret))
        }
        val encryptedInput = PushbackInputStream(CipherInputStream(socket.getInputStream(), decryptCipher), 16_384)
        val encryptedOut = CipherOutputStream(socket.getOutputStream(), encryptCipher)

        sendNativeLoginSuccess(encryptedOut, profile.uuid, profile.name)
        runNativePostLoginSession(
            input = encryptedInput,
            out = encryptedOut,
            profileUuid = profile.uuid,
            profileName = profile.name,
            remoteIp = remoteIp,
            connectionId = connectionId
        )
    }

    private fun runNativePostLoginSession(
        input: PushbackInputStream,
        out: OutputStream,
        profileUuid: UUID,
        profileName: String,
        remoteIp: String,
        connectionId: String
    ) {
        if (!waitForPacket(input, expectedPacketId = MC_STATE_LOGIN_ACK_PACKET, timeoutMillis = 5_000L)) {
            minecraftNativeLoginRejected.incrementAndGet()
            respondMinecraftDisconnect(out, "Native login acknowledgement timeout.")
            return
        }
        sendNativeConfiguration(out)
        writeMcPacket(out, MC_STATE_CONFIG_FINISH_PACKET, ByteArray(0))
        if (!waitForPacket(input, expectedPacketId = MC_STATE_CONFIG_FINISH_ACK_PACKET, timeoutMillis = 5_000L)) {
            minecraftNativeLoginRejected.incrementAndGet()
            respondMinecraftDisconnect(out, "Native configuration finish timeout.")
            return
        }
        sendNativePlayBootstrap(out)
        audit(
            kind = "minecraft_native_play_started",
            remoteIp = remoteIp,
            connectionId = connectionId,
            detail = "name=$profileName uuid=$profileUuid"
        )

        var runningSession = true
        var nextKeepAliveAt = System.currentTimeMillis()
        while (running.get() && runningSession) {
            val now = System.currentTimeMillis()
            if (now >= nextKeepAliveAt) {
                sendNativeKeepAlive(out, now)
                nextKeepAliveAt = now + MC_NATIVE_KEEPALIVE_INTERVAL_MILLIS
            }
            val frameLen = readVarInt(input) ?: break
            if (frameLen <= 0 || frameLen > 262_144) break
            val frame = ByteArray(frameLen)
            if (!readFully(input, frame)) break
            val (packetId, consumed) = readVarIntFromFrame(frame, 0)
            if (packetId == MC_STATE_PLAY_KEEPALIVE_RESPONSE_PACKET) continue
            if (packetId == 0x00 && consumed < frame.size) continue
            if (packetId < 0) runningSession = false
        }
    }

    private fun sendNativeLoginSuccess(rawOut: OutputStream, uuid: UUID, playerName: String) {
        val payload = ByteArrayOutputStream(128)
        payload.write(encodeUuid(uuid))
        payload.write(encodeMcString(playerName))
        payload.write(encodeVarInt(0)) // properties
        writeMcPacket(rawOut, MC_STATE_LOGIN_SUCCESS_PACKET, payload.toByteArray())
    }

    private fun parseMcEncryptionResponse(frame: ByteArray): McEncryptionResponse? {
        var idx = 0
        fun readVarIntFromFrame(): Int? {
            var numRead = 0
            var result = 0
            while (numRead < 5) {
                if (idx >= frame.size) return null
                val raw = frame[idx].toInt() and 0xFF
                idx += 1
                val value = raw and 0x7F
                result = result or (value shl (7 * numRead))
                numRead += 1
                if ((raw and 0x80) == 0) return result
            }
            return null
        }
        fun readByteArray(maxLen: Int): ByteArray? {
            val len = readVarIntFromFrame() ?: return null
            if (len < 0 || len > maxLen) return null
            if (idx + len > frame.size) return null
            val out = frame.copyOfRange(idx, idx + len)
            idx += len
            return out
        }
        val packetId = readVarIntFromFrame() ?: return null
        if (packetId != MC_STATE_LOGIN_ENCRYPTION_RESPONSE_PACKET) return null
        val encryptedSecret = readByteArray(1024) ?: return null
        val encryptedToken = readByteArray(1024) ?: return null
        return McEncryptionResponse(encryptedSharedSecret = encryptedSecret, encryptedVerifyToken = encryptedToken)
    }

    private fun decryptRsa(input: ByteArray): ByteArray? {
        return runCatching {
            val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
            cipher.init(Cipher.DECRYPT_MODE, onlineKeyPair.private)
            cipher.doFinal(input)
        }.getOrNull()
    }

    private fun computeMinecraftServerHash(serverId: String, sharedSecret: ByteArray, publicKey: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update(serverId.toByteArray(StandardCharsets.ISO_8859_1))
        digest.update(sharedSecret)
        digest.update(publicKey)
        val hash = digest.digest()
        return java.math.BigInteger(hash).toString(16)
    }

    private fun verifyMojangJoin(username: String, serverHash: String): MojangProfile? {
        val encodedName = URLEncoder.encode(username, StandardCharsets.UTF_8)
        val encodedHash = URLEncoder.encode(serverHash, StandardCharsets.UTF_8)
        val url = "${config.minecraftOnlineSessionServerUrl}?username=$encodedName&serverId=$encodedHash"
        val request = HttpRequest.newBuilder()
            .uri(java.net.URI.create(url))
            .timeout(java.time.Duration.ofMillis(config.minecraftOnlineAuthTimeoutMillis.toLong()))
            .GET()
            .build()
        val response = runCatching {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        }.getOrNull() ?: return null
        if (response.statusCode() != 200) return null
        val body = response.body()
        val idHex = Regex("\"id\"\\s*:\\s*\"([0-9a-fA-F]{32})\"").find(body)?.groupValues?.getOrNull(1) ?: return null
        val returnedName = Regex("\"name\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.getOrNull(1) ?: username
        val uuid = parseMojangUuidHex(idHex) ?: return null
        return MojangProfile(uuid = uuid, name = returnedName)
    }

    private fun parseMojangUuidHex(value: String): UUID? {
        if (value.length != 32) return null
        val dashed = buildString(36) {
            append(value.substring(0, 8))
            append('-')
            append(value.substring(8, 12))
            append('-')
            append(value.substring(12, 16))
            append('-')
            append(value.substring(16, 20))
            append('-')
            append(value.substring(20, 32))
        }
        return runCatching { UUID.fromString(dashed) }.getOrNull()
    }

    private fun sendNativeConfiguration(rawOut: OutputStream) {
        writeMcBody(rawOut, nativePacketTemplates["config.feature_flags"])
        nativePacketTemplates.entries
            .asSequence()
            .filter { it.key.startsWith("config.registry.") }
            .forEach { writeMcBody(rawOut, it.value) }
        writeMcBody(rawOut, nativePacketTemplates["config.tags"])
    }

    private fun sendNativePlayBootstrap(rawOut: OutputStream) {
        writeMcBody(rawOut, nativePacketTemplates["play.login"])
        writeMcBody(rawOut, nativePacketTemplates["play.update_view_position"])
        writeMcBody(rawOut, nativePacketTemplates["play.spawn_position"])
        writeMcBody(rawOut, nativePacketTemplates["play.abilities"])
        writeMcBody(rawOut, nativePacketTemplates["play.position"])
    }

    private fun waitForPacket(input: PushbackInputStream, expectedPacketId: Int, timeoutMillis: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() <= deadline) {
            val frameLen = readVarInt(input) ?: return false
            if (frameLen <= 0 || frameLen > 262_144) return false
            val frame = ByteArray(frameLen)
            if (!readFully(input, frame)) return false
            val packetId = readVarIntFromFrame(frame, 0).first
            if (packetId == expectedPacketId) return true
        }
        return false
    }

    private fun sendNativeKeepAlive(rawOut: OutputStream, nonce: Long) {
        val payload = ByteArrayOutputStream(16)
        DataOutputStream(payload).use { it.writeLong(nonce) }
        writeMcPacket(rawOut, MC_STATE_PLAY_KEEPALIVE_PACKET, payload.toByteArray())
    }

    private fun writeMcBody(rawOut: OutputStream, body: ByteArray?) {
        if (body == null || body.isEmpty()) return
        rawOut.write(encodeVarInt(body.size))
        rawOut.write(body)
        rawOut.flush()
    }

    private fun encodeUuid(uuid: UUID): ByteArray {
        val out = ByteArray(16)
        var msb = uuid.mostSignificantBits
        var lsb = uuid.leastSignificantBits
        for (i in 7 downTo 0) {
            out[i] = (msb and 0xFFL).toByte()
            msb = msb ushr 8
        }
        for (i in 15 downTo 8) {
            out[i] = (lsb and 0xFFL).toByte()
            lsb = lsb ushr 8
        }
        return out
    }

    private fun loadNativePacketTemplates(): LinkedHashMap<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        val stream = this::class.java.classLoader.getResourceAsStream("minecraft-1.21.11-native-packets.txt") ?: return out
        stream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
            lines.map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains("=") }
                .forEach { line ->
                    val idx = line.indexOf('=')
                    val key = line.substring(0, idx).trim()
                    val value = line.substring(idx + 1).trim()
                    if (key.isNotEmpty() && value.isNotEmpty()) {
                        out[key] = Base64.getDecoder().decode(value)
                    }
                }
        }
        return out
    }

    private fun readVarIntFromFrame(frame: ByteArray, offset: Int): Pair<Int, Int> {
        var idx = offset
        var numRead = 0
        var result = 0
        while (numRead < 5) {
            if (idx >= frame.size) return -1 to idx
            val raw = frame[idx].toInt() and 0xFF
            idx += 1
            val value = raw and 0x7F
            result = result or (value shl (7 * numRead))
            numRead += 1
            if ((raw and 0x80) == 0) return result to idx
        }
        return -1 to idx
    }

    private fun proxyMinecraft(
        clientSocket: Socket,
        clientInput: PushbackInputStream,
        clientOutput: OutputStream,
        initialClientBytes: ByteArray,
        remoteIp: String,
        connectionId: String
    ): Boolean {
        val upstream = Socket()
        var proxied = false
        val started = System.nanoTime()
        return try {
            runCatching { clientSocket.soTimeout = 0 }
            runCatching { clientSocket.sendBufferSize = config.minecraftBridgeSocketBufferBytes }
            runCatching { clientSocket.receiveBufferSize = config.minecraftBridgeSocketBufferBytes }
            runCatching { clientSocket.keepAlive = true }
            upstream.connect(
                InetSocketAddress(config.minecraftBridgeHost, config.minecraftBridgePort),
                config.minecraftBridgeConnectTimeoutMillis
            )
            upstream.tcpNoDelay = true
            upstream.soTimeout = 0
            runCatching { upstream.sendBufferSize = config.minecraftBridgeSocketBufferBytes }
            runCatching { upstream.receiveBufferSize = config.minecraftBridgeSocketBufferBytes }
            runCatching { upstream.keepAlive = true }
            val upstreamIn = upstream.getInputStream()
            val upstreamOut = upstream.getOutputStream()
            if (initialClientBytes.isNotEmpty()) {
                upstreamOut.write(initialClientBytes)
                upstreamOut.flush()
                minecraftBytesClientToUpstream.addAndGet(initialClientBytes.size.toLong())
            }
            minecraftProxiedSessions.incrementAndGet()
            minecraftActiveProxiedSessions.incrementAndGet()
            proxied = true
            audit(
                kind = "minecraft_bridge_proxy_started",
                remoteIp = remoteIp,
                connectionId = connectionId,
                detail = "upstream=${config.minecraftBridgeHost}:${config.minecraftBridgePort}"
            )

            val upstreamToClient = Thread({
                runCatching {
                    val copied = pipeStream(upstreamIn, clientOutput)
                    minecraftBytesUpstreamToClient.addAndGet(copied)
                }
            }, "clockwork-net-mc-bridge-upstream").apply { isDaemon = true }
            upstreamToClient.start()

            runCatching {
                val copied = pipeStream(clientInput, upstreamOut)
                minecraftBytesClientToUpstream.addAndGet(copied)
            }
            runCatching { upstream.shutdownOutput() }
            runCatching { clientSocket.shutdownOutput() }
            upstreamToClient.join(2_000)
            true
        } catch (_: Exception) {
            minecraftBridgeConnectFailures.incrementAndGet()
            audit(
                kind = "minecraft_bridge_connect_failed",
                remoteIp = remoteIp,
                connectionId = connectionId,
                detail = "upstream=${config.minecraftBridgeHost}:${config.minecraftBridgePort}"
            )
            respondMinecraftDisconnect(
                clientOutput,
                "Minecraft bridge unavailable. Check upstream server at ${config.minecraftBridgeHost}:${config.minecraftBridgePort}."
            )
            true
        } finally {
            val elapsed = System.nanoTime() - started
            if (proxied) {
                minecraftProxyTotalNanos.addAndGet(elapsed)
                minecraftProxyMaxNanos.updateAndGet { current -> if (elapsed > current) elapsed else current }
                minecraftActiveProxiedSessions.updateAndGet { current ->
                    if (current <= 0) 0 else current - 1
                }
                audit(
                    kind = "minecraft_bridge_proxy_finished",
                    remoteIp = remoteIp,
                    connectionId = connectionId,
                    detail = "durationNanos=$elapsed"
                )
            }
            runCatching { upstream.close() }
        }
    }

    private fun pipeStream(input: InputStream, output: OutputStream): Long {
        val buffer = ByteArray(config.minecraftBridgeStreamBufferBytes)
        var total = 0L
        while (running.get()) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            output.write(buffer, 0, read)
            total += read.toLong()
        }
        runCatching { output.flush() }
        return total
    }

    private fun parseMcHandshake(frame: ByteArray): McHandshake? {
        var idx = 0
        fun readVarIntFromFrame(): Int? {
            var numRead = 0
            var result = 0
            while (numRead < 5) {
                if (idx >= frame.size) return null
                val raw = frame[idx].toInt() and 0xFF
                idx += 1
                val value = raw and 0x7F
                result = result or (value shl (7 * numRead))
                numRead++
                if ((raw and 0x80) == 0) return result
            }
            return null
        }
        fun readStringFromFrame(): String? {
            val len = readVarIntFromFrame() ?: return null
            if (len < 0 || len > 512) return null
            if (idx + len > frame.size) return null
            val text = String(frame, idx, len, StandardCharsets.UTF_8)
            idx += len
            return text
        }
        val packetId = readVarIntFromFrame() ?: return null
        if (packetId != 0) return null
        val protocol = readVarIntFromFrame() ?: return null
        val host = readStringFromFrame() ?: return null
        if (idx + 1 >= frame.size) return null
        val port = ((frame[idx].toInt() and 0xFF) shl 8) or (frame[idx + 1].toInt() and 0xFF)
        idx += 2
        val nextState = readVarIntFromFrame() ?: return null
        if (nextState != 1 && nextState != 2) return null
        return McHandshake(protocolVersion = protocol, host = host, port = port, nextState = nextState)
    }

    private fun parseMcLoginStart(frame: ByteArray): McLoginStart? {
        var idx = 0
        fun readVarIntFromFrame(): Int? {
            var numRead = 0
            var result = 0
            while (numRead < 5) {
                if (idx >= frame.size) return null
                val raw = frame[idx].toInt() and 0xFF
                idx += 1
                val value = raw and 0x7F
                result = result or (value shl (7 * numRead))
                numRead++
                if ((raw and 0x80) == 0) return result
            }
            return null
        }
        fun readStringFromFrame(maxLen: Int): String? {
            val len = readVarIntFromFrame() ?: return null
            if (len < 0 || len > maxLen * 4) return null
            if (idx + len > frame.size) return null
            val text = String(frame, idx, len, StandardCharsets.UTF_8)
            idx += len
            if (text.length > maxLen) return null
            return text
        }
        val packetId = readVarIntFromFrame() ?: return null
        if (packetId != 0) return null
        val name = readStringFromFrame(16) ?: return null
        if (idx + 16 > frame.size) return null
        var msb = 0L
        var lsb = 0L
        repeat(8) {
            msb = (msb shl 8) or (frame[idx].toLong() and 0xFFL)
            idx += 1
        }
        repeat(8) {
            lsb = (lsb shl 8) or (frame[idx].toLong() and 0xFFL)
            idx += 1
        }
        return McLoginStart(name = name, uuid = UUID(msb, lsb))
    }

    private fun respondMinecraftStatus(input: PushbackInputStream, rawOut: java.io.OutputStream) {
        val mode = config.minecraftMode.lowercase()
        val statusPayload = when (mode) {
            "native-offline" -> mcStatusPayloadNativeOffline
            "native-online" -> mcStatusPayloadNativeOnline
            else -> mcStatusPayloadBridge
        }
        writeMcPacket(
            rawOut,
            packetId = 0,
            payload = statusPayload
        )

        val maybePingLen = readVarInt(input) ?: return
        if (maybePingLen <= 0 || maybePingLen > 64) return
        val pingFrame = ByteArray(maybePingLen)
        if (!readFully(input, pingFrame)) return
        if (pingFrame.isEmpty()) return

        var idx = 0
        fun readVarIntFromFrame(): Int? {
            var numRead = 0
            var result = 0
            while (numRead < 5) {
                if (idx >= pingFrame.size) return null
                val raw = pingFrame[idx].toInt() and 0xFF
                idx += 1
                val value = raw and 0x7F
                result = result or (value shl (7 * numRead))
                numRead++
                if ((raw and 0x80) == 0) return result
            }
            return null
        }
        val pingPacketId = readVarIntFromFrame() ?: return
        if (pingPacketId != 1) return
        if (idx + 7 >= pingFrame.size) return
        val payload = pingFrame.copyOfRange(idx, idx + 8)
        writeMcPacket(rawOut, packetId = 1, payload = payload)
    }

    private fun respondMinecraftDisconnect(rawOut: java.io.OutputStream) {
        respondMinecraftDisconnect(rawOut, "Clockwork Net API port detected. Vanilla login is not supported on this port yet.")
    }

    private fun respondMinecraftDisconnect(rawOut: java.io.OutputStream, message: String) {
        val safeMessage = message.replace("\"", "'")
        val reason = """{"text":"$safeMessage"}"""
        writeMcPacket(
            rawOut,
            packetId = 0,
            payload = encodeMcString(reason)
        )
    }

    private fun writeMcPacket(rawOut: java.io.OutputStream, packetId: Int, payload: ByteArray) {
        val packetIdBytes = encodeVarInt(packetId)
        val packetLength = packetIdBytes.size + payload.size
        val header = encodeVarInt(packetLength)
        rawOut.write(header)
        rawOut.write(packetIdBytes)
        rawOut.write(payload)
        rawOut.flush()
    }

    private fun encodeMcString(value: String): ByteArray {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        val out = ArrayList<Byte>(bytes.size + 5)
        encodeVarInt(bytes.size).forEach { out += it }
        bytes.forEach { out += it }
        return out.toByteArray()
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

    private fun readFully(input: PushbackInputStream, buffer: ByteArray): Boolean {
        var read = 0
        while (read < buffer.size) {
            val chunk = input.read(buffer, read, buffer.size - read)
            if (chunk < 0) return false
            read += chunk
        }
        return true
    }

    private fun readVarInt(input: PushbackInputStream): Int? {
        return readVarInt { input.read() }
    }

    private fun readVarInt(readByte: () -> Int): Int? {
        var numRead = 0
        var result = 0
        while (numRead < 5) {
            val raw = readByte()
            if (raw < 0) return null
            val value = raw and 0x7F
            result = result or (value shl (7 * numRead))
            numRead++
            if ((raw and 0x80) == 0) return result
        }
        return null
    }

    private data class SessionResult(
        val response: String,
        val context: SessionContext
    )

    private fun processLine(line: String, context: SessionContext): SessionResult {
        val started = System.nanoTime()
        totalRequests.incrementAndGet()
        val result = if (looksLikeJson(line)) {
            val packet = codec.tryDecodeRequest(line)
            if (packet == null) {
                jsonRequests.incrementAndGet()
                SessionResult(
                    response = codec.encodeResponseParts(
                        requestId = null,
                        success = false,
                        code = "BAD_REQUEST",
                        message = "Invalid JSON request",
                        payload = emptyMap()
                    ),
                    context = context
                )
            } else {
                jsonRequests.incrementAndGet()
                processPacket(packet, context)
            }
        } else {
            legacyRequests.incrementAndGet()
            processLegacyLine(line, context)
        }
        totalRequestNanos.addAndGet(System.nanoTime() - started)
        return result
    }

    private fun processPacket(packet: SessionRequestPacket, context: SessionContext): SessionResult {
        val started = System.nanoTime()
        if (packet.protocol != "clockwork-standalone-net" || packet.version != 1) {
            recordActionMetric("json.bad_protocol", System.nanoTime() - started, false)
            return SessionResult(
                response = codec.encodeResponseParts(
                    requestId = packet.requestId,
                    success = false,
                    code = "BAD_PROTOCOL",
                    message = "Unsupported protocol/version",
                    payload = emptyMap()
                ),
                context = context
            )
        }
        val payloadValidationError = validateJsonPayload(packet.payload)
        if (payloadValidationError != null) {
            recordActionMetric("json.bad_payload", System.nanoTime() - started, false)
            return SessionResult(
                response = codec.encodeResponseParts(
                    requestId = packet.requestId,
                    success = false,
                    code = "PAYLOAD_LIMIT",
                    message = payloadValidationError,
                    payload = emptyMap()
                ),
                context = context
            )
        }

        var nextContext = context
        val action = canonicalAction(packet.action)
        val payload = PayloadReader(packet.payload)
        fun requireSession(): SessionActionResult? = validateSession(payload.trimmedOrNull("sessionId"), context)
        fun requireRole(minimumLevel: Int, minimumRoleName: String): SessionActionResult? =
            authorizeRole(context, minimumLevel, minimumRoleName)
        fun withPlayerSession(block: () -> SessionActionResult): SessionActionResult {
            val sessionError = requireSession()
            if (sessionError != null) return sessionError
            val roleError = requireRole(ROLE_PLAYER, "player")
            if (roleError != null) return roleError
            return block()
        }
        fun withAdminSession(block: () -> SessionActionResult): SessionActionResult {
            val sessionError = requireSession()
            if (sessionError != null) return sessionError
            val roleError = requireRole(ROLE_ADMIN, "admin")
            if (roleError != null) return roleError
            return block()
        }

        val outcome: SessionActionResult = when (action) {
            "negotiate" -> {
                val min = payload.int("minVersion") ?: 1
                val max = payload.int("maxVersion") ?: 1
                if (min <= 1 && max >= 1) {
                    SessionActionResult(
                        success = true,
                        code = "NEGOTIATED",
                        message = "Version negotiated",
                        payload = negotiatedPayload
                    )
                } else {
                    SessionActionResult(
                        success = false,
                        code = "VERSION_UNSUPPORTED",
                        message = "No compatible protocol version"
                    )
                }
            }

            "handshake" -> SessionActionResult(
                success = true,
                code = "HANDSHAKE_OK",
                message = "Handshake successful",
                payload = HashMap<String, String>(handshakePayload.size + 1).apply {
                    put("connectionId", context.connectionId)
                    putAll(handshakePayload)
                }
            )

            "auth" -> {
                val provided = payload.text("secret")
                val role = roleForSecret(provided)
                if (config.authRequired && role == null) {
                    SessionActionResult(false, "AUTH_FAILED", "Invalid secret")
                } else {
                    val grantedRole = role ?: "player"
                    nextContext = issueSession(
                        context.copy(
                            authenticated = true,
                            role = grantedRole,
                            roleLevel = roleLevel(grantedRole)
                        )
                    )
                    SessionActionResult(
                        success = true,
                        code = if (config.authRequired) "AUTH_OK" else "AUTH_SKIPPED",
                        message = if (config.authRequired) "Authenticated" else "Auth optional; session granted",
                        payload = sessionPayload(nextContext)
                    )
                }
            }

            "refresh" -> {
                val sessionCheck = requireSession()
                if (sessionCheck != null) {
                    sessionCheck
                } else {
                    nextContext = issueSession(context)
                    SessionActionResult(
                        success = true,
                        code = "REFRESHED",
                        message = "Session refreshed",
                        payload = sessionPayload(nextContext)
                    )
                }
            }

            "revoke" -> {
                val sessionCheck = requireSession()
                if (sessionCheck != null) {
                    sessionCheck
                } else {
                    nextContext = context.copy(
                        sessionId = null,
                        authenticated = false,
                        currentName = null,
                        role = "guest",
                        roleLevel = ROLE_GUEST,
                        sessionExpiresAtMillis = null
                    )
                    SessionActionResult(
                        success = true,
                        code = "REVOKED",
                        message = "Session revoked",
                        payload = mapOf("connectionId" to context.connectionId)
                    )
                }
            }

            "hello" -> withPlayerSession {
                    val name = payload.trimmed("name")
                    if (name.isEmpty()) {
                        SessionActionResult(false, "BAD_REQUEST", "Missing payload.name")
                    } else {
                        val world = payload.worldOrDefault("world", "world")
                        val x = payload.double("x") ?: 0.0
                        val y = payload.double("y") ?: 64.0
                        val z = payload.double("z") ?: 0.0
                        val joined = handler.joinWithContext(
                            name = name,
                            world = world,
                            x = x,
                            y = y,
                            z = z,
                            context = SessionJoinContext(
                                clientBrand = payload.trimmedOrNull("clientBrand"),
                                clientMods = parseClientMods(
                                    payload.trimmedOrNull("clientMods")
                                        ?: payload.trimmedOrNull("mods")
                                )
                            )
                        )
                        if (joined.success) nextContext = context.copy(currentName = name)
                        joined
                    }
                }

            "move" -> withPlayerSession {
                if (context.currentName == null) {
                    SessionActionResult(false, "NOT_JOINED", "Not joined; send hello first")
                } else {
                    val x = payload.double("x")
                    val y = payload.double("y")
                    val z = payload.double("z")
                    if (x == null || y == null || z == null) {
                        SessionActionResult(false, "BAD_REQUEST", "Missing/invalid payload x,y,z")
                    } else {
                        handler.move(context.currentName, x, y, z, payload.worldOrNull("world"))
                    }
                }
            }

            "lookup" -> withPlayerSession {
                    val name = payload.trimmed("name")
                    if (name.isEmpty()) {
                        SessionActionResult(false, "BAD_REQUEST", "Missing payload.name")
                    } else {
                        handler.lookup(name)
                    }
                }

            "whoami" -> withPlayerSession {
                handler.who(context.currentName)
            }

            "leave" -> withPlayerSession {
                    val target = payload.trimmedOrNull("name") ?: context.currentName
                    if (target.isNullOrBlank()) {
                        SessionActionResult(false, "BAD_REQUEST", "No session player to leave")
                    } else {
                        val left = handler.leave(target)
                        if (target.equals(context.currentName, ignoreCase = true) && left.success) {
                            nextContext = context.copy(currentName = null)
                        }
                        left
                    }
                }

            "world.create" -> withAdminSession {
                    val name = payload.trimmed("name")
                    if (name.isEmpty()) {
                        SessionActionResult(false, "BAD_REQUEST", "Missing payload.name")
                    } else {
                        val seed = payload.long("seed") ?: 0L
                        handler.worldCreate(name, seed)
                    }
                }

            "entity.spawn" -> withAdminSession {
                    val type = payload.trimmed("type")
                    val world = payload.trimmed("world")
                    val x = payload.double("x")
                    val y = payload.double("y")
                    val z = payload.double("z")
                    if (type.isEmpty() || world.isEmpty() || x == null || y == null || z == null) {
                        SessionActionResult(false, "BAD_REQUEST", "Missing payload type/world/x/y/z")
                    } else {
                        handler.entitySpawn(type, world, x, y, z)
                    }
                }

            "inventory.set" -> withAdminSession {
                    val owner = payload.trimmed("owner")
                    val slot = payload.int("slot")
                    val itemId = payload.trimmed("itemId")
                    if (owner.isEmpty() || slot == null || itemId.isEmpty()) {
                        SessionActionResult(false, "BAD_REQUEST", "Missing payload owner/slot/itemId")
                    } else {
                        handler.inventorySet(owner, slot, itemId)
                    }
                }

            "ping" -> SessionActionResult(
                success = true,
                code = "PONG",
                message = "pong",
                payload = sessionPayload(context)
            )

            else -> SessionActionResult(false, "UNKNOWN_ACTION", "Unknown action '$action'")
        }

        val response = codec.encodeResponseParts(
            requestId = packet.requestId,
            success = outcome.success,
            code = outcome.code,
            message = outcome.message,
            payload = outcome.payload
        )
        recordActionMetric("json.$action", System.nanoTime() - started, outcome.success)
        return SessionResult(response = response, context = nextContext)
    }

    private fun roleForSecret(secret: String): String? {
        if (secret.isBlank()) return null
        val admin = config.adminSecret.orEmpty()
        if (admin.isNotBlank() && secret == admin) return "admin"
        val player = config.sharedSecret.orEmpty()
        if (player.isNotBlank() && secret == player) return "player"
        return null
    }

    private fun roleLevel(role: String): Int {
        return when {
            role.equals("admin", ignoreCase = true) -> ROLE_ADMIN
            role.equals("player", ignoreCase = true) -> ROLE_PLAYER
            else -> ROLE_GUEST
        }
    }

    private fun authorizeRole(context: SessionContext, minimumLevel: Int, minimumRole: String): SessionActionResult? {
        if (context.roleLevel < minimumLevel) {
            return SessionActionResult(
                success = false,
                code = "FORBIDDEN",
                message = "Requires role '$minimumRole'"
            )
        }
        return null
    }

    private fun validateSession(sessionId: String?, context: SessionContext): SessionActionResult? {
        if (config.authRequired && !context.authenticated) {
            return SessionActionResult(false, "UNAUTHORIZED", "Authenticate first via 'auth'")
        }
        val sid = sessionId.orEmpty()
        if (sid.isBlank()) {
            return SessionActionResult(false, "MISSING_SESSION", "Missing payload.sessionId")
        }
        if (context.sessionId == null || sid != context.sessionId) {
            return SessionActionResult(false, "INVALID_SESSION", "Invalid sessionId")
        }
        val expiresAt = context.sessionExpiresAtMillis
        if (expiresAt == null || System.currentTimeMillis() >= expiresAt) {
            return SessionActionResult(false, "SESSION_EXPIRED", "Session expired")
        }
        return null
    }

    private fun issueSession(context: SessionContext): SessionContext {
        val ttlMillis = (config.sessionTtlSeconds.coerceAtLeast(1L)) * 1000L
        return context.copy(
            sessionId = context.sessionId ?: UUID.randomUUID().toString(),
            sessionExpiresAtMillis = System.currentTimeMillis() + ttlMillis
        )
    }

    private fun sessionPayload(context: SessionContext): Map<String, String> {
        val sid = context.sessionId
        val expires = context.sessionExpiresAtMillis
        if (sid == null && expires == null) {
            return mapOf(
                "connectionId" to context.connectionId,
                "role" to context.role
            )
        }
        return HashMap<String, String>(4).apply {
            put("connectionId", context.connectionId)
            put("role", context.role)
            if (sid != null) put("sessionId", sid)
            if (expires != null) put("expiresAt", expires.toString())
        }
    }

    private fun legacySessionError(context: SessionContext): String? {
        if (!config.authRequired) return null
        if (!context.authenticated) return "ERR authenticate first using AUTH <secret>"
        val expiresAt = context.sessionExpiresAtMillis ?: return "ERR session missing"
        if (System.currentTimeMillis() >= expiresAt) return "ERR session expired; run AUTH again"
        return null
    }

    private fun legacyRoleError(context: SessionContext, minimumRole: String): String? {
        val minimumLevel = roleLevel(minimumRole)
        return if (context.roleLevel < minimumLevel) {
            "ERR forbidden; requires role '$minimumRole'"
        } else {
            null
        }
    }

    private fun processLegacyLine(line: String, context: SessionContext): SessionResult {
        val started = System.nanoTime()
        var nextContext = context
        val parts = splitWhitespace(line)
        if (parts.isEmpty()) return SessionResult("ERR empty command", context)
        val command = canonicalLegacyCommand(parts.first())
        val result = when (command) {
            "AUTH" -> {
                val secret = parts.getOrNull(1).orEmpty()
                val role = roleForSecret(secret)
                if (config.authRequired && role == null) {
                    SessionResult("ERR invalid secret", context)
                } else {
                    val grantedRole = role ?: "player"
                    nextContext = issueSession(
                        context.copy(
                            authenticated = true,
                            role = grantedRole,
                            roleLevel = roleLevel(grantedRole)
                        )
                    )
                    SessionResult("OK authenticated sid=${nextContext.sessionId} role=${nextContext.role}", nextContext)
                }
            }

            "HELLO" -> {
                val sessionError = legacySessionError(context)
                val roleError = legacyRoleError(context, "player")
                val name = parts.getOrNull(1)
                if (name.isNullOrBlank()) {
                    SessionResult("ERR usage: HELLO <name> [world] [x] [y] [z]", context)
                } else if (sessionError != null) {
                    SessionResult(sessionError, context)
                } else if (roleError != null) {
                    SessionResult(roleError, context)
                } else {
                    val world = parts.getOrNull(2) ?: "world"
                    val x = parts.getOrNull(3)?.toDoubleOrNull() ?: 0.0
                    val y = parts.getOrNull(4)?.toDoubleOrNull() ?: 64.0
                    val z = parts.getOrNull(5)?.toDoubleOrNull() ?: 0.0
                    val result = handler.joinWithContext(
                        name = name,
                        world = world,
                        x = x,
                        y = y,
                        z = z,
                        context = SessionJoinContext(
                            clientBrand = parts.getOrNull(6),
                            clientMods = parseClientMods(parts.getOrNull(7))
                        )
                    )
                    if (result.success) {
                        nextContext = issueSession(context.copy(currentName = name))
                        SessionResult("OK ${result.message} sid=${nextContext.sessionId} role=${nextContext.role}", nextContext)
                    } else {
                        SessionResult("ERR ${result.message}", context)
                    }
                }
            }

            "MOVE" -> {
                val sessionError = legacySessionError(context)
                val roleError = legacyRoleError(context, "player")
                if (sessionError != null) {
                    SessionResult(sessionError, context)
                } else if (roleError != null) {
                    SessionResult(roleError, context)
                } else if (context.currentName == null) {
                    SessionResult("ERR not joined; use HELLO first", context)
                } else {
                    val x = parts.getOrNull(1)?.toDoubleOrNull()
                    val y = parts.getOrNull(2)?.toDoubleOrNull()
                    val z = parts.getOrNull(3)?.toDoubleOrNull()
                    if (x == null || y == null || z == null) {
                        SessionResult("ERR usage: MOVE <x> <y> <z> [world]", context)
                    } else {
                        val world = parts.getOrNull(4)
                        val result = handler.move(context.currentName, x, y, z, world)
                        SessionResult("${if (result.success) "OK" else "ERR"} ${result.message}", context)
                    }
                }
            }

            "LOOKUP" -> {
                val sessionError = legacySessionError(context)
                val roleError = legacyRoleError(context, "player")
                if (sessionError != null) {
                    SessionResult(sessionError, context)
                } else if (roleError != null) {
                    SessionResult(roleError, context)
                } else {
                    val target = parts.getOrNull(1)
                    if (target.isNullOrBlank()) {
                        SessionResult("ERR usage: LOOKUP <name>", context)
                    } else {
                        val result = handler.lookup(target)
                        SessionResult("${if (result.success) "OK" else "ERR"} ${result.message}", context)
                    }
                }
            }

            "WHOAMI" -> {
                val sessionError = legacySessionError(context)
                val roleError = legacyRoleError(context, "player")
                if (sessionError != null) {
                    SessionResult(sessionError, context)
                } else if (roleError != null) {
                    SessionResult(roleError, context)
                } else {
                    val result = handler.who(context.currentName)
                    SessionResult("${if (result.success) "OK" else "ERR"} ${result.message}", context)
                }
            }

            "LEAVE" -> {
                val sessionError = legacySessionError(context)
                val roleError = legacyRoleError(context, "player")
                if (sessionError != null) {
                    SessionResult(sessionError, context)
                } else if (roleError != null) {
                    SessionResult(roleError, context)
                } else {
                    val target = parts.getOrNull(1) ?: context.currentName
                    if (target.isNullOrBlank()) {
                        SessionResult("ERR usage: LEAVE [name]", context)
                    } else {
                        val result = handler.leave(target)
                        if (target.equals(context.currentName, ignoreCase = true) && result.success) {
                            nextContext = context.copy(currentName = null)
                        }
                        SessionResult("${if (result.success) "OK" else "ERR"} ${result.message}", nextContext)
                    }
                }
            }

            "WORLDCREATE" -> {
                val sessionError = legacySessionError(context)
                val roleError = legacyRoleError(context, "admin")
                if (sessionError != null) {
                    SessionResult(sessionError, context)
                } else if (roleError != null) {
                    SessionResult(roleError, context)
                } else {
                    val name = parts.getOrNull(1)
                    if (name.isNullOrBlank()) {
                        SessionResult("ERR usage: WORLDCREATE <name> [seed]", context)
                    } else {
                        val seed = parts.getOrNull(2)?.toLongOrNull() ?: 0L
                        val result = handler.worldCreate(name, seed)
                        SessionResult("${if (result.success) "OK" else "ERR"} ${result.message}", context)
                    }
                }
            }

            "ENTITYSPAWN" -> {
                val sessionError = legacySessionError(context)
                val roleError = legacyRoleError(context, "admin")
                if (sessionError != null) {
                    SessionResult(sessionError, context)
                } else if (roleError != null) {
                    SessionResult(roleError, context)
                } else {
                    val type = parts.getOrNull(1)
                    val world = parts.getOrNull(2)
                    val x = parts.getOrNull(3)?.toDoubleOrNull()
                    val y = parts.getOrNull(4)?.toDoubleOrNull()
                    val z = parts.getOrNull(5)?.toDoubleOrNull()
                    if (type.isNullOrBlank() || world.isNullOrBlank() || x == null || y == null || z == null) {
                        SessionResult("ERR usage: ENTITYSPAWN <type> <world> <x> <y> <z>", context)
                    } else {
                        val result = handler.entitySpawn(type, world, x, y, z)
                        SessionResult("${if (result.success) "OK" else "ERR"} ${result.message}", context)
                    }
                }
            }

            "INVENTORYSET" -> {
                val sessionError = legacySessionError(context)
                val roleError = legacyRoleError(context, "admin")
                if (sessionError != null) {
                    SessionResult(sessionError, context)
                } else if (roleError != null) {
                    SessionResult(roleError, context)
                } else {
                    val owner = parts.getOrNull(1)
                    val slot = parts.getOrNull(2)?.toIntOrNull()
                    val itemId = parts.getOrNull(3)
                    if (owner.isNullOrBlank() || slot == null || itemId.isNullOrBlank()) {
                        SessionResult("ERR usage: INVENTORYSET <owner> <slot> <itemId>", context)
                    } else {
                        val result = handler.inventorySet(owner, slot, itemId)
                        SessionResult("${if (result.success) "OK" else "ERR"} ${result.message}", context)
                    }
                }
            }

            "REVOKE" -> {
                nextContext = context.copy(
                    sessionId = null,
                    authenticated = false,
                    currentName = null,
                    role = "guest",
                    roleLevel = ROLE_GUEST,
                    sessionExpiresAtMillis = null
                )
                SessionResult("OK session revoked", nextContext)
            }

            "QUIT", "EXIT" -> SessionResult("BYE", context)
            else -> SessionResult("ERR unknown command", context)
        }
        val ok = result.response.startsWith("OK") || result.response == "BYE"
        recordActionMetric("legacy.${command.lowercase()}", System.nanoTime() - started, ok)
        return result
    }

    private fun canonicalAction(raw: String): String {
        return when {
            raw.equals("negotiate", ignoreCase = true) -> "negotiate"
            raw.equals("handshake", ignoreCase = true) -> "handshake"
            raw.equals("auth", ignoreCase = true) -> "auth"
            raw.equals("refresh", ignoreCase = true) -> "refresh"
            raw.equals("revoke", ignoreCase = true) -> "revoke"
            raw.equals("hello", ignoreCase = true) -> "hello"
            raw.equals("move", ignoreCase = true) -> "move"
            raw.equals("lookup", ignoreCase = true) -> "lookup"
            raw.equals("whoami", ignoreCase = true) -> "whoami"
            raw.equals("leave", ignoreCase = true) -> "leave"
            raw.equals("world.create", ignoreCase = true) -> "world.create"
            raw.equals("entity.spawn", ignoreCase = true) -> "entity.spawn"
            raw.equals("inventory.set", ignoreCase = true) -> "inventory.set"
            raw.equals("ping", ignoreCase = true) -> "ping"
            else -> raw.lowercase()
        }
    }

    private fun canonicalLegacyCommand(raw: String): String {
        return when {
            raw.equals("AUTH", ignoreCase = true) -> "AUTH"
            raw.equals("HELLO", ignoreCase = true) -> "HELLO"
            raw.equals("MOVE", ignoreCase = true) -> "MOVE"
            raw.equals("LOOKUP", ignoreCase = true) -> "LOOKUP"
            raw.equals("WHOAMI", ignoreCase = true) -> "WHOAMI"
            raw.equals("LEAVE", ignoreCase = true) -> "LEAVE"
            raw.equals("WORLDCREATE", ignoreCase = true) -> "WORLDCREATE"
            raw.equals("ENTITYSPAWN", ignoreCase = true) -> "ENTITYSPAWN"
            raw.equals("INVENTORYSET", ignoreCase = true) -> "INVENTORYSET"
            raw.equals("REVOKE", ignoreCase = true) -> "REVOKE"
            raw.equals("QUIT", ignoreCase = true) -> "QUIT"
            raw.equals("EXIT", ignoreCase = true) -> "EXIT"
            else -> raw.uppercase()
        }
    }

    private fun parseClientMods(raw: String?): Set<String> {
        if (raw.isNullOrBlank()) return emptySet()
        return raw
            .split(',', ';', '|')
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toCollection(LinkedHashSet())
    }

    private fun recordActionMetric(action: String, durationNanos: Long, success: Boolean) {
        actionMetrics.computeIfAbsent(action) { MutableActionMetric() }.record(durationNanos, success)
    }

    private fun allowConnection(socket: Socket, remoteIp: String): Boolean {
        while (true) {
            val current = activeSessions.get()
            if (current >= config.maxConcurrentSessions) {
                recordActionMetric("transport.connection_limit", 0L, false)
                audit("connection_limit", remoteIp, null, "maxConcurrentSessions reached")
                runCatching { socket.close() }
                return false
            }
            if (activeSessions.compareAndSet(current, current + 1)) break
        }
        val counter = sessionsPerIp.computeIfAbsent(remoteIp) { AtomicInteger(0) }
        val next = counter.incrementAndGet()
        if (next > config.maxSessionsPerIp) {
            counter.decrementAndGet()
            activeSessions.decrementAndGet()
            recordActionMetric("transport.per_ip_connection_limit", 0L, false)
            audit("per_ip_connection_limit", remoteIp, null, "maxSessionsPerIp reached")
            runCatching { socket.close() }
            return false
        }
        return true
    }

    private fun releaseConnection(remoteIp: String) {
        activeSessions.updateAndGet { current -> (current - 1).coerceAtLeast(0) }
        val counter = sessionsPerIp[remoteIp] ?: return
        val remaining = counter.decrementAndGet()
        if (remaining <= 0) {
            sessionsPerIp.remove(remoteIp, counter)
        }
    }

    private fun allowRequest(remoteIp: String, connectionWindow: RateWindow): Boolean {
        val now = System.currentTimeMillis()
        if (!allowInWindow(connectionWindow, now, config.maxRequestsPerMinutePerConnection)) {
            recordActionMetric("transport.connection_rate_limit", 0L, false)
            return false
        }
        val ipWindow = requestsPerIp.computeIfAbsent(remoteIp) { RateWindow() }
        if (!allowInWindow(ipWindow, now, config.maxRequestsPerMinutePerIp)) {
            rollbackWindow(connectionWindow)
            recordActionMetric("transport.ip_rate_limit", 0L, false)
            return false
        }
        return true
    }

    private fun rollbackWindow(window: RateWindow) {
        synchronized(window) {
            if (window.size <= 0) return
            if (window.calls.isNotEmpty()) {
                window.calls.removeLast()
            }
            window.size = (window.size - 1).coerceAtLeast(0)
        }
    }

    private fun allowInWindow(window: RateWindow, now: Long, limit: Int): Boolean {
        val windowStart = now - 60_000L
        synchronized(window) {
            while (true) {
                val head = window.calls.peekFirst() ?: break
                if (head >= windowStart) break
                window.calls.removeFirst()
                window.size--
            }
            if (window.size >= limit) return false
            window.calls.addLast(now)
            window.size++
            return true
        }
    }

    private fun validateJsonPayload(payload: Map<String, String>): String? {
        if (payload.size > config.maxJsonPayloadEntries) {
            return "Payload exceeds ${config.maxJsonPayloadEntries} entries"
        }
        var totalChars = 0
        for ((key, value) in payload) {
            if (key.isBlank() || key.length > config.maxJsonPayloadKeyChars) {
                return "Payload contains invalid key"
            }
            if (value.length > config.maxJsonPayloadValueChars) {
                return "Payload contains oversized value"
            }
            totalChars += key.length + value.length
            if (totalChars > config.maxJsonPayloadTotalChars) {
                return "Payload exceeds ${config.maxJsonPayloadTotalChars} chars"
            }
        }
        return null
    }

    private fun rateLimitResponse(line: String): String {
        return if (looksLikeJson(line)) {
            rateLimitJsonResponse
        } else {
            "ERR rate limit exceeded"
        }
    }

    private fun looksLikeJson(input: String): Boolean {
        if (input.isEmpty()) return false
        val first = input[0]
        if (first == '{') return true
        if (!first.isWhitespace()) return false
        return firstNonWhitespaceChar(input) == '{'
    }

    private fun audit(kind: String, remoteIp: String, connectionId: String?, detail: String) {
        if (!config.auditLogEnabled) return
        if (kind == "invalid_frame") {
            val now = System.currentTimeMillis()
            val last = invalidFrameAuditByIp.computeIfAbsent(remoteIp) { AtomicLong(0L) }
            val previous = last.get()
            if (previous > 0L && now - previous < 5_000L) {
                return
            }
            last.set(now)
        }
        val cid = connectionId ?: "-"
        logger.info("[net-audit] kind=$kind ip=$remoteIp connectionId=$cid detail=\"$detail\"")
    }

    fun metrics(): StandaloneNetMetricsSnapshot {
        val total = totalRequests.get()
        return StandaloneNetMetricsSnapshot(
            totalRequests = total,
            jsonRequests = jsonRequests.get(),
            legacyRequests = legacyRequests.get(),
            averageRequestNanos = if (total <= 0L) 0L else totalRequestNanos.get() / total,
            actionMetrics = actionMetrics.entries.associate { it.key to it.value.snapshot() }.toSortedMap(),
            minecraftBridge = MinecraftBridgeMetricsSnapshot(
                handshakesDetected = minecraftHandshakesDetected.get(),
                proxiedSessions = minecraftProxiedSessions.get(),
                activeProxiedSessions = minecraftActiveProxiedSessions.get(),
                bytesClientToUpstream = minecraftBytesClientToUpstream.get(),
                bytesUpstreamToClient = minecraftBytesUpstreamToClient.get(),
                connectFailures = minecraftBridgeConnectFailures.get(),
                averageProxySessionNanos = if (minecraftProxiedSessions.get() <= 0L) 0L else minecraftProxyTotalNanos.get() / minecraftProxiedSessions.get(),
                maxProxySessionNanos = minecraftProxyMaxNanos.get(),
                nativeLoginAttempts = minecraftNativeLoginAttempts.get(),
                nativeLoginRejected = minecraftNativeLoginRejected.get()
            )
        )
    }

    private fun splitWhitespace(input: String): List<String> {
        val out = ArrayList<String>(8)
        val len = input.length
        var i = 0
        while (i < len) {
            while (i < len && input[i].isWhitespace()) i++
            if (i >= len) break
            val start = i
            while (i < len && !input[i].isWhitespace()) i++
            out.add(input.substring(start, i))
        }
        return out
    }

    private data class TextLineRead(
        val value: String,
        val oversize: Boolean
    )

    private fun readTextLine(input: PushbackInputStream): TextLineRead? {
        val maxBytes = config.maxTextLineBytes
        val bytes = ByteArray(maxBytes)
        var count = 0
        var sawAny = false
        while (true) {
            val next = input.read()
            if (next < 0) {
                if (!sawAny) return null
                break
            }
            sawAny = true
            if (next == '\n'.code) break
            if (next == '\r'.code) continue
            if (count >= maxBytes) {
                while (true) {
                    val drain = input.read()
                    if (drain < 0 || drain == '\n'.code) break
                }
                return TextLineRead(value = "", oversize = true)
            }
            bytes[count] = next.toByte()
            count += 1
        }
        return TextLineRead(
            value = String(bytes, 0, count, Charsets.UTF_8),
            oversize = false
        )
    }

    private fun firstNonWhitespaceChar(input: String): Char? {
        for (ch in input) {
            if (!ch.isWhitespace()) return ch
        }
        return null
    }

    private fun writeLine(writer: BufferedWriter, text: String, flushNow: Boolean) {
        writer.write(text)
        writer.newLine()
        if (flushNow) writer.flush()
    }

    private fun readFrame(input: DataInputStream, firstLengthByte: Int?): String? {
        val len = if (firstLengthByte != null) {
            val b1 = firstLengthByte and 0xFF
            val b2 = input.readUnsignedByte()
            val b3 = input.readUnsignedByte()
            val b4 = input.readUnsignedByte()
            (b1 shl 24) or (b2 shl 16) or (b3 shl 8) or b4
        } else {
            runCatching { input.readInt() }.getOrNull() ?: return null
        }
        if (len <= 0 || len > config.maxFrameBytes) {
            throw IllegalStateException("Invalid frame size: $len")
        }
        val bytes = ByteArray(len)
        input.readFully(bytes)
        return bytes.toString(Charsets.UTF_8)
    }

    private fun writeFrame(output: DataOutputStream, payload: String, flushNow: Boolean) {
        val bytes = payload.toByteArray(Charsets.UTF_8)
        require(bytes.size <= config.maxFrameBytes) { "Response frame exceeds maxFrameBytes" }
        output.writeInt(bytes.size)
        output.write(bytes)
        if (flushNow) output.flush()
    }
}
