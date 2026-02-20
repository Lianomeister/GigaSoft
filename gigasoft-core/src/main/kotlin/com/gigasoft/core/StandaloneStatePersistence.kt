package com.gigasoft.core

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

data class StandaloneHostSnapshot(
    val worlds: List<StandaloneWorld> = emptyList(),
    val players: List<StandalonePlayer> = emptyList(),
    val entities: List<StandaloneEntity> = emptyList(),
    val inventories: Map<String, Map<Int, String>> = emptyMap()
)

data class StandaloneStateEnvelope(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val snapshot: StandaloneHostSnapshot = StandaloneHostSnapshot()
)

private const val CURRENT_SCHEMA_VERSION = 1

class StandaloneStatePersistence(
    private val path: Path
) {
    private val mapper = ObjectMapper()
        .registerKotlinModule()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    fun load(): StandaloneHostSnapshot? {
        if (!Files.exists(path)) return null
        val root = Files.newInputStream(path).use { mapper.readTree(it) }
        return decodeWithMigration(root)
    }

    fun save(snapshot: StandaloneHostSnapshot) {
        Files.createDirectories(path.parent)
        val tmp = path.resolveSibling("${path.fileName}.tmp")
        val envelope = StandaloneStateEnvelope(
            schemaVersion = CURRENT_SCHEMA_VERSION,
            snapshot = snapshot
        )
        Files.newOutputStream(tmp).use { output ->
            mapper.writerWithDefaultPrettyPrinter().writeValue(output, envelope)
        }
        try {
            Files.move(
                tmp,
                path,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun decodeWithMigration(root: JsonNode): StandaloneHostSnapshot {
        val schemaNode = root["schemaVersion"]
        val snapshotNode = root["snapshot"]

        return if (schemaNode != null && snapshotNode != null) {
            val schemaVersion = schemaNode.asInt(0)
            when (schemaVersion) {
                1 -> mapper.treeToValue(snapshotNode, StandaloneHostSnapshot::class.java)
                else -> mapper.treeToValue(snapshotNode, StandaloneHostSnapshot::class.java)
            }
        } else {
            // Legacy v0 format: plain StandaloneHostSnapshot without envelope.
            mapper.treeToValue(root, StandaloneHostSnapshot::class.java)
        }
    }
}