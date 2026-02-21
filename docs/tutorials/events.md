# Tutorial: Events

Goal: subscribe to built-in events safely and debug event flow.

## Basic Subscription

```kotlin
ctx.events.subscribe(GigaPlayerJoinEvent::class.java) { event ->
    ctx.logger.info("join: ${event.player.name}")
}
```

## Useful Built-in Events

- `GigaTickEvent`
- `GigaPlayerJoinEvent`
- `GigaPlayerLeaveEvent`
- `GigaPlayerMoveEvent`
- `GigaWorldCreatedEvent`
- `GigaEntitySpawnEvent`
- `GigaInventoryChangeEvent`

## Best Practices

- keep listeners cheap (no heavy blocking work)
- use counters/aggregation instead of spamming logs
- avoid mutating shared state without synchronization
- validate payload fields before use

## Debug Pattern

```kotlin
ctx.events.subscribe(GigaTickEvent::class.java) { tick ->
    if (tick.tick % 200L == 0L) {
        ctx.logger.info("heartbeat tick=${tick.tick}")
    }
}
```

Verify with:

- `status --json` (tick progression)
- `profile <plugin> --json` (system/runtime counters)
