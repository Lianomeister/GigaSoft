package com.clockwork.showcase.enchanted

import com.clockwork.api.*

data class EnchantedState(
    val playerPresets: Map<String, String> = emptyMap(),
    val customPresets: Map<String, Map<String, Int>> = emptyMap(),
    val durationSeconds: Int = 300
)

class EnchantedPlugin : GigaPlugin {
    private val stateKey = "enchanted-state"
    private val playerPresets = linkedMapOf<String, String>()
    private val customPresets = linkedMapOf<String, Map<String, Int>>()
    private var durationSeconds: Int = 300

    private val builtInPresets = linkedMapOf(
        "duelist" to mapOf("speed" to 1, "strength" to 1),
        "assassin" to mapOf("speed" to 2, "invisibility" to 1, "jump_boost" to 1),
        "guardian" to mapOf("resistance" to 1, "regeneration" to 1),
        "berserker" to mapOf("strength" to 2, "haste" to 1),
        "tank" to mapOf("resistance" to 2, "slowness" to 1),
        "archer" to mapOf("speed" to 1, "night_vision" to 1),
        "vampire" to mapOf("strength" to 1, "regeneration" to 2)
    )

    private val delegate = gigaPlugin(id = "showcase-enchanted", name = "Showcase Enchanted", version = "1.1.0") {
        commands {
            spec(
                command = "enchanted-list",
                argsSchema = listOf(CommandArgSpec("detail", CommandArgType.BOOLEAN, required = false)),
                usage = "enchanted-list [detail]"
            ) { inv ->
                val all = allPresets()
                if (all.isEmpty()) return@spec CommandResult.ok("No presets")
                val detail = inv.parsedArgs.boolean("detail") ?: false
                if (!detail) return@spec CommandResult.ok("Presets: ${all.keys.joinToString(", ")}")
                CommandResult.ok(
                    all.entries.joinToString(" | ") { (name, effects) ->
                        "$name=[${effects.entries.joinToString(",") { "${it.key}:${it.value}" }}]"
                    }
                )
            }

            spec(
                command = "enchanted-apply",
                argsSchema = listOf(
                    CommandArgSpec("preset", CommandArgType.STRING),
                    CommandArgSpec("player", CommandArgType.STRING, required = false)
                ),
                usage = "enchanted-apply <preset> [player]"
            ) { inv ->
                val ctx = inv.pluginContext
                val player = (inv.parsedArgs.string("player") ?: inv.sender.id).trim()
                val key = player.lowercase()
                val preset = inv.parsedArgs.requiredString("preset").trim().lowercase()
                val effects = allPresets()[preset] ?: return@spec CommandResult.error("Unknown preset", code = "E_NOT_FOUND")
                clearEffects(ctx, player, playerPresets[key])
                effects.forEach { (effect, amp) ->
                    ctx.host.addPlayerEffect(player, effect, durationTicks = durationSeconds * 20, amplifier = amp.coerceIn(0, 10))
                }
                playerPresets[key] = preset
                saveState(ctx)
                ctx.host.sendPlayerMessage(player, "[Enchanted] Applied preset '$preset' (${durationSeconds}s)")
                CommandResult.ok("Applied '$preset' to $player")
            }

            spec(
                command = "enchanted-show",
                argsSchema = listOf(CommandArgSpec("player", CommandArgType.STRING, required = false)),
                usage = "enchanted-show [player]"
            ) { inv ->
                val player = (inv.parsedArgs.string("player") ?: inv.sender.id).trim().lowercase()
                val preset = playerPresets[player] ?: return@spec CommandResult.ok("No enchanted preset for $player")
                CommandResult.ok("$player => $preset (${durationSeconds}s)")
            }

            spec(
                command = "enchanted-clear",
                argsSchema = listOf(CommandArgSpec("player", CommandArgType.STRING, required = false)),
                usage = "enchanted-clear [player]"
            ) { inv ->
                val ctx = inv.pluginContext
                val player = (inv.parsedArgs.string("player") ?: inv.sender.id).trim()
                val key = player.lowercase()
                val removed = playerPresets.remove(key)
                if (removed == null) return@spec CommandResult.ok("No preset assigned to $player")
                clearEffects(ctx, player, removed)
                saveState(ctx)
                CommandResult.ok("Cleared enchanted preset for $player")
            }

            spec(
                command = "enchanted-duration",
                argsSchema = listOf(CommandArgSpec("seconds", CommandArgType.INT)),
                usage = "enchanted-duration <seconds>"
            ) { inv ->
                val seconds = inv.parsedArgs.requiredInt("seconds").coerceIn(30, 3600)
                durationSeconds = seconds
                saveState(inv.pluginContext)
                CommandResult.ok("Enchanted duration set to ${durationSeconds}s")
            }

            spec(
                command = "enchanted-create",
                argsSchema = listOf(CommandArgSpec("name", CommandArgType.STRING), CommandArgSpec("effects", CommandArgType.STRING)),
                usage = "enchanted-create <name> <effect:amp,...>"
            ) { inv ->
                val name = inv.parsedArgs.requiredString("name").trim().lowercase()
                if (name.length < 2) return@spec CommandResult.error("Preset name too short", code = "E_ARGS")
                if (name in builtInPresets) return@spec CommandResult.error("Cannot override built-in preset", code = "E_ARGS")
                val parsed = parseEffects(inv.parsedArgs.requiredString("effects"))
                if (parsed.isEmpty()) return@spec CommandResult.error("No valid effects parsed", code = "E_ARGS")
                customPresets[name] = parsed
                saveState(inv.pluginContext)
                CommandResult.ok("Created preset '$name' with ${parsed.size} effect(s)")
            }

            spec(
                command = "enchanted-delete",
                argsSchema = listOf(CommandArgSpec("name", CommandArgType.STRING)),
                usage = "enchanted-delete <name>"
            ) { inv ->
                val name = inv.parsedArgs.requiredString("name").trim().lowercase()
                if (name in builtInPresets) return@spec CommandResult.error("Built-in preset cannot be deleted", code = "E_ARGS")
                val removed = customPresets.remove(name)
                    ?: return@spec CommandResult.error("Preset not found", code = "E_NOT_FOUND")
                playerPresets.entries.removeIf { it.value == name }
                saveState(inv.pluginContext)
                CommandResult.ok("Deleted preset '$name' (${removed.size} effects)")
            }
        }
    }

