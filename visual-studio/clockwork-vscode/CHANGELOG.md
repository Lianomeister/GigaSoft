# Changelog

## 0.5.0

- Updated extension defaults/templates to `1.5.0-rc.2`.
- Updated manifest diagnostics and quick-fixes to suggest `1.5.0-rc.2`.
- Updated snippets and docs wording to match the 1.5 API surface.
- Kept CommandSpec/Event/Network guidance as first-class snippets for Plugin API UX 2.0.

## 0.4.0

- Added Kotlin diagnostics for plugin code quality:
  - CommandSpec-first command registration recommendation.
  - Event 2.0 subscription options recommendation.
  - adapter capability guard recommendation for adapter invocations.
- Added new code actions / quick fixes:
  - Kotlin template inserts for CommandSpec and EventSubscriptionOptions.
  - manifest API-version fix.
  - manifest source fix-all for common best-practice updates.
- Extended manifest permission validation for 1.2:
  - `host.mutation.batch`
  - `adapter.invoke.*`
  - `adapter.capability.*`
- Expanded snippets:
  - CommandSpec-first command snippet
  - Event 2.0 tracing/options snippet
  - Plugin network channel snippet
  - Updated YAML permissions snippet for 1.2 security scopes.

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

- Added `Clockwork: Create Texture/Model Asset Template` command.
- Expanded manifest permission validation for latest host permission set.
- Improved manifest permission parsing to support YAML list style (`- permission`).
- Added Kotlin snippet for `textures {}` / `models {}` API usage.
- Updated YAML snippet to use list-style permissions by default.

## 0.1.0

- Initial VS Code extension scaffold for Clockwork plugins.
- Added template generation command.
- Added gigaplugin manifest validation command.
- Added release update check command (GitHub latest release).
- Added Kotlin and YAML snippets.

