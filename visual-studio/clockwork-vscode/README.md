# Clockwork Plugin Tools (VS Code)

VS Code extension for Clockwork plugin development.

## Features

- `Clockwork: Create Plugin Template`:
  - creates `src/main/kotlin/.../MainPlugin.kt`
  - creates `gigaplugin.yml`
  - includes modern adapter payload helper usage (`payloadRequired`, `payloadIntRequired`)
  - includes updated model DSL fields (`material`, `scale`, `collision`)
- `Clockwork: Create Texture/Model Asset Template`:
  - creates `src/main/resources/assets/<plugin-id>/...` scaffold
  - adds example model json and asset README
- `Clockwork: Validate gigaplugin.yml`:
  - checks required keys (`id`, `name`, `version`, `main`, `apiVersion`)
  - warns for unknown host permissions
  - warns for duplicate permissions
  - warns for non-standard plugin id and version format
  - validates inline dependency entries for common formatting errors
  - validates new permission families:
    - `host.mutation.batch`
    - `adapter.invoke.*`
    - `adapter.capability.*`
  - Kotlin diagnostics recommend dedicated host bridge adapters:
    - `bridge.host.world` for `world.*`
    - `bridge.host.entity` for `entity.*`
    - `bridge.host.inventory` for `inventory.*`
  - provides Quick Fixes for:
    - removing duplicate permission entries
    - normalizing plugin id
    - setting version to a valid default
    - inserting missing required keys
    - setting `apiVersion: 1`
    - applying manifest best-practice fix-all
  - supports both inline (`permissions: [a, b]`) and list style:
    - `permissions:`
    - `  - host.server.read`
- `Clockwork: Check Extension Updates`:
  - checks latest release from GitHub repo (`Lianomeister/Clockwork`)
- Clockwork-aware syntax highlighting:
  - highlights `gigaplugin.yml` keys (`id`, `name`, `version`, `main`, `apiVersion`, `dependencies`, `permissions`)
  - highlights Clockwork permission strings (`host.*`, `adapter.*`) in YAML and Kotlin
  - highlights common Clockwork Kotlin DSL/API calls and types without replacing normal Kotlin coloring

## Snippets

- Kotlin snippets for plugin class, command result, adapter handler, typed event subscription, and texture/model DSL blocks
- Kotlin snippets for:
  - CommandSpec-first command templates
  - Event 2.0 options/tracing
  - Plugin network channels
- YAML snippet for `gigaplugin.yml`

Updated Kotlin snippets include:

- advanced payload helpers: `payloadCsv`, `payloadEnum`, `payloadByPrefix`, and required numeric/bool helpers
- extended model options: `ModelBounds`, `ModelLod`, `material`, `doubleSided`, `scale`, `collision`, `animations`
- gameplay pre/post policy snippet for cancelable events
- network and command/event snippets for the 1.5 API surface

## Settings

- `clockwork.plugin.defaultId`
- `clockwork.plugin.defaultMainClass`
- `clockwork.plugin.defaultVersion`
- `clockwork.update.checkOwner`
- `clockwork.update.checkRepo`

## Local Development

```bash
cd visual-studio/clockwork-vscode
code .
```

Press `F5` in VS Code to launch Extension Development Host.

## Packaging and Release

Build a VSIX package:

```bash
cd visual-studio/clockwork-vscode
npm install
npm run package
```

Pre-release package:

```bash
npm run package:pre
```

Quick publish readiness check:

```bash
npm run publish:check
```

Release checklist:

1. bump `version` in `package.json`
2. update `CHANGELOG.md`
3. run `npm run package`
4. validate extension in local Extension Development Host (`F5`)
