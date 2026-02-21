# Tutorial: Cancelable Gameplay Events

Goal: enforce gameplay rules with `Pre`/`Post` events and predictable outcomes.

## When to use this

Use gameplay lifecycle events when your plugin must:

- block or rewrite moves/spawns/breaks before they execute
- audit final result (success/cancel/error) after execution
- apply policy centrally without patching every command/adapter call

## Player Move Policy

```kotlin
ctx.events.subscribe<GigaPlayerMovePreEvent> { event ->
    if (event.player.name.equals("Alex", ignoreCase = true) && event.targetWorld == "admin_world") {
        event.cancelled = true
        event.cancelReason = "admin_world is restricted"
    }
}

ctx.events.subscribe<GigaPlayerMovePostEvent> { event ->
    if (!event.success) {
        ctx.logger.info("move denied player=${event.player.name} reason=${event.error}")
    }
}
```

## Spawn Rewrite Policy

```kotlin
ctx.events.subscribe<GigaEntitySpawnPreEvent> { event ->
    if (event.entityType.equals("zombie", ignoreCase = true)) {
        event.entityType = "sheep" // rewrite
    }
}
```

## Block Protection Policy

```kotlin
ctx.events.subscribe<GigaBlockBreakPreEvent> { event ->
    val protected = event.world == "spawn" && event.x in -16..16 && event.z in -16..16
    if (protected) {
        event.cancelled = true
        event.cancelReason = "spawn area is protected"
    }
}

ctx.events.subscribe<GigaBlockBreakPostEvent> { event ->
    if (event.cancelled) {
        ctx.logger.info("block break cancelled at ${event.world}:${event.x},${event.y},${event.z}")
    }
}
```

## Best Practices

- always set a clear `cancelReason`
- keep pre listeners fast and deterministic
- use post events for telemetry/audit only
- avoid recursive side effects inside pre handlers

