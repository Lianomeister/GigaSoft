package com.clockwork.showcase.voiced

import com.clockwork.api.*
import kotlin.math.roundToInt

data class VoicedState(
    val playerProfiles: Map<String, VoiceProfile> = emptyMap(),
    val bridgeEnabled: Boolean = true,
    val bridgeRevision: Long = 0L
)

data class VoiceProfile(
    val playerId: String = "",
    val enabled: Boolean = true,
    val mode: String = "normal",
    val intensity: Int = 100,
    val pitch: Int = 100,
    val reverb: Int = 0,
    val bassBoost: Int = 0,
    val clarity: Int = 100,
    val expiresAtEpochMillis: Long? = null
)

class VoicedPlugin : GigaPlugin {
    private val stateKey = "voiced-state"
    private val cleanupTaskId = "voiced-cleanup-expired"
    private val bridgeHeartbeatTaskId = "voiced-bridge-heartbeat"
    private val playerProfiles = linkedMapOf<String, VoiceProfile>()
    private var bridgeEnabled = true
    private var bridgeRevision = 0L
    private val allowedModes = linkedSetOf("normal", "robot", "deep", "chipmunk", "radio", "studio", "ghost")
    private val presets = linkedMapOf(
        "normal" to VoiceProfile(mode = "normal"),
        "radio" to VoiceProfile(mode = "radio", intensity = 120, pitch = 95, reverb = 15, bassBoost = 10, clarity = 80),
        "tank" to VoiceProfile(mode = "deep", intensity = 140, pitch = 70, reverb = 5, bassBoost = 60, clarity = 85),
        "arcade" to VoiceProfile(mode = "chipmunk", intensity = 125, pitch = 155, reverb = 0, bassBoost = 0, clarity = 110),
        "drone" to VoiceProfile(mode = "robot", intensity = 130, pitch = 90, reverb = 20, bassBoost = 20, clarity = 70),
        "cinema" to VoiceProfile(mode = "studio", intensity = 110, pitch = 100, reverb = 25, bassBoost = 30, clarity = 120)
    )
    private val compatibilityChannels = listOf(
        "simplevoicemod:voice_profile",
        "simple_voice_mod:voice_profile",
        "simple-voicemod:voice_profile"
    )
    private val compatibilityRequestChannels = listOf(
        "simplevoicemod:voice_profile_request",
        "simple_voice_mod:voice_profile_request",
        "simple-voicemod:voice_profile_request"
    )
    private val compatibilitySchemaVersion = 1

