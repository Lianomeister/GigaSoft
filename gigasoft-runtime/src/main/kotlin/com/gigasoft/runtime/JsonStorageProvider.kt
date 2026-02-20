package com.gigasoft.runtime

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.gigasoft.api.PersistentStore
import com.gigasoft.api.StorageProvider
import java.nio.file.Files
import java.nio.file.Path

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
        return object : PersistentStore<T> {
            override fun load(): T? {
                if (!Files.exists(targetFile)) return null
                return mapper.readValue(Files.readString(targetFile), type)
            }

            override fun save(value: T) {
                Files.writeString(targetFile, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value))
                Files.writeString(versionFile, version.toString())
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
}
