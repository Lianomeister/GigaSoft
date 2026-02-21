package com.clockwork.showcase.nick

import com.clockwork.api.*

data class NickState(val displayName: String = "", val skinAlias: String = "")

class NickPlugin : GigaPlugin {
    private val delegate = gigaPlugin(id = "showcase-nick", name = "Showcase Nick", version = "1.0.0") {
        commands {
            spec(
                command = "nick",
                description = "Set a local nickname alias",
                argsSchema = listOf(
                    CommandArgSpec("player", CommandArgType.STRING),
                    CommandArgSpec("name", CommandArgType.STRING)
                ),
                usage = "nick <player> <newName>"
            ) { inv ->
                val ctx = inv.pluginContext
                val player = inv.parsedArgs.requiredString("player")
                val newName = inv.parsedArgs.requiredString("name")
                val key = "nick-state:${player.lowercase()}"
                val store = ctx.storage.store(key, NickState::class.java, version = 1)
                val previous = store.load() ?: NickState()
                store.save(previous.copy(displayName = newName))
                val ok = ctx.host.sendPlayerMessage(player, "[Nick] You are now known as '$newName' (showcase mode)")
                if (ok) CommandResult.ok("Nick updated for $player -> $newName")
                else CommandResult.error("Player '$player' not found", code = "E_NOT_FOUND")
            }

            spec(
                command = "nick-skin",
                description = "Set a skin alias to pair with nickname",
                argsSchema = listOf(
                    CommandArgSpec("player", CommandArgType.STRING),
                    CommandArgSpec("skinName", CommandArgType.STRING)
                ),
                usage = "nick-skin <player> <skinName>"
            ) { inv ->
                val ctx = inv.pluginContext
                val player = inv.parsedArgs.requiredString("player")
                val skinName = inv.parsedArgs.requiredString("skinName")
                val key = "nick-state:${player.lowercase()}"
                val store = ctx.storage.store(key, NickState::class.java, version = 1)
                val previous = store.load() ?: NickState()
                store.save(previous.copy(skinAlias = skinName))
                val ok = ctx.host.sendPlayerMessage(player, "[Nick] Skin alias set to '$skinName' (host skin integration required)")
                if (ok) CommandResult.ok("Skin alias updated for $player -> $skinName")
                else CommandResult.error("Player '$player' not found", code = "E_NOT_FOUND")
            }
        }
    }

    override fun onEnable(ctx: PluginContext) = delegate.onEnable(ctx)
    override fun onDisable(ctx: PluginContext) = delegate.onDisable(ctx)
}
