package com.gigasoft.runtime

import com.gigasoft.api.AdapterInvocation
import com.gigasoft.api.AdapterResponse
import com.gigasoft.api.GigaAdapterPostInvokeEvent
import com.gigasoft.api.GigaAdapterPreInvokeEvent
import com.gigasoft.api.ModAdapter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RuntimeModAdapterRegistryTest {
    @Test
    fun `duplicate adapter ids are rejected`() {
        val registry = RuntimeModAdapterRegistry("demo", logger())
        registry.register(adapter("bridge"))

        assertFailsWith<IllegalArgumentException> {
            registry.register(adapter("bridge"))
        }
    }

    @Test
    fun `invoke delegates to adapter`() {
        val registry = RuntimeModAdapterRegistry("demo", logger())
        registry.register(
            adapter("bridge") { invocation ->
                AdapterResponse(
                    success = invocation.action == "ping",
                    payload = mapOf("echo" to invocation.action)
                )
            }
        )

        val result = registry.invoke("bridge", AdapterInvocation("ping"))
        assertTrue(result.success)
        assertEquals("ping", result.payload["echo"])
    }

    @Test
    fun `invoke wraps adapter failures`() {
        val registry = RuntimeModAdapterRegistry("demo", logger())
        registry.register(
            adapter("broken") {
                error("boom")
            }
        )

        val result = registry.invoke("broken", AdapterInvocation("x"))
        assertFalse(result.success)
        assertTrue(result.message?.contains("failed") == true)
    }

    @Test
    fun `invoke rejects invalid action`() {
        val registry = RuntimeModAdapterRegistry("demo", logger())
        registry.register(adapter("bridge"))

        val result = registry.invoke("bridge", AdapterInvocation("bad action"))
        assertFalse(result.success)
        assertTrue(result.message?.contains("Invalid adapter action") == true)
    }

    @Test
    fun `invoke rejects missing capability`() {
        val registry = RuntimeModAdapterRegistry("demo", logger())
        registry.register(adapter("bridge"))

        val result = registry.invoke(
            "bridge",
            AdapterInvocation(
                action = "ping",
                payload = mapOf("required_capability" to "admin")
            )
        )
        assertFalse(result.success)
        assertTrue(result.message?.contains("does not provide capability") == true)
    }

    @Test
    fun `invoke times out slow adapters`() {
        val registry = RuntimeModAdapterRegistry("demo", logger())
        registry.register(
            adapter("slow") {
                Thread.sleep(400)
                AdapterResponse(success = true)
            }
        )

        val result = registry.invoke("slow", AdapterInvocation("ping"))
        assertFalse(result.success)
        assertTrue(result.message?.contains("timed out") == true)
    }

    @Test
    fun `security config applies custom timeout`() {
        val registry = RuntimeModAdapterRegistry(
            pluginId = "demo",
            logger = logger(),
            securityConfig = AdapterSecurityConfig(invocationTimeoutMillis = 50L)
        )
        registry.register(
            adapter("slow") {
                Thread.sleep(120)
                AdapterResponse(success = true)
            }
        )

        val result = registry.invoke("slow", AdapterInvocation("ping"))
        assertFalse(result.success)
        assertTrue(result.message?.contains("50ms") == true)
    }

    @Test
    fun `security config applies custom rate limit`() {
        val registry = RuntimeModAdapterRegistry(
            pluginId = "demo",
            logger = logger(),
            securityConfig = AdapterSecurityConfig(maxCallsPerMinute = 1)
        )
        registry.register(adapter("bridge"))

        assertTrue(registry.invoke("bridge", AdapterInvocation("ping")).success)
        val second = registry.invoke("bridge", AdapterInvocation("ping"))
        assertFalse(second.success)
        assertTrue(second.message?.contains("rate limit exceeded") == true)
    }

    @Test
    fun `security config applies per plugin quota across adapters`() {
        val registry = RuntimeModAdapterRegistry(
            pluginId = "demo",
            logger = logger(),
            securityConfig = AdapterSecurityConfig(
                maxCallsPerMinute = 10,
                maxCallsPerMinutePerPlugin = 1
            )
        )
        registry.register(adapter("bridge-a"))
        registry.register(adapter("bridge-b"))

        assertTrue(registry.invoke("bridge-a", AdapterInvocation("ping")).success)
        val second = registry.invoke("bridge-b", AdapterInvocation("ping"))
        assertFalse(second.success)
        assertTrue(second.message?.contains("rate limit exceeded") == true)
    }

    @Test
    fun `security config enforces per adapter concurrency limit`() {
        val begin = CountDownLatch(1)
        val release = CountDownLatch(1)
        val registry = RuntimeModAdapterRegistry(
            pluginId = "demo",
            logger = logger(),
            securityConfig = AdapterSecurityConfig(
                maxConcurrentInvocationsPerAdapter = 1,
                invocationTimeoutMillis = 2_000L
            )
        )
        registry.register(
            adapter("slow") {
                begin.countDown()
                release.await()
                AdapterResponse(success = true)
            }
        )

        val t1 = Thread {
            registry.invoke("slow", AdapterInvocation("ping"))
        }
        t1.start()
        begin.await()
        val second = registry.invoke("slow", AdapterInvocation("ping"))
        assertFalse(second.success)
        assertTrue(second.message?.contains("concurrency limit exceeded") == true)
        release.countDown()
        t1.join()
    }

    @Test
    fun `audit logs are emitted for denied and timeout outcomes`() {
        val logs = CopyOnWriteArrayList<String>()
        val registry = RuntimeModAdapterRegistry(
            pluginId = "demo",
            logger = { msg -> logs += msg },
            securityConfig = AdapterSecurityConfig(
                maxCallsPerMinute = 1,
                invocationTimeoutMillis = 20L,
                auditLogEnabled = true
            )
        )
        registry.register(
            adapter("slow") {
                Thread.sleep(120)
                AdapterResponse(success = true)
            }
        )

        val first = registry.invoke("slow", AdapterInvocation("ping"))
        assertFalse(first.success)
        val second = registry.invoke("slow", AdapterInvocation("ping"))
        assertFalse(second.success)

        assertTrue(logs.any { it.contains("[adapter-audit]") && it.contains("outcome=TIMEOUT") })
        assertTrue(logs.any { it.contains("[adapter-audit]") && it.contains("outcome=DENIED") })
    }

    @Test
    fun `fast mode bypasses rate limit and timeout`() {
        val registry = RuntimeModAdapterRegistry(
            pluginId = "demo",
            logger = logger(),
            securityConfig = AdapterSecurityConfig(
                maxCallsPerMinute = 0,
                invocationTimeoutMillis = 0L,
                executionMode = AdapterExecutionMode.FAST
            )
        )
        registry.register(
            adapter("slow") {
                Thread.sleep(20)
                AdapterResponse(success = true)
            }
        )

        val first = registry.invoke("slow", AdapterInvocation("ping"))
        val second = registry.invoke("slow", AdapterInvocation("ping"))
        assertTrue(first.success)
        assertTrue(second.success)
    }

    @Test
    fun `adapter pre event can cancel invocation`() {
        val bus = RuntimeEventBus(mode = EventDispatchMode.EXACT)
        val registry = RuntimeModAdapterRegistry(
            pluginId = "demo",
            logger = logger(),
            eventBus = bus
        )
        registry.register(adapter("bridge"))
        bus.subscribe(GigaAdapterPreInvokeEvent::class.java) {
            it.cancelled = true
            it.cancelReason = "adapter disabled by policy"
        }

        val result = registry.invoke("bridge", AdapterInvocation("ping"))
        assertFalse(result.success)
        assertTrue(result.message?.contains("disabled by policy") == true)
    }

    @Test
    fun `adapter post event is published`() {
        val bus = RuntimeEventBus(mode = EventDispatchMode.EXACT)
        val registry = RuntimeModAdapterRegistry(
            pluginId = "demo",
            logger = logger(),
            eventBus = bus
        )
        registry.register(adapter("bridge"))
        var post: GigaAdapterPostInvokeEvent? = null
        bus.subscribe(GigaAdapterPostInvokeEvent::class.java) { post = it }

        val result = registry.invoke("bridge", AdapterInvocation("ping"))
        assertTrue(result.success)
        assertEquals("bridge", post?.adapterId)
        assertEquals("ACCEPTED", post?.outcome)
    }

    @Test
    fun `adapter invocation can be restricted per adapter id`() {
        val registry = RuntimeModAdapterRegistry(
            pluginId = "demo",
            logger = logger(),
            rawPermissions = listOf("adapter.invoke.bridge")
        )
        registry.register(adapter("bridge"))
        registry.register(adapter("other"))

        val allowed = registry.invoke("bridge", AdapterInvocation("ping"))
        val denied = registry.invoke("other", AdapterInvocation("ping"))
        assertTrue(allowed.success)
        assertFalse(denied.success)
        assertTrue(denied.message?.contains("not allowed to invoke adapter") == true)
    }

    @Test
    fun `adapter capability can be restricted by plugin permissions`() {
        val registry = RuntimeModAdapterRegistry(
            pluginId = "demo",
            logger = logger(),
            rawPermissions = listOf("adapter.capability.read", "adapter.invoke.*")
        )
        registry.register(
            object : ModAdapter {
                override val id: String = "bridge"
                override val name: String = "Bridge"
                override val version: String = "1.0.0"
                override val capabilities: Set<String> = setOf("read", "write")
                override fun invoke(invocation: AdapterInvocation): AdapterResponse = AdapterResponse(success = true)
            }
        )

        val ok = registry.invoke("bridge", AdapterInvocation("ping", mapOf("required_capability" to "read")))
        val denied = registry.invoke("bridge", AdapterInvocation("ping", mapOf("required_capability" to "write")))
        assertTrue(ok.success)
        assertFalse(denied.success)
        assertTrue(denied.message?.contains("not allowed to request capability") == true)
    }

    private fun logger() = com.gigasoft.api.GigaLogger { }

    private fun adapter(
        id: String,
        handler: (AdapterInvocation) -> AdapterResponse = { AdapterResponse(success = true) }
    ): ModAdapter {
        return object : ModAdapter {
            override val id: String = id
            override val name: String = "Adapter-$id"
            override val version: String = "1.0.0"
            override val capabilities: Set<String> = emptySet()

            override fun invoke(invocation: AdapterInvocation): AdapterResponse = handler(invocation)
        }
    }
}
