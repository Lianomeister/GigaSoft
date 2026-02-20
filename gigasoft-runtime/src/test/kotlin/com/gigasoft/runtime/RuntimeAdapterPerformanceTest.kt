package com.gigasoft.runtime

import com.gigasoft.api.AdapterInvocation
import com.gigasoft.api.AdapterResponse
import com.gigasoft.api.ModAdapter
import org.junit.jupiter.api.Tag
import kotlin.system.measureNanoTime
import kotlin.test.Test
import kotlin.test.assertTrue

@Tag("performance")
class RuntimeAdapterPerformanceTest {
    @Test
    fun `adapter invoke baseline`() {
        val registry = RuntimeModAdapterRegistry(
            pluginId = "perf",
            logger = {},
            securityConfig = AdapterSecurityConfig(
                invocationTimeoutMillis = 2_000L,
                maxCallsPerMinute = 1_000_000
            )
        )
        registry.register(
            object : ModAdapter {
                override val id: String = "bench"
                override val name: String = "Bench Adapter"
                override val version: String = "1.0.0"
                override val capabilities: Set<String> = emptySet()

                override fun invoke(invocation: AdapterInvocation): AdapterResponse {
                    return AdapterResponse(success = true, payload = mapOf("ok" to "1"))
                }
            }
        )

        val iterations = 5_000
        repeat(1_000) {
            val warm = registry.invoke("bench", AdapterInvocation("ping"))
            assertTrue(warm.success)
        }
        val rounds = 5
        val samples = LongArray(rounds)
        repeat(rounds) { round ->
            samples[round] = measureNanoTime {
                repeat(iterations) {
                    val response = registry.invoke("bench", AdapterInvocation("ping"))
                    assertTrue(response.success)
                }
            }
        }
        val medianNanos = samples.sorted()[samples.size / 2]
        val perInvokeMicros = (medianNanos / iterations) / 1_000.0
        assertTrue(perInvokeMicros < 500.0, "Adapter invoke performance regression: $perInvokeMicros us")
        println(
            "PERF runtime.adapter.invoke iterations=$iterations rounds=$rounds " +
                "medianMs=${medianNanos / 1_000_000.0} perInvokeMicros=$perInvokeMicros"
        )
        registry.shutdown()
    }
}
