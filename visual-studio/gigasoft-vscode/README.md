# GigaSoft Plugin Tools (VS Code)

VS Code extension for GigaSoft plugin development.

## Features

- `GigaSoft: Create Plugin Template`:
  - creates `src/main/kotlin/.../MainPlugin.kt`
  - creates `gigaplugin.yml`
- `GigaSoft: Validate gigaplugin.yml`:
  - checks required keys (`id`, `name`, `version`, `main`, `apiVersion`)
  - warns for unknown host permissions
- `GigaSoft: Check Extension Updates`:
  - checks latest release from GitHub repo (`Lianomeister/GigaSoft`)

## Snippets

- Kotlin snippets for plugin class, command result, adapter handler
- YAML snippet for `gigaplugin.yml`

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
