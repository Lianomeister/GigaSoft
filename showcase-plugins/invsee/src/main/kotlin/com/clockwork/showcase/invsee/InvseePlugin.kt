package com.clockwork.showcase.invsee

import com.clockwork.api.*

class InvseePlugin : GigaPlugin {
    private val delegate = gigaPlugin(id = "showcase-invsee", name = "Showcase Invsee", version = "1.0.0") {
        commands {
            spec(
                command = "invsee",
                argsSchema = listOf(CommandArgSpec("player", CommandArgType.STRING)),
                usage = "invsee <player>"
            ) { inv ->
                val ctx = inv.pluginContext
                val player = inv.parsedArgs.requiredString("player").trim()
                val snapshot = ctx.host.playerInventory(player)
                    ?: return@spec CommandResult.error("Inventory not available", code = "E_NOT_FOUND")
                val peek = (0 until minOf(snapshot.size, 9)).mapNotNull { slot ->
                    val item = ctx.host.inventoryItem(player, slot)
                    if (item.isNullOrBlank()) null else "$slot:$item"
                }
                val preview = if (peek.isEmpty()) "empty" else peek.joinToString(", ")
                CommandResult.ok("$player inv: nonEmpty=${snapshot.nonEmptySlots}/${snapshot.size} preview=$preview")
            }

            spec(
                command = "invsee-set",
                argsSchema = listOf(
                    CommandArgSpec("player", CommandArgType.STRING),
                    CommandArgSpec("slot", CommandArgType.INT),
                    CommandArgSpec("item", CommandArgType.STRING)
                ),
                usage = "invsee-set <player> <slot> <item>"
            ) { inv ->
                val ctx = inv.pluginContext
                val player = inv.parsedArgs.requiredString("player").trim()
                val slot = inv.parsedArgs.requiredInt("slot").coerceIn(0, 53)
                val item = inv.parsedArgs.requiredString("item").trim()
                val ok = ctx.host.setPlayerInventoryItem(player, slot, item)
                if (!ok) return@spec CommandResult.error("Could not set slot", code = "E_HOST")
                CommandResult.ok("Set $player slot $slot to $item")
            }

            spec(
                command = "invsee-slot",
                argsSchema = listOf(
                    CommandArgSpec("player", CommandArgType.STRING),
                    CommandArgSpec("slot", CommandArgType.INT)
                ),
                usage = "invsee-slot <player> <slot>"
            ) { inv ->
                val ctx = inv.pluginContext
                val player = inv.parsedArgs.requiredString("player").trim()
                val slot = inv.parsedArgs.requiredInt("slot").coerceIn(0, 53)
                val item = ctx.host.inventoryItem(player, slot)
                CommandResult.ok("$player slot $slot = ${item ?: "<empty>"}")
            }
        }
    }

    override fun onEnable(ctx: PluginContext) = delegate.onEnable(ctx)
    override fun onDisable(ctx: PluginContext) = delegate.onDisable(ctx)
}