    private val delegate = gigaPlugin(id = "showcase-voiced", name = "Showcase Voiced", version = "1.3.1") {
        commands {
            spec(command = "voice", usage = "voice") { _ ->
                CommandResult.ok(
                    "Voiced commands: voice-mode, voice-preset, voice-intensity, voice-tune, voice-timer, voice-toggle, voice-sync, voice-bridge <on|off>, voice-bridge-sync-all, voice-bridge-status, voice-status, voice-export, voice-clear, voice-list"
                )
            }

            spec(
                command = "voice-mode",
                argsSchema = listOf(
                    CommandArgSpec("mode", CommandArgType.STRING),
                    CommandArgSpec("player", CommandArgType.STRING, required = false)
                ),
                usage = "voice-mode <normal|robot|deep|chipmunk|radio|studio|ghost> [player]"
            ) { inv ->
                val mode = inv.parsedArgs.requiredString("mode").trim().lowercase()
                if (mode !in allowedModes) {
                    return@spec CommandResult.error("Unknown mode '$mode'", code = "E_ARGS")
                }
                val player = resolveTargetPlayer(inv) ?: return@spec targetError(inv)
                setProfile(
                    inv = inv,
                    player = player,
                    updater = { current -> current.copy(mode = mode) },
                    feedback = "Mode set to $mode"
                )
            }

            spec(
                command = "voice-status",
                argsSchema = listOf(CommandArgSpec("player", CommandArgType.STRING, required = false)),
                usage = "voice-status [player]"
            ) { inv ->
                purgeExpiredProfiles(inv.pluginContext)
                val player = resolveTargetPlayer(inv) ?: return@spec targetError(inv)
                val profile = playerProfiles[player.playerKey()]?.let(::sanitizeProfile) ?: VoiceProfile(playerId = player)
                CommandResult.ok("$player => ${profileSummary(profile, includeTimer = true)}")
            }

            spec(
                command = "voice-clear",
                argsSchema = listOf(CommandArgSpec("player", CommandArgType.STRING, required = false)),
                usage = "voice-clear [player]"
            ) { inv ->
                val player = resolveTargetPlayer(inv) ?: return@spec targetError(inv)
                playerProfiles.remove(player.playerKey())
                persist(inv.pluginContext)
                inv.pluginContext.host.sendPlayerMessage(player, "[Voiced] Voice profile cleared")
                broadcastVoiceProfile(
                    ctx = inv.pluginContext,
                    player = player,
                    profile = VoiceProfile(playerId = player),
                    reason = "cleared",
                    op = "reset"
                )
                CommandResult.ok("Voice profile cleared for $player")
            }

            spec(
                command = "voice-preset",
                argsSchema = listOf(
                    CommandArgSpec("preset", CommandArgType.STRING),
                    CommandArgSpec("player", CommandArgType.STRING, required = false)
                ),
                usage = "voice-preset <normal|radio|tank|arcade|drone|cinema> [player]"
            ) { inv ->
                val presetId = inv.parsedArgs.requiredString("preset").trim().lowercase()
                val preset = presets[presetId]
                    ?: return@spec CommandResult.error("Unknown preset '$presetId'", code = "E_ARGS")
                val player = resolveTargetPlayer(inv) ?: return@spec targetError(inv)
                setProfile(
                    inv = inv,
                    player = player,
                    updater = { current ->
                        preset.copy(
                            playerId = player,
                            enabled = current.enabled,
                            expiresAtEpochMillis = current.expiresAtEpochMillis
                        )
                    },
                    feedback = "Preset $presetId applied (${profileSummary(preset)})"
                )
            }

            spec(
                command = "voice-intensity",
                argsSchema = listOf(
                    CommandArgSpec("value", CommandArgType.INT),
                    CommandArgSpec("player", CommandArgType.STRING, required = false)
                ),
                usage = "voice-intensity <0-200> [player]"
            ) { inv ->
                val value = inv.parsedArgs.requiredInt("value")
                if (value !in 0..200) {
                    return@spec CommandResult.error("Intensity must be between 0 and 200", code = "E_ARGS")
                }
                val player = resolveTargetPlayer(inv) ?: return@spec targetError(inv)
                setProfile(
                    inv = inv,
                    player = player,
                    updater = { current -> current.copy(intensity = value) },
                    feedback = "Intensity set to $value"
                )
            }

            spec(
                command = "voice-timer",
                argsSchema = listOf(
                    CommandArgSpec("duration", CommandArgType.STRING),
                    CommandArgSpec("player", CommandArgType.STRING, required = false)
                ),
                usage = "voice-timer <90s|5m|1h|off> [player]"
            ) { inv ->
                val durationRaw = inv.parsedArgs.requiredString("duration").trim().lowercase()
                val player = resolveTargetPlayer(inv) ?: return@spec targetError(inv)
                if (durationRaw == "off" || durationRaw == "none" || durationRaw == "reset") {
                    return@spec setProfile(
                        inv = inv,
                        player = player,
                        updater = { current -> current.copy(expiresAtEpochMillis = null) },
                        feedback = "Timer disabled"
                    )
                }
                val durationMillis = inv.parsedArgs.durationMillis("duration")
                    ?: return@spec CommandResult.error("Invalid duration format (use 90s/5m/1h/off)", code = "E_ARGS")
                if (durationMillis <= 0L) {
                    return@spec CommandResult.error("Duration must be positive", code = "E_ARGS")
                }
                val expiresAt = System.currentTimeMillis() + durationMillis
                setProfile(
                    inv = inv,
                    player = player,
                    updater = { current -> current.copy(expiresAtEpochMillis = expiresAt) },
                    feedback = "Timer set: ${formatDuration(durationMillis)}"
                )
            }

            spec(
                command = "voice-tune",
                argsSchema = listOf(
                    CommandArgSpec("settings", CommandArgType.STRING),
                    CommandArgSpec("player", CommandArgType.STRING, required = false)
                ),
                usage = "voice-tune <pitch=110,reverb=15,bass=25,clarity=120> [player]"
            ) { inv ->
                val settingsRaw = inv.parsedArgs.requiredString("settings")
                val parsed = parseSettings(settingsRaw)
                if (parsed.valid.isEmpty()) {
                    return@spec CommandResult.error(
                        "No valid settings parsed. Valid keys: intensity,pitch,reverb,bass,clarity",
                        code = "E_ARGS"
                    )
                }
                val player = resolveTargetPlayer(inv) ?: return@spec targetError(inv)
                setProfile(
                    inv = inv,
                    player = player,
                    updater = { current -> applySettings(current, parsed.valid) },
                    feedback = buildString {
                        append("Tuned: ")
                        append(parsed.valid.entries.joinToString(", ") { "${it.key}=${it.value}" })
                        if (parsed.unknownKeys.isNotEmpty()) {
                            append(" | ignored keys: ")
                            append(parsed.unknownKeys.joinToString(", "))
                        }
                        if (parsed.invalidValues.isNotEmpty()) {
                            append(" | invalid values: ")
                            append(parsed.invalidValues.joinToString(", "))
                        }
                    }
                )
            }

            spec(
                command = "voice-toggle",
                argsSchema = listOf(CommandArgSpec("player", CommandArgType.STRING, required = false)),
                usage = "voice-toggle [player]"
            ) { inv ->
                val player = resolveTargetPlayer(inv) ?: return@spec targetError(inv)
                val nextEnabled = !(playerProfiles[player.playerKey()]?.enabled ?: true)
                setProfile(
                    inv = inv,
                    player = player,
                    updater = { current -> current.copy(enabled = !current.enabled) },
                    feedback = "Voice ${if (nextEnabled) "enabled" else "disabled"}"
                )
            }

            spec(
                command = "voice-sync",
                argsSchema = listOf(CommandArgSpec("player", CommandArgType.STRING, required = false)),
                usage = "voice-sync [player]"
            ) { inv ->
                purgeExpiredProfiles(inv.pluginContext)
                val player = resolveTargetPlayer(inv) ?: return@spec targetError(inv)
                val profile = sanitizeProfile(playerProfiles[player.playerKey()] ?: VoiceProfile(playerId = player))
                val delivered = broadcastVoiceProfile(
                    ctx = inv.pluginContext,
                    player = player,
                    profile = profile,
                    reason = "manual-sync",
                    op = "upsert"
                )
                CommandResult.ok("Synced voice profile for $player to compatibility channels (delivered=$delivered)")
            }

            spec(
                command = "voice-bridge",
                argsSchema = listOf(CommandArgSpec("enabled", CommandArgType.STRING)),
                usage = "voice-bridge <on|off>"
            ) { inv ->
                val raw = inv.parsedArgs.requiredString("enabled").trim().lowercase()
                val nextEnabled = when (raw) {
                    "on", "true", "1", "enable", "enabled" -> true
                    "off", "false", "0", "disable", "disabled" -> false
                    else -> return@spec CommandResult.error("Use 'on' or 'off'", code = "E_ARGS")
                }
                bridgeEnabled = nextEnabled
                persist(inv.pluginContext)
                if (bridgeEnabled) {
                    registerCompatibilityChannels(inv.pluginContext)
                }
                CommandResult.ok("Voice bridge ${if (bridgeEnabled) "enabled" else "disabled"}")
            }

            spec(
                command = "voice-bridge-sync-all",
                usage = "voice-bridge-sync-all"
            ) { inv ->
                purgeExpiredProfiles(inv.pluginContext)
                if (playerProfiles.isEmpty()) return@spec CommandResult.ok("No profiles to sync")
                var deliveredTotal = 0
                playerProfiles.forEach { (playerKey, profile) ->
                    val targetPlayer = profile.playerId.takeIf { it.isNotBlank() } ?: playerKey
                    deliveredTotal += broadcastVoiceProfile(
                        ctx = inv.pluginContext,
                        player = targetPlayer,
                        profile = sanitizeProfile(profile),
                        reason = "manual-sync-all",
                        op = "upsert"
                    )
                }
                CommandResult.ok("Synced ${playerProfiles.size} profiles (delivered=$deliveredTotal)")
            }

            spec(
                command = "voice-bridge-status",
                usage = "voice-bridge-status"
            ) { inv ->
                val lines = (compatibilityChannels + compatibilityRequestChannels).map { channel ->
                    val stats = inv.pluginContext.network.channelStats(channel)
                    if (stats == null) {
                        "$channel: unavailable"
                    } else {
                        "$channel: accepted=${stats.accepted} rejected=${stats.rejected} inFlight=${stats.inFlight}"
                    }
                }
                CommandResult.ok("bridgeEnabled=$bridgeEnabled revision=$bridgeRevision | ${lines.joinToString(" | ")}")
            }

            spec(
                command = "voice-export",
                argsSchema = listOf(CommandArgSpec("player", CommandArgType.STRING, required = false)),
                usage = "voice-export [player]"
            ) { inv ->
                purgeExpiredProfiles(inv.pluginContext)
                val player = resolveTargetPlayer(inv) ?: return@spec targetError(inv)
                val profile = sanitizeProfile(playerProfiles[player.playerKey()] ?: VoiceProfile(playerId = player))
                CommandResult.ok(
                    "player=$player enabled=${profile.enabled} mode=${profile.mode} intensity=${profile.intensity} " +
                        "pitch=${profile.pitch} reverb=${profile.reverb} bass=${profile.bassBoost} clarity=${profile.clarity} " +
                        "timerMs=${profile.expiresAtEpochMillis ?: 0L}"
                )
            }

            spec(command = "voice-list", usage = "voice-list") { _ ->
                CommandResult.ok(
                    "Modes: ${allowedModes.joinToString(", ")} | Presets: ${presets.keys.joinToString(", ")} | Tune keys: intensity,pitch,reverb,bass,clarity | Compat channels: ${compatibilityChannels.joinToString(", ")}"
                )
            }
        }
    }

