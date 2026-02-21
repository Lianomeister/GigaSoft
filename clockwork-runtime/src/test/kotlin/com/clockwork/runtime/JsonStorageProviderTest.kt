package com.clockwork.runtime

import java.nio.file.Files
import kotlin.test.assertFailsWith
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JsonStorageProviderTest {
    data class Payload(var value: Int = 0)

    @Test
    fun `store save load and migrate`() {
        val dir = Files.createTempDirectory("giga-storage-test")
        val storage = JsonStorageProvider(dir)
        val v1 = storage.store("payload", Payload::class.java, version = 1)
        v1.save(Payload(5))
        val store = storage.store("payload", Payload::class.java, version = 2)

        val loaded = store.load()
        assertEquals(5, loaded?.value)

        store.migrate(1) { old -> old.copy(value = old.value + 1) }
        assertEquals(6, store.load()?.value)
    }

    @Test
    fun `load restores from backup when primary checksum is corrupted`() {
        val dir = Files.createTempDirectory("giga-storage-recovery")
        val storage = JsonStorageProvider(dir)
        val store = storage.store("payload", Payload::class.java, version = 1)

        store.save(Payload(1))
        store.save(Payload(2)) // creates backup of previous state

        Files.writeString(dir.resolve("payload.sha256"), "bad-checksum")
        val recovered = store.load()
        assertEquals(1, recovered?.value)
    }

    @Test
    fun `load fails when primary and backup checksums are both invalid`() {
        val dir = Files.createTempDirectory("giga-storage-corrupt")
        val storage = JsonStorageProvider(dir)
        val store = storage.store("payload", Payload::class.java, version = 1)

        store.save(Payload(9))
        store.save(Payload(10))

        Files.writeString(dir.resolve("payload.sha256"), "broken")
        Files.writeString(dir.resolve("payload.sha256.bak"), "broken")

        val error = assertFailsWith<IllegalStateException> { store.load() }
        assertTrue(error.message.orEmpty().contains("Corrupted store"))
    }
}
