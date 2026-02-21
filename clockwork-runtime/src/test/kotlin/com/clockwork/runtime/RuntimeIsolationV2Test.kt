package com.clockwork.runtime

import com.clockwork.api.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RuntimeIsolationV2Test {
    @Test
    fun `storage deny by default when filesystem allowlist is empty`() {
        val delegate = RecordingStorageProvider()
        val policy = RuntimeIsolationPolicy(
            enabled = true,
            capabilities = setOf(RuntimeCapability.FILESYSTEM),
            filesystemAllowlist = emptyList(),
            networkProtocolAllowlist = emptyList(),
            networkHostAllowlist = emptyList(),
            networkPathAllowlist = emptyList(),
            commandAllowlist = emptyList()
        )
        val eventBus = RuntimeEventBus()
        val auditor = RuntimeIsolationAuditor()
        val storage = RuntimeIsolatedStorageProvider(
            delegate = delegate,
            pluginId = "demo",
            policy = policy,
            logger = GigaLogger { },
            auditor = auditor,
            eventBus = eventBus
        )

        val denied = storage.store<StringState>("state.main")
        denied.save(StringState("x"))
        assertNull(denied.load())
        assertEquals(0, delegate.savedCount)
        assertTrue(auditor.snapshotByPlugin()["demo"]?.any { it.code == IsolationDiagnosticCodes.FILESYSTEM_PATH_DENIED } == true)
    }

    @Test
    fun `commands require commands capability and allowlist`() {
        val delegate = RuntimeCommandRegistry("demo")
        val policy = RuntimeIsolationPolicy(
            enabled = true,
            capabilities = setOf(RuntimeCapability.COMMANDS),
            filesystemAllowlist = emptyList(),
            networkProtocolAllowlist = emptyList(),
            networkHostAllowlist = emptyList(),
            networkPathAllowlist = emptyList(),
            commandAllowlist = listOf("allowed-*")
        )
        val eventBus = RuntimeEventBus()
        val auditor = RuntimeIsolationAuditor()
        val commands = RuntimeIsolatedCommandRegistry(
            delegate = delegate,
            pluginId = "demo",
            policy = policy,
            logger = GigaLogger { },
            auditor = auditor,
            eventBus = eventBus
        )

        assertFailsWith<IllegalStateException> {
            commands.registerSpec(CommandSpec(command = "blocked")) { CommandResult.ok("x") }
        }
        commands.registerSpec(CommandSpec(command = "allowed-ping")) { CommandResult.ok("pong") }
        assertTrue(commands.registeredCommands().containsKey("allowed-ping"))
        assertTrue(auditor.snapshotByPlugin()["demo"]?.any { it.code == IsolationDiagnosticCodes.COMMAND_PATH_DENIED } == true)
    }

    @Test
    fun `plugin network requires network capability`() {
        val hub = RuntimePluginNetworkHub()
        val raw = hub.viewFor("demo")
        val policy = RuntimeIsolationPolicy(
            enabled = true,
            capabilities = emptySet(),
            filesystemAllowlist = emptyList(),
            networkProtocolAllowlist = emptyList(),
            networkHostAllowlist = emptyList(),
            networkPathAllowlist = emptyList(),
            commandAllowlist = emptyList()
        )
        val eventBus = RuntimeEventBus()
        val auditor = RuntimeIsolationAuditor()
        val network = RuntimeIsolatedPluginNetwork(
            delegate = raw,
            pluginId = "demo",
            policy = policy,
            logger = GigaLogger { },
            auditor = auditor,
            eventBus = eventBus
        )

        val ok = network.registerChannel(PluginChannelSpec(id = "demo:chat"))
        val result = network.send("demo:chat", PluginMessage(channel = "demo:chat", payload = mapOf("x" to "1")))
        assertFalse(ok)
        assertEquals(PluginMessageStatus.DENIED, result.status)
        assertTrue(result.reason?.contains(IsolationDiagnosticCodes.NETWORK_CAPABILITY_REQUIRED) == true)
        assertTrue(auditor.snapshotByPlugin()["demo"]?.any { it.code == IsolationDiagnosticCodes.NETWORK_CAPABILITY_REQUIRED } == true)
    }

    @Test
    fun `host network allowlist blocks disallowed hosts`() {
        val host = object : HostAccess by HostAccess.unavailable() {
            override fun httpGet(url: String, connectTimeoutMillis: Int, readTimeoutMillis: Int, maxBodyChars: Int): HostHttpResponse? {
                return HostHttpResponse(success = true, statusCode = 200, body = "ok")
            }
        }
        val policy = RuntimeIsolationPolicy(
            enabled = true,
            capabilities = setOf(RuntimeCapability.NETWORK),
            filesystemAllowlist = emptyList(),
            networkProtocolAllowlist = listOf("https"),
            networkHostAllowlist = listOf("api.example.com"),
            networkPathAllowlist = listOf("/v1/*"),
            commandAllowlist = emptyList()
        )
        val auditor = RuntimeIsolationAuditor()
        val access = RuntimeHostAccess(
            delegate = host,
            pluginId = "demo",
            rawPermissions = listOf(HostPermissions.INTERNET_HTTP_GET),
            logger = GigaLogger { },
            isolationPolicy = policy,
            isolationAuditor = auditor,
            eventBus = RuntimeEventBus()
        )

        assertNull(access.httpGet("https://evil.example.net/v1/data"))
        assertNull(access.httpGet("https://api.example.com/v2/data"))
        assertTrue(auditor.snapshotByPlugin()["demo"]?.any { it.code == IsolationDiagnosticCodes.NETWORK_HOST_DENIED } == true)
        assertTrue(auditor.snapshotByPlugin()["demo"]?.any { it.code == IsolationDiagnosticCodes.NETWORK_PATH_DENIED } == true)
    }

    private data class StringState(val value: String)

    private class RecordingStorageProvider : StorageProvider {
        var savedCount: Int = 0
        private val data = linkedMapOf<String, Any>()

        @Suppress("UNCHECKED_CAST")
        override fun <T : Any> store(key: String, type: Class<T>, version: Int): PersistentStore<T> {
            return object : PersistentStore<T> {
                override fun load(): T? = data[key] as? T
                override fun save(value: T) {
                    data[key] = value
                    savedCount++
                }

                override fun migrate(fromVersion: Int, migration: (T) -> T) {}
            }
        }
    }
}
