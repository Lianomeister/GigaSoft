package com.clockwork.showcase.betterworld

import com.clockwork.api.*

data class BetterWorldState(
    val presetByWorld: Map<String, String> = emptyMap()
)

class BetterWorldPlugin : GigaPlugin {
    private val stateKey = "betterworld-state"
    private val presetByWorld = linkedMapOf<String, String>()

    private val delegate = gigaPlugin(id = "showcase-betterworld", name = "Showcase BetterWorld", version = "1.0.0") {
        systems {
            system("betterworld-ambience") { ctx ->
                presetByWorld.forEach { (world, preset) ->
                    applyPreset(ctx, world, preset, silent = true)
                }
            }
        }

        commands {
            spec(
                command = "betterworld-preset",
                argsSchema = listOf(CommandArgSpec("preset", CommandArgType.STRING)),
                usage = "betterworld-preset <lush|dramatic|calm>"
            ) { inv ->
                val ctx = inv.pluginContext
                val player = ctx.host.findPlayer(inv.sender.id) ?: return@spec CommandResult.error("Player not found", code = "E_NOT_FOUND")
                val world = player.location.world
                val preset = inv.parsedArgs.requiredString("preset").trim().lowercase()
                if (preset !in setOf("lush", "dramatic", "calm")) {
                    return@spec CommandResult.error("Unknown preset", code = "E_ARGS")
                }
                presetByWorld[world.lowercase()] = preset
                applyPreset(ctx, world, preset, silent = false)
                ctx.saveState(stateKey, BetterWorldState(presetByWorld.toMap()))
                CommandResult.ok("BetterWorld preset '$preset' applied to $world")
            }

            spec(command = "betterworld-status", usage = "betterworld-status") { _ ->
                if (presetByWorld.isEmpty()) return@spec CommandResult.ok("No BetterWorld presets active")
                CommandResult.ok(presetByWorld.entries.joinToString(" | ") { "${it.key}:${it.value}" })
            }
        }
    }

    override fun onEnable(ctx: PluginContext) {
        val state = ctx.loadOrDefault(stateKey) { BetterWorldState() }
        presetByWorld.clear(); presetByWorld.putAll(state.presetByWorld)
        delegate.onEnable(ctx)
    }

    override fun onDisable(ctx: PluginContext) {
        ctx.saveState(stateKey, BetterWorldState(presetByWorld.toMap()))
        delegate.onDisable(ctx)
    }

    private fun applyPreset(ctx: PluginContext, world: String, preset: String, silent: Boolean) {
        when (preset) {
            "lush" -> {
                ctx.host.setWorldTime(world, 1000L)
                ctx.host.setWorldWeather(world, "clear")
                ctx.host.setWorldData(world, mapOf("betterworld.theme" to "lush", "betterworld.foliageBoost" to "1"))
            }
            "dramatic" -> {
                ctx.host.setWorldTime(world, 13000L)
                ctx.host.setWorldWeather(world, "storm")
                ctx.host.setWorldData(world, mapOf("betterworld.theme" to "dramatic", "betterworld.fog" to "heavy"))
            }
            "calm" -> {
                ctx.host.setWorldTime(world, 6000L)
                ctx.host.setWorldWeather(world, "clear")
                ctx.host.setWorldData(world, mapOf("betterworld.theme" to "calm", "betterworld.wind" to "light"))
            }
        }
        if (!silent) {
            ctx.host.broadcast("[BetterWorld] $world updated with '$preset' ambience")
        }
    }
}