    override fun onEnable(ctx: PluginContext) {
        val state = ctx.loadOrDefault(stateKey) { VoicedState() }
        playerProfiles.clear()
        playerProfiles.putAll(state.playerProfiles)
        bridgeEnabled = state.bridgeEnabled
        bridgeRevision = state.bridgeRevision
        registerCompatibilityChannels(ctx)
        ctx.scheduler.repeating(cleanupTaskId, periodTicks = 40) {
            purgeExpiredProfiles(ctx)
        }
        ctx.scheduler.repeating(bridgeHeartbeatTaskId, periodTicks = 20 * 30) {
            if (!bridgeEnabled) return@repeating
            val snapshot = playerProfiles.values.map { sanitizeProfile(it) }
            snapshot.forEach { profile ->
                val targetPlayer = profile.playerId.takeIf { it.isNotBlank() } ?: return@forEach
                broadcastVoiceProfile(
                    ctx = ctx,
                    player = targetPlayer,
                    profile = profile,
                    reason = "heartbeat",
                    op = "upsert"
                )
            }
        }
        delegate.onEnable(ctx)
    }

    override fun onDisable(ctx: PluginContext) {
        ctx.scheduler.cancel(cleanupTaskId)
        ctx.scheduler.cancel(bridgeHeartbeatTaskId)
        persist(ctx)
        delegate.onDisable(ctx)
    }

