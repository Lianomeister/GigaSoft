package com.gigasoft.runtime

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.gigasoft.api.PersistentStore
import com.gigasoft.api.StorageProvider
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

class JsonStorageProvider(
    private val pluginDataPath: Path,
    private val mapper: ObjectMapper = ObjectMapper().registerModule(KotlinModule.Builder().build())
) : StorageProvider {
    init {
        Files.createDirectories(pluginDataPath)
    }

    override fun <T : Any> store(key: String, type: Class<T>, version: Int): PersistentStore<T> {
        val targetFile = pluginDataPath.resolve("$key.json")
        val versionFile = pluginDataPath.resolve("$key.version")
        val checksumFile = pluginDataPath.resolve("$key.sha256")
        val backupDataFile = pluginDataPath.resolve("$key.json.bak")
        val backupVersionFile = pluginDataPath.resolve("$key.version.bak")
        val backupChecksumFile = pluginDataPath.resolve("$key.sha256.bak")
        return object : PersistentStore<T> {
            override fun load(): T? {
                if (!Files.exists(targetFile)) return null
                if (!verifyChecksum(targetFile, checksumFile)) {
                    if (verifyChecksum(backupDataFile, backupChecksumFile)) {
                        restoreFromBackup(
                            data = targetFile,
                            version = versionFile,
                            checksum = checksumFile,
                            backupData = backupDataFile,
                            backupVersion = backupVersionFile,
                            backupChecksum = backupChecksumFile
                        )
                    } else {
                        error("Corrupted store '$key': checksum mismatch and no valid backup")
                    }
                }
                return mapper.readValue(Files.readString(targetFile), type)
            }

            override fun save(value: T) {
                snapshotBackup(
                    data = targetFile,
                    version = versionFile,
                    checksum = checksumFile,
                    backupData = backupDataFile,
                    backupVersion = backupVersionFile,
                    backupChecksum = backupChecksumFile
                )
                val data = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value)
                atomicWrite(targetFile, data)
                atomicWrite(versionFile, version.toString())
                atomicWrite(checksumFile, sha256Hex(data))
            }

            override fun migrate(fromVersion: Int, migration: (T) -> T) {
                val existing = load() ?: return
                val currentVersion = if (Files.exists(versionFile)) Files.readString(versionFile).trim().toInt() else fromVersion
                if (currentVersion < version) {
                    save(migration(existing))
                }
            }
        }
    }

    private fun atomicWrite(target: Path, content: String) {
        Files.createDirectories(target.parent)
        val tmp = target.resolveSibling("${target.fileName}.tmp")
        Files.writeString(tmp, content)
        try {
            Files.move(
                tmp,
                target,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun snapshotBackup(
        data: Path,
        version: Path,
        checksum: Path,
        backupData: Path,
        backupVersion: Path,
        backupChecksum: Path
    ) {
        if (Files.exists(data)) Files.copy(data, backupData, StandardCopyOption.REPLACE_EXISTING)
        if (Files.exists(version)) Files.copy(version, backupVersion, StandardCopyOption.REPLACE_EXISTING)
        if (Files.exists(checksum)) Files.copy(checksum, backupChecksum, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun restoreFromBackup(
        data: Path,
        version: Path,
        checksum: Path,
        backupData: Path,
        backupVersion: Path,
        backupChecksum: Path
    ) {
        if (Files.exists(backupData)) Files.copy(backupData, data, StandardCopyOption.REPLACE_EXISTING)
        if (Files.exists(backupVersion)) Files.copy(backupVersion, version, StandardCopyOption.REPLACE_EXISTING)
        if (Files.exists(backupChecksum)) Files.copy(backupChecksum, checksum, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun verifyChecksum(data: Path, checksum: Path): Boolean {
        if (!Files.exists(data) || !Files.exists(checksum)) return false
        val actual = sha256Hex(Files.readString(data))
        val expected = Files.readString(checksum).trim().lowercase()
        return expected.isNotBlank() && actual == expected
    }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return buildString(digest.size * 2) {
            for (byte in digest) {
                append(((byte.toInt() ushr 4) and 0xF).toString(16))
                append((byte.toInt() and 0xF).toString(16))
            }
        }
    }
}
