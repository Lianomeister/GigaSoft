# Contributing

## Requirements
- JDK 21
- Gradle 8.10+

## Local flow
1. `gradle test`
2. `gradle performanceBaseline`
3. `gradle standaloneReleaseCandidate`
4. Start `gigasoft-standalone` and use standalone console commands (`scan`, `reload`, `status`)

## Coding rules
- Keep API module stable and backwards compatible.
- Keep host-neutral abstractions in core/runtime modules.
- Add tests for registry, lifecycle, persistence, scheduler, and performance-sensitive changes.
