# Machine Basics

This tutorial now builds on the new guide stack:

1. Start with `docs/tutorials/plugin-start.md`.
2. Add recipes and machines via DSL (`recipes {}`, `machines {}`).
3. Drive machine logic in `systems {}` tick handlers.
4. Persist machine state using `ctx.storage` (see `docs/tutorials/persistence.md`).
5. Validate reload behavior (see `docs/tutorials/reload-safe-coding.md`).

Quick reminder:

- keep tick logic cheap
- avoid blocking IO in machine tick handlers
- use deterministic IDs for machine systems
