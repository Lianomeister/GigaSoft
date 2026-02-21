package com.clockwork.showcase.scrible

import com.clockwork.api.*

data class ScribleState(
    val snippets: Map<String, String> = emptyMap()
)

class ScriblePlugin : GigaPlugin {
    private val stateKey = "scrible-state"
    private val snippets = linkedMapOf<String, String>()

    private val legacyCodes = mapOf(
        "&0" to "<black>", "&1" to "<dark_blue>", "&2" to "<dark_green>", "&3" to "<dark_aqua>",
        "&4" to "<dark_red>", "&5" to "<dark_purple>", "&6" to "<gold>", "&7" to "<gray>",
        "&8" to "<dark_gray>", "&9" to "<blue>", "&a" to "<green>", "&b" to "<aqua>",
        "&c" to "<red>", "&d" to "<light_purple>", "&e" to "<yellow>", "&f" to "<white>",
        "&l" to "<bold>", "&n" to "<underline>", "&o" to "<italic>", "&m" to "<strikethrough>",
        "&r" to "<reset>"
    )

    private val delegate = gigaPlugin(id = "showcase-scrible", name = "Showcase Scrible", version = "1.0.0") {
        commands {
            spec(
                command = "scribble-preview",
                argsSchema = listOf(CommandArgSpec("text", CommandArgType.STRING)),
                usage = "scribble-preview <text>"
            ) { inv ->
                val raw = inv.parsedArgs.requiredString("text")
                val formatted = format(raw)
                inv.pluginContext.host.sendPlayerMessage(inv.sender.id, "[Scrible] $formatted")
                CommandResult.ok(formatted)
            }

            spec(
                command = "scribble-save",
                argsSchema = listOf(CommandArgSpec("name", CommandArgType.STRING), CommandArgSpec("text", CommandArgType.STRING)),
                usage = "scribble-save <name> <text>"
            ) { inv ->
                val name = inv.parsedArgs.requiredString("name").trim().lowercase()
                val text = inv.parsedArgs.requiredString("text")
                if (name.length < 2) return@spec CommandResult.error("Name too short", code = "E_ARGS")
                snippets[name] = text
                inv.pluginContext.saveState(stateKey, ScribleState(snippets.toMap()))
                CommandResult.ok("Saved snippet '$name'")
            }

            spec(
                command = "scribble-show",
                argsSchema = listOf(CommandArgSpec("name", CommandArgType.STRING)),
                usage = "scribble-show <name>"
            ) { inv ->
                val name = inv.parsedArgs.requiredString("name").trim().lowercase()
                val raw = snippets[name] ?: return@spec CommandResult.error("Snippet not found", code = "E_NOT_FOUND")
                val formatted = format(raw)
                inv.pluginContext.host.sendPlayerMessage(inv.sender.id, "[Scrible] $formatted")
                CommandResult.ok(formatted)
            }

            spec(command = "scribble-list", usage = "scribble-list") { _ ->
                if (snippets.isEmpty()) return@spec CommandResult.ok("No snippets")
                CommandResult.ok("Snippets: ${snippets.keys.sorted().joinToString(", ")}")
            }
        }
    }

    override fun onEnable(ctx: PluginContext) {
        val state = ctx.loadOrDefault(stateKey) { ScribleState() }
        snippets.clear(); snippets.putAll(state.snippets)
        delegate.onEnable(ctx)
    }

    override fun onDisable(ctx: PluginContext) {
        ctx.saveState(stateKey, ScribleState(snippets.toMap()))
        delegate.onDisable(ctx)
    }

    private fun format(input: String): String {
        var out = input
        legacyCodes.forEach { (from, to) -> out = out.replace(from, to, ignoreCase = true) }
        out = out.replace(Regex("<g:([^:>]+):([^>]+)>(.*?)</g>"), "<gradient:$1:$2>$3</gradient>")
        return out
    }
}
