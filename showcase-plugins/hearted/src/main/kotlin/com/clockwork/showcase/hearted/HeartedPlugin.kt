package com.clockwork.showcase.hearted

import com.clockwork.api.*

data class HeartedState(
    val defaultHearts: Int = 10,
    val overrides: Map<String, Int> = emptyMap(),
    val knownPlayers: Set<String> = emptySet()
)

class HeartedPlugin : GigaPlugin {
    private val stateKey = "hearted-state"
    private var defaultHearts: Int = 10
    private val overrides = linkedMapOf<String, Int>()
    private val knownPlayers = linkedSetOf<String>()

    private val delegate = gigaPlugin(id = "showcase-hearted", name = "Showcase Hearted", version = "1.0.0") {
        events {
            subscribe(GigaPlayerJoinEvent::class.java) { event ->
                val key = event.player.name.lowercase()
                knownPlayers.add(key)
                applyHearts(pluginContextRef ?: return@subscribe, event.player.name, overrides[key] ?: defaultHearts)
            }
        }

        commands {
            spec(
                command = "hearted",
                argsSchema = listOf(
                    CommandArgSpec("hearts", CommandArgType.INT),
                    CommandArgSpec("target", CommandArgType.STRING)
                ),
                usage = "hearted <hearts> <all|player>"
            ) { inv ->
                val ctx = inv.pluginContext
                val hearts = inv.parsedArgs.requiredInt("hearts").coerceIn(1, 40)
                val target = inv.parsedArgs.requiredString("target").trim()
                if (target.equals("all", ignoreCase = true)) {
                    defaultHearts = hearts
                    knownPlayers.forEach { player -> applyHearts(ctx, player, hearts) }
                    saveState(ctx)
                    return@spec CommandResult.ok("Set default hearts to $hearts for known players")
                }
                val player = target.removePrefix("@").trim().lowercase()
                overrides[player] = hearts
                knownPlayers.add(player)
                applyHearts(ctx, player, hearts)
                saveState(ctx)
                CommandResult.ok("Set hearts for $player to $hearts")
            }

            spec(
                command = "hearted-show",
                argsSchema = listOf(CommandArgSpec("player", CommandArgType.STRING, required = false)),
                usage = "hearted-show [player]"
            ) { inv ->
                val input = inv.parsedArgs.string("player")?.removePrefix("@")?.trim()?.lowercase()
                if (input.isNullOrBlank()) {
                    return@spec CommandResult.ok("Hearted default=$defaultHearts overrides=${overrides.size}")
                }
                val hearts = overrides[input] ?: defaultHearts
                CommandResult.ok("$input hearts=$hearts")
            }
        }
    }

    @Volatile
    private var pluginContextRef: PluginContext? = null

    override fun onEnable(ctx: PluginContext) {
        pluginContextRef = ctx
        val state = ctx.storage.store(stateKey, HeartedState::class.java, version = 1).load() ?: HeartedState()
        defaultHearts = state.defaultHearts.coerceIn(1, 40)
        overrides.clear(); overrides.putAll(state.overrides.mapValues { it.value.coerceIn(1, 40) })
        knownPlayers.clear(); knownPlayers.addAll(state.knownPlayers)
        delegate.onEnable(ctx)
    }

    override fun onDisable(ctx: PluginContext) {
        saveState(ctx)
        pluginContextRef = null
        delegate.onDisable(ctx)
    }

    private fun applyHearts(ctx: PluginContext, player: String, hearts: Int) {
        val status = ctx.host.playerStatus(player) ?: return
        val maxHealth = hearts * 2.0
        val currentHealth = status.health.coerceAtMost(maxHealth)
        ctx.host.setPlayerStatus(player, status.copy(maxHealth = maxHealth, health = currentHealth))
        ctx.host.sendPlayerMessage(player, "[Hearted] Max hearts set to $hearts")
    }

    private fun saveState(ctx: PluginContext) {
        ctx.storage.store(stateKey, HeartedState::class.java, version = 1).save(
            HeartedState(defaultHearts = defaultHearts, overrides = overrides.toMap(), knownPlayers = knownPlayers.toSet())
        )
    }
}
