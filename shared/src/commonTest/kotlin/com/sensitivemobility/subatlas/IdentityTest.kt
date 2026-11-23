package com.sensitivemobility.subatlas

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class IdentityTest {
    private val uuidShape = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")

    private fun legacyStorage(): MemoryStorage = MemoryStorage().apply {
        // Données d'avant la synchronisation : ni uuid ni updatedAt.
        write("sites.json", """[{"id":1,"name":"Tobia Arbaa","country":"Egypt"}]""")
        write("dives.json", """[{"id":10,"siteId":1,"date":"2026-11-01","startTime":"09:00"},{"id":11,"siteId":1,"date":"2026-11-02"}]""")
    }

    @Test
    fun existingDataGetsStableUuidOnce() {
        val storage = legacyStorage()
        val first = AppState(storage, "fr")
        val uuids = first.dives.map { it.uuid } + first.sites.map { it.uuid }
        assertTrue(uuids.all { uuidShape.matches(it) }, "uuid v4 attendu : $uuids")
        assertEquals(uuids.size, uuids.toSet().size)
        assertTrue(first.dives.all { it.updatedAt.isNotBlank() } && first.sites.all { it.updatedAt.isNotBlank() })

        // Une seconde lecture des mêmes fichiers redonne les MÊMES uuid (ils ont été enregistrés).
        val second = AppState(storage, "fr")
        assertEquals(uuids, second.dives.map { it.uuid } + second.sites.map { it.uuid })
    }

    @Test
    fun saveKeepsUuidWhenFormRebuildsEntityAndTouchesUpdatedAt() {
        val state = AppState(legacyStorage(), "fr")
        state.saveDive(state.dive(10)!!.copy(updatedAt = "2026-01-01T00:00:00Z"), touch = false)
        val before = state.dive(10)!!
        // Le formulaire reconstruit la plongée sans uuid.
        state.saveDive(Dive(id = 10, siteId = 1, date = "2026-11-01", maxDepthMeters = 20.0))
        val after = state.dive(10)!!
        assertEquals(before.uuid, after.uuid)
        assertNotEquals(before.updatedAt, after.updatedAt)
    }

    @Test
    fun syncWritesCanKeepTheirOwnUpdatedAt() {
        val state = AppState(legacyStorage(), "fr")
        val d = state.dive(10)!!.copy(updatedAt = "2026-01-01T00:00:00Z")
        state.saveDive(d, touch = false)
        assertEquals("2026-01-01T00:00:00Z", state.dive(10)!!.updatedAt)
    }

    @Test
    fun newDiveAndSiteGetUuid() {
        val state = AppState(MemoryStorage(), "fr")
        state.saveSite(DiveSite(id = 5, name = "Site"))
        state.saveDive(Dive(id = 6, siteId = 5, date = "2026-11-03"))
        assertTrue(uuidShape.matches(state.site(5)!!.uuid))
        assertTrue(uuidShape.matches(state.dive(6)!!.uuid))
    }

    @Test
    fun mergingSitesKeepsDiveUuidAndTouchesMovedDives() {
        val state = AppState(legacyStorage(), "fr")
        state.saveSite(DiveSite(id = 2, name = "Tobia Arbaa 2"))
        val diveUuid = state.dive(11)!!.uuid
        state.saveDive(state.dive(11)!!.copy(updatedAt = "2026-01-01T00:00:00Z"), touch = false)
        val before = state.dive(11)!!.updatedAt
        state.mergeSites(keeperId = 2, otherIds = setOf(1))
        assertEquals(diveUuid, state.dive(11)!!.uuid)
        assertEquals(2L, state.dive(11)!!.siteId)
        assertNotEquals(before, state.dive(11)!!.updatedAt)
    }

    @Test
    fun backupRoundTripKeepsUuidAndUpdatedAt() {
        val state = AppState(legacyStorage(), "fr")
        val data = BackupCodec.decode(state.exportJson())
        assertEquals(state.dives.toList(), data.dives)
        assertEquals(state.sites.toList(), data.sites)

        val other = AppState(MemoryStorage(), "fr")
        other.importData(data, replace = true)
        assertEquals(state.dives.map { it.uuid to it.updatedAt }, other.dives.map { it.uuid to it.updatedAt })
    }

    @Test
    fun oldBackupWithoutUuidKeepsExistingUuidOnMergeImport() {
        val state = AppState(legacyStorage(), "fr")
        val diveUuid = state.dive(10)!!.uuid
        val old = BackupCodec.decode("""{"format":"subatlas-backup","version":1,"sites":[],"dives":[{"id":10,"siteId":1,"date":"2026-11-01","notes":"x"}]}""")
        state.importData(old, replace = false)
        assertEquals(diveUuid, state.dive(10)!!.uuid)
        assertEquals("x", state.dive(10)!!.notes)
    }

    @Test
    fun sharedDiveFileDoesNotCarryIdentity() {
        val state = AppState(legacyStorage(), "fr")
        val dive = state.dive(10)!!
        val text = DiveFileCodec.encode(dive, state.site(1)!!)
        assertTrue(dive.uuid !in text)
        val received = DiveFileCodec.decode(text.encodeToByteArray())!!
        assertEquals("", received.dive.uuid)
        val newId = state.addDiveFile(received)
        assertNotEquals(dive.uuid, state.dive(newId)!!.uuid)
    }
}
