# API Reference v1

Versioned companion docs:

- `docs/api/v1.5.0.md`
- `docs/migrations/v1.5.md`

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
- `network: PluginNetwork`
- `ui: PluginUi`
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
- `registerTexture`
- `registerModel`
- `registerAnimation`
- `registerSound`
- `registerSystem`

Read views:

- `items()`, `blocks()`, `recipes()`, `machines()`, `textures()`, `models()`, `systems()`
- `animations()`, `sounds()`
- `validateAssets(options)`
- `buildResourcePackBundle(options)`

## EventBus

- `subscribe(eventType, listener)`
- `subscribe(eventType, options, listener)`
- `unsubscribe(eventType, listener)`
- `publish(event)`
- `publishAsync(event)`
- `setTracingEnabled(enabled): Boolean`
- `eventTraceSnapshot(): EventTraceSnapshot`
- `resetEventTrace()`
- 1.1 helper: `subscribe<T> { ... }` (reified typed subscription)
- 1.5 helper: `subscribe<T>(options) { ... }`
- 1.1 helper: `subscribeOnce<T> { ... }`
- 1.5 helper: `publishAsyncUnit(event)`

`EventSubscriptionOptions`:

- `priority: EventPriority` (`HIGHEST`, `HIGH`, `NORMAL`, `LOW`, `LOWEST`)
- `ignoreCancelled: Boolean`
- `mainThreadOnly: Boolean`

`EventTraceSnapshot`:

- global totals: `totalEvents`, `totalListenerCalls`, `totalErrors`
- per-event metrics: `eventType`, `events`, `listenerCalls`, `errors`, `averageNanos`, `maxNanos`, `lastDurationNanos`, `lastThread`

Dispatch mode:

- `exact` (default): listener type must match exact event class
- `polymorphic`: superclass/interface listeners receive subtype events
- `hybrid`: exact listeners first, then superclass/interface listeners (deterministic order)

Built-in events:

- `GigaTickEvent`
- `GigaPlayerJoinEvent`
- `GigaPlayerLeaveEvent`
- `GigaPlayerMoveEvent`
- `GigaPlayerMovePreEvent` (cancel capable, mutable target/cause)
- `GigaPlayerMovePostEvent`
- `GigaWorldCreatedEvent`
- `GigaEntitySpawnEvent`
- `GigaEntitySpawnPreEvent` (cancel capable, mutable type/location/cause)
- `GigaEntitySpawnPostEvent`
- `GigaInventoryChangeEvent`
- `GigaEntityRemoveEvent`
- `GigaPlayerTeleportEvent`
- `GigaPlayerGameModeChangeEvent`
- `GigaPlayerMessageEvent`
- `GigaPlayerKickEvent`
- `GigaPlayerOpChangeEvent`
- `GigaPlayerPermissionChangeEvent`
- `GigaWorldTimeChangeEvent`
- `GigaWorldDataChangeEvent`
- `GigaWorldWeatherChangeEvent`
- `GigaPlayerStatusChangeEvent`
- `GigaPlayerEffectChangeEvent`
- `GigaBlockChangeEvent`
- `GigaBlockBreakPreEvent` (cancel capable, mutable target/dropLoot/cause)
- `GigaBlockBreakPostEvent`
- `GigaBlockDataChangeEvent`
- `GigaEntityDataChangeEvent`
- `GigaTextureRegisteredEvent`
- `GigaModelRegisteredEvent`
- `GigaCommandPreExecuteEvent` (cancel/override capable)
- `GigaCommandPostExecuteEvent`
- `GigaAdapterPreInvokeEvent` (cancel/override capable)
- `GigaAdapterPostInvokeEvent`

## HostAccess

