# Changelog

## 0.3.0

- Updated plugin template to use modern adapter payload helpers (`payloadRequired`, `payloadIntRequired`).
- Updated template model DSL usage with extended model fields (`material`, `scale`, `collision`).
- Updated asset README template with `ModelLod` example.
- Improved manifest validation:
  - duplicate host permission detection,
  - plugin id/version format checks,
  - dependency entry format warnings.
- Added manifest Quick Fixes for common issues (duplicate permissions, invalid id/version, missing required keys).
- Expanded Kotlin snippets:
  - richer adapter helper usage,
  - extended texture/model snippet (bounds, lods, animations),
  - new typed event subscription snippet,
  - new cancelable gameplay policy snippet.
- Added explicit VSIX packaging scripts (`package`, `package:pre`, `publish:check`) for release workflow.

## 0.2.0

- Added `GigaSoft: Create Texture/Model Asset Template` command.
- Expanded manifest permission validation for latest host permission set.
- Improved manifest permission parsing to support YAML list style (`- permission`).
- Added Kotlin snippet for `textures {}` / `models {}` API usage.
- Updated YAML snippet to use list-style permissions by default.

## 0.1.0

- Initial VS Code extension scaffold for GigaSoft plugins.
- Added template generation command.
- Added gigaplugin manifest validation command.
- Added release update check command (GitHub latest release).
- Added Kotlin and YAML snippets.
