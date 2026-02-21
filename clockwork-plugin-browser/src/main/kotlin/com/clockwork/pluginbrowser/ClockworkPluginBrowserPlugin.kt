package com.clockwork.pluginbrowser

import com.clockwork.api.*
import java.util.Locale

data class BrowserConfig(
    val sourceUrl: String = DEFAULT_SOURCE_URL,
    val cacheTtlSeconds: Int = DEFAULT_CACHE_TTL_SECONDS,
    val searchResultLimit: Int = DEFAULT_SEARCH_LIMIT,
    val connectTimeoutMillis: Int = DEFAULT_CONNECT_TIMEOUT_MILLIS,
    val readTimeoutMillis: Int = DEFAULT_READ_TIMEOUT_MILLIS
)

private data class CatalogItem(
    val id: String,
    val name: String,
    val version: String,
    val minecraft: String,
    val category: String,
    val downloadUrl: String,
    val description: String
)

private data class CatalogCache(
    val loadedAtMillis: Long,
    val sourceUrl: String,
    val items: List<CatalogItem>,
    val fromFallback: Boolean,
    val error: String?
)

private data class CatalogLoadMeta(
    val loadedAtMillis: Long,
    val sourceUrl: String,
    val entryCount: Int,
    val fromFallback: Boolean,
    val error: String?
)

private const val DEFAULT_SOURCE_URL = "https://raw.githubusercontent.com/Lianomeister/Clockwork/main/docs/marketplace/catalog-v1.txt"
private const val DEFAULT_CACHE_TTL_SECONDS = 30
private const val DEFAULT_SEARCH_LIMIT = 8
private const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 3_000
private const val DEFAULT_READ_TIMEOUT_MILLIS = 6_000

private const val CONFIG_KEY = "browser_config"

class ClockworkPluginBrowserPlugin : GigaPlugin {
    @Volatile
    private var cache: CatalogCache? = null

    @Volatile
    private var lastLoadMeta: CatalogLoadMeta? = null