    private fun persist(ctx: PluginContext) {
        ctx.saveState(
            stateKey,
            VoicedState(
                playerProfiles = playerProfiles.toMap(),
                bridgeEnabled = bridgeEnabled,
                bridgeRevision = bridgeRevision
            )
        )
    }

    private fun registerCompatibilityChannels(ctx: PluginContext) {
        if (!bridgeEnabled) return
        (compatibilityChannels + compatibilityRequestChannels).forEach { channel ->
            val registered = ctx.registerNetworkChannel(
                PluginChannelSpec(
                    id = channel,
                    schemaVersion = compatibilitySchemaVersion,
                    maxInFlight = 128,
                    maxMessagesPerMinute = 2_400,
                    maxPayloadEntries = 32,
                    maxPayloadTotalChars = 4096
                )
            )
            if (registered) {
                ctx.logger.info("Voiced compatibility channel registered: $channel")
            }
        }
        compatibilityRequestChannels.forEach { requestChannel ->
            ctx.network.subscribe(requestChannel) { message ->
                val requestedPlayer = message.payload["player"].orEmpty().trim()
                if (requestedPlayer.isEmpty()) return@subscribe
                val profile = sanitizeProfile(
                    playerProfiles[requestedPlayer.playerKey()] ?: VoiceProfile(playerId = requestedPlayer)
                )
                broadcastVoiceProfile(
                    ctx = ctx,
                    player = requestedPlayer,
                    profile = profile,
                    reason = "request-sync",
                    op = "upsert"
                )
            }
        }
    }

