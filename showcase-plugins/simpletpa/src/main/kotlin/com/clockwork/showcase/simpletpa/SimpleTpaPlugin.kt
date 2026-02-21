package com.clockwork.showcase.simpletpa

import com.clockwork.api.*

data class TpaRequest(
    val from: String,
    val to: String,
    val here: Boolean,
    val createdAtMillis: Long
)

data class TpaState(
    val requestsEnabled: Map<String, Boolean> = emptyMap()
)

class SimpleTpaPlugin : GigaPlugin {
    private val stateKey = "simpletpa-state"
    private val pendingByTarget = linkedMapOf<String, TpaRequest>()
    private val requestsEnabled = linkedMapOf<String, Boolean>()

    private val delegate = gigaPlugin(id = "showcase-simpletpa", name = "Showcase SimpleTpa", version = "1.0.0") {
        commands {
            spec(
                command = "tpa",
                argsSchema = listOf(CommandArgSpec("player", CommandArgType.STRING)),
                usage = "tpa <player>"
            ) { inv -> createRequest(inv.pluginContext, inv.sender.id, inv.parsedArgs.requiredString("player"), here = false) }

            spec(
                command = "tpahere",
                argsSchema = listOf(CommandArgSpec("player", CommandArgType.STRING)),
                usage = "tpahere <player>"
            ) { inv -> createRequest(inv.pluginContext, inv.sender.id, inv.parsedArgs.requiredString("player"), here = true) }

            spec(command = "tpaaccept", usage = "tpaaccept") { inv ->
                val ctx = inv.pluginContext
                val target = inv.sender.id.lowercase()
                val request = pendingByTarget.remove(target)
                    ?: return@spec CommandResult.error("No pending TPA request", code = "E_NOT_FOUND")

                val fromPlayer = ctx.host.findPlayer(request.from)
                val toPlayer = ctx.host.findPlayer(request.to)
                if (fromPlayer == null || toPlayer == null) {
                    return@spec CommandResult.error("One of the players is offline", code = "E_NOT_FOUND")
                }

                if (request.here) {
                    ctx.host.movePlayer(request.to, fromPlayer.location)
                } else {
                    ctx.host.movePlayer(request.from, toPlayer.location)
                }

                ctx.host.sendPlayerMessage(request.from, "[SimpleTPA] Request accepted")
                ctx.host.sendPlayerMessage(request.to, "[SimpleTPA] Request accepted")
                CommandResult.ok("TPA request accepted")
            }

            spec(command = "tpadecline", usage = "tpadecline") { inv ->
                val target = inv.sender.id.lowercase()
                val request = pendingByTarget.remove(target)
                    ?: return@spec CommandResult.error("No pending TPA request", code = "E_NOT_FOUND")
                inv.pluginContext.host.sendPlayerMessage(request.from, "[SimpleTPA] Request declined")
                CommandResult.ok("TPA request declined")
            }

            spec(
                command = "tparequests",
                argsSchema = listOf(CommandArgSpec("enabled", CommandArgType.STRING)),
                usage = "tparequests <on|off>"
            ) { inv ->
                val raw = inv.parsedArgs.requiredString("enabled").trim().lowercase()
                val enabled = when (raw) {
                    "on" -> true
                    "off" -> false
                    else -> return@spec CommandResult.error("Use on/off", code = "E_ARGS")
                }
                requestsEnabled[inv.sender.id.lowercase()] = enabled
                saveState(inv.pluginContext)
                CommandResult.ok("TPA requests ${if (enabled) "enabled" else "disabled"}")
            }
        }
    }

    override fun onEnable(ctx: PluginContext) {
        val state = ctx.storage.store(stateKey, TpaState::class.java, version = 1).load() ?: TpaState()
        requestsEnabled.clear()
        requestsEnabled.putAll(state.requestsEnabled)
        delegate.onEnable(ctx)
    }

    override fun onDisable(ctx: PluginContext) {
        saveState(ctx)
        delegate.onDisable(ctx)
    }

    private fun createRequest(ctx: PluginContext, from: String, toRaw: String, here: Boolean): CommandResult {
        val to = toRaw.trim()
        if (to.isBlank()) return CommandResult.error("Target player required", code = "E_ARGS")
        if (from.equals(to, ignoreCase = true)) return CommandResult.error("Cannot send request to yourself", code = "E_ARGS")
        if (requestsEnabled[to.lowercase()] == false) {
            return CommandResult.error("$to has disabled tpa requests", code = "E_PERMISSION")
        }
        if (ctx.host.findPlayer(from) == null || ctx.host.findPlayer(to) == null) {
            return CommandResult.error("One of the players is offline", code = "E_NOT_FOUND")
        }
        pendingByTarget[to.lowercase()] = TpaRequest(from = from, to = to, here = here, createdAtMillis = System.currentTimeMillis())
        ctx.host.sendPlayerMessage(to, "[SimpleTPA] ${if (here) from + " wants you to teleport to them" else from + " wants to teleport to you"}")
        CommandResult.ok("TPA request sent to $to")
    }

    private fun saveState(ctx: PluginContext) {
        ctx.storage.store(stateKey, TpaState::class.java, version = 1).save(TpaState(requestsEnabled.toMap()))
    }
}
