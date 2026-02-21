package com.clockwork.showcase.framed

import com.clockwork.api.*

data class FrameConfig(
    val maxTiles: Int = 32,
    val validateUrl: Boolean = true,
    val connectTimeoutMillis: Int = 3_000,
    val readTimeoutMillis: Int = 4_000
)

data class FrameJob(
    val id: String,
    val createdBy: String,
    val link: String,
    val width: Int,
    val height: Int,
    val createdAtMillis: Long
)

data class FrameState(
    val jobs: List<FrameJob> = emptyList()
)

class FramedPlugin : GigaPlugin {
    private val configKey = "framed-config"
    private val stateKey = "framed-state"

    @Volatile
    private var config = FrameConfig()
    private val jobs = linkedMapOf<String, FrameJob>()

    private val delegate = gigaPlugin(id = "showcase-framed", name = "Showcase Framed", version = "1.0.0") {
        commands {
            spec(
                command = "framed",
                description = "Create map frame job from image/gif link",
                argsSchema = listOf(
                    CommandArgSpec("link", CommandArgType.STRING),
                    CommandArgSpec("width", CommandArgType.INT, required = false),
                    CommandArgSpec("height", CommandArgType.INT, required = false)
                ),
                usage = "framed <link> [width] [height]"
            ) { inv ->
                val ctx = inv.pluginContext
                val link = inv.parsedArgs.requiredString("link").trim()
                if (!isHttpUrl(link)) {
                    return@spec CommandResult.error("Only http/https links are supported", code = "E_ARGS")
                }
                val width = (inv.parsedArgs.int("width") ?: 1).coerceIn(1, 8)
                val height = (inv.parsedArgs.int("height") ?: 1).coerceIn(1, 8)
                val tiles = width * height
                if (tiles > config.maxTiles) {
                    return@spec CommandResult.error("Frame too large (${tiles} > max ${config.maxTiles})", code = "E_ARGS")
                }
                if (config.validateUrl) {
                    val probe = ctx.host.httpGet(
                        url = link,
                        connectTimeoutMillis = config.connectTimeoutMillis,
                        readTimeoutMillis = config.readTimeoutMillis,
                        maxBodyChars = 2_048
                    )
                    if (probe == null || !probe.success) {
                        return@spec CommandResult.error("Could not fetch link", code = "E_HTTP")
                    }
                }
                val id = "frame-${System.currentTimeMillis()}"
                jobs[id] = FrameJob(
                    id = id,
                    createdBy = inv.sender.id,
                    link = link,
                    width = width,
                    height = height,
                    createdAtMillis = System.currentTimeMillis()
                )
                saveState(ctx)
                CommandResult.ok("Created frame job $id (${width}x${height}) from $link")
            }

            spec(command = "framed-list", usage = "framed-list") { _ ->
                if (jobs.isEmpty()) return@spec CommandResult.ok("No frame jobs")
                val text = jobs.values.takeLast(20).joinToString(" | ") { "${it.id}:${it.width}x${it.height}" }
                CommandResult.ok("Frame jobs (${jobs.size}): $text")
            }

            spec(
                command = "framed-remove",
                argsSchema = listOf(CommandArgSpec("id", CommandArgType.STRING)),
                usage = "framed-remove <id>"
            ) { inv ->
                val id = inv.parsedArgs.requiredString("id")
                val removed = jobs.remove(id) ?: return@spec CommandResult.error("Unknown frame id", code = "E_NOT_FOUND")
                saveState(inv.pluginContext)
                CommandResult.ok("Removed frame job ${removed.id}")
            }

            spec(
                command = "framed-config",
                argsSchema = listOf(
                    CommandArgSpec("maxTiles", CommandArgType.INT, required = false),
                    CommandArgSpec("validateUrl", CommandArgType.BOOLEAN, required = false)
                ),
                usage = "framed-config [maxTiles] [validateUrl]"
            ) { inv ->
                if (inv.rawArgs.isEmpty()) {
                    return@spec CommandResult.ok("Framed config: maxTiles=${config.maxTiles} validateUrl=${config.validateUrl}")
                }
                val maxTiles = (inv.parsedArgs.int("maxTiles") ?: config.maxTiles).coerceIn(1, 64)
                val validate = inv.parsedArgs.boolean("validateUrl") ?: config.validateUrl
                config = config.copy(maxTiles = maxTiles, validateUrl = validate)
                saveConfig(inv.pluginContext)
                CommandResult.ok("Framed config updated")
            }
        }
    }

    override fun onEnable(ctx: PluginContext) {
        config = ctx.storage.store(configKey, FrameConfig::class.java, version = 1).load() ?: FrameConfig()
        val state = ctx.storage.store(stateKey, FrameState::class.java, version = 1).load() ?: FrameState()
        jobs.clear()
        state.jobs.forEach { jobs[it.id] = it }
        delegate.onEnable(ctx)
    }

    override fun onDisable(ctx: PluginContext) {
        saveConfig(ctx)
        saveState(ctx)
        delegate.onDisable(ctx)
    }

    private fun saveConfig(ctx: PluginContext) {
        ctx.storage.store(configKey, FrameConfig::class.java, version = 1).save(config)
    }

    private fun saveState(ctx: PluginContext) {
        ctx.storage.store(stateKey, FrameState::class.java, version = 1).save(FrameState(jobs = jobs.values.toList()))
    }

    private fun isHttpUrl(value: String): Boolean {
        val normalized = value.trim().lowercase()
        return normalized.startsWith("http://") || normalized.startsWith("https://")
    }
}
