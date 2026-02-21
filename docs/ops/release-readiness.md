# Ops Release Readiness

## Diagnostics Contract

Standalone console diagnostics are expected to be machine-readable:

- `status --json`
- `doctor --json`
- `profile <id> --json`

`profile <id> --json` returns:

- full profile object when present
- `{ "pluginId": "<id>", "found": false }` when not present

## Pipelines

- Release gate:
  - `./gradlew --no-daemon clean :gigasoft-api:apiCheck test performanceBaseline standaloneReleaseCandidate`
- Smoke:
  - `./gradlew --no-daemon smokeTest`
- Soak:
  - `./gradlew --no-daemon soakTest`

## CI Workflows

- `.github/workflows/ci.yml`
  - release gate command
  - smoke pipeline
- `.github/workflows/soak.yml`
  - scheduled + manual soak run

## Definition of Done

- `test` green
- `:gigasoft-api:apiCheck` green
- `performanceBaseline` green
- `standaloneReleaseCandidate` green
- smoke pipeline green
