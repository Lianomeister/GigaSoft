# Showcase: Restocker

Commands:
- `restocker-interval <ticks>`
- `restocker-max <count>`
- `restocker-run`
- `restocker-status`

Behavior:
- Periodically scans villagers and writes restock metadata to entity data.
- Uses deterministic cycle timing and capped per-cycle workload.
- Persists tuning config in plugin storage.

Notes:
- Real merchant inventory refill behavior depends on host implementation.
- This showcases scheduling/configuration + entity data mutation patterns.
