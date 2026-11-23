package com.sensitivemobility.subatlas

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SitesExchangeTest {
    private fun state(sitesJson: String = "[]") = AppState(MemoryStorage().apply { write("sites.json", sitesJson) }, "fr")

    private fun fullSite(state: AppState) = state.saveSite(
        DiveSite(
            id = state.newId(), name = "Tobia Arbaa", latitude = 24.9, longitude = 34.9, maxDepthMeters = 30,
            access = SiteAccess.BOAT, notes = "Courant fort", country = "Egypt", type = SiteType.SEA,
        ),
    )

    @Test
    fun roundTripKeepsEveryField() {
        val source = state()
        fullSite(source)
        val original = source.sites.single()

        val text = SitesExchange.encode(source.sites.toList(), "2026-11-23")
        val target = state()
        val r = target.importSites(SitesExchange.decode(text)!!)

        assertEquals(1, r.added)
        val copy = target.sites.single()
        assertEquals(original.copy(id = copy.id, updatedAt = copy.updatedAt), copy)
        assertEquals(30, copy.maxDepthMeters)
        assertEquals(SiteAccess.BOAT, copy.access)
        assertEquals("Courant fort", copy.notes)
        assertEquals(original.uuid, copy.uuid)
    }

    @Test
    fun siteWithoutPositionIsImported() {
        val target = state()
        val r = target.importSites(listOf(ExchangedSite(name = "Sans GPS", maxDepthMeters = 12)))
        assertEquals(1, r.added)
        assertNull(target.sites.single().latitude)
        assertEquals(12, target.sites.single().maxDepthMeters)
    }

    @Test
    fun oldFileWithoutNewFieldsStillReads() {
        val old = """{"format":"subatlas-sites","version":1,"sites":[{"name":"Vieux","country":"France","lat":43.0,"lon":5.0,"type":"SEA"}]}"""
        val sites = assertNotNull(SitesExchange.decode(old))
        val target = state()
        target.importSites(sites)
        val s = target.sites.single()
        assertEquals(SiteAccess.SHORE, s.access)
        assertNull(s.maxDepthMeters)
        assertEquals("", s.notes)
    }

    @Test
    fun byteOrderMarkIsAccepted() {
        val text = SitesExchange.encode(emptyList(), "2026-11-23")
        assertNotNull(SitesExchange.decode("﻿$text"))
    }

    @Test
    fun knownSiteOnlyGetsWhatIsMissing() {
        val target = state()
        target.saveSite(DiveSite(id = target.newId(), name = "Tobia Arbaa", latitude = 1.0, longitude = 2.0, notes = "à moi", access = SiteAccess.SHORE))
        val r = target.importSites(
            listOf(
                ExchangedSite(
                    name = "tobia arbaa", lat = 9.0, lon = 9.0, maxDepthMeters = 30, access = SiteAccess.BOAT,
                    notes = "autre", country = "Egypt", type = SiteType.SEA,
                ),
            ),
        )
        assertEquals(1, r.updated)
        val s = target.sites.single()
        assertEquals(1.0, s.latitude)
        assertEquals("à moi", s.notes)
        assertEquals(SiteAccess.SHORE, s.access)
        assertEquals(30, s.maxDepthMeters)
        assertEquals("Egypt", s.country)
        assertEquals(SiteType.SEA, s.type)

        // Un second import ne change plus rien.
        assertEquals(1, target.importSites(listOf(ExchangedSite(name = "Tobia Arbaa", maxDepthMeters = 99))).unchanged)
    }

    @Test
    fun uuidMatchesRenamedSiteAndIsNotDuplicated() {
        val target = state()
        fullSite(target)
        val known = target.sites.single()
        val r = target.importSites(listOf(ExchangedSite(name = "Autre nom", uuid = known.uuid, maxDepthMeters = 55)))
        assertEquals(0, r.added)
        assertEquals(1, target.sites.size)
        assertEquals(30, target.sites.single().maxDepthMeters)
    }

    @Test
    fun importedUuidIsNotAdoptedWhenAlreadyUsed() {
        val target = state()
        fullSite(target)
        val taken = target.sites.single().uuid
        // Même identifiant mais le site local est reconnu par uuid : pas de doublon.
        target.importSites(listOf(ExchangedSite(name = "Nouveau site", lat = 1.0, lon = 1.0, uuid = "autre-uuid")))
        assertEquals(2, target.sites.size)
        assertEquals(setOf(taken, "autre-uuid"), target.sites.map { it.uuid }.toSet())
    }

    @Test
    fun blankNameIsIgnored() {
        val r = state().importSites(listOf(ExchangedSite(name = " ")))
        assertEquals(1, r.ignored)
    }
}