    private fun resolveTargetPlayer(inv: CommandInvocationContext): String? {
        val explicit = inv.parsedArgs.string("player")?.trim()?.takeIf { it.isNotEmpty() }
        if (explicit == null) {
            if (inv.sender.type != CommandSenderType.PLAYER) return null
            return inv.sender.id.trim()
        }
        val isSelf = explicit.playerKey() == inv.sender.id.playerKey()
        if (inv.sender.type == CommandSenderType.PLAYER && !isSelf) return null
        return explicit
    }

    private fun targetError(inv: CommandInvocationContext): CommandResult {
        if (inv.sender.type == CommandSenderType.PLAYER) {
            return CommandResult.error("You can only change your own voice profile", code = "E_PERMISSION")
        }
        return CommandResult.error("Console/system must specify a target player", code = "E_ARGS")
    }

    private fun setProfile(
        inv: CommandInvocationContext,
        player: String,
        updater: (VoiceProfile) -> VoiceProfile,
        feedback: String
    ): CommandResult {
        purgeExpiredProfiles(inv.pluginContext)
        val key = player.playerKey()
        val current = playerProfiles[key] ?: VoiceProfile(playerId = player)
        val next = sanitizeProfile(updater(current).copy(playerId = player))
        playerProfiles[key] = next
        persist(inv.pluginContext)
        inv.pluginContext.host.sendPlayerMessage(player, "[Voiced] $feedback")
        val status = if (next.enabled) "${next.mode} (${next.intensity}%)" else "disabled"
        inv.pluginContext.actionBar(player, "Voice: $status", durationTicks = 60)
        broadcastVoiceProfile(
            ctx = inv.pluginContext,
            player = player,
            profile = next,
            reason = "profile-updated",
            op = "upsert"
        )
        return CommandResult.ok("Voice profile for $player => ${profileSummary(next, includeTimer = true)}")
    }

    private fun sanitizeProfile(profile: VoiceProfile): VoiceProfile {
        val normalizedMode = profile.mode.trim().lowercase().ifEmpty { "normal" }
        return profile.copy(
            playerId = profile.playerId.trim(),
            enabled = profile.enabled,
            mode = if (normalizedMode in allowedModes) normalizedMode else "normal",
            intensity = profile.intensity.coerceIn(0, 200),
            pitch = profile.pitch.coerceIn(50, 200),
            reverb = profile.reverb.coerceIn(0, 100),
            bassBoost = profile.bassBoost.coerceIn(0, 100),
            clarity = profile.clarity.coerceIn(0, 150)
        )
    }

    private data class ParsedTuneSettings(
        val valid: Map<String, Int>,
        val unknownKeys: List<String>,
        val invalidValues: List<String>
    )

    private fun parseSettings(raw: String): ParsedTuneSettings {
        if (raw.isBlank()) return ParsedTuneSettings(emptyMap(), emptyList(), emptyList())
        val out = linkedMapOf<String, Int>()
        val unknown = linkedSetOf<String>()
        val invalidValues = linkedSetOf<String>()
        raw.split(',', ';').forEach { token ->
            val segment = token.trim()
            if (segment.isEmpty()) return@forEach
            val idx = segment.indexOf('=')
            if (idx <= 0) {
                invalidValues += segment
                return@forEach
            }
            val key = segment.substring(0, idx).trim().lowercase()
            val normalizedKey = when (key) {
                "bassboost" -> "bass"
                "treble" -> "clarity"
                else -> key
            }
            val value = segment.substring(idx + 1).trim().removeSuffix("%").toIntOrNull()
            if (value == null) {
                invalidValues += segment
                return@forEach
            }
            when (normalizedKey) {
                "intensity", "pitch", "reverb", "bass", "clarity" -> out[normalizedKey] = value
                else -> unknown += normalizedKey
            }
        }
        return ParsedTuneSettings(out, unknown.toList(), invalidValues.toList())
    }

