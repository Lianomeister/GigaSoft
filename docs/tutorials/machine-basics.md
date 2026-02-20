# Machine Basics

1. Register inputs/outputs in `items {}`.
2. Register a process recipe in `recipes {}`.
3. Implement `MachineBehavior` and register with `machines {}`.
4. Use a `system("name")` tick handler to drive machine behavior.
5. Persist production metrics with `ctx.storage.store(...)`.
