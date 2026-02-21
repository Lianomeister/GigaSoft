package com.clockwork.showcase.softanticheat

import com.clockwork.api.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.absoluteValue

data class SoftAcState(
    var score: Double = 0.0,
    var checks: Long = 0L,
    var lastDistance: Double = 0.0,
    var maxDistanceSeen: Double = 0.0,
    var setbacks: Int = 0,
    var lastSeenAtMillis: Long = 0L,
    var lastAlertAtMillis: Long = 0L,
    var lastSafeLocation: HostLocationRef? = null,
    val reasons: MutableMap<String, Int> = linkedMapOf()
)

data class SoftAcConfig(
    val maxDistancePerMove: Double = 6.5,
    val maxHorizontalPerMove: Double = 5.8,
    val maxVerticalPerMove: Double = 1.6,
    val maxAcceleration: Double = 3.0,
    val decayPerSecond: Double = 1.2,
    val alertScore: Double = 3.0,
    val setbackScore: Double = 6.0,
    val kickScore: Double = 11.0,
    val maxSetbacksBeforeKick: Int = 3,
    val alertCooldownMillis: Long = 1500L,
    val exemptOps: Boolean = true,
    val kickEnabled: Boolean = true
)

class SoftAnticheatPlugin : GigaPlugin {
    private val configKey = "softac-config"
    private val state = ConcurrentHashMap<String, SoftAcState>()

    @Volatile
    private var config = SoftAcConfig()

    @Volatile
    private var pluginContextRef: PluginContext? = null