- `serverInfo()`
- `broadcast(message)`
- `findPlayer(name)`
- `sendPlayerMessage(name, message)`
- `kickPlayer(name, reason)`
- `playerIsOp(name)`
- `setPlayerOp(name, op)`
- `playerPermissions(name)`
- `hasPlayerPermission(name, permission)`
- `grantPlayerPermission(name, permission)`
- `revokePlayerPermission(name, permission)`
- `worlds()`
- `entities(world?)`
- `spawnEntity(type, location)`
- `findEntity(uuid)`
- `removeEntity(uuid)`
- `entityData(uuid)`
- `setEntityData(uuid, data)`
- `blockAt(world, x, y, z)`
- `setBlock(world, x, y, z, blockId)`
- `breakBlock(world, x, y, z, dropLoot)`
- `blockData(world, x, y, z)`
- `setBlockData(world, x, y, z, data)`
- `playerInventory(name)`
- `setPlayerInventoryItem(name, slot, itemId)`
- `inventoryItem(name, slot)`
- `givePlayerItem(name, itemId, count)`
- `createWorld(name, seed)`
- `worldTime(name)`
- `setWorldTime(name, time)`
- `worldData(name)`
- `setWorldData(name, data)`
- `worldWeather(name)`
- `setWorldWeather(name, weather)`
- `movePlayer(name, location)`
- `playerGameMode(name)`
- `setPlayerGameMode(name, gameMode)`
- `playerStatus(name)`
- `setPlayerStatus(name, status)`
- `addPlayerEffect(name, effectId, durationTicks, amplifier)`
- `removePlayerEffect(name, effectId)`
- `applyMutationBatch(batch): HostMutationBatchResult`

Batch mutation types (`HostMutationType`):

- `CREATE_WORLD`
- `SET_WORLD_TIME`
- `SET_WORLD_DATA`
- `SET_WORLD_WEATHER`
- `SPAWN_ENTITY`
- `REMOVE_ENTITY`
- `SET_PLAYER_INVENTORY_ITEM`
- `GIVE_PLAYER_ITEM`
- `MOVE_PLAYER`
- `SET_PLAYER_GAMEMODE`
- `ADD_PLAYER_EFFECT`
- `REMOVE_PLAYER_EFFECT`
- `SET_BLOCK`
- `BREAK_BLOCK`
- `SET_BLOCK_DATA`

Plugin helper:

- `ctx.applyHostMutationBatch(batch) { rollbackResult -> ... }`

Additional permission gate:

- `host.mutation.batch` is required to execute `applyMutationBatch(...)` through host access.

## Plugin Network

- `registerChannel(spec: PluginChannelSpec)`
- `listChannels()`
- `subscribe(channel, listener)`
- `unsubscribe(channel, listener)`
- `send(channel, message): PluginMessageResult`
- `channelStats(channel): PluginChannelStats?`

Core contracts:

- `PluginChannelSpec(id, schemaVersion, maxInFlight, maxMessagesPerMinute, maxPayloadEntries, maxPayloadTotalChars)`
- `PluginMessage(channel, schemaVersion, payload, traceId, sourcePluginId)`
- `PluginMessageResult(status, deliveredSubscribers, reason)`
- `PluginMessageStatus`:
  - `ACCEPTED`
  - `CHANNEL_NOT_FOUND`
  - `SCHEMA_MISMATCH`
  - `PAYLOAD_INVALID`
  - `BACKPRESSURE`
  - `QUOTA_EXCEEDED`
  - `DENIED`

Helpers:

- `ctx.registerNetworkChannel(spec)`
- `ctx.sendPluginMessage(channel, payload)`

## UI API

Types:

- `PluginUi`
- `UiNotice(title, message, level, durationMillis)`
- `UiMenu(id, title, items)` + `UiMenuItem(id, label, description, enabled)`
- `UiDialog(id, title, fields)` + `UiDialogField(id, label, type, required, options, placeholder)`

Operations:

- `ctx.ui.notify(player, notice)`
- `ctx.ui.actionBar(player, message, durationTicks)`
- `ctx.ui.openMenu(player, menu)`
- `ctx.ui.openDialog(player, dialog)`
- `ctx.ui.close(player)`

Helper shortcuts:

- `ctx.notifyInfo(...)`
- `ctx.notifySuccess(...)`
- `ctx.notifyWarning(...)`
- `ctx.notifyError(...)`
- `ctx.notify(player, level, message, title, durationMillis)`
- `ctx.actionBar(player, message, durationTicks)`
- `ctx.showMenu(...)`
- `ctx.showDialog(...)`
- `ctx.closeUi(player)`
- `ctx.broadcastNotice(message, level, title)`

