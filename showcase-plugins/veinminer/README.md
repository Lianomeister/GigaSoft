# Showcase: Veinminer

Behavior:
- When an ore block is broken, connected ore of same type is collected.
- Remaining ore blocks are broken after a delay based on vein size.

Tuning:
- Current max vein scan: 256 blocks.
- Delay scales by amount (`size / 2`) to feel balanced.
