# Tutorial: Persistence

Goal: store plugin state reliably across restarts and reloads.

## Define State Model

```kotlin
data class CounterState(
    val totalEvents: Long = 0
)
```

## Open Store

```kotlin
val store = ctx.storage.store("counter-state", CounterState::class.java, version = 1)
```

## Load + Save

```kotlin
var state = store.load() ?: CounterState()
state = state.copy(totalEvents = state.totalEvents + 1)
store.save(state)
```

## Migration Pattern

When schema changes, bump store version and migrate once:

```kotlin
store.migrate(fromVersion = 1) { old ->
    old.copy(totalEvents = old.totalEvents) // transform fields as needed
}
```

## Best Practices

- keep state small and explicit
- use one key per cohesive domain
- perform deterministic migrations
- avoid frequent blocking writes in hot tick paths
- save important state before expensive reload operations