UI lifecycle events:

- `GigaUiNoticeEvent`
- `GigaUiActionBarEvent`
- `GigaUiMenuOpenEvent`
- `GigaUiDialogOpenEvent`

Note:

- `HostServerSnapshot.platformVersion` is the only server platform field in v1

Host permission enforcement:

- Host calls are denied unless permissions are declared in `gigaplugin.yml`
- common permissions:
  - `host.server.read`
  - `host.server.broadcast`
  - `host.player.read`
  - `host.player.message`
  - `host.player.kick`
  - `host.player.op.read`
  - `host.player.op.write`
  - `host.player.permission.read`
  - `host.player.permission.write`
  - `host.world.read`
  - `host.world.write`
  - `host.world.data.read`
  - `host.world.data.write`
  - `host.world.weather.read`
  - `host.world.weather.write`
  - `host.entity.read`
  - `host.entity.spawn`
  - `host.entity.remove`
  - `host.entity.data.read`
  - `host.entity.data.write`
  - `host.inventory.read`
  - `host.inventory.write`
  - `host.player.move`
  - `host.player.gamemode.read`
  - `host.player.gamemode.write`
  - `host.player.status.read`
  - `host.player.status.write`
  - `host.player.effect.write`
  - `host.block.read`
  - `host.block.write`
  - `host.block.data.read`
  - `host.block.data.write`

## Adapters

Core types:

- `AdapterInvocation(action, payload)`
- `AdapterResponse(success, payload, message)`
- `ModAdapter`
- `ModAdapterRegistry`

1.1 payload helpers on `AdapterInvocation`:

- `payloadString(key)`
- `payloadTrimmed(key)`
- `payloadRequired(key)` (throws if missing/blank)
- `payloadInt(key)`
- `payloadLong(key)`
- `payloadDouble(key)`
- `payloadBool(key)` (`true/false`, `1/0`, `yes/no`, `on/off`)
- `payloadCsv(key, separator=',')`
- `payloadEnum<YourEnum>(key)`
- `payloadEnumRequired<YourEnum>(key)`
- `payloadIntRequired(key)`, `payloadLongRequired(key)`, `payloadDoubleRequired(key)`, `payloadBoolRequired(key)`
- `payloadByPrefix(prefix, stripPrefix=true)`
- `payloadDurationMillis(key)` (`1500`, `500ms`, `2s`, `3m`, `1h`)
- `payloadMap(key)` (e.g. `"a=1;b=2"` -> map)

Registry operations:

- `register(adapter)`
- `list()`
- `find(id)`
- `invoke(adapterId, invocation)`

Runtime policies:

- `SAFE`: validation, quota, concurrency and timeout guardrails
- `FAST`: lower overhead mode for trusted setups

## Assets (Textures/Models)

- `TextureDefinition(id, path, category, animated)`
- `ModelDefinition(id, format, geometryPath, textures, metadata, material, doubleSided, scale, collision, bounds, lods, animations)`
- `AnimationDefinition(id, path, targetModelId, loop)`
- `SoundDefinition(id, path, category, stream, volume, pitch)`
- `ResourcePackBundle(pluginId, textures, models, animations, sounds, assets)`
- `AssetValidationResult(valid, issues)`
- `ModelBounds(minX, minY, minZ, maxX, maxY, maxZ)`
- `ModelLod(distance, geometryPath, format)`

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

`CommandRegistry.registerSpec(spec, middleware, completion, completionAsync, policy, action)`

Additional lifecycle-safe operations:

- `registerOrReplaceSpec(spec, middleware, completion, completionAsync, policy, action)`
- `unregister(command)`
- `registerAlias(alias, command)`
- `unregisterAlias(alias)`
- `resolve(commandOrAlias)`
- `registeredCommands()`
- `commandTelemetry(commandOrAlias)`
- `commandTelemetry()`

Action signature:

- `(CommandInvocationContext) -> CommandResult`

1.5 command UX contracts:

- `CommandSpec`:
  - `permission`
  - `argsSchema`
  - `cooldownMillis`
  - `rateLimitPerMinute`
  - `usage` / `help`
  - `subcommands`
