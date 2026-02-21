package com.clockwork.showcase.carry

import com.clockwork.api.*

data class CarryPayload(
    val type: String,
    val target: String
)

data class CarryState(
    val payloads: Map<String, CarryPayload> = emptyMap()
)

class CarryPlugin : GigaPlugin {
    private val stateKey = "carry-state"
    private val payloadByCarrier = linkedMapOf<String, CarryPayload>()

    private val delegate = gigaPlugin(id = "showcase-carry", name = "Showcase Carry", version = "1.0.0") {
        commands {
            spec(
                command = "carry",
                argsSchema = listOf(
                    CommandArgSpec("type", CommandArgType.STRING),
                    CommandArgSpec("target", CommandArgType.STRING)
                ),
                usage = "carry <player|block> <target>"
            ) { inv ->
                val type = inv.parsedArgs.requiredString("type").trim().lowercase()
                val target = inv.parsedArgs.requiredString("target").trim()
                val carrier = inv.sender.id.lowercase()
                when (type) {
                    "player" -> {
                        if (inv.pluginContext.host.findPlayer(target) == null) {
                            return@spec CommandResult.error("Player not found", code = "E_NOT_FOUND")
                        }
                        payloadByCarrier[carrier] = CarryPayload("player", target)
                    }
                    "block" -> {
                        payloadByCarrier[carrier] = CarryPayload("block", target)
                    }
                    else -> return@spec CommandResult.error("Type must be player or block", code = "E_ARGS")
                }
                saveState(inv.pluginContext)
                CommandResult.ok("Now carrying ${payloadByCarrier[carrier]?.type}:${payloadByCarrier[carrier]?.target}")
            }

            spec(command = "drop", usage = "drop") { inv ->
                val carrier = inv.sender.id.lowercase()
                val payload = payloadByCarrier.remove(carrier)
                    ?: return@spec CommandResult.error("You are not carrying anything", code = "E_NOT_FOUND")
                val ctx = inv.pluginContext
                val me = ctx.host.findPlayer(inv.sender.id)
                if (me != null && payload.type == "block") {
                    ctx.host.setBlock(me.location.world, me.location.x.toInt(), me.location.y.toInt(), me.location.z.toInt(), payload.target)
                }
                saveState(ctx)
                CommandResult.ok("Dropped ${payload.type}:${payload.target}")
            }

            spec(
                command = "throw",
                argsSchema = listOf(CommandArgSpec("power", CommandArgType.INT, required = false)),
                usage = "throw [power]"
            ) { inv ->
                val carrier = inv.sender.id.lowercase()
                val payload = payloadByCarrier.remove(carrier)
                    ?: return@spec CommandResult.error("You are not carrying anything", code = "E_NOT_FOUND")
                val power = (inv.parsedArgs.int("power") ?: 4).coerceIn(1, 12)
                val ctx = inv.pluginContext
                val me = ctx.host.findPlayer(inv.sender.id) ?: return@spec CommandResult.error("Carrier not found", code = "E_NOT_FOUND")
                val targetX = me.location.x + power
                val targetY = me.location.y + 1.0
                val targetZ = me.location.z
                if (payload.type == "player") {
                    ctx.host.movePlayer(payload.target, HostLocationRef(me.location.world, targetX, targetY, targetZ))
                } else {
                    ctx.host.setBlock(me.location.world, targetX.toInt(), targetY.toInt(), targetZ.toInt(), payload.target)
                }
                saveState(ctx)
                CommandResult.ok("Threw ${payload.type}:${payload.target} with power $power")
            }

            spec(command = "carry-status", usage = "carry-status") { inv ->
                val payload = payloadByCarrier[inv.sender.id.lowercase()]
                    ?: return@spec CommandResult.ok("You are not carrying anything")
                CommandResult.ok("Carrying ${payload.type}:${payload.target}")
            }
        }
    }

    override fun onEnable(ctx: PluginContext) {
        val state = ctx.storage.store(stateKey, CarryState::class.java, version = 1).load() ?: CarryState()
        payloadByCarrier.clear()
        payloadByCarrier.putAll(state.payloads)
        delegate.onEnable(ctx)
    }

    override fun onDisable(ctx: PluginContext) {
        saveState(ctx)
        delegate.onDisable(ctx)
    }

    private fun saveState(ctx: PluginContext) {
        ctx.storage.store(stateKey, CarryState::class.java, version = 1).save(CarryState(payloadByCarrier.toMap()))
    }
}
