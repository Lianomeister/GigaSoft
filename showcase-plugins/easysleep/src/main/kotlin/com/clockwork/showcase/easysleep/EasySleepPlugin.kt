package com.clockwork.showcase.easysleep

import com.clockwork.api.*

data class EasySleepState(
    val requiredPercent: Int = 50
)

class EasySleepPlugin : GigaPlugin {
    private val stateKey = "easysleep-state"
    private val sleepingPlayers = linkedSetOf<String>()
    @Volatile
    private var requiredPercent: Int = 50

    private val delegate = gigaPlugin(id = "showcase-easysleep", name = "Showcase EasySleep", version = "1.0.0") {
        events {
            subscribe(GigaPlayerLeaveEvent::class.java) { event ->
                sleepingPlayers.remove(event.player.name.lowercase())
            }
        }

        commands {
            spec(
                command = "sleeping",
                argsSchema = listOf(CommandArgSpec("percent", CommandArgType.INT)),
                usage = "sleeping <percent>"
            ) { inv ->
                val percent = inv.parsedArgs.requiredInt("percent").coerceIn(1, 100)
                requiredPercent = percent
                inv.pluginContext.saveState(stateKey, EasySleepState(requiredPercent = requiredPercent))
                CommandResult.ok("EasySleep threshold set to $requiredPercent%")
            }

            spec(command = "sleep", usage = "sleep") { inv ->
                val ctx = inv.pluginContext
                sleepingPlayers.add(inv.sender.id.lowercase())
                val result = evaluateSleep(ctx)
                CommandResult.ok(result)
            }

            spec(command = "wake", usage = "wake") { inv ->
                sleepingPlayers.remove(inv.sender.id.lowercase())
                CommandResult.ok("You are no longer sleeping")
            }

            spec(command = "sleep-status", usage = "sleep-status") { inv ->
                val online = estimateOnline(inv.pluginContext)
                val sleeping = sleepingPlayers.size
                val ratio = if (online <= 0) 0.0 else (sleeping.toDouble() / online.toDouble()) * 100.0
                CommandResult.ok("sleeping=$sleeping online=$online ratio=${"%.1f".format(ratio)}% required=$requiredPercent%")
            }
        }
    }

    override fun onEnable(ctx: PluginContext) {
        val state = ctx.loadOrDefault(stateKey) { EasySleepState() }
        requiredPercent = state.requiredPercent.coerceIn(1, 100)
        delegate.onEnable(ctx)
    }

    override fun onDisable(ctx: PluginContext) {
        ctx.saveState(stateKey, EasySleepState(requiredPercent = requiredPercent))
        sleepingPlayers.clear()
        delegate.onDisable(ctx)
    }

    private fun estimateOnline(ctx: PluginContext): Int {
        val count = ctx.host.serverInfo()?.onlinePlayers ?: 0
        return count.coerceAtLeast(sleepingPlayers.size)
    }

    private fun evaluateSleep(ctx: PluginContext): String {
        val online = estimateOnline(ctx)
        if (online <= 0) return "No online players"
        val sleeping = sleepingPlayers.size
        val ratio = (sleeping.toDouble() / online.toDouble()) * 100.0
        if (ratio + 1e-9 >= requiredPercent.toDouble()) {
            ctx.host.worlds().forEach { world ->
                ctx.host.setWorldTime(world.name, 1000L)
                ctx.host.setWorldWeather(world.name, "clear")
            }
            sleepingPlayers.clear()
            return "Night skipped (${sleeping}/${online}, ${"%.1f".format(ratio)}%)"
        }
        return "Not enough sleepers (${sleeping}/${online}, ${"%.1f".format(ratio)}%)"
    }
}
