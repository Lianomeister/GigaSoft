# Tutorial: Plugin Start (from zero to first command)

Goal: build and run a plugin without any hidden assumptions.

## Prerequisites

- JDK 21
- running standalone server project
- access to plugin output jar

## Step 1: Create Plugin Class

```kotlin
package demo

import com.clockwork.api.GigaPlugin
import com.clockwork.api.PluginContext
import com.clockwork.api.gigaPlugin

class DemoPlugin : GigaPlugin {
    private val plugin = gigaPlugin(
        id = "demo",
        name = "Demo Plugin",
        version = "1.0.0",
        apiVersion = "1"
    ) {
        commands {
            command("ping", "returns pong") { _, sender, _ -> "pong from $sender" }
        }
    }

    override fun onEnable(ctx: PluginContext) = plugin.onEnable(ctx)
    override fun onDisable(ctx: PluginContext) = plugin.onDisable(ctx)
}
```

## Step 2: Add Manifest

Create `clockworkplugin.yml` in jar root:

```yaml
id: demo
name: Demo Plugin
version: 1.0.0
main: demo.DemoPlugin
apiVersion: 1
dependencies: []
permissions: []
```

## Step 3: Deploy

1. Copy jar into plugin folder (`dev-runtime/giga-plugins` by default).
2. Start standalone server.
3. Run:
   - `scan`
   - `plugins`
   - `run demo ping`

Expected:

- plugin appears in `plugins`
- `run demo ping` returns pong string

## Step 4: First Diagnostics

Run:

- `status --json`
- `doctor --json`
- `profile demo --json`

This is your baseline before adding features.

## Common Mistakes

- wrong `main` class path
- manifest not included in jar root
- mismatched `id` (manifest id vs command usage)
- `apiVersion` not set to `1`
