# Showcase: Restocker

Commands:
- `restocker-interval <ticks>`
- `restocker-status`

Behavior:
- Periodically scans villagers and writes restock metadata to entity data.
- Interval is configurable in ticks.

Notes:
- Real merchant inventory refill behavior depends on host implementation.
- This showcases timing/config + entity data mutation patterns.
