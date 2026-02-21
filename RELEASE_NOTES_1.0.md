# Clockwork 1.0.0 Release Notes

## Highlights

- Standalone runtime is the primary and supported runtime target for v1.0.
- API contracts are frozen for the 1.x line.
- Plugin security is stricter by default (host permission enforcement, adapter guardrails).
- Reload, diagnostics and performance gates are integrated into release workflows.

## Plugin API and Runtime

- `PluginContext`, DSL and event contracts are finalized for v1.0.
- `CommandRegistry` now supports lifecycle-safe command management:
  - `register(...)`
  - `registerOrReplace(...)`
  - `unregister(...)`
- Runtime command registration now rejects duplicate command IDs unless explicitly replaced.
- Event dispatch mode supports:
  - `exact` (default)
  - `polymorphic`

## Security

- Host access calls are permission-gated and require manifest permissions.
- Host bridge adapter actions also enforce the same permission model.
- Adapter/net guardrails remain active in `SAFE` mode (validation, quotas, limits, timeout/audit).

## Persistence and Recovery

- JSON store writes use temp-file + move semantics.
- State files now include SHA-256 checksums.
- On corruption, runtime attempts backup recovery before failing the store load.

## Operations and Gates

Release gate remains:

```bash
./gradlew --no-daemon clean :clockwork-api:apiCheck test performanceBaseline standaloneReleaseCandidate
```

Additional API compatibility gate is active via:

```bash
./gradlew --no-daemon :clockwork-api:apiCheck
```

Performance baseline evidence:

- `docs/performance/baseline-1.0.0.md`

## Migration

Use this checklist before upgrading production plugins:

- `docs/migrations/checklist-1.0.md`
- `docs/migrations/v1.0.md`
- `docs/api/v1.0.0.md`

## Known Defaults

- Event dispatch mode defaults to `exact`.
- Adapter execution mode defaults to `safe`.
- Host access requires explicit permissions in `gigaplugin.yml`.