    private val delegate = gigaPlugin(id = "showcase-softanticheat", name = "Showcase SoftAnticheat", version = "1.2.0") {
        events {
            subscribe(GigaPlayerMoveEvent::class.java) { event ->
                val ctx = pluginContextRef ?: return@subscribe
                val playerName = normalizePlayer(event.current.name)
                if (playerName.isBlank()) return@subscribe
                if (config.exemptOps && ctx.host.isPlayerOp(event.current.name)) {
                    state.remove(playerName)
                    return@subscribe
                }

                val now = System.currentTimeMillis()
                val local = state.computeIfAbsent(playerName) {
                    SoftAcState(lastSafeLocation = event.previous.location, lastSeenAtMillis = now)
                }

                if (!event.previous.location.world.equals(event.current.location.world, ignoreCase = true)) {
                    local.score = (local.score - 1.5).coerceAtLeast(0.0)
                    local.lastSafeLocation = event.current.location
                    local.lastSeenAtMillis = now
                    return@subscribe
                }

                decayState(local, now)

                val dist = event.distance3d()
                val horizontal = event.horizontalDistance()
                val vertical = event.verticalDistanceAbs()

                var suspicion = 0.0
                if (dist > config.maxDistancePerMove) {
                    suspicion += scoreExcess(dist, config.maxDistancePerMove, base = 1.6, multiplier = 0.55)
                    recordReason(local, "distance")
                }
                if (horizontal > config.maxHorizontalPerMove) {
                    suspicion += scoreExcess(horizontal, config.maxHorizontalPerMove, base = 1.4, multiplier = 0.5)
                    recordReason(local, "horizontal")
                }
                if (vertical > config.maxVerticalPerMove) {
                    suspicion += scoreExcess(vertical, config.maxVerticalPerMove, base = 1.1, multiplier = 0.6)
                    recordReason(local, "vertical")
                }

                val acceleration = (dist - local.lastDistance).absoluteValue
                if (acceleration > config.maxAcceleration) {
                    suspicion += scoreExcess(acceleration, config.maxAcceleration, base = 1.0, multiplier = 0.35)
                    recordReason(local, "acceleration")
                }

                local.score += suspicion
                local.lastDistance = dist
                local.maxDistanceSeen = maxOf(local.maxDistanceSeen, dist)
                local.checks += 1

                if (local.score >= config.alertScore && now - local.lastAlertAtMillis >= config.alertCooldownMillis) {
                    ctx.host.sendPlayerMessage(
                        event.current.name,
                        "[SoftAC] suspicious movement score=${"%.2f".format(local.score)}"
                    )
                    local.lastAlertAtMillis = now
                }

                if (local.score >= config.kickScore || local.setbacks >= config.maxSetbacksBeforeKick) {
                    if (config.kickEnabled && ctx.host.kickPlayer(event.current.name, "SoftAnticheat: movement exploit detected")) {
                        state.remove(playerName)
                        return@subscribe
                    }
                    local.score = (config.setbackScore + 1.0).coerceAtLeast(0.0)
                }

                if (local.score >= config.setbackScore) {
                    val safe = local.lastSafeLocation
                    if (safe != null) {
                        ctx.host.movePlayer(event.current.name, safe)
                        local.setbacks += 1
                        local.score = (local.score - 2.2).coerceAtLeast(0.0)
                        recordReason(local, "setback")
                    }
                } else {
                    local.lastSafeLocation = event.current.location
                }

                local.lastSeenAtMillis = now
            }
        }

        commands {
            spec(
                command = "softac-status",
                description = "Show anticheat status (optional player)",
                argsSchema = listOf(CommandArgSpec("player", CommandArgType.STRING, required = false)),
                usage = "softac-status [player]"
            ) { inv ->
                val player = inv.parsedArgs.string("player")?.let(::normalizePlayer)
                if (player.isNullOrBlank()) {
                    return@spec CommandResult.ok("SoftAC tracking ${state.size} player(s), cfg=$config")
                }
                val row = state[player] ?: return@spec CommandResult.ok("No SoftAC state for $player")
                val reasons = if (row.reasons.isEmpty()) "<none>" else row.reasons.entries
                    .sortedByDescending { it.value }
                    .joinToString(",") { "${it.key}:${it.value}" }
                CommandResult.ok(
                    "$player score=${"%.2f".format(row.score)} last=${"%.2f".format(row.lastDistance)} " +
                        "max=${"%.2f".format(row.maxDistanceSeen)} checks=${row.checks} setbacks=${row.setbacks} reasons=$reasons"
                )
            }

            spec(
                command = "softac-reset",
                description = "Reset anticheat state for player or all",
                argsSchema = listOf(CommandArgSpec("player", CommandArgType.STRING, required = false)),
                usage = "softac-reset [player]"
            ) { inv ->
                val player = inv.parsedArgs.string("player")?.let(::normalizePlayer)
                if (player.isNullOrBlank()) {
                    val count = state.size
                    state.clear()
                    return@spec CommandResult.ok("SoftAC state cleared ($count entries)")
                }
                state.remove(player)
                CommandResult.ok("SoftAC state cleared for $player")
            }

            spec(
                command = "softac-threshold",
                description = "Set max 3D movement distance per move",
                argsSchema = listOf(CommandArgSpec("distance", CommandArgType.DOUBLE)),
                usage = "softac-threshold <distance>"
            ) { inv ->
                val distance = inv.parsedArgs.double("distance")
                    ?.coerceIn(1.0, 20.0)
                    ?: return@spec CommandResult.error("distance must be a number", code = "E_ARGS")
                config = config.copy(maxDistancePerMove = distance)
                saveConfig(inv.pluginContext)
                CommandResult.ok("SoftAC maxDistancePerMove set to ${"%.2f".format(distance)}")
            }

            spec(
                command = "softac-setback",
                description = "Set score threshold for automatic setback",
                argsSchema = listOf(CommandArgSpec("score", CommandArgType.DOUBLE)),
                usage = "softac-setback <score>"
            ) { inv ->
                val score = inv.parsedArgs.requiredDouble("score").coerceIn(1.0, 100.0)
                config = config.copy(setbackScore = score)
                saveConfig(inv.pluginContext)
                CommandResult.ok("SoftAC setbackScore set to ${"%.2f".format(score)}")
            }

            spec(
                command = "softac-kick",
                description = "Enable/disable kick action",
                argsSchema = listOf(CommandArgSpec("enabled", CommandArgType.BOOLEAN)),
                usage = "softac-kick <true|false>"
            ) { inv ->
                val enabled = inv.parsedArgs.boolean("enabled")
                    ?: return@spec CommandResult.error("enabled must be true/false", code = "E_ARGS")
                config = config.copy(kickEnabled = enabled)
                saveConfig(inv.pluginContext)
                CommandResult.ok("SoftAC kick action ${if (enabled) "enabled" else "disabled"}")
            }

            spec(
                command = "softac-top",
                description = "Show top suspicious players by score",
                argsSchema = listOf(CommandArgSpec("limit", CommandArgType.INT, required = false)),
                usage = "softac-top [limit]"
            ) { inv ->
                val limit = (inv.parsedArgs.int("limit") ?: 5).coerceIn(1, 20)
                val top = state.entries
                    .sortedByDescending { it.value.score }
                    .take(limit)
                if (top.isEmpty()) return@spec CommandResult.ok("SoftAC has no tracked players")
                CommandResult.ok(
                    top.joinToString(" | ") { (player, row) ->
                        "$player:${"%.2f".format(row.score)}"
                    }
                )
            }
        }
    }

    override fun onEnable(ctx: PluginContext) {
        pluginContextRef = ctx
        config = loadConfig(ctx)
        delegate.onEnable(ctx)
    }

    override fun onDisable(ctx: PluginContext) {
        saveConfig(ctx)
        pluginContextRef = null
        delegate.onDisable(ctx)
    }

    private fun normalizePlayer(raw: String): String = raw.trim().lowercase()

    private fun decayState(local: SoftAcState, nowMillis: Long) {
        val last = local.lastSeenAtMillis
        if (last <= 0L) return
        val elapsedSec = ((nowMillis - last).coerceAtLeast(0L).toDouble() / 1000.0)
        if (elapsedSec <= 0.0) return
        local.score = (local.score - (config.decayPerSecond * elapsedSec)).coerceAtLeast(0.0)
    }

    private fun scoreExcess(value: Double, threshold: Double, base: Double, multiplier: Double): Double {
        val excess = (value - threshold).coerceAtLeast(0.0)
        return base + (excess * multiplier)
    }

    private fun recordReason(local: SoftAcState, reason: String) {
        local.reasons[reason] = (local.reasons[reason] ?: 0) + 1
    }

    private fun loadConfig(ctx: PluginContext): SoftAcConfig {
        return ctx.loadOrDefault(configKey, version = 2) { SoftAcConfig() }
    }

    private fun saveConfig(ctx: PluginContext? = pluginContextRef) {
        val local = ctx ?: return
        local.saveState(configKey, config, version = 2)
    }
}
