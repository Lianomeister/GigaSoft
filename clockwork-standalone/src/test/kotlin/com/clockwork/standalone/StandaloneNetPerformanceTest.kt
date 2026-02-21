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
import java.util.Locale
import kotlin.system.measureNanoTime
import kotlin.test.Test
import kotlin.test.assertTrue

@Tag("performance")
class StandaloneNetPerformanceTest {
    private val mapper = ObjectMapper()

    @Test
    fun `net ping baseline`() {
        val root = Files.createTempDirectory("clockwork-standalone-net-perf")
        val core = newCore(root)
        core.start()
        val net = StandaloneNetServer(
            config = StandaloneNetConfig(
                host = "127.0.0.1",
                port = 0,
                authRequired = false,
                maxRequestsPerMinutePerConnection = 50_000,
                maxRequestsPerMinutePerIp = 50_000
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

                repeat(100) { idx ->
                    val warm = sendJson(reader, writer, "ping", "w$idx")
                    assertTrue(warm["success"] == true)
                }

                val iterations = 1_000
                val rounds = 9
                val samplesMicros = DoubleArray(rounds)
                repeat(rounds) { round ->
                    val nanos = measureNanoTime {
                        repeat(iterations) { idx ->
                            val response = sendJson(reader, writer, "ping", "p${round}_$idx")
                            assertTrue(response["success"] == true)
                        }
                    }
                    samplesMicros[round] = (nanos / iterations) / 1_000.0
                }
                val metrics = net.metrics()
                val ping = metrics.actionMetrics["json.ping"]
                val p50Micros = percentile(samplesMicros, 0.50)
                val p95Micros = percentile(samplesMicros, 0.95)
                assertTrue(p50Micros < 2_200.0, "Net ping p50 regression: $p50Micros us")
                assertTrue(p95Micros < 3_200.0, "Net ping p95 regression: $p95Micros us")
                assertTrue(metrics.averageRequestNanos < 500_000L, "Net request regression: ${metrics.averageRequestNanos}ns")
                println("PERF_V2 metric=standalone.net.ping.per_request_micros p50=${formatMetric(p50Micros)} p95=${formatMetric(p95Micros)} unit=micros")
                println(
                    "PERF_V2 metric=standalone.net.server_average_request_ns p50=${formatMetric(metrics.averageRequestNanos.toDouble())} " +
                        "p95=${formatMetric((ping?.averageNanos ?: metrics.averageRequestNanos).toDouble())} unit=ns"
                )
            }
        } finally {
            net.stop()
            core.stop()
        }
    }

    @Test
    fun `standalone cold start latency baseline`() {
        val rounds = 7
        val samplesMs = DoubleArray(rounds)
        repeat(rounds) { round ->
            val root = Files.createTempDirectory("clockwork-standalone-cold-start-$round")
            val nanos = measureNanoTime {
                val core = newCore(root)
                core.start()
                try {
                    Thread.sleep(120)
                    assertTrue(core.status().running)
                } finally {
                    core.stop()
                }
            }
            samplesMs[round] = nanos / 1_000_000.0
        }
        val p50Ms = percentile(samplesMs, 0.50)
        val p95Ms = percentile(samplesMs, 0.95)
        assertTrue(p50Ms < 750.0, "Standalone cold start p50 regression: ${p50Ms}ms")
        assertTrue(p95Ms < 1_600.0, "Standalone cold start p95 regression: ${p95Ms}ms")
        println("PERF_V2 metric=standalone.cold_start.ms p50=${formatMetric(p50Ms)} p95=${formatMetric(p95Ms)} unit=ms")
    }

    @Test
    fun `tick jitter baseline`() {
        val root = Files.createTempDirectory("clockwork-standalone-jitter")
        val core = newCore(root)
        core.start()
        try {
            val rounds = 9
            val jitterMsSamples = DoubleArray(rounds)
            repeat(rounds) { round ->
                Thread.sleep(120)
                val status = core.status()
                assertTrue(status.tickCount > 0L)
                jitterMsSamples[round] = status.averageTickJitterNanos / 1_000_000.0
            }
            val p50Ms = percentile(jitterMsSamples, 0.50)
            val p95Ms = percentile(jitterMsSamples, 0.95)
            assertTrue(p50Ms < 2.5, "Tick jitter p50 regression: ${p50Ms}ms")
            assertTrue(p95Ms < 8.0, "Tick jitter p95 regression: ${p95Ms}ms")
            println("PERF_V2 metric=standalone.tick_jitter.ms p50=${formatMetric(p50Ms)} p95=${formatMetric(p95Ms)} unit=ms")
        } finally {
            core.stop()
        }
    }

    private fun newCore(root: java.nio.file.Path): GigaStandaloneCore {
        return GigaStandaloneCore(
            config = StandaloneCoreConfig(
                pluginsDirectory = root.resolve("plugins"),
                dataDirectory = root.resolve("data"),
                tickPeriodMillis = 1L,
                autoSaveEveryTicks = 0L
            ),
            logger = {}
        )
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

    private fun percentile(samples: DoubleArray, p: Double): Double {
        require(samples.isNotEmpty()) { "samples must not be empty" }
        val sorted = samples.sortedArray()
        val index = ((sorted.lastIndex) * p).toInt().coerceIn(0, sorted.lastIndex)
        return sorted[index]
    }

    private fun formatMetric(value: Double): String = String.format(Locale.US, "%.3f", value)
}
