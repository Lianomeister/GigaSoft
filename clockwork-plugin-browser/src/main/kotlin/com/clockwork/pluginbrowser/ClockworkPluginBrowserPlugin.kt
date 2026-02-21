package com.clockwork.pluginbrowser

import com.clockwork.api.*
import java.util.Locale

data class BrowserConfig(val sourceUrl: String = DEFAULT_SOURCE_URL)

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
    val items: List<CatalogItem>
)

private const val DEFAULT_SOURCE_URL = "https://raw.githubusercontent.com/Lianomeister/Clockwork/main/docs/marketplace/catalog-v1.txt"

class ClockworkPluginBrowserPlugin : GigaPlugin {
    @Volatile
    private var cache: CatalogCache? = null

    private val delegate = gigaPlugin(
        id = "clockwork-plugin-browser",
        name = "Clockwork Plugin Browser",
        version = "1.5.0"
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
                val query = collectTail(inv.rawArgs, 0)
                val items = filteredCatalog(ctx, query)
                if (items.isEmpty()) {
                    return@spec CommandResult.ok("No plugins found for '$query'")
                }
                val top = items.take(8).joinToString(" | ") {
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
                command = "browser-install",
                description = "Install plugin from browser catalog by id",
                argsSchema = listOf(
                    CommandArgSpec("pluginId", CommandArgType.STRING)
                ),
                usage = "browser-install <pluginId>"
            ) { inv ->
                val ctx = inv.pluginContext
                val pluginId = inv.parsedArgs.requiredString("pluginId")
                val item = loadCatalog(ctx, forceRefresh = false)
                    .firstOrNull { it.id.equals(pluginId, ignoreCase = true) }
                    ?: return@spec CommandResult.error("Unknown plugin id '$pluginId'", code = "E_NOT_FOUND")
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
                val store = ctx.storage.store("browser_config", BrowserConfig::class.java, version = 1)
                val current = store.load() ?: BrowserConfig()
                if (input.isBlank()) {
                    return@spec CommandResult.ok("Catalog source: ${current.sourceUrl}")
                }
                val normalized = input.trim()
                if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
                    return@spec CommandResult.error("Only http/https source URLs are supported", code = "E_ARGS")
                }
                store.save(BrowserConfig(sourceUrl = normalized))
                cache = null
                CommandResult.ok("Catalog source updated")
            }

            spec(
                command = "browser-refresh",
                description = "Refresh plugin catalog cache from remote source",
                usage = "browser-refresh"
            ) { inv ->
                val ctx = inv.pluginContext
                val items = loadCatalog(ctx, forceRefresh = true)
                CommandResult.ok("Catalog refreshed (${items.size} entries)")
            }
        }
    }

    override fun onEnable(ctx: PluginContext) = delegate.onEnable(ctx)

    override fun onDisable(ctx: PluginContext) = delegate.onDisable(ctx)

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
        val local = cache
        if (!forceRefresh && local != null && (now - local.loadedAtMillis) < 30_000L) {
            return local.items
        }

        val source = (ctx.storage.store("browser_config", BrowserConfig::class.java, version = 1).load() ?: BrowserConfig()).sourceUrl
        val remote = runCatching {
            ctx.host.httpGet(
                url = source,
                connectTimeoutMillis = 3_000,
                readTimeoutMillis = 6_000,
                maxBodyChars = 250_000
            )
        }.getOrNull()

        val parsed = if (remote != null && remote.success && remote.body.isNotBlank()) {
            parseCatalogText(remote.body)
        } else {
            fallbackCatalog()
        }
        cache = CatalogCache(loadedAtMillis = now, items = parsed)
        return parsed
    }

    private fun parseCatalogText(raw: String): List<CatalogItem> {
        return raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { line ->
                val parts = line.split('|').map { it.trim() }
                if (parts.size < 7) return@mapNotNull null
                CatalogItem(
                    id = parts[0],
                    name = parts[1],
                    version = parts[2],
                    minecraft = parts[3],
                    category = parts[4],
                    downloadUrl = parts[5],
                    description = parts[6]
                )
            }
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

    private fun collectTail(tokens: List<String>, fromIndex: Int): String {
        if (fromIndex >= tokens.size) return ""
        return tokens.drop(fromIndex).joinToString(" ").trim()
    }
}
