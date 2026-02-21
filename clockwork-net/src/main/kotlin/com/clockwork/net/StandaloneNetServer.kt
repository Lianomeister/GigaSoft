package com.clockwork.net

import com.clockwork.api.GigaLogger
import java.io.BufferedWriter
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.PushbackInputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.ArrayDeque
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
    val frameFlushEveryResponses: Int = 1
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
    val actionMetrics: Map<String, NetActionMetricSnapshot>
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
    }

    private val codec = NetPacketCodec()
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
        logger.info("Session connected: $remote")
        try {
            val rawIn = socket.getInputStream()
            val rawOut = socket.getOutputStream()
            val first = rawIn.read()
            if (first < 0) return

            val isText = first == '{'.code ||
                (first in 'A'.code..'Z'.code) ||
                (first in 'a'.code..'z'.code)

            if (isText) {
                val input = PushbackInputStream(rawIn, 1)
                input.unread(first)
                rawOut.bufferedWriter().use { writer ->
                    var pendingTextResponses = 0
                    while (running.get()) {
                        val line = readTextLine(input) ?: break
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
                val dataIn = DataInputStream(rawIn)
                val dataOut = DataOutputStream(rawOut)
                var pendingFrames = 0
                var nextFrame = readFrame(dataIn, first)
                while (running.get() && nextFrame != null) {
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
        }
        sessions.remove(socket)
        releaseConnection(remoteIp)
        runCatching { socket.close() }
        logger.info("Session disconnected: $remote")
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
                        val joined = handler.join(name, world, x, y, z)
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
                    val result = handler.join(name, world, x, y, z)
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
            actionMetrics = actionMetrics.entries.associate { it.key to it.value.snapshot() }.toSortedMap()
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
