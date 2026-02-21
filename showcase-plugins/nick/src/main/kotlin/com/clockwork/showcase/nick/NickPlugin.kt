package com.clockwork.showcase.nick

import com.clockwork.api.*

data class NickState(
    val displayName: String = "",
    val skinAlias: String = "",
    val updatedAtMillis: Long = 0L
)

data class NickIndex(
    val players: Set<String> = emptySet()
)

class NickPlugin : GigaPlugin {
    private val indexKey = "nick-index"

    private val delegate = gigaPlugin(id = "showcase-nick", name = "Showcase Nick", version = "1.1.0") {
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
                val player = normalizePlayer(inv.parsedArgs.requiredString("player"))
                val newName = inv.parsedArgs.requiredString("name").trim()
                validateNick(newName)?.let { msg ->
                    return@spec CommandResult.error(msg, code = "E_ARGS")
                }
                val previous = loadState(ctx, player)
                saveState(ctx, player, previous.copy(displayName = newName, updatedAtMillis = System.currentTimeMillis()))
                addToIndex(ctx, player)
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
                val player = normalizePlayer(inv.parsedArgs.requiredString("player"))
                val skinName = inv.parsedArgs.requiredString("skinName").trim()
                validateAlias(skinName)?.let { msg ->
                    return@spec CommandResult.error(msg, code = "E_ARGS")
                }
                val previous = loadState(ctx, player)
                saveState(ctx, player, previous.copy(skinAlias = skinName, updatedAtMillis = System.currentTimeMillis()))
                addToIndex(ctx, player)
                val ok = ctx.host.sendPlayerMessage(player, "[Nick] Skin alias set to '$skinName' (host skin integration required)")
                if (ok) CommandResult.ok("Skin alias updated for $player -> $skinName")
                else CommandResult.error("Player '$player' not found", code = "E_NOT_FOUND")
            }

            spec(
                command = "nick-show",
                description = "Show nickname and skin alias for a player",
                argsSchema = listOf(CommandArgSpec("player", CommandArgType.STRING)),
                usage = "nick-show <player>"
            ) { inv ->
                val ctx = inv.pluginContext
                val player = normalizePlayer(inv.parsedArgs.requiredString("player"))
                val state = loadState(ctx, player)
                if (state.displayName.isBlank() && state.skinAlias.isBlank()) {
                    return@spec CommandResult.ok("No nick profile stored for $player")
                }
                CommandResult.ok("$player -> nick='${state.displayName.ifBlank { "-" }}' skin='${state.skinAlias.ifBlank { "-" }}'")
            }

            spec(
                command = "nick-clear",
                description = "Clear nickname and skin alias for a player",
                argsSchema = listOf(CommandArgSpec("player", CommandArgType.STRING)),
                usage = "nick-clear <player>"
            ) { inv ->
                val ctx = inv.pluginContext
                val player = normalizePlayer(inv.parsedArgs.requiredString("player"))
                saveState(ctx, player, NickState())
                removeFromIndexIfEmpty(ctx, player)
                CommandResult.ok("Nick profile cleared for $player")
            }

            spec(
                command = "nick-list",
                description = "List players with stored nick profiles",
                usage = "nick-list"
            ) { inv ->
                val ctx = inv.pluginContext
                val players = loadIndex(ctx).players.sorted()
                if (players.isEmpty()) {
                    return@spec CommandResult.ok("No nick profiles stored")
                }
                val preview = players.take(25).joinToString(", ")
                val suffix = if (players.size > 25) " ... (+${players.size - 25})" else ""
                CommandResult.ok("Nick profiles (${players.size}): $preview$suffix")
            }
        }
    }

    override fun onEnable(ctx: PluginContext) = delegate.onEnable(ctx)
    override fun onDisable(ctx: PluginContext) = delegate.onDisable(ctx)

    private fun stateKey(player: String): String = "nick-state:${normalizePlayer(player)}"

    private fun normalizePlayer(raw: String): String = raw.trim().lowercase()

    private fun loadState(ctx: PluginContext, player: String): NickState {
        return ctx.storage.store(stateKey(player), NickState::class.java, version = 1).load() ?: NickState()
    }

    private fun saveState(ctx: PluginContext, player: String, state: NickState) {
        ctx.storage.store(stateKey(player), NickState::class.java, version = 1).save(state)
    }

    private fun loadIndex(ctx: PluginContext): NickIndex {
        return ctx.storage.store(indexKey, NickIndex::class.java, version = 1).load() ?: NickIndex()
    }

    private fun saveIndex(ctx: PluginContext, index: NickIndex) {
        ctx.storage.store(indexKey, NickIndex::class.java, version = 1).save(index)
    }

    private fun addToIndex(ctx: PluginContext, player: String) {
        val normalized = normalizePlayer(player)
        val current = loadIndex(ctx)
        saveIndex(ctx, current.copy(players = current.players + normalized))
    }

    private fun removeFromIndexIfEmpty(ctx: PluginContext, player: String) {
        val normalized = normalizePlayer(player)
        val state = loadState(ctx, normalized)
        if (state.displayName.isNotBlank() || state.skinAlias.isNotBlank()) return
        val current = loadIndex(ctx)
        saveIndex(ctx, current.copy(players = current.players - normalized))
    }

    private fun validateNick(value: String): String? {
        if (value.length !in 3..24) return "Nickname must be between 3 and 24 characters"
        if (!Regex("^[A-Za-z0-9_\\- ]+$").matches(value)) {
            return "Nickname contains unsupported characters"
        }
        return null
    }

    private fun validateAlias(value: String): String? {
        if (value.length !in 3..32) return "Skin alias must be between 3 and 32 characters"
        if (!Regex("^[A-Za-z0-9_\\-]+$").matches(value)) {
            return "Skin alias contains unsupported characters"
        }
        return null
    }
}
