package com.clockwork.standalone

import com.fasterxml.jackson.databind.ObjectMapper
import com.clockwork.core.GigaStandaloneCore
import com.clockwork.core.StandaloneCoreConfig
import com.clockwork.net.SessionActionResult
import com.clockwork.net.StandaloneNetConfig
import com.clockwork.net.StandaloneNetServer
import com.clockwork.net.StandaloneSessionHandler
import org.junit.jupiter.api.Tag
import java.net.Socket
import java.nio.file.Files
import kotlin.system.measureNanoTime
import kotlin.test.Test
import kotlin.test.assertTrue

@Tag("performance")
class StandaloneNetPerformanceTest {
    private val mapper = ObjectMapper()

    @Test
    fun `net ping baseline`() {
        val root = Files.createTempDirectory("clockwork-standalone-net-perf")
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
            config = StandaloneNetConfig(host = "127.0.0.1", port = 0, authRequired = false),
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

                repeat(100) { idx ->
                    val warm = sendJson(reader, writer, "ping", "w$idx")
                    assertTrue(warm["success"] == true)
                }

                val iterations = 1_000
                val rounds = 5
                val samples = LongArray(rounds)
                repeat(rounds) { round ->
                    samples[round] = measureNanoTime {
                        repeat(iterations) { idx ->
                            val response = sendJson(reader, writer, "ping", "p${round}_$idx")
                            assertTrue(response["success"] == true)
                        }
                    }
                }
                val median = samples.sorted()[rounds / 2]
                val metrics = net.metrics()
                val ping = metrics.actionMetrics["json.ping"]
                val perPingMicros = median / iterations / 1_000.0
                assertTrue(perPingMicros < 1_500.0, "Net ping regression: $perPingMicros us")
                assertTrue(metrics.averageRequestNanos < 500_000L, "Net request regression: ${metrics.averageRequestNanos}ns")
                println(
                    "PERF standalone.net.ping iterations=$iterations rounds=$rounds " +
                        "medianMs=${median / 1_000_000.0} perPingMicros=${median / iterations / 1_000.0} " +
                        "serverAvgReqNs=${metrics.averageRequestNanos} serverPingAvgNs=${ping?.averageNanos ?: 0L}"
                )
            }
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

    private fun sendJson(
        reader: java.io.BufferedReader,
        writer: java.io.BufferedWriter,
        action: String,
        requestId: String
    ): Map<*, *> {
        val request = mapOf(
            "protocol" to "clockwork-standalone-net",
            "version" to 1,
            "requestId" to requestId,
            "action" to action,
            "payload" to emptyMap<String, String>()
        )
        writer.write(mapper.writeValueAsString(request))
        writer.newLine()
        writer.flush()
        val line = reader.readLine()
        return mapper.readValue(line, Map::class.java)
    }
}
