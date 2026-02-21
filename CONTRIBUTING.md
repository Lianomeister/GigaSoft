# Contributing

## Requirements
- JDK 21
- Gradle 8.10+

## Local flow
1. `./gradlew --no-daemon :gigasoft-api:apiCheck`
2. `./gradlew --no-daemon test`
3. `./gradlew --no-daemon performanceBaseline`
4. `./gradlew --no-daemon standaloneReleaseCandidate`
5. `./gradlew --no-daemon smokeTest`
6. Optional before release: `./gradlew --no-daemon soakTest`
7. Start `gigasoft-standalone` and use standalone console commands (`scan`, `reload`, `status --json`, `doctor --json`, `profile <id> --json`)

## Coding rules
- Keep API module stable and backwards compatible.
- For API 1.x, apply additive-only changes and use deprecations before removals.
- Update `docs/api/v1.5.0.md`, `docs/migrations/v1.5.md`, and `CHANGELOG.md` for any API-surface change.
- Keep `website/` and `docs/tutorials/*` aligned with CLI/API behavior.
- Keep host-neutral abstractions in core/runtime modules.
- Add tests for registry, lifecycle, persistence, scheduler, and performance-sensitive changes.
