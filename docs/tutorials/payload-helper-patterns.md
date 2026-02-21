# Tutorial: Payload Helper Patterns

Goal: parse adapter payloads safely with minimal boilerplate.

## Why helpers matter

Helpers remove repetitive parsing/validation and make adapter handlers predictable:

- fewer null/format bugs
- clear required vs optional inputs
- easier error messages for callers

## Required + Optional Pattern

```kotlin
adapters {
    adapter(id = "economy.transfer", name = "Economy Transfer") { invocation ->
        val from = invocation.payloadRequired("from")
        val to = invocation.payloadRequired("to")
        val amount = invocation.payloadIntRequired("amount")
        val dryRun = invocation.payloadBool("dryRun") ?: false

        if (amount <= 0) {
            return@adapter AdapterResponse(success = false, message = "amount must be > 0")
        }

        AdapterResponse(
            success = true,
            payload = mapOf(
                "from" to from,
                "to" to to,
                "amount" to amount.toString(),
                "dryRun" to dryRun.toString()
            )
        )
    }
}
```

## Enum + CSV Pattern

```kotlin
enum class SpawnMode { SAFE, FAST }

val mode = invocation.payloadEnum<SpawnMode>("mode") ?: SpawnMode.SAFE
val tags = invocation.payloadCsv("tags") // "a,b,c" -> ["a","b","c"]
```

## Prefix Map Pattern

```kotlin
val worldConfig = invocation.payloadByPrefix("world.")
// world.seed=42, world.weather=rain -> { "seed": "42", "weather": "rain" }
```

## Recommended Failure Style

- validation errors: `AdapterResponse(success = false, message = "...")`
- never throw for user input in normal flow
- reserve thrown exceptions for internal runtime failures

