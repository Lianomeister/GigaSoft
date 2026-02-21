# Showcase: Veinminer

Commands:
- `veinminer-status`
- `veinminer-max-scan <nodes>`
- `veinminer-delay-divisor <value>`
- `veinminer-max-delay <ticks>`
- `veinminer-drop-loot <true|false>`

Behavior:
- Breaks connected ore veins with a deferred batch strategy.
- Supports expanded ore set (including deepslate/nether variants).
- Uses deterministic limits and suppression to avoid recursive break loops.
- Persists tuning config in plugin storage.
