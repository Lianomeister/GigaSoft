# Tutorial: Adapters

Goal: define a custom adapter and invoke host adapters safely.

Host bridge actions are permission-gated. Add required permissions in `gigaplugin.yml`, for example:

```yaml
permissions:
  - host.server.read
  - adapter.invoke.*
  - adapter.capability.echo
```

## Register Custom Adapter

```kotlin
adapters {
    adapter(
        id = "demo.tools",
        name = "Demo Tools",
        version = "1.0.0",
        capabilities = setOf("echo")
    ) { invocation ->
        if (invocation.action != "echo") {
            AdapterResponse(success = false, message = "unknown action")
        } else {
            AdapterResponse(success = true, payload = invocation.payload)
        }
    }
}
```

## Invoke Host Adapter

```kotlin
val result = ctx.adapters.invoke(
    "bridge.host.world",
    AdapterInvocation("world.list", emptyMap())
)
if (!result.success) {
    ctx.logger.info("adapter failed: ${result.message}")
}
```

Common built-ins:
- `bridge.host.server` for `server.*`
- `bridge.host.player` for `player.*`
- `bridge.host.world` for `world.*` and `block.*`
- `bridge.host.entity` for `entity.*`
- `bridge.host.inventory` for `inventory.*`

If your runtime enables adapter permission scopes, plugin permissions can restrict:

- adapter IDs: `adapter.invoke.<id>` or `adapter.invoke.*`
- adapter capabilities: `adapter.capability.<capability>` or `adapter.capability.*`

## SAFE vs FAST

- `SAFE`: validation + quota + concurrency + timeout
- `FAST`: trusted low-latency path with fewer runtime checks

Use `SAFE` by default in production unless you control all plugin code and inputs.

## Adapter Debug Checklist

1. `adapters <plugin> --json`
2. `adapter invoke <plugin> <adapter> <action> --json`
3. `profile <plugin> --json` and inspect adapter metrics:
   - `deny`
   - `timeout`
   - `fail`