    private fun applySettings(current: VoiceProfile, settings: Map<String, Int>): VoiceProfile {
        var next = current
        settings.forEach { (key, value) ->
            next = when (key) {
                "intensity" -> next.copy(intensity = value)
                "pitch" -> next.copy(pitch = value)
                "reverb" -> next.copy(reverb = value)
                "bass", "bassboost" -> next.copy(bassBoost = value)
                "clarity" -> next.copy(clarity = value)
                else -> next
            }
        }
        return sanitizeProfile(next)
    }

    private fun purgeExpiredProfiles(ctx: PluginContext) {
        val now = System.currentTimeMillis()
        val expiredPlayers = playerProfiles
            .filterValues { profile -> (profile.expiresAtEpochMillis ?: Long.MAX_VALUE) <= now }
            .keys
            .toList()
        if (expiredPlayers.isEmpty()) return
        expiredPlayers.forEach { key ->
            val expired = playerProfiles.remove(key)
            val targetPlayer = expired?.playerId?.takeIf { it.isNotBlank() } ?: key
            ctx.host.sendPlayerMessage(targetPlayer, "[Voiced] Timed voice profile expired. Back to normal.")
            broadcastVoiceProfile(
                ctx = ctx,
                player = targetPlayer,
                profile = VoiceProfile(playerId = targetPlayer),
                reason = "timer-expired",
                op = "reset"
            )
        }
        persist(ctx)
    }

    private fun broadcastVoiceProfile(
        ctx: PluginContext,
        player: String,
        profile: VoiceProfile,
        reason: String,
        op: String
    ): Int {
        if (!bridgeEnabled) return 0
        bridgeRevision += 1L
        val payload = mapOf(
            "format" to "simple-voicechat-addon-v1",
            "op" to op,
            "revision" to bridgeRevision.toString(),
            "timestamp" to System.currentTimeMillis().toString(),
            "player" to player.playerKey(),
            "enabled" to profile.enabled.toString(),
            "mode" to profile.mode,
            "intensity" to profile.intensity.toString(),
            "pitch" to profile.pitch.toString(),
            "reverb" to profile.reverb.toString(),
            "bass" to profile.bassBoost.toString(),
            "clarity" to profile.clarity.toString(),
            "expiresAtEpochMillis" to (profile.expiresAtEpochMillis ?: 0L).toString(),
            "reason" to reason
        )
        var delivered = 0
        compatibilityChannels.forEach { channel ->
            val result = ctx.sendPluginMessage(channel, payload)
            delivered += result.deliveredSubscribers
            if (result.status != PluginMessageStatus.ACCEPTED) {
                ctx.logger.info("Voiced compatibility bridge send failed channel=$channel status=${result.status} reason=${result.reason.orEmpty()}")
            }
        }
        return delivered
    }

    private fun profileSummary(profile: VoiceProfile, includeTimer: Boolean = false): String {
        val base = buildString {
            append(if (profile.enabled) "enabled" else "disabled")
            append(" ")
            append(profile.mode)
            append(" intensity=")
            append(profile.intensity)
            append("% pitch=")
            append(profile.pitch)
            append("% reverb=")
            append(profile.reverb)
            append("% bass=")
            append(profile.bassBoost)
            append("% clarity=")
            append(profile.clarity)
            append("%")
        }
        if (!includeTimer) return base
        val expiresAt = profile.expiresAtEpochMillis ?: return "$base timer=off"
        val left = (expiresAt - System.currentTimeMillis()).coerceAtLeast(0L)
        return "$base timer=${formatDuration(left)}"
    }

    private fun formatDuration(durationMillis: Long): String {
        val totalSeconds = (durationMillis / 1_000.0).roundToInt().coerceAtLeast(0)
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return when {
            hours > 0 -> "${hours}h ${minutes}m ${seconds}s"
            minutes > 0 -> "${minutes}m ${seconds}s"
            else -> "${seconds}s"
        }
    }
}
