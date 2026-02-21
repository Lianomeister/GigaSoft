# Showcase: Guarded

Commands:
- `guard-pos1 <world> <x> <y> <z>`
- `guard-pos2 <world> <x> <y> <z>`
- `guard-create <id> [denyBuild] [denyBreak] [denyPvp]`
- `guard-allow <id> <player>`

Behavior:
- Creates protected cuboid areas.
- Cancels block-break in denied zones.

Notes:
- Current API pre-break event does not expose breaker identity/tool item.
- WorldEdit-style axe selection can be added once interaction event includes player + held item context.
