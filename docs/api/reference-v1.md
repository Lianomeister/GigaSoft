# API Reference v1

This page is the practical reference for plugin authors targeting `apiVersion: 1`.

## Plugin Lifecycle

Interface: `GigaPlugin`

- `onEnable(ctx: PluginContext)`
- `onDisable(ctx: PluginContext)`
- `onReload(ctx: PluginContext)` (default: disable + enable)

## PluginContext

- `manifest: PluginManifest`
- `logger: GigaLogger`
- `scheduler: Scheduler`
- `registry: RegistryFacade`
- `adapters: ModAdapterRegistry`
- `storage: StorageProvider`
- `commands: CommandRegistry`
- `events: EventBus`
- `host: HostAccess`

## Scheduler

- `repeating(taskId, periodTicks, block)`
- `once(taskId, delayTicks, block)`
- `cancel(taskId)`
- `clear()`

Use stable `taskId`s and clear/cancel tasks in `onDisable`.

## RegistryFacade

Register:

- `registerItem`
- `registerBlock`
- `registerRecipe`
- `registerMachine`
- `registerSystem`

Read views:

- `items()`, `blocks()`, `recipes()`, `machines()`, `systems()`

## EventBus

- `subscribe(eventType, listener)`
- `publish(event)`
- 1.1 helper: `subscribe<T> { ... }` (reified typed subscription)

Dispatch mode:

- `exact` (default): listener type must match exact event class
- `polymorphic`: superclass/interface listeners receive subtype events

Built-in events:

- `GigaTickEvent`
- `GigaPlayerJoinEvent`
- `GigaPlayerLeaveEvent`
- `GigaPlayerMoveEvent`
- `GigaWorldCreatedEvent`
- `GigaEntitySpawnEvent`
- `GigaInventoryChangeEvent`

## HostAccess

- `serverInfo()`
- `broadcast(message)`
- `findPlayer(name)`
- `worlds()`
- `entities(world?)`
- `spawnEntity(type, location)`
- `playerInventory(name)`
- `setPlayerInventoryItem(name, slot, itemId)`

Note:

- `HostServerSnapshot.platformVersion` is the only server platform field in v1

Host permission enforcement:

- Host calls are denied unless permissions are declared in `gigaplugin.yml`
- common permissions:
  - `host.server.read`
  - `host.server.broadcast`
  - `host.player.read`
  - `host.world.read`
  - `host.entity.read`
  - `host.entity.spawn`
  - `host.inventory.read`
  - `host.inventory.write`

## Adapters

Models:

- `AdapterInvocation(action, payload)`
- `AdapterResponse(success, payload, message)`
- `ModAdapter`
- `ModAdapterRegistry`

Registry operations:

- `register(adapter)`
- `list()`
- `find(id)`
- `invoke(adapterId, invocation)`

Runtime policies:

- `SAFE`: validation, quota, concurrency and timeout guardrails
- `FAST`: lower overhead mode for trusted setups

## Storage

`StorageProvider.store(key, type, version)` returns `PersistentStore<T>`

1.1 helper:

- `store<T>(key, version)` (reified typed store lookup)

- `load()`
- `save(value)`
- `migrate(fromVersion, migration)`

Rules:

- bump version only for real schema changes
- keep migration deterministic and idempotent

## Commands

`CommandRegistry.register(command, description, action)`

Additional lifecycle-safe operations:

- `registerOrReplace(command, description, action)`
- `unregister(command)`

Action signature:

- `(ctx, sender, args) -> String`

1.1 helpers:

- `register(command, description) { sender, args -> ... }`
- `registerOrReplace(command, description) { sender, args -> ... }`
- `registerResult(command, description) { ... -> CommandResult }`
- `registerOrReplaceResult(command, description) { ... -> CommandResult }`

`CommandResult`:

- `CommandResult.ok(message, code?)`
- `CommandResult.error(message, code?)`
- `render()` converts to legacy command string output (`[CODE] message` when code is set).

## Plugin Permission Helpers

1.1 helpers on `PluginContext`:

- `hasPermission(permission)`
- `requirePermission(permission)`

## DSL Entry

`gigaPlugin(...)` supports:

- metadata (`id`, `name`, `version`, `apiVersion`)
- dependency declarations:
  - `dependencySpecs = listOf(dependency("core"), dependency("x", ">=1 <2"))`
  - legacy compatibility: `dependencies = listOf("core", "x >=1 <2")`
- sections:
  - `items { }`
  - `blocks { }`
  - `recipes { }`
  - `machines { }`
  - `systems { }`
  - `commands { }`
  - `adapters { }`
