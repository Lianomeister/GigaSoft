package com.gigasoft.integration

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertTrue
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.Collections
import java.util.concurrent.TimeUnit

class PaperSmokeIntegrationTest {
    private val mapper = ObjectMapper()
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build()

    @Test
    fun `paper boots and giga plugin reload works`() {
        val bridgeJar = Path.of(requireSystemProperty("gigasoft.bridgeJar"))
        val demoJar = Path.of(requireSystemProperty("gigasoft.demoJar"))

        val runtimeDir = Files.createTempDirectory("gigasoft-paper-smoke")
        val paperJar = runtimeDir.resolve("paper.jar")
        val pluginsDir = runtimeDir.resolve("plugins")
        val gigaPluginsDir = runtimeDir.resolve("plugins").resolve("GigaSoftBridge").resolve("giga-plugins")

        Files.createDirectories(pluginsDir)
        Files.createDirectories(gigaPluginsDir)

        Files.copy(bridgeJar, pluginsDir.resolve(bridgeJar.fileName), StandardCopyOption.REPLACE_EXISTING)
        Files.copy(demoJar, gigaPluginsDir.resolve(demoJar.fileName), StandardCopyOption.REPLACE_EXISTING)
        Files.writeString(runtimeDir.resolve("eula.txt"), "eula=true\n")

        downloadLatestPaper(paperJar)

        val process = ProcessBuilder(
            "java",
            "-Xmx1G",
            "-jar",
            paperJar.fileName.toString(),
            "--nogui"
        )
            .directory(runtimeDir.toFile())
            .redirectErrorStream(true)
            .start()

        val logs = Collections.synchronizedList(mutableListOf<String>())
        val logThread = Thread {
            InputStreamReader(process.inputStream).buffered().useLines { lines ->
                lines.forEach { line ->
                    logs += line
                    println("[paper-smoke] $line")
                }
            }
        }.apply {
            isDaemon = true
            start()
        }

        val writer = process.outputStream.bufferedWriter()

        try {
            val booted = waitForLog(logs, Duration.ofMinutes(8)) {
                it.contains("Done (") || it.contains("For help, type \"help\"")
            }
            if (!booted) {
                val downloadingMojang = synchronized(logs) { logs.any { line -> line.contains("Downloading mojang_") } }
                assumeTrue(!downloadingMojang, "Paper bootstrap download is too slow/unavailable in this environment")
                error("Paper server did not finish booting in time")
            }

            runCommand(writer, "giga plugins")
            assertTrue(waitForLog(logs, Duration.ofSeconds(30)) { it.contains("Loaded GigaPlugins: 1") })
            assertTrue(waitForLog(logs, Duration.ofSeconds(30)) { it.contains("gigasoft-demo@0.1.0-rc.1") })

            runCommand(writer, "giga reload all")
            assertTrue(waitForLog(logs, Duration.ofSeconds(30)) { it.contains("Reloaded 1 plugins") })

            runCommand(writer, "giga plugins")
            assertTrue(waitForLog(logs, Duration.ofSeconds(30)) { it.contains("Loaded GigaPlugins: 1") })

            runCommand(writer, "stop")
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Paper server did not stop within timeout")
        } finally {
            writer.close()
            if (process.isAlive) {
                process.destroy()
                process.waitFor(10, TimeUnit.SECONDS)
            }
            if (process.isAlive) {
                process.destroyForcibly()
            }
            logThread.join(3000)
        }
    }

    private fun runCommand(writer: BufferedWriter, command: String) {
        writer.write(command)
        writer.newLine()
        writer.flush()
    }

    private fun waitForLog(logs: List<String>, timeout: Duration, condition: (String) -> Boolean): Boolean {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (System.nanoTime() < deadline) {
            synchronized(logs) {
                if (logs.any(condition)) return true
            }
            Thread.sleep(200)
        }
        return false
    }

    private fun downloadLatestPaper(target: Path) {
        val projectJson = getJson("https://api.papermc.io/v2/projects/paper")
        val version = projectJson["versions"].last().asText()

        val versionJson = getJson("https://api.papermc.io/v2/projects/paper/versions/$version")
        val build = versionJson["builds"].last().asInt()

        val buildJson = getJson("https://api.papermc.io/v2/projects/paper/versions/$version/builds/$build")
        val fileName = buildJson["downloads"]["application"]["name"].asText()

        val downloadUrl = "https://api.papermc.io/v2/projects/paper/versions/$version/builds/$build/downloads/$fileName"
        val request = HttpRequest.newBuilder(URI(downloadUrl)).GET().build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofFile(target))
        require(response.statusCode() in 200..299) { "Failed to download Paper: HTTP ${response.statusCode()}" }
    }

    private fun getJson(url: String): JsonNode {
        val request = HttpRequest.newBuilder(URI(url)).GET().build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        require(response.statusCode() in 200..299) { "Failed GET $url: HTTP ${response.statusCode()}" }
        return mapper.readTree(response.body())
    }

    private fun requireSystemProperty(name: String): String {
        return System.getProperty(name)
            ?: error("Missing required system property: $name")
    }
}