    private val delegate = gigaPlugin(
        id = "clockwork-plugin-browser",
        name = "Clockwork Plugin Browser",
        version = "1.5.1"
    ) {
        commands {
            spec(
                command = "browser-search",
                aliases = listOf("browser", "plugins"),
                description = "Search plugin catalog",
                argsSchema = listOf(
                    CommandArgSpec("query", CommandArgType.STRING, required = false)
                ),
                usage = "browser-search [query]"
            ) { inv ->
                val ctx = inv.pluginContext
                val cfg = config(ctx)
                val query = collectTail(inv.rawArgs, 0)
                val items = filteredCatalog(ctx, query)
                if (items.isEmpty()) {
                    return@spec CommandResult.ok("No plugins found for '$query'")
                }
                val top = items.take(cfg.searchResultLimit).joinToString(" | ") {
                    "${it.id} (${it.version}, mc ${it.minecraft}, ${it.category})"
                }
                CommandResult.ok("Found ${items.size}: $top")
            }

            spec(
                command = "browser-open",
                description = "Open in-game plugin browser menu for a player",
                argsSchema = listOf(
                    CommandArgSpec("player", CommandArgType.STRING),
                    CommandArgSpec("query", CommandArgType.STRING, required = false)
                ),
                usage = "browser-open <player> [query]"
            ) { inv ->
                val ctx = inv.pluginContext
                val player = inv.parsedArgs.requiredString("player")
                val query = collectTail(inv.rawArgs, 1)
                val items = filteredCatalog(ctx, query).take(9)
                if (items.isEmpty()) {
                    return@spec CommandResult.ok("No catalog entries for '$query'")
                }
                val menu = UiMenu(
                    id = "plugin-browser.main",
                    title = if (query.isBlank()) "Plugin Browser" else "Plugin Browser: $query",
                    items = items.map { item ->
                        UiMenuItem(
                            id = item.id,
                            label = "${item.name} (${item.version})",
                            description = "mc ${item.minecraft} | ${item.category}"
                        )
                    }
                )
                val opened = ctx.showMenu(player, menu)
                if (opened) {
                    CommandResult.ok("Opened plugin browser for '$player'")
                } else {
                    CommandResult.error("Could not open menu for '$player'", code = "E_UI")
                }
            }

            spec(
                command = "browser-show",
                description = "Show detailed catalog entry by plugin id",
                argsSchema = listOf(CommandArgSpec("pluginId", CommandArgType.STRING)),
                usage = "browser-show <pluginId>"
            ) { inv ->
                val ctx = inv.pluginContext
                val input = inv.parsedArgs.requiredString("pluginId").trim()
                val all = loadCatalog(ctx, forceRefresh = false)
                val item = resolveItem(all, input)
                    ?: return@spec CommandResult.error("No plugin matches '$input'", code = "E_NOT_FOUND")
                CommandResult.ok(
                    "${item.id} | ${item.name} ${item.version} | mc ${item.minecraft} | ${item.category} | ${item.downloadUrl} | ${item.description}"
                )
            }

            spec(
                command = "browser-install",
                description = "Install plugin from browser catalog by id or unique prefix",
                argsSchema = listOf(
                    CommandArgSpec("pluginId", CommandArgType.STRING)
                ),
                usage = "browser-install <pluginIdOrPrefix>"
            ) { inv ->
                val ctx = inv.pluginContext
                val pluginInput = inv.parsedArgs.requiredString("pluginId").trim()
                val all = loadCatalog(ctx, forceRefresh = false)
                val exact = all.firstOrNull { it.id.equals(pluginInput, ignoreCase = true) }
                val prefixed = if (exact == null) all.filter { it.id.startsWith(pluginInput, ignoreCase = true) } else emptyList()
                val item = when {
                    exact != null -> exact
                    prefixed.size == 1 -> prefixed.first()
                    prefixed.isEmpty() -> return@spec CommandResult.error("Unknown plugin id '$pluginInput'", code = "E_NOT_FOUND")
                    else -> {
                        val options = prefixed.take(10).joinToString(", ") { it.id }
                        return@spec CommandResult.error(
                            "Ambiguous plugin id '$pluginInput' (matches: $options)",
                            code = "E_ARGS"
                        )
                    }
                }
                val result = ctx.host.installPluginFromUrl(
                    url = item.downloadUrl,
                    fileName = null,
                    loadNow = true
                )
                if (!result.success) {
                    return@spec CommandResult.error(
                        "Install failed: ${result.message ?: "unknown"}",
                        code = "E_INSTALL"
                    )
                }
                val loadedPart = if (result.loaded) "loaded now" else "installed (scan/reload pending)"
                CommandResult.ok("Installed ${item.id} -> ${result.filePath} ($loadedPart)")
            }

            spec(
                command = "browser-source",
                description = "Get or set plugin catalog source URL",
                argsSchema = listOf(
                    CommandArgSpec("url", CommandArgType.STRING, required = false)
                ),
                usage = "browser-source [url]"
            ) { inv ->
                val ctx = inv.pluginContext
                val input = collectTail(inv.rawArgs, 0)
                val store = ctx.storage.store(CONFIG_KEY, BrowserConfig::class.java, version = 1)
                val current = config(ctx)
                if (input.isBlank()) {
                    return@spec CommandResult.ok("Catalog source: ${current.sourceUrl}")
                }
                val normalized = input.trim()
                if (!isHttpUrl(normalized)) {
                    return@spec CommandResult.error("Only http/https source URLs are supported", code = "E_ARGS")
                }
                store.save(current.copy(sourceUrl = normalized))
                cache = null
                CommandResult.ok("Catalog source updated")
            }

            spec(
                command = "browser-source-reset",
                description = "Reset catalog source to default",
                usage = "browser-source-reset"
            ) { inv ->
                val ctx = inv.pluginContext
                val store = ctx.storage.store(CONFIG_KEY, BrowserConfig::class.java, version = 1)
                store.save(config(ctx).copy(sourceUrl = DEFAULT_SOURCE_URL))
                cache = null
                CommandResult.ok("Catalog source reset to default")
            }

            spec(
                command = "browser-config",
                description = "Set browser config values",
                argsSchema = listOf(
                    CommandArgSpec("cacheTtlSeconds", CommandArgType.INT, required = false),
                    CommandArgSpec("searchLimit", CommandArgType.INT, required = false),
                    CommandArgSpec("connectTimeoutMillis", CommandArgType.INT, required = false),
                    CommandArgSpec("readTimeoutMillis", CommandArgType.INT, required = false)
                ),
                usage = "browser-config [cacheTtlSeconds] [searchLimit] [connectTimeoutMillis] [readTimeoutMillis]"
            ) { inv ->
                val ctx = inv.pluginContext
                val store = ctx.storage.store(CONFIG_KEY, BrowserConfig::class.java, version = 1)
                val current = config(ctx)
                val hasAny = inv.rawArgs.isNotEmpty()
                if (!hasAny) {
                    return@spec CommandResult.ok(
                        "Config ttl=${current.cacheTtlSeconds}s searchLimit=${current.searchResultLimit} connect=${current.connectTimeoutMillis}ms read=${current.readTimeoutMillis}ms"
                    )
                }

                val ttl = inv.parsedArgs.int("cacheTtlSeconds")?.coerceIn(5, 600) ?: current.cacheTtlSeconds
                val limit = inv.parsedArgs.int("searchLimit")?.coerceIn(1, 25) ?: current.searchResultLimit
                val connect = inv.parsedArgs.int("connectTimeoutMillis")?.coerceIn(500, 30_000) ?: current.connectTimeoutMillis
                val read = inv.parsedArgs.int("readTimeoutMillis")?.coerceIn(1_000, 60_000) ?: current.readTimeoutMillis

                store.save(
                    current.copy(
                        cacheTtlSeconds = ttl,
                        searchResultLimit = limit,
                        connectTimeoutMillis = connect,
                        readTimeoutMillis = read
                    )
                )
                cache = null
                CommandResult.ok("Config updated: ttl=${ttl}s searchLimit=$limit connect=${connect}ms read=${read}ms")
            }

            spec(
                command = "browser-info",
                description = "Show browser catalog cache/load status",
                usage = "browser-info"
            ) { inv ->
                val ctx = inv.pluginContext
                val cfg = config(ctx)
                val local = cache
                val meta = lastLoadMeta
                val cacheText = if (local == null) {
                    "cache=empty"
                } else {
                    val ageSeconds = ((System.currentTimeMillis() - local.loadedAtMillis).coerceAtLeast(0L) / 1000L)
                    "cache=${local.items.size} entries age=${ageSeconds}s fallback=${local.fromFallback}"
                }
                val loadText = if (meta == null) {
                    "lastLoad=none"
                } else {
                    val ageSeconds = ((System.currentTimeMillis() - meta.loadedAtMillis).coerceAtLeast(0L) / 1000L)
                    "lastLoad=${ageSeconds}s source=${meta.sourceUrl} entries=${meta.entryCount} fallback=${meta.fromFallback}" +
                        (meta.error?.let { " error=$it" } ?: "")
                }
                CommandResult.ok(
                    "source=${cfg.sourceUrl} ttl=${cfg.cacheTtlSeconds}s searchLimit=${cfg.searchResultLimit} $cacheText $loadText"
                )
            }

            spec(
                command = "browser-refresh",
                description = "Refresh plugin catalog cache from remote source",
                usage = "browser-refresh"
            ) { inv ->
                val ctx = inv.pluginContext
                val items = loadCatalog(ctx, forceRefresh = true)
                val meta = lastLoadMeta
                val suffix = if (meta?.fromFallback == true) " (fallback)" else ""
                CommandResult.ok("Catalog refreshed (${items.size} entries)$suffix")
            }
        }
    }

