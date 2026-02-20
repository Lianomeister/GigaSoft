package com.gigasoft.bridge.paper

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.bukkit.plugin.PluginManager
import org.bukkit.plugin.java.JavaPlugin
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration

data class ViaPluginConfig(
    val logicalName: String,
    val bukkitName: String,
    val enabled: Boolean,
    val projectSlug: String,
    val fileName: String
)

class ViaProvisioner(
    private val plugin: JavaPlugin,
    private val objectMapper: ObjectMapper
) {
    private val client = HttpClient.newBuilder().build()

    fun ensureViaStack() {
        val section = plugin.config.getConfigurationSection("compatibility.via") ?: return
        val autoInstall = section.getBoolean("auto_install", true)
        val autoLoad = section.getBoolean("auto_load", true)
        val timeoutSeconds = section.getLong("request_timeout_seconds", 20)
        val providers = listOf(
            readPluginConfig(section, "viaversion", "ViaVersion", "viaversion", "ViaVersion.jar"),
            readPluginConfig(section, "viabackwards", "ViaBackwards", "viabackwards", "ViaBackwards.jar")
        )

        providers.forEach { target ->
            if (!target.enabled) {
                plugin.logger.info("${target.logicalName}: disabled in config")
                return@forEach
            }

            val pluginManager = plugin.server.pluginManager
            val alreadyLoaded = pluginManager.getPlugin(target.bukkitName)
            if (alreadyLoaded != null) {
                plugin.logger.info("${target.logicalName}: already loaded (${alreadyLoaded.description.version})")
                return@forEach
            }

            val pluginsDir = plugin.dataFolder.toPath().parent
            val targetFile = pluginsDir.resolve(target.fileName)

            if (!Files.exists(targetFile)) {
                if (!autoInstall) {
                    plugin.logger.warning("${target.logicalName}: missing and auto_install=false")
                    return@forEach
                }
                installFromModrinth(target, targetFile, timeoutSeconds)
            }

            if (!Files.exists(targetFile)) {
                plugin.logger.warning("${target.logicalName}: jar unavailable after install attempt")
                return@forEach
            }

            if (!autoLoad) {
                plugin.logger.info("${target.logicalName}: installed but auto_load=false")
                return@forEach
            }

            loadPluginJar(pluginManager, target, targetFile)
        }
    }

    private fun installFromModrinth(target: ViaPluginConfig, targetFile: Path, timeoutSeconds: Long) {
        try {
            val versions = fetchProjectVersions(target.projectSlug, timeoutSeconds)
            val downloadUrl = findBestDownloadUrl(versions)
                ?: throw IllegalStateException("No compatible paper/spigot file found")

            Files.createDirectories(targetFile.parent)
            val request = HttpRequest.newBuilder(URI(downloadUrl))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("User-Agent", "GigaSoftBridge/0.1")
                .GET()
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
            require(response.statusCode() in 200..299) {
                "HTTP ${response.statusCode()} while downloading ${target.logicalName}"
            }
            response.body().use { input ->
                Files.copy(input, targetFile, StandardCopyOption.REPLACE_EXISTING)
            }
            plugin.logger.info("${target.logicalName}: downloaded to $targetFile")
        } catch (t: Throwable) {
            plugin.logger.warning("${target.logicalName}: auto install failed (${t.message})")
        }
    }

    private fun loadPluginJar(pluginManager: PluginManager, target: ViaPluginConfig, targetFile: Path) {
        try {
            val loaded = pluginManager.loadPlugin(targetFile.toFile())
                ?: throw IllegalStateException("Plugin manager returned null for ${target.fileName}")
            pluginManager.enablePlugin(loaded)
            plugin.logger.info("${target.logicalName}: loaded dynamically (${loaded.description.version})")
            plugin.logger.info("${target.logicalName}: dynamic load done; restart is still recommended for full cross-plugin initialization order")
        } catch (t: Throwable) {
            plugin.logger.warning("${target.logicalName}: dynamic load failed (${t.message})")
        }
    }

    private fun fetchProjectVersions(projectSlug: String, timeoutSeconds: Long): JsonNode {
        val request = HttpRequest.newBuilder(URI("https://api.modrinth.com/v2/project/$projectSlug/version"))
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .header("User-Agent", "GigaSoftBridge/0.1")
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        require(response.statusCode() in 200..299) {
            "HTTP ${response.statusCode()} while querying modrinth"
        }
        return objectMapper.readTree(response.body())
    }

    internal fun findBestDownloadUrl(versions: JsonNode): String? {
        if (!versions.isArray) return null
        val supportedLoaders = setOf("paper", "spigot", "purpur")

        for (version in versions) {
            val loaders = version.path("loaders")
            val matchesLoader = loaders.isArray && loaders.any { it.asText() in supportedLoaders }
            if (!matchesLoader) continue

            val files = version.path("files")
            if (!files.isArray || files.isEmpty) continue

            val primaryJar = files.firstOrNull { file ->
                val name = file.path("filename").asText()
                file.path("primary").asBoolean(false) && name.endsWith(".jar")
            }
            if (primaryJar != null) {
                val url = primaryJar.path("url").asText()
                if (url.isNotBlank()) return url
            }

            val fallback = files.firstOrNull { file -> file.path("filename").asText().endsWith(".jar") }
            if (fallback != null) {
                val url = fallback.path("url").asText()
                if (url.isNotBlank()) return url
            }
        }
        return null
    }

    private fun readPluginConfig(
        section: org.bukkit.configuration.ConfigurationSection,
        key: String,
        bukkitName: String,
        defaultSlug: String,
        defaultFileName: String
    ): ViaPluginConfig {
        val child = section.getConfigurationSection(key)
        return ViaPluginConfig(
            logicalName = key,
            bukkitName = bukkitName,
            enabled = child?.getBoolean("enabled", true) ?: true,
            projectSlug = child?.getString("project_slug")?.trim().orEmpty().ifBlank { defaultSlug },
            fileName = child?.getString("file_name")?.trim().orEmpty().ifBlank { defaultFileName }
        )
    }
}
