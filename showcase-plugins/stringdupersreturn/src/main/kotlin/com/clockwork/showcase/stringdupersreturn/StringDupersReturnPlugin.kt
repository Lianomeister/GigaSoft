package com.clockwork.showcase.stringdupersreturn

import com.clockwork.api.*
import java.util.concurrent.ConcurrentHashMap

data class StringDuperConfig(
    val enabled: Boolean = true,
    val multiplier: Int = 2,
    val cooldownMillis: Long = 1500L
)

class StringDupersReturnPlugin : GigaPlugin {
    private val configKey = "stringduper-config"

    @Volatile
    private var config = StringDuperConfig()

    private val lastDupedAt = ConcurrentHashMap<String, Long>()

    private val delegate = gigaPlugin(id = "showcase-stringdupersreturn", name = "Showcase String Dupers Return", version = "1.0.0") {
        events {
            subscribe(GigaInventoryChangeEvent::class.java) { event ->
                if (!config.enabled) return@subscribe
                val itemId = event.itemId.lowercase()
                if (!itemId.contains("string")) return@subscribe
                val ownerKey = event.owner.lowercase()
                val now = System.currentTimeMillis()
                val last = lastDupedAt[ownerKey] ?: 0L
                if (now - last < config.cooldownMillis) return@subscribe
                val ctx = pluginContextRef ?: return@subscribe
                val add = (config.multiplier - 1).coerceAtLeast(1)
                ctx.host.givePlayerItem(event.owner, event.itemId, add)
                ctx.host.sendPlayerMessage(event.owner, "[StringDuper] Duped ${event.itemId} x$add")
                lastDupedAt[ownerKey] = now
            }
        }

        commands {
            spec(
                command = "stringduper",
                argsSchema = listOf(CommandArgSpec("enabled", CommandArgType.STRING, required = false)),
                usage = "stringduper [on|off]"
            ) { inv ->
                val raw = inv.parsedArgs.string("enabled")?.trim()?.lowercase()
                if (raw == null) {
                    return@spec CommandResult.ok("StringDuper enabled=${config.enabled} multiplier=${config.multiplier} cooldownMs=${config.cooldownMillis}")
                }
                val enabled = when (raw) {
                    "on" -> true
                    "off" -> false
                    else -> return@spec CommandResult.error("Use on/off", code = "E_ARGS")
                }
                config = config.copy(enabled = enabled)
                saveConfig(inv.pluginContext)
                CommandResult.ok("StringDuper ${if (enabled) "enabled" else "disabled"}")
            }

            spec(
                command = "dupestring",
                argsSchema = listOf(
                    CommandArgSpec("player", CommandArgType.STRING),
                    CommandArgSpec("count", CommandArgType.INT, required = false)
                ),
                usage = "dupestring <player> [count]"
            ) { inv ->
                val player = inv.parsedArgs.requiredString("player").trim()
                val count = (inv.parsedArgs.int("count") ?: config.multiplier).coerceIn(1, 64)
                val added = inv.pluginContext.host.givePlayerItem(player, "minecraft:string", count)
                CommandResult.ok("Added minecraft:string x$added to $player")
            }

            spec(command = "stringduper-status", usage = "stringduper-status") { _ ->
                CommandResult.ok("StringDuper enabled=${config.enabled} multiplier=${config.multiplier} cooldownMs=${config.cooldownMillis}")
            }
        }
    }

    @Volatile
    private var pluginContextRef: PluginContext? = null

    override fun onEnable(ctx: PluginContext) {
        pluginContextRef = ctx
        config = ctx.storage.store(configKey, StringDuperConfig::class.java, version = 1).load() ?: StringDuperConfig()
        delegate.onEnable(ctx)
    }

    override fun onDisable(ctx: PluginContext) {
        saveConfig(ctx)
        pluginContextRef = null
        lastDupedAt.clear()
        delegate.onDisable(ctx)
    }

    private fun saveConfig(ctx: PluginContext) {
        ctx.storage.store(configKey, StringDuperConfig::class.java, version = 1).save(config)
    }
}
