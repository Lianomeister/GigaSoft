# Showcase: SoftAnticheat

Commands:
- `softac-status [player]`
- `softac-reset [player]`
- `softac-threshold <distance>`
- `softac-setback <score>`
- `softac-kick <true|false>`
- `softac-top [limit]`

Features:
- Score-based movement anomaly detector (distance/horizontal/vertical/acceleration).
- Time-based decay, alert cooldown, automatic setback, optional kick escalation.
- Per-player reason counters for quick operator diagnosis.
- Persisted config via simplified Plugin API storage helpers.

Notes:
- Intentionally conservative baseline before advanced packet/physics checks.
- Designed for low overhead and clear operator visibility.
