# Tutorial: Plugin Network Channels

Goal: exchange plugin messages over stable channels with schema/version and throughput guardrails.

## Register Channel

```kotlin
ctx.registerNetworkChannel(
    PluginChannelSpec(
        id = "demo:chat",
        schemaVersion = 1,
        maxInFlight = 32,
        maxMessagesPerMinute = 300,
        maxPayloadEntries = 16,
        maxPayloadTotalChars = 2048
    )
)
```

## Subscribe

```kotlin
ctx.network.subscribe("demo:chat") { message ->
    val text = message.payload["text"].orEmpty()
    ctx.logger.info("chat[${message.sourcePluginId}]: $text")
}
```

## Send

```kotlin
val result = ctx.sendPluginMessage(
    channel = "demo:chat",
    payload = mapOf("text" to "hello world")
)
if (result.status != PluginMessageStatus.ACCEPTED) {
    ctx.logger.info("message failed: ${result.status} ${result.reason.orEmpty()}")
}
```

## Runtime Notes

- schema mismatches are rejected (`SCHEMA_MISMATCH`)
- in-flight overflow is rejected (`BACKPRESSURE`)
- per-plugin throughput overflow is rejected (`QUOTA_EXCEEDED`)
- monitor with `ctx.network.channelStats(channel)`