    override fun onEnable(ctx: PluginContext) {
        val state = ctx.loadOrDefault(stateKey, version = 2) { EnchantedState() }
        playerPresets.clear(); playerPresets.putAll(state.playerPresets)
        customPresets.clear(); customPresets.putAll(state.customPresets)
        durationSeconds = state.durationSeconds.coerceIn(30, 3600)
        delegate.onEnable(ctx)
    }

    override fun onDisable(ctx: PluginContext) {
        saveState(ctx)
        delegate.onDisable(ctx)
    }

    private fun allPresets(): Map<String, Map<String, Int>> {
        val out = linkedMapOf<String, Map<String, Int>>()
        out.putAll(builtInPresets)
        out.putAll(customPresets)
        return out
    }

    private fun clearEffects(ctx: PluginContext, player: String, presetName: String?) {
        if (presetName == null) return
        val effects = allPresets()[presetName].orEmpty()
        effects.keys.forEach { effect ->
            ctx.host.removePlayerEffect(player, effect)
        }
    }

    private fun parseEffects(raw: String): Map<String, Int> {
        return raw.split(',')
            .mapNotNull { token ->
                val part = token.trim()
                if (part.isEmpty()) return@mapNotNull null
                val chunks = part.split(':', limit = 2)
                val effect = chunks[0].trim().lowercase()
                if (effect.isEmpty()) return@mapNotNull null
                val amp = if (chunks.size == 2) chunks[1].trim().toIntOrNull() ?: return@mapNotNull null else 1
                effect to amp.coerceIn(0, 10)
            }
            .toMap()
    }

    private fun saveState(ctx: PluginContext) {
        ctx.saveState(
            stateKey,
            EnchantedState(
                playerPresets = playerPresets.toMap(),
                customPresets = customPresets.toMap(),
                durationSeconds = durationSeconds
            ),
            version = 2
        )
    }
}
