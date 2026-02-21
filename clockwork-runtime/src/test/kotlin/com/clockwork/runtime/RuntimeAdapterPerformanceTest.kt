package com.clockwork.runtime

import com.clockwork.api.AdapterInvocation
import com.clockwork.api.AdapterResponse
import com.clockwork.api.ModAdapter
import org.junit.jupiter.api.Tag
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.system.measureNanoTime
import kotlin.test.Test
import kotlin.test.assertEquals
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
        val rounds = 9
        val samples = DoubleArray(rounds)
        repeat(rounds) { round ->
            val nanos = measureNanoTime {
                repeat(iterations) {
                    val response = registry.invoke("bench", AdapterInvocation("ping"))
                    assertTrue(response.success)
                }
            }
            samples[round] = (nanos / iterations) / 1_000.0
        }
        val p50Micros = percentile(samples, 0.50)
        val p95Micros = percentile(samples, 0.95)
        assertTrue(p50Micros < 500.0, "Adapter invoke p50 regression: $p50Micros us")
        assertTrue(p95Micros < 700.0, "Adapter invoke p95 regression: $p95Micros us")
        println(
            "PERF_V2 metric=runtime.adapter.invoke.per_invoke_micros " +
                "p50=${formatMetric(p50Micros)} p95=${formatMetric(p95Micros)} unit=micros"
        )
        registry.shutdown()
    }

    @Test
    fun `runtime reload latency baseline`() {
        val root = Files.createTempDirectory("runtime-reload-v2")
        val pluginsDir = root.resolve("plugins")
        val dataDir = root.resolve("data")
        Files.createDirectories(pluginsDir)
        Files.createDirectories(dataDir)

        val sourceJar = pluginsDir.resolve("demo.jar")
        createPluginJar(sourceJar, version = "1.0.0")

        val runtime = GigaRuntime(pluginsDir, dataDir)
        runtime.scanAndLoad()

        val rounds = 9
        val samplesMillis = DoubleArray(rounds)
        repeat(rounds) { index ->
            createPluginJar(sourceJar, version = "1.0.${index + 1}")
            val nanos = measureNanoTime {
                val report = runtime.reloadWithReport("demo")
                assertEquals(ReloadStatus.SUCCESS, report.status)
            }
            samplesMillis[index] = nanos / 1_000_000.0
        }

        val p50Ms = percentile(samplesMillis, 0.50)
        val p95Ms = percentile(samplesMillis, 0.95)
        assertTrue(p50Ms < 250.0, "Runtime reload latency p50 regression: ${p50Ms}ms")
        assertTrue(p95Ms < 650.0, "Runtime reload latency p95 regression: ${p95Ms}ms")
        println(
            "PERF_V2 metric=runtime.reload.latency_ms " +
                "p50=${formatMetric(p50Ms)} p95=${formatMetric(p95Ms)} unit=ms"
        )
        runtime.unload("demo")
    }

    private fun createPluginJar(targetJar: Path, version: String) {
        Files.createDirectories(targetJar.parent)
        JarOutputStream(Files.newOutputStream(targetJar)).use { jar ->
            jar.putNextEntry(JarEntry("clockworkplugin.yml"))
            jar.write(
                """
                id: demo
                name: demo
                version: $version
                main: com.clockwork.runtime.TestGoodPlugin
                apiVersion: 1
                dependencies: []
                permissions: []
                """.trimIndent().toByteArray()
            )
            jar.closeEntry()
        }
    }

    private fun percentile(samples: DoubleArray, p: Double): Double {
        require(samples.isNotEmpty()) { "samples must not be empty" }
        val sorted = samples.sortedArray()
        val index = ((sorted.lastIndex) * p).toInt().coerceIn(0, sorted.lastIndex)
        return sorted[index]
    }

    private fun formatMetric(value: Double): String = String.format(Locale.US, "%.3f", value)
}