    override fun onEnable(ctx: PluginContext) = delegate.onEnable(ctx)

    override fun onDisable(ctx: PluginContext) = delegate.onDisable(ctx)

    private fun config(ctx: PluginContext): BrowserConfig {
        val store = ctx.storage.store(CONFIG_KEY, BrowserConfig::class.java, version = 1)
        val raw = store.load() ?: BrowserConfig()
        val normalized = normalizeConfig(raw)
        if (normalized != raw) {
            store.save(normalized)
        }
        return normalized
    }

    private fun normalizeConfig(input: BrowserConfig): BrowserConfig {
        return input.copy(
            sourceUrl = input.sourceUrl.trim().ifEmpty { DEFAULT_SOURCE_URL },
            cacheTtlSeconds = input.cacheTtlSeconds.coerceIn(5, 600),
            searchResultLimit = input.searchResultLimit.coerceIn(1, 25),
            connectTimeoutMillis = input.connectTimeoutMillis.coerceIn(500, 30_000),
            readTimeoutMillis = input.readTimeoutMillis.coerceIn(1_000, 60_000)
        )
    }

    private fun filteredCatalog(ctx: PluginContext, query: String): List<CatalogItem> {
        val q = query.trim().lowercase(Locale.ROOT)
        val all = loadCatalog(ctx, forceRefresh = false)
        if (q.isEmpty()) return all
        return all.filter { item ->
            item.id.lowercase(Locale.ROOT).contains(q) ||
                item.name.lowercase(Locale.ROOT).contains(q) ||
                item.category.lowercase(Locale.ROOT).contains(q) ||
                item.minecraft.lowercase(Locale.ROOT).contains(q) ||
                item.description.lowercase(Locale.ROOT).contains(q)
        }
    }

