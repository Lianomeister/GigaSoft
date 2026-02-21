package com.clockwork.showcase.graved

import com.clockwork.api.*

data class GraveEntry(
    val id: String,
    val owner: String,
    val world: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val createdAtMillis: Long,
    val lootSummary: String,
    val claimed: Boolean = false
)

data class GraveState(
    val graves: List<GraveEntry> = emptyList(),
    val allowSteal: Boolean = false
)

class GravedPlugin : GigaPlugin {
    private val stateKey = "graved-state"
    private val graves = linkedMapOf<String, GraveEntry>()

    @Volatile
    private var allowSteal: Boolean = false

    private val delegate = gigaPlugin(id = "showcase-graved", name = "Showcase Graved", version = "1.0.0") {
        commands {
            spec(
                command = "grave-create",
                argsSchema = listOf(CommandArgSpec("player", CommandArgType.STRING, required = false)),
                usage = "grave-create [player]"
            ) { inv ->
                val ctx = inv.pluginContext
                val player = (inv.parsedArgs.string("player") ?: inv.sender.id).trim()
                val snapshot = ctx.host.findPlayer(player) ?: return@spec CommandResult.error("Player not found", code = "E_NOT_FOUND")
                val inventory = ctx.host.playerInventory(player)
                val summary = if (inventory == null) "unknown" else "nonEmpty=${inventory.nonEmptySlots}/${inventory.size}"
                val id = "grave-${System.currentTimeMillis()}-${player.lowercase()}"
                graves[id] = GraveEntry(
                    id = id,
                    owner = player.lowercase(),
                    world = snapshot.location.world,
                    x = snapshot.location.x,
                    y = snapshot.location.y,
                    z = snapshot.location.z,
                    createdAtMillis = System.currentTimeMillis(),
                    lootSummary = summary
                )
                saveState(ctx)
                CommandResult.ok("Created grave $id for $player ($summary)")
            }

            spec(command = "grave-list", usage = "grave-list") { _ ->
                if (graves.isEmpty()) return@spec CommandResult.ok("No graves")
                val text = graves.values.takeLast(25).joinToString(" | ") {
                    "${it.id}:${it.owner}@${it.world} claimed=${it.claimed}"
                }
                CommandResult.ok("Graves (${graves.size}): $text")
            }

            spec(
                command = "grave-claim",
                argsSchema = listOf(CommandArgSpec("id", CommandArgType.STRING)),
                usage = "grave-claim <id>"
            ) { inv ->
                val id = inv.parsedArgs.requiredString("id")
                val key = inv.sender.id.lowercase()
                val grave = graves[id] ?: return@spec CommandResult.error("Unknown grave", code = "E_NOT_FOUND")
                if (grave.claimed) return@spec CommandResult.error("Grave already claimed", code = "E_STATE")
                if (grave.owner != key && !allowSteal) {
                    return@spec CommandResult.error("Stealing disabled for this grave", code = "E_PERMISSION")
                }
                graves[id] = grave.copy(claimed = true)
                saveState(inv.pluginContext)
                CommandResult.ok("Grave $id claimed by $key")
            }

            spec(
                command = "grave-policy",
                argsSchema = listOf(CommandArgSpec("allowSteal", CommandArgType.BOOLEAN)),
                usage = "grave-policy <true|false>"
            ) { inv ->
                allowSteal = inv.parsedArgs.boolean("allowSteal")
                    ?: return@spec CommandResult.error("allowSteal must be true/false", code = "E_ARGS")
                saveState(inv.pluginContext)
                CommandResult.ok("Grave stealing ${if (allowSteal) "enabled" else "disabled"}")
            }
        }
    }

    override fun onEnable(ctx: PluginContext) {
        val state = ctx.storage.store(stateKey, GraveState::class.java, version = 1).load() ?: GraveState()
        graves.clear()
        state.graves.forEach { graves[it.id] = it }
        allowSteal = state.allowSteal
        delegate.onEnable(ctx)
    }

    override fun onDisable(ctx: PluginContext) {
        saveState(ctx)
        delegate.onDisable(ctx)
    }

    private fun saveState(ctx: PluginContext) {
        ctx.storage.store(stateKey, GraveState::class.java, version = 1).save(
            GraveState(graves = graves.values.toList(), allowSteal = allowSteal)
        )
    }
}