- typed sender:
  - `CommandSender(id, type)`
  - `CommandSenderType` (`PLAYER`, `CONSOLE`, `SYSTEM`)
- typed args via `CommandParsedArgs` (`string/int/long/double/boolean/enum`)
- `registerSpec(spec, middleware, completion, completionAsync, policy) { ... }`
- `CommandPolicyProfile(permissionPrefix, defaultCooldownMillis, defaultRateLimitPerMinute)`
- per-plugin defaults:
  - `CommandPolicyProfiles.set(pluginId, profile)`
- deterministic middleware chain via phases:
  - `AUTH`
  - `VALIDATION`
  - `AUDIT`
- completion contract:
  - `CommandCompletionContract`
  - `CommandCompletionAsyncContract`
  - `CommandCompletionCatalog.suggest(...)`
  - `CommandCompletionCatalog.suggestAsync(...)`

Recommended registration flow:

- register canonical command id once (`registerSpec` / `registerOrReplaceSpec`)
- attach short aliases (`registerAlias` or `registerAliasOrThrow`)
- resolve before custom routing (`resolve(...)`)
- remove command on unload (`unregister` also drops linked aliases in runtime)
- inspect command hotspots via `commandTelemetry()`

`CommandResult`:

- `CommandResult.ok(message, code?)`
- `CommandResult.error(message, code?, field?, hint?)`
- machine-readable error:
  - `CommandError(code, field, hint)`
- `render()` converts to legacy command string output (`[CODE] message` when code is set).

Command lifecycle events:

- `GigaCommandPreExecuteEvent`
  - sender type: `CommandSender`
  - mutable: `cancelled`, `cancelReason`, `overrideResponse: CommandResult?`
- `GigaCommandPostExecuteEvent`
  - includes `success`, `response: CommandResult`, `durationNanos`, `error: CommandError?`

Adapter lifecycle events:

- `GigaAdapterPreInvokeEvent`
  - mutable: `cancelled`, `cancelReason`, `overrideResponse`
- `GigaAdapterPostInvokeEvent`
  - includes `outcome`, `response`, `durationNanos`

Gameplay lifecycle events:

- `GigaPlayerMovePreEvent`
  - mutable: `targetWorld`, `targetX`, `targetY`, `targetZ`, `cause`, `cancelled`, `cancelReason`
- `GigaPlayerMovePostEvent`
  - includes `success`, `cancelled`, `current`, `durationNanos`, `error`
- `GigaEntitySpawnPreEvent`
  - mutable: `entityType`, `world`, `x`, `y`, `z`, `cause`, `cancelled`, `cancelReason`
  - cancellation aborts spawn (host adapter call resolves as failure / `null`)
- `GigaEntitySpawnPostEvent`
  - includes `success`, `cancelled`, `entity`, `durationNanos`, `error`
- `GigaBlockBreakPreEvent`
  - mutable: `world`, `x`, `y`, `z`, `dropLoot`, `cause`, `cancelled`, `cancelReason`
- `GigaBlockBreakPostEvent`
  - includes `success`, `cancelled`, `previousBlockId`, `durationNanos`, `error`

## Plugin Permission Helpers

1.1 helpers on `PluginContext`:

- `hasPermission(permission)`
- `requirePermission(permission)`

## DSL Entry

`gigaPlugin(...)` supports:

- metadata (`id`, `name`, `version`, `apiVersion`)
- dependency declarations:
  - `dependencySpecs = listOf(dependency("core"), dependency("x", ">=1 <2"))`
- sections:
  - `items { }`
  - `blocks { }`
  - `recipes { }`
  - `machines { }`
  - `textures { }`
  - `models { }` including extended model attributes:
    - `material`, `doubleSided`, `scale`, `collision`
    - `bounds` (`ModelBounds`)
    - `lods` (`ModelLod`)
    - `animations` map
  - `systems { }`
  - `commands { }`
    - 1.5 spec-first:
      - `command(spec = CommandSpec(...), middleware = [...], completion = ...) { invocation -> CommandResult }`
      - `spec(command = "...", argsSchema = [...], permission = "...", cooldownMillis = ..., rateLimitPerMinute = ...) { invocation -> CommandResult }`
  - `adapters { }`