    private fun loadCatalog(ctx: PluginContext, forceRefresh: Boolean): List<CatalogItem> {
        val now = System.currentTimeMillis()
        val cfg = config(ctx)
        val local = cache
        if (!forceRefresh && local != null && local.sourceUrl == cfg.sourceUrl) {
            val ageMillis = now - local.loadedAtMillis
            if (ageMillis in 0 until (cfg.cacheTtlSeconds * 1000L)) {
                return local.items
            }
        }

        val source = cfg.sourceUrl
        val remote = runCatching {
            ctx.host.httpGet(
                url = source,
                connectTimeoutMillis = cfg.connectTimeoutMillis,
                readTimeoutMillis = cfg.readTimeoutMillis,
                maxBodyChars = 250_000
            )
        }.getOrNull()

        val remoteError = when {
            remote == null -> "http_get_unavailable"
            !remote.success -> (remote.error ?: "http_get_failed")
            remote.body.isBlank() -> "empty_catalog"
            else -> null
        }

        val parsedRemote = if (remoteError == null) parseCatalogText(remote!!.body) else emptyList()
        val useFallback = parsedRemote.isEmpty()
        val parsed = if (useFallback) fallbackCatalog() else parsedRemote
        val error = if (useFallback) {
            remoteError ?: "catalog_parse_failed"
        } else {
            null
        }

        cache = CatalogCache(
            loadedAtMillis = now,
            sourceUrl = source,
            items = parsed,
            fromFallback = useFallback,
            error = error
        )
        lastLoadMeta = CatalogLoadMeta(
            loadedAtMillis = now,
            sourceUrl = source,
            entryCount = parsed.size,
            fromFallback = useFallback,
            error = error
        )
        return parsed
    }

    private fun parseCatalogText(raw: String): List<CatalogItem> {
        val byId = linkedMapOf<String, CatalogItem>()
        raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .forEach { line ->
                val parts = line.split('|', limit = 7).map { it.trim() }
                if (parts.size < 7) return@forEach
                val id = normalizePluginId(parts[0]) ?: return@forEach
                val name = parts[1]
                val version = parts[2]
                val minecraft = parts[3]
                val category = parts[4]
                val downloadUrl = parts[5]
                val description = parts[6]
                if (name.isBlank() || version.isBlank() || minecraft.isBlank() || category.isBlank()) return@forEach
                if (!isHttpUrl(downloadUrl)) return@forEach
                byId[id] = CatalogItem(
                    id = id,
                    name = name,
                    version = version,
                    minecraft = minecraft,
                    category = category,
                    downloadUrl = downloadUrl,
                    description = description
                )
            }
        return byId.values
            .sortedBy { it.name.lowercase(Locale.ROOT) }
            .toList()
    }

    private fun fallbackCatalog(): List<CatalogItem> {
        return listOf(
            CatalogItem(
                id = "clockwork-demo",
                name = "Clockwork Demo",
                version = "1.5.0",
                minecraft = "1.21.x",
                category = "automation",
                downloadUrl = "https://github.com/Lianomeister/Clockwork/releases/latest/download/clockwork-demo-standalone-1.5.0.jar",
                description = "Reference plugin with commands, systems, and events."
            )
        )
    }

    private fun normalizePluginId(raw: String): String? {
        val value = raw.trim().lowercase(Locale.ROOT)
        if (value.isBlank()) return null
        if (!Regex("^[a-z0-9._-]{2,64}$").matches(value)) return null
        return value
    }

    private fun isHttpUrl(value: String): Boolean {
        val normalized = value.trim().lowercase(Locale.ROOT)
        return normalized.startsWith("http://") || normalized.startsWith("https://")
    }

    private fun resolveItem(items: List<CatalogItem>, input: String): CatalogItem? {
        val exact = items.firstOrNull { it.id.equals(input, ignoreCase = true) }
        if (exact != null) return exact
        val prefixMatches = items.filter { it.id.startsWith(input, ignoreCase = true) }
        return if (prefixMatches.size == 1) prefixMatches.first() else null
    }

    private fun collectTail(tokens: List<String>, fromIndex: Int): String {
        if (fromIndex >= tokens.size) return ""
        return tokens.drop(fromIndex).joinToString(" ").trim()
    }
}
