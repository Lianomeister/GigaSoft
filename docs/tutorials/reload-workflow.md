# Reload Workflow

1. Build new plugin jar.
2. Replace jar in your plugin directory (default: `dev-runtime/giga-plugins`).
3. Use hot-reload commands:
   - `sync` (loads new jars and reloads changed jars automatically)
   - `reload changed` (reload only plugins whose jar changed, including dependents)
   - `reload <plugin-id>` or `reload all` for explicit control.
4. Verify:
   - `plugins`
   - `doctor --json`
   - `profile <plugin-id> --json`
   - `status --json`

If behavior changes unexpectedly, use:

- `docs/tutorials/reload-safe-coding.md`
- `docs/troubleshooting/plugin-debug-playbook.md`
