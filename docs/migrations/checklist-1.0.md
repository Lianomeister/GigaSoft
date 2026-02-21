# Migration Checklist for 1.0

Use this checklist for each plugin before shipping on Clockwork 1.0.

## 1. Manifest

- [ ] `apiVersion: 1` is set.
- [ ] `id`, `name`, `version`, `main` pass manifest validation.
- [ ] Required `permissions` are declared and de-duplicated.

## 2. Host Permissions

Declare only what is needed:

- [ ] `host.server.read`
- [ ] `host.server.broadcast`
- [ ] `host.player.read`
- [ ] `host.world.read`
- [ ] `host.entity.read`
- [ ] `host.entity.spawn`
- [ ] `host.inventory.read`
- [ ] `host.inventory.write`

## 3. Commands

- [ ] Command IDs are stable and unique.
- [ ] Use `registerOrReplace(...)` for reload-safe replacement.
- [ ] Use `unregister(...)` where explicit teardown is required.

## 4. Events

- [ ] Confirm expected dispatch mode:
  - `exact` (default)
  - `polymorphic` (if superclass/interface listeners are required)
- [ ] Event handlers remain deterministic and bounded.

## 5. Persistence

- [ ] Stored payloads can be migrated with deterministic logic.
- [ ] Recovery is tested with simulated corrupted primary store.

## 6. Runtime Validation

Run and verify:

```bash
./gradlew --no-daemon test
./gradlew --no-daemon performanceBaseline
./gradlew --no-daemon standaloneReleaseCandidate
```

## 7. Ops/Diagnostics

- [ ] `status --json` is parsable and attached in bug reports.
- [ ] `doctor --json` is clean for dependency/API state.
- [ ] `profile <plugin> --json` shows expected runtime behavior.

## 8. Final Release Checks

- [ ] API compatibility gate passes (`:clockwork-api:apiCheck`).
- [ ] Changelog entry is complete.
- [ ] `RELEASE_NOTES_1.0.md` reviewed.
