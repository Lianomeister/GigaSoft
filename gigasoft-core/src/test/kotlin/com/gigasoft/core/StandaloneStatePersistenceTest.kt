package com.gigasoft.core

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class StandaloneStatePersistenceTest {
    private val mapper = ObjectMapper().registerKotlinModule()

    @Test
    fun `loadWithReport migrates legacy v0 snapshot`() {
        val root = Files.createTempDirectory("gigasoft-persistence-v0")
        val file = root.resolve("state.json")
        val legacy = StandaloneHostSnapshot(
            worlds = listOf(StandaloneWorld(name = "world", seed = 1L, time = 5L))
        )
        Files.newOutputStream(file).use { mapper.writerWithDefaultPrettyPrinter().writeValue(it, legacy) }

        val persistence = StandaloneStatePersistence(file)
        val loaded = persistence.loadWithReport()
        assertNotNull(loaded)
        assertEquals(0, loaded.report.originalSchemaVersion)
        assertEquals(2, loaded.report.targetSchemaVersion)
        assertTrue(loaded.report.migrated)
        assertEquals(2, loaded.report.appliedSteps.size)
        assertEquals("world", loaded.snapshot.worlds.firstOrNull()?.name)
    }

    @Test
    fun `loadWithReport migrates schema v1 envelope`() {
        val root = Files.createTempDirectory("gigasoft-persistence-v1")
        val file = root.resolve("state.json")
        val envelope = StandaloneStateEnvelope(
            schemaVersion = 1,
            snapshot = StandaloneHostSnapshot(
                worlds = listOf(StandaloneWorld(name = "mod", seed = 7L, time = 11L))
            )
        )
        Files.newOutputStream(file).use { mapper.writerWithDefaultPrettyPrinter().writeValue(it, envelope) }

        val persistence = StandaloneStatePersistence(file)
        val loaded = persistence.loadWithReport()
        assertNotNull(loaded)
        assertEquals(1, loaded.report.originalSchemaVersion)
        assertTrue(loaded.report.migrated)
        assertEquals(listOf("1->2: add snapshot metadata/checksum envelope"), loaded.report.appliedSteps)
        assertEquals("mod", loaded.snapshot.worlds.firstOrNull()?.name)
    }

    @Test
    fun `save writes schema v2 metadata with migration history`() {
        val root = Files.createTempDirectory("gigasoft-persistence-save")
        val file = root.resolve("state.json")
        val persistence = StandaloneStatePersistence(file)
        persistence.save(
            snapshot = StandaloneHostSnapshot(
                worlds = listOf(StandaloneWorld(name = "prod", seed = 5L, time = 30L))
            ),
            migrationHistory = listOf("1->2: add snapshot metadata/checksum envelope")
        )

        val rootNode = Files.newInputStream(file).use { mapper.readTree(it) }
        assertEquals(2, rootNode.path("schemaVersion").asInt(-1))
        assertTrue(rootNode.path("metadata").path("savedAtEpochMillis").asLong(0L) > 0L)
        assertTrue(rootNode.path("metadata").path("snapshotSha256").asText("").isNotBlank())
        assertEquals(
            "1->2: add snapshot metadata/checksum envelope",
            rootNode.path("metadata").path("migrationHistory").first().asText("")
        )
    }

    @Test
    fun `loadWithReport adds warning when checksum mismatches`() {
        val root = Files.createTempDirectory("gigasoft-persistence-checksum")
        val file = root.resolve("state.json")
        val persistence = StandaloneStatePersistence(file)
        persistence.save(
            snapshot = StandaloneHostSnapshot(
                worlds = listOf(StandaloneWorld(name = "safe", seed = 1L, time = 1L))
            )
        )

        val node = Files.newInputStream(file).use { mapper.readTree(it) }
        val snapshotNode = node.path("snapshot").deepCopy<com.fasterxml.jackson.databind.JsonNode>()
        (snapshotNode.path("worlds").first() as com.fasterxml.jackson.databind.node.ObjectNode).put("time", 99L)
        (node as com.fasterxml.jackson.databind.node.ObjectNode).set<com.fasterxml.jackson.databind.JsonNode>("snapshot", snapshotNode)
        Files.newOutputStream(file).use { mapper.writerWithDefaultPrettyPrinter().writeValue(it, node) }

        val loaded = persistence.loadWithReport()
        assertNotNull(loaded)
        assertTrue(loaded.report.warnings.any { it.contains("checksum mismatch", ignoreCase = true) })
    }
}

