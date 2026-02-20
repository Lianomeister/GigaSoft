package com.gigasoft.runtime

import com.gigasoft.api.AdapterInvocation
import com.gigasoft.api.AdapterResponse
import com.gigasoft.api.ModAdapter
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
