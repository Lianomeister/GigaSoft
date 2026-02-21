# Showcase: Godmode

Commands:
- `godmode [player]`
- `godmode-list`
- `godmode-clear`

Behavior:
- Toggles players in a protected set.
- Tick system keeps player health at max while enabled.
- Enabled players are persisted in plugin storage.

Notes:
- In full host integrations this should hook into damage events directly.
- This version demonstrates stateful command UX + persistence.
