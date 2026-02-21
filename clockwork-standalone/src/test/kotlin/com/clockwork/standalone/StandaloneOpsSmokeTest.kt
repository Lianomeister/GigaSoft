package com.clockwork.standalone

import com.fasterxml.jackson.databind.ObjectMapper
import com.clockwork.core.GigaStandaloneCore
import com.clockwork.core.StandaloneCoreConfig
import org.junit.jupiter.api.Tag
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Tag("smoke")
class StandaloneOpsSmokeTest {
    private val mapper = ObjectMapper()

    @Test
    fun `status doctor and profile remain parseable`() {
        val root = Files.createTempDirectory("clockwork-standalone-ops-smoke")
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
        try {
            val statusJson = mapper.writeValueAsString(statusView(core, null, emptyList()))
            val statusTree = mapper.readTree(statusJson)
            assertTrue(statusTree.has("core"))
            assertTrue(statusTree.path("core").has("tickCount"))
            assertTrue(statusTree.path("core").has("tickPhasesAverageNanos"))

            val doctorJson = mapper.writeValueAsString(core.doctor())
            val doctorTree = mapper.readTree(doctorJson)
            assertTrue(doctorTree.has("loadedPlugins"))
            assertTrue(doctorTree.has("currentLoadOrder"))

            val missingProfileJson = mapper.writeValueAsString(mapOf("pluginId" to "missing", "found" to false))
            val profileTree = mapper.readTree(missingProfileJson)
            assertEquals("missing", profileTree.path("pluginId").asText())
            assertTrue(!profileTree.path("found").asBoolean())

            val status = core.status()
            assertNotNull(status)
            assertTrue(status.running)
        } finally {
            core.stop()
        }
    }
}
