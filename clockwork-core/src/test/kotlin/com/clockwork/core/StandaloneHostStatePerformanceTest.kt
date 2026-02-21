package com.clockwork.core

import org.junit.jupiter.api.Tag
import java.util.Locale
import kotlin.system.measureNanoTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Tag("performance")
class StandaloneHostStatePerformanceTest {
    @Test
    fun `host state mutation baseline`() {
        val players = 2_000
        val entitySpawn = 5_000
        val rounds = 5
        val joinSamples = LongArray(rounds)
        val moveSamples = LongArray(rounds)
        val spawnSamples = LongArray(rounds)

        repeat(rounds) { round ->
            val state = StandaloneHostState()
            joinSamples[round] = measureNanoTime {
                repeat(players) { idx ->
                    state.joinPlayer(name = "p$idx", world = "world", x = idx.toDouble(), y = 64.0, z = idx.toDouble())
                }
            }
            assertEquals(players, state.onlinePlayerCount())

            moveSamples[round] = measureNanoTime {
                repeat(players) { idx ->
                    state.movePlayer(name = "p$idx", x = idx + 1.0, y = 65.0, z = idx + 1.0, world = if (idx % 2 == 0) "world_nether" else null)
                }
            }

            spawnSamples[round] = measureNanoTime {
                repeat(entitySpawn) { idx ->
                    state.spawnEntity(type = "zombie", world = "world", x = idx.toDouble(), y = 65.0, z = idx.toDouble())
                }
            }
        }

        val joinMillis = joinSamples.map { it / 1_000_000.0 }.toDoubleArray()
        val moveMillis = moveSamples.map { it / 1_000_000.0 }.toDoubleArray()
        val spawnMillis = spawnSamples.map { it / 1_000_000.0 }.toDoubleArray()
        val joinP50 = percentile(joinMillis, 0.50)
        val joinP95 = percentile(joinMillis, 0.95)
        val moveP50 = percentile(moveMillis, 0.50)
        val moveP95 = percentile(moveMillis, 0.95)
        val spawnP50 = percentile(spawnMillis, 0.50)
        val spawnP95 = percentile(spawnMillis, 0.95)
        assertTrue(joinP50 < 250.0, "Join p50 regression: ${joinP50}ms")
        assertTrue(joinP95 < 350.0, "Join p95 regression: ${joinP95}ms")
        assertTrue(moveP50 < 150.0, "Move p50 regression: ${moveP50}ms")
        assertTrue(moveP95 < 250.0, "Move p95 regression: ${moveP95}ms")
        assertTrue(spawnP50 < 350.0, "Spawn p50 regression: ${spawnP50}ms")
        assertTrue(spawnP95 < 500.0, "Spawn p95 regression: ${spawnP95}ms")
        println("PERF_V2 metric=core.hoststate.join_ms p50=${formatMetric(joinP50)} p95=${formatMetric(joinP95)} unit=ms")
        println("PERF_V2 metric=core.hoststate.move_ms p50=${formatMetric(moveP50)} p95=${formatMetric(moveP95)} unit=ms")
        println("PERF_V2 metric=core.hoststate.spawn_ms p50=${formatMetric(spawnP50)} p95=${formatMetric(spawnP95)} unit=ms")
    }

    @Test
    fun `chunk loading and block access baseline`() {
        val rounds = 7
        val blockWrites = 40_000
        val writeMillis = DoubleArray(rounds)
        val readMillis = DoubleArray(rounds)
        var lastLoadedChunks = 0
        var lastChunkLoads = 0L

        repeat(rounds) { round ->
            val state = StandaloneHostState(
                chunkViewDistance = 4,
                maxChunkLoadsPerTick = 256,
                maxLoadedChunksPerWorld = 2048
            )
            state.joinPlayer("Perf-$round", "world", 0.0, 64.0, 0.0)

            writeMillis[round] = measureNanoTime {
                repeat(blockWrites) { idx ->
                    val x = (idx % 400) - 200
                    val z = ((idx / 400) % 400) - 200
                    val y = 64 + (idx % 4)
                    state.setBlock("world", x, y, z, "stone")
                    if (idx % 200 == 0) {
                        state.tickWorlds()
                    }
                }
            } / 1_000_000.0

            readMillis[round] = measureNanoTime {
                repeat(blockWrites) { idx ->
                    val x = (idx % 400) - 200
                    val z = ((idx / 400) % 400) - 200
                    val y = 64 + (idx % 4)
                    state.blockAt("world", x, y, z)
                }
            } / 1_000_000.0

            val metrics = state.chunkLoadingMetrics()
            lastLoadedChunks = metrics.loadedChunks
            lastChunkLoads = metrics.chunkLoads
            assertTrue(metrics.chunkLoads > 0L)
            assertTrue(metrics.loadedChunks <= 2048)
        }

        val writeP50 = percentile(writeMillis, 0.50)
        val writeP95 = percentile(writeMillis, 0.95)
        val readP50 = percentile(readMillis, 0.50)
        val readP95 = percentile(readMillis, 0.95)
        assertTrue(writeP50 < 500.0, "Block write p50 regression: ${writeP50}ms")
        assertTrue(writeP95 < 750.0, "Block write p95 regression: ${writeP95}ms")
        assertTrue(readP50 < 300.0, "Block read p50 regression: ${readP50}ms")
        assertTrue(readP95 < 450.0, "Block read p95 regression: ${readP95}ms")
        println("PERF_V2 metric=core.chunk.write_ms p50=${formatMetric(writeP50)} p95=${formatMetric(writeP95)} unit=ms")
        println("PERF_V2 metric=core.chunk.read_ms p50=${formatMetric(readP50)} p95=${formatMetric(readP95)} unit=ms")
        println("PERF_V2 metric=core.world.chunk_loads p50=${formatMetric(lastChunkLoads.toDouble())} p95=${formatMetric(lastChunkLoads.toDouble())} unit=count")
        println("PERF_V2 metric=core.world.loaded_chunks p50=${formatMetric(lastLoadedChunks.toDouble())} p95=${formatMetric(lastLoadedChunks.toDouble())} unit=count")
    }

    private fun percentile(samples: DoubleArray, p: Double): Double {
        require(samples.isNotEmpty()) { "samples must not be empty" }
        val sorted = samples.sortedArray()
        val index = ((sorted.lastIndex) * p).toInt().coerceIn(0, sorted.lastIndex)
        return sorted[index]
    }

    private fun formatMetric(value: Double): String = String.format(Locale.US, "%.3f", value)
}
