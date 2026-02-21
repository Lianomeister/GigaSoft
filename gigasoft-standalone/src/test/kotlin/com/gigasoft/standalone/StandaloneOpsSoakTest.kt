package com.gigasoft.standalone

import com.fasterxml.jackson.databind.ObjectMapper
import com.gigasoft.core.GigaStandaloneCore
import com.gigasoft.core.StandaloneCoreConfig
import com.gigasoft.net.SessionActionResult
import com.gigasoft.net.StandaloneNetConfig
import com.gigasoft.net.StandaloneNetServer
import com.gigasoft.net.StandaloneSessionHandler
import org.junit.jupiter.api.Tag
import java.net.Socket
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

@Tag("soak")
class StandaloneOpsSoakTest {
    private val mapper = ObjectMapper()

    @Test
    fun `net and diagnostics stay stable under sustained load`() {
        val root = Files.createTempDirectory("gigasoft-standalone-ops-soak")
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
                maxRequestsPerMinutePerConnection = 20_000,
                maxRequestsPerMinutePerIp = 20_000
            ),
            logger = {},
            handler = object : StandaloneSessionHandler {
                override fun join(name: String, world: String, x: Double, y: Double, z: Double): SessionActionResult = SessionActionResult(true, "JOINED", "ok")
                override fun leave(name: String): SessionActionResult = SessionActionResult(true, "LEFT", "ok")
                override fun move(name: String, x: Double, y: Double, z: Double, world: String?): SessionActionResult = SessionActionResult(true, "MOVED", "ok")
                override fun lookup(name: String): SessionActionResult = SessionActionResult(true, "FOUND", "ok")
                override fun who(name: String?): SessionActionResult = SessionActionResult(true, "WHOAMI", "ok")
                override fun worldCreate(name: String, seed: Long): SessionActionResult = SessionActionResult(true, "WORLD_CREATED", "ok")
                override fun entitySpawn(type: String, world: String, x: Double, y: Double, z: Double): SessionActionResult = SessionActionResult(true, "ENTITY_SPAWNED", "ok")
                override fun inventorySet(owner: String, slot: Int, itemId: String): SessionActionResult = SessionActionResult(true, "INVENTORY_UPDATED", "ok")
            }
        )
        net.start()
        try {
            val port = waitForPort(net)
            Socket("127.0.0.1", port).use { socket ->
                val reader = socket.getInputStream().bufferedReader()
                val writer = socket.getOutputStream().bufferedWriter()
                repeat(5_000) { idx ->
                    val request = mapOf(
                        "protocol" to "gigasoft-standalone-net",
                        "version" to 1,
                        "requestId" to "soak-$idx",
                        "action" to "ping",
                        "payload" to emptyMap<String, String>()
                    )
                    writer.write(mapper.writeValueAsString(request))
                    writer.newLine()
                    writer.flush()
                    val response = mapper.readTree(reader.readLine())
                    assertTrue(response.path("success").asBoolean())
                }
            }
            val statusJson = mapper.writeValueAsString(statusView(core, net, emptyList()))
            val status = mapper.readTree(statusJson)
            assertTrue(status.path("core").path("running").asBoolean())
            assertTrue(status.path("net").path("totalRequests").asLong() >= 5_000L)
        } finally {
            net.stop()
            core.stop()
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
}
