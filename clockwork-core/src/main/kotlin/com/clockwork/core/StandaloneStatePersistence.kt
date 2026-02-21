package com.clockwork.core

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.security.MessageDigest
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

data class StandaloneHostSnapshot(
    val worlds: List<StandaloneWorld> = emptyList(),
    val players: List<StandalonePlayer> = emptyList(),
    val entities: List<StandaloneEntity> = emptyList(),
    val worldData: Map<String, Map<String, String>> = emptyMap(),
    val worldWeather: Map<String, String> = emptyMap(),
    val playerOps: Map<String, Boolean> = emptyMap(),
    val playerPermissions: Map<String, Set<String>> = emptyMap(),
    val playerGameModes: Map<String, String> = emptyMap(),
    val playerStatus: Map<String, StandalonePlayerStatus> = emptyMap(),
    val inventories: Map<String, Map<Int, String>> = emptyMap(),
    val blocks: List<StandaloneBlock> = emptyList(),
    val blockData: List<StandaloneBlockData> = emptyList(),
    val entityData: Map<String, Map<String, String>> = emptyMap()
)

data class StandaloneStateEnvelope(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val snapshot: StandaloneHostSnapshot = StandaloneHostSnapshot(),
    val metadata: StandaloneStateMetadata = StandaloneStateMetadata()
)

data class StandaloneStateMetadata(
    val savedAtEpochMillis: Long = System.currentTimeMillis(),
    val snapshotSha256: String? = null,
    val migrationHistory: List<String> = emptyList()
)

data class StandaloneStateMigrationReport(
    val originalSchemaVersion: Int,
    val targetSchemaVersion: Int,
    val migrated: Boolean,
    val appliedSteps: List<String> = emptyList(),
    val warnings: List<String> = emptyList()
)

data class StandaloneStateLoadResult(
    val snapshot: StandaloneHostSnapshot,
    val report: StandaloneStateMigrationReport
)

private const val CURRENT_SCHEMA_VERSION = 2

class StandaloneStatePersistence(
    private val path: Path
) {
    private val mapper = ObjectMapper()
        .registerKotlinModule()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    fun load(): StandaloneHostSnapshot? {
        return loadWithReport()?.snapshot
    }

    fun loadWithReport(): StandaloneStateLoadResult? {
        if (!Files.exists(path)) return null
        val root = Files.newInputStream(path).use { mapper.readTree(it) }
        return decodeWithMigration(root)
    }

    fun save(snapshot: StandaloneHostSnapshot, migrationHistory: List<String> = emptyList()) {
        Files.createDirectories(path.parent)
        val tmp = path.resolveSibling("${path.fileName}.tmp")
        val snapshotBytes = mapper.writeValueAsBytes(snapshot)
        val envelope = StandaloneStateEnvelope(
            schemaVersion = CURRENT_SCHEMA_VERSION,
            snapshot = snapshot,
            metadata = StandaloneStateMetadata(
                savedAtEpochMillis = System.currentTimeMillis(),
                snapshotSha256 = sha256(snapshotBytes),
                migrationHistory = migrationHistory
            )
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

    private fun decodeWithMigration(root: JsonNode): StandaloneStateLoadResult {
        val schemaNode = root["schemaVersion"]
        val hasEnvelope = schemaNode != null && root["snapshot"] != null
        val originalSchemaVersion = if (hasEnvelope) schemaNode.asInt(0) else 0
        val workingSnapshotNode = if (hasEnvelope) {
            root["snapshot"].deepCopy<JsonNode>()
        } else {
            root.deepCopy<JsonNode>()
        }

        var effectiveSchema = originalSchemaVersion
        val appliedSteps = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        if (effectiveSchema < 0) {
            warnings += "Negative schema version detected ($effectiveSchema); treating as legacy v0."
            effectiveSchema = 0
        }
        while (effectiveSchema < CURRENT_SCHEMA_VERSION) {
            when (effectiveSchema) {
                0 -> {
                    appliedSteps += "0->1: wrap legacy snapshot format"
                    effectiveSchema = 1
                }
                1 -> {
                    appliedSteps += "1->2: add snapshot metadata/checksum envelope"
                    effectiveSchema = 2
                }
                else -> {
                    warnings += "No explicit migration handler for schema $effectiveSchema; forcing target schema."
                    effectiveSchema = CURRENT_SCHEMA_VERSION
                }
            }
        }
        if (effectiveSchema > CURRENT_SCHEMA_VERSION) {
            warnings += "Snapshot schema $effectiveSchema is newer than runtime schema $CURRENT_SCHEMA_VERSION."
        }

        if (hasEnvelope && originalSchemaVersion >= 2) {
            val expectedChecksum = root.path("metadata").path("snapshotSha256").asText("").trim().ifEmpty { null }
            if (expectedChecksum != null) {
                val actualChecksum = sha256(mapper.writeValueAsBytes(workingSnapshotNode))
                if (!expectedChecksum.equals(actualChecksum, ignoreCase = true)) {
                    warnings += "Snapshot checksum mismatch detected."
                }
            }
        }

        val snapshot = mapper.treeToValue(workingSnapshotNode, StandaloneHostSnapshot::class.java)
        return StandaloneStateLoadResult(
            snapshot = snapshot,
            report = StandaloneStateMigrationReport(
                originalSchemaVersion = originalSchemaVersion,
                targetSchemaVersion = if (effectiveSchema > CURRENT_SCHEMA_VERSION) effectiveSchema else CURRENT_SCHEMA_VERSION,
                migrated = appliedSteps.isNotEmpty(),
                appliedSteps = appliedSteps,
                warnings = warnings
            )
        )
    }

    fun inspectMigrationReport(): StandaloneStateMigrationReport? {
        if (!Files.exists(path)) return null
        val root = Files.newInputStream(path).use { mapper.readTree(it) }
        return decodeWithMigration(root).report
    }

    fun migrateInPlace(): StandaloneStateMigrationReport? {
        val loaded = loadWithReport() ?: return null
        if (loaded.report.migrated) {
            save(
                snapshot = loaded.snapshot,
                migrationHistory = loaded.report.appliedSteps
            )
        }
        return loaded.report
    }

    private fun sha256(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        val out = StringBuilder(digest.size * 2)
        digest.forEach { b -> out.append("%02x".format(b)) }
        return out.toString()
    }
}
