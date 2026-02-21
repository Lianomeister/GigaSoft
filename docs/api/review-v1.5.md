# API Review v1.5 (clockwork-api + clockwork-host-api)

Date: 2026-02-21  
Scope: naming, nullability, contract clarity, additive safety improvements for `1.x`.

## Findings

1. `HostAccess`/host ports had nullable capability checks (`Boolean?`) and nullable maps (`Map<String, String>?`) that forced repeated defensive code in plugins.
2. Naming between methods was mixed (`findPlayer` vs lookup semantics, `worlds`/`entities` list methods without explicit list aliases), which reduced readability in larger plugins.
3. Contract behavior for "unsupported host feature" was technically valid but not ergonomic for plugin authors, especially in standalone-unavailable contexts.

## Decisions (1.x-safe, additive)

1. Keep existing methods unchanged for compatibility.
2. Add additive alias/helper methods to make contracts explicit and null-safe by default.
3. Do not introduce breaking result-wrapper types in `1.5.x`; keep that for potential `2.x`.

## Implemented API additions

### `clockwork-api` (`HostAccess`)

- `lookupPlayer(name)` -> alias for `findPlayer(name)`
- `isPlayerOp(name)` -> `playerIsOp(name) == true`
- `permissionsOfPlayer(name)` -> `playerPermissions(name).orEmpty()`
- `playerHasPermission(name, permission)` -> `hasPlayerPermission(...) == true`
- `listWorlds()` -> alias for `worlds()`
- `listEntities(world?)` -> alias for `entities(world?)`
- `worldDataOrEmpty(name)` -> `worldData(name).orEmpty()`
- `entityDataOrEmpty(uuid)` -> `entityData(uuid).orEmpty()`

### `clockwork-host-api` (ports)

Added equivalent additive helpers on:

- `HostPlayerPort`
- `HostWorldPort`
- `HostEntityPort`

This keeps bridge and standalone host implementations aligned.

## Validation

- Added/updated tests for alias/nullability helper behavior:
  - `clockwork-api/src/test/kotlin/com/clockwork/api/PluginApiExtensionsTest.kt`
  - `clockwork-host-api/src/test/kotlin/com/clockwork/host/api/HostAccessAdapterTest.kt`
  - `clockwork-host-api/src/test/kotlin/com/clockwork/host/api/HostAccessAdapterPortContractsTest.kt`

## Follow-ups (post-GA candidates)

1. Introduce explicit capability/result wrappers for host operations (e.g. unsupported/not-found/denied) to replace ambiguous `Boolean?` in a future major API.
2. Add API compatibility reporting in CI (`binary + source` surface checks).
3. Expand contract tests around failure/error-path semantics for host bridges.
