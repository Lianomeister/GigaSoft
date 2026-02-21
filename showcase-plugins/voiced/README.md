# Showcase: Voiced

`Voiced` ist als **Simple Voice Chat Companion/Add-on** gedacht:
- Mit Simple Voice Chat (Client/Server) + passender Bridge liefert `Voiced` die Profilsteuerung.
- Ohne Voicechat-Mod bleibt es bei Commands/Status (kein echtes Sprach-Audio).

Commands:
- `voice`
- `voice-mode <normal|robot|deep|chipmunk|radio|studio|ghost> [player]`
- `voice-preset <normal|radio|tank|arcade|drone|cinema> [player]`
- `voice-intensity <0-200> [player]`
- `voice-tune <pitch=110,reverb=15,bass=25,clarity=120> [player]`
- `voice-timer <90s|5m|1h|off> [player]`
- `voice-toggle [player]`
- `voice-sync [player]`
- `voice-bridge <on|off>`
- `voice-bridge-sync-all`
- `voice-bridge-status`
- `voice-status [player]`
- `voice-export [player]`
- `voice-clear [player]`
- `voice-list`

Behavior:
- Persistiert vollstandige Voice-Profile pro Spieler (Enabled, Mode, Intensity, Pitch, Reverb, Bass, Clarity).
- Bietet Presets fur schnelle PVP/Event-Profile.
- Unterstutzt zeitlich begrenzte Voice-Mods uber Duration-Argumente und `off/reset` zum Deaktivieren.
- Bereinigt abgelaufene Profile automatisch und setzt danach wieder auf Normal.
- Robusteres `voice-tune`: akzeptiert `%`-Werte und Key-Aliases (`bassboost`, `treble`).
- Spieler koennen nur ihr eigenes Profil aendern; fremde Ziele sind fuer Player-Sender geblockt.

Simple Voice Chat Bridge:
- Registriert Channels: `simplevoicemod:voice_profile`, `simple_voice_mod:voice_profile`, `simple-voicemod:voice_profile`
- Registriert Request-Channels: `simplevoicemod:voice_profile_request`, `simple_voice_mod:voice_profile_request`, `simple-voicemod:voice_profile_request`
- Sendet bei Aenderung/Expiry/Clear automatisch Profil-Updates
- Heartbeat-Resync alle 30s fuer aktive Profile
- `voice-sync` pusht ein einzelnes Profil manuell
- `voice-bridge-sync-all` pusht alle gespeicherten Profile manuell
- `voice-bridge-status` zeigt Channel-Telemetrie + Bridge-Revision
- `voice-bridge on/off` aktiviert/deaktiviert die Bridge zur Laufzeit

Payload-Felder:
- `format`, `op`, `revision`, `timestamp`, `player`, `enabled`, `mode`, `intensity`, `pitch`, `reverb`, `bass`, `clarity`, `expiresAtEpochMillis`, `reason`
- `format=simple-voicechat-addon-v1`
- `op=upsert|reset`