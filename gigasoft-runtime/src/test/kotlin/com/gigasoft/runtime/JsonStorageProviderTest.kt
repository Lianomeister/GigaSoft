package com.gigasoft.runtime

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
