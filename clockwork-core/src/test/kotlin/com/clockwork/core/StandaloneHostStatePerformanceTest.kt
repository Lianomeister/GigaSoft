package com.clockwork.core

import org.junit.jupiter.api.Tag
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

        val joinNanos = joinSamples.sorted()[rounds / 2]
        val moveNanos = moveSamples.sorted()[rounds / 2]
        val spawnNanos = spawnSamples.sorted()[rounds / 2]
        assertTrue(joinNanos < 250_000_000L, "Join regression: ${joinNanos / 1_000_000.0}ms")
        assertTrue(moveNanos < 150_000_000L, "Move regression: ${moveNanos / 1_000_000.0}ms")
        assertTrue(spawnNanos < 350_000_000L, "Spawn regression: ${spawnNanos / 1_000_000.0}ms")
        println(
            "PERF core.hoststate players=$players rounds=$rounds " +
                "joinMedianMs=${joinNanos / 1_000_000.0} moveMedianMs=${moveNanos / 1_000_000.0} " +
                "spawn=$entitySpawn spawnMedianMs=${spawnNanos / 1_000_000.0}"
        )
    }

    @Test
    fun `chunk loading and block access baseline`() {
        val state = StandaloneHostState(
            chunkViewDistance = 4,
            maxChunkLoadsPerTick = 256,
            maxLoadedChunksPerWorld = 2048
        )
        state.joinPlayer("Perf", "world", 0.0, 64.0, 0.0)

        val blockWrites = 40_000
        val writesNanos = measureNanoTime {
            repeat(blockWrites) { idx ->
                val x = (idx % 400) - 200
                val z = ((idx / 400) % 400) - 200
                val y = 64 + (idx % 4)
                state.setBlock("world", x, y, z, "stone")
                if (idx % 200 == 0) {
                    state.tickWorlds()
                }
            }
        }

        val readsNanos = measureNanoTime {
            repeat(blockWrites) { idx ->
                val x = (idx % 400) - 200
                val z = ((idx / 400) % 400) - 200
                val y = 64 + (idx % 4)
                state.blockAt("world", x, y, z)
            }
        }

        val metrics = state.chunkLoadingMetrics()
        assertTrue(metrics.chunkLoads > 0L)
        assertTrue(metrics.loadedChunks <= 2048)
        assertTrue(writesNanos < 500_000_000L, "Block write regression: ${writesNanos / 1_000_000.0}ms")
        assertTrue(readsNanos < 300_000_000L, "Block read regression: ${readsNanos / 1_000_000.0}ms")
        println(
            "PERF core.chunk blocks=$blockWrites writesMs=${writesNanos / 1_000_000.0} " +
                "readsMs=${readsNanos / 1_000_000.0} loadedChunks=${metrics.loadedChunks} " +
                "chunkLoads=${metrics.chunkLoads} evictions=${metrics.chunkEvictions}"
        )
    }
}
