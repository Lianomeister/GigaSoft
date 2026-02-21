# GigaSoft Plugin Tools (VS Code)

VS Code extension for GigaSoft plugin development.

## Features

- `GigaSoft: Create Plugin Template`:
  - creates `src/main/kotlin/.../MainPlugin.kt`
  - creates `gigaplugin.yml`
  - includes modern adapter payload helper usage (`payloadRequired`, `payloadIntRequired`)
  - includes updated model DSL fields (`material`, `scale`, `collision`)
- `GigaSoft: Create Texture/Model Asset Template`:
  - creates `src/main/resources/assets/<plugin-id>/...` scaffold
  - adds example model json and asset README
- `GigaSoft: Validate gigaplugin.yml`:
  - checks required keys (`id`, `name`, `version`, `main`, `apiVersion`)
  - warns for unknown host permissions
  - warns for duplicate permissions
  - warns for non-standard plugin id and version format
  - validates inline dependency entries for common formatting errors
  - provides Quick Fixes for:
    - removing duplicate permission entries
    - normalizing plugin id
    - setting version to a valid default
    - inserting missing required keys
  - supports both inline (`permissions: [a, b]`) and list style:
    - `permissions:`
    - `  - host.server.read`
- `GigaSoft: Check Extension Updates`:
  - checks latest release from GitHub repo (`Lianomeister/GigaSoft`)

## Snippets

- Kotlin snippets for plugin class, command result, adapter handler, typed event subscription, and texture/model DSL blocks
- YAML snippet for `gigaplugin.yml`

Updated Kotlin snippets include:

- advanced payload helpers: `payloadCsv`, `payloadEnum`, `payloadByPrefix`, and required numeric/bool helpers
- extended model options: `ModelBounds`, `ModelLod`, `material`, `doubleSided`, `scale`, `collision`, `animations`
- gameplay pre/post policy snippet for cancelable events

## Settings

- `gigasoft.plugin.defaultId`
- `gigasoft.plugin.defaultMainClass`
- `gigasoft.plugin.defaultVersion`
- `gigasoft.update.checkOwner`
- `gigasoft.update.checkRepo`

## Local Development

```bash
cd visual-studio/gigasoft-vscode
code .
```

Press `F5` in VS Code to launch Extension Development Host.

## Packaging and Release

Build a VSIX package:

```bash
cd visual-studio/gigasoft-vscode
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
