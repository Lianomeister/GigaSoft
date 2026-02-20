# Contributing

## Requirements
- JDK 21
- Gradle 8.10+

## Local flow
1. `gradle test`
2. `gradle buildPlugin`
3. Copy `gigasoft-bridge-paper/build/libs/gigasoft-bridge-paper-0.1.0-rc.1.jar` to Paper `plugins/`
4. Copy `gigasoft-demo/build/libs/gigasoft-demo-0.1.0-rc.1.jar` to `plugins/GigaSoftBridge/giga-plugins/`

## Coding rules
- Keep API module stable and backwards compatible.
- Keep Paper-specific code only in `gigasoft-bridge-paper`.
- Add tests for registry, lifecycle, persistence, and scheduler changes.
