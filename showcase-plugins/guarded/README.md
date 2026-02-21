# Showcase: Guarded

Commands:
- `guard-pos1 <world> <x> <y> <z>`
- `guard-pos2 <world> <x> <y> <z>`
- `guard-create <id> [denyBuild] [denyBreak] [denyPvp]`
- `guard-allow <id> <player>`
- `guard-disallow <id> <player>`
- `guard-delete <id>`
- `guard-list`
- `guard-info <id>`
- `guard-enforce-unknown <true|false>`

Behavior:
- Creates protected cuboid areas with per-area allowlists.
- Persists guard areas + enforcement mode.
- Cancels block-break in denied zones.

Notes:
- Current block-break pre-event may not include actor identity in all hosts.
- `guard-enforce-unknown` controls whether unknown actors are blocked or ignored.
