# Showcase: Nick

Commands:
- `nick <player> <newName>`
- `nick-skin <player> <skinName>`
- `nick-show <player>`
- `nick-clear <player>`
- `nick-list`

Behavior:
- Stores nickname and skin alias per player.
- Maintains a searchable profile index for operator workflows.
- Validates nickname/alias formats before persisting.

Limitations:
- Real tablist/overhead name replacement and true skin swap need host-level integration.
- This showcase focuses on robust command UX + persistence patterns.
