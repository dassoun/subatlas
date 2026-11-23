package com.sensitivemobility.subatlas

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncStateTest {
    @Test
    fun stateIsPersistedInItsOwnFileAndReloaded() {
        val storage = MemoryStorage()
        val store = SyncStore(storage)
        val id = store.deviceId()
        assertEquals(id, store.deviceId())
        store.update {
            it.copy(
                server = "http://10.0.2.2:8080", account = "julien", cursor = "abc==",
                dives = mapOf("u1" to SyncedDive("2026-11-23T10:15:00Z", "2026-11-23T10:14:00Z", JsonObject(mapOf("max_depth_m" to JsonPrimitive(20.5))), "h")),
                photos = mapOf("p1" to SyncedPhoto("s1", "u1")),
                tombstones = mapOf("u2" to Tombstone("2026-11-23T11:00:00Z")),
            )
        }
        assertEquals(setOf(SyncStore.FILE), storage.files.keys)

        val reloaded = SyncStore(storage).state
        assertEquals(store.state, reloaded)
        assertTrue(reloaded.connected)
        assertEquals(id, reloaded.deviceId)
    }

    @Test
    fun corruptedFileFallsBackToEmptyState() {
        val storage = MemoryStorage().apply { write(SyncStore.FILE, "{pas du json") }
        assertFalse(SyncStore(storage).state.connected)
    }

    @Test
    fun syncStateNeverEndsUpInTheBackup() {
        val storage = MemoryStorage()
        SyncStore(storage).update { it.copy(server = "https://subatlas.com", account = "julien", cursor = "c") }
        val backup = AppState(storage, "fr").exportJson()
        assertFalse("cursor" in backup || "subatlas.com" in backup || "julien" in backup)
    }
}
