package com.sensitivemobility.subatlas

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncMappingTest {
    private fun dive(block: Dive.() -> Dive = { this }) = Dive(id = 1, siteId = 7, date = "2026-11-01", startTime = "09:05", endTime = "09:45", maxDepthMeters = 23.4).block()
    private fun fields(d: Dive, site: String? = "site-1") = SyncMapping.diveFields(d, site)

    @Test
    fun startedAtIsLocalTimeWithoutOffsetAndUnknownTimeBecomesNoon() {
        assertEquals("2026-11-01T09:05:00", fields(dive()).str("started_at"))
        assertEquals("2026-11-01T12:00:00", fields(dive { copy(startTime = null, endTime = null) }).str("started_at"))
    }

    @Test
    fun durationComesFromTimesThenFromTheProfileThenIsOmitted() {
        assertEquals(2400.0, fields(dive()).dbl("duration_seconds"))
        // Passage de minuit.
        assertEquals(3600.0, fields(dive { copy(startTime = "23:30", endTime = "00:30") }).dbl("duration_seconds"))
        val profile = DiveProfile(intervalSeconds = 10, depthDm = List(31) { 10 })
        assertEquals(300.0, fields(dive { copy(startTime = null, endTime = null, profile = profile) }).dbl("duration_seconds"))
        assertNull(fields(dive { copy(startTime = null, endTime = null) }).dbl("duration_seconds"))
        // Début et fin identiques : 0 min est invalide, on retombe sur la courbe, sinon rien.
        assertNull(fields(dive { copy(endTime = "09:05") }).dbl("duration_seconds"))
    }

    @Test
    fun outOfRangeValuesAreOmittedInsteadOfBeingRefused() {
        val d = dive { copy(maxDepthMeters = 900.0, waterTempC = 60.0, weightKg = 120.0, visibilityMeters = 500, suitThicknessMm = 20.0) }
        val sent = SyncMapping.withoutNulls(fields(d))
        listOf("max_depth_m", "water_temp_c", "weight_kg", "visibility_m", "suit_thickness_mm").forEach { assertFalse(it in sent, it) }
        assertTrue("started_at" in sent)
    }

    @Test
    fun purposePeopleAndTanks() {
        val d = dive {
            copy(
                kind = DiveKind.TEACHING, buddy = "Bob", group = listOf(Person("Alice"), Person("Zoé")),
                tanks = listOf(Tank(), Tank(volumeLiters = 12.0, o2Percent = 32, pressureStartBar = 200, pressureEndBar = 60, usedMinutes = 40)),
            )
        }
        val f = fields(d)
        assertEquals("teaching", f.str("purpose"))
        val people = (f["people"] as kotlinx.serialization.json.JsonArray).map { it as JsonObject }
        assertEquals(listOf("Bob" to "buddy", "Alice" to "student", "Zoé" to "student"), people.map { it.str("name")!! to it.str("role")!! })
        val tanks = (f["tanks"] as kotlinx.serialization.json.JsonArray).map { it as JsonObject }
        assertEquals(1, tanks.size, "la bouteille entièrement vide est ignorée")
        assertEquals("bottom", tanks[0].str("role"))
        assertEquals(32.0, tanks[0].dbl("o2_percent"))
        // Hors enseignement, la palanquée est une équipe.
        val team = fields(d.copy(kind = DiveKind.SUPERVISING))
        assertEquals("team", ((team["people"] as kotlinx.serialization.json.JsonArray)[1] as JsonObject).str("role"))
    }

    @Test
    fun activityComesFromTheProfileModeOnly() {
        assertFalse("activity" in fields(dive()))
        val open = DiveProfile(10, listOf(0, 5, 0), diveMode = "opencircuit")
        val ccr = DiveProfile(10, listOf(0, 5, 0), diveMode = "closedcircuit")
        assertEquals("open_circuit", fields(dive { copy(profile = open) }).str("activity"))
        assertEquals("ccr", fields(dive { copy(profile = ccr) }).str("activity"))
    }

    @Test
    fun neverSendsWhatMustNotBeSent() {
        val f = fields(dive { copy(importKey = "k", importModified = mapOf("k" to "x"), noMerge = true, suit = "ancien", notes = "privé") })
        listOf("visibility", "comment", "importKey", "noMerge", "suit", "photos", "profile", "rating", "current", "air_temp_c").forEach { assertFalse(it in f, it) }
        assertEquals("privé", f.str("private_notes"))
    }

    @Test
    fun siteFieldsUseIsoCountryAndBothCoordinatesOrNone() {
        val s = DiveSite(1, " Tobia Arbaa ", latitude = 27.0, longitude = null, country = "Égypte", type = SiteType.SEA, access = SiteAccess.BOAT, maxDepthMeters = 40, notes = "n")
        val f = SyncMapping.siteFields(s)
        assertEquals("Tobia Arbaa", f.str("name"))
        assertEquals("EG", f.str("country_code"))
        assertTrue(f["latitude"] is JsonNull && f["longitude"] is JsonNull)
        assertEquals("boat", f.str("access"))
        assertTrue(SyncMapping.siteFields(s.copy(country = "Atlantide"))["country_code"] is JsonNull)
        assertEquals(27.0, SyncMapping.siteFields(s.copy(longitude = 34.0)).dbl("latitude"))
    }

    @Test
    fun receivingReplacesManagedFieldsAndKeepsTheRest() {
        val local = dive {
            copy(
                notes = "ancien", buddy = "Bob", buddyPhone = "0612", buddyFromContacts = true, importKey = "imp", noMerge = true,
                profile = DiveProfile(10, listOf(0, 10, 0)), photos = listOf(DivePhoto("p1")), suit = "ancien", uuid = "u", updatedAt = "t",
            )
        }
        val remote = buildJsonObject {
            put("started_at", "2026-11-02T10:30:00"); put("duration_seconds", 3000); put("purpose", "exploration")
            put("max_depth_m", "31.50"); put("visibility_m", "12.6"); put("water_temp_c", 19); put("suit_type", "semi_dry"); put("suit_thickness_mm", "5.00")
            put("computer_label", "Peregrine"); put("private_notes", "du site"); put("rating", 5); put("comment", "public")
            put("people", kotlinx.serialization.json.buildJsonArray {
                add(buildJsonObject { put("name", "Alice"); put("role", "guide") })
                add(buildJsonObject { put("name", "Bob"); put("role", "buddy") })
            })
            put("tanks", kotlinx.serialization.json.buildJsonArray {
                add(buildJsonObject { put("volume_l", "12.00"); put("o2_percent", "32.0"); put("he_percent", 0); put("pressure_start_bar", "200.0"); put("pressure_end_bar", 50); put("used_minutes", 38) })
            })
        }
        val r = SyncMapping.applyReceived(local, remote, 9) { 0L }
        assertEquals("2026-11-02", r.date)
        assertEquals("10:30", r.startTime)
        assertEquals("11:20", r.endTime)
        assertEquals(DiveKind.EXPLORATION, r.kind)
        assertEquals(31.5, r.maxDepthMeters)
        assertEquals(13, r.visibilityMeters)
        assertEquals(SuitType.SEMI_DRY, r.suitType)
        assertEquals(5.0, r.suitThicknessMm)
        assertEquals("du site", r.notes)
        assertEquals("Peregrine", r.computer)
        assertEquals(9L, r.siteId)
        assertEquals("Bob", r.buddy)
        assertEquals("0612", r.buddyPhone, "contact du binôme gardé quand le nom est le même")
        assertEquals(listOf("Alice"), r.group.map { it.name })
        assertEquals(1, r.tanks.size)
        assertEquals(32, r.tanks[0].o2Percent)
        assertEquals(200, r.tanks[0].pressureStartBar)
        // Conservé : tout ce que l'appli seule connaît.
        assertEquals("imp", r.importKey)
        assertTrue(r.noMerge)
        assertEquals(local.profile, r.profile)
        assertEquals(local.photos, r.photos)
        assertEquals("ancien", r.suit)
        assertEquals(local.id, r.id)
    }

    @Test
    fun noonFallbackIsNotTurnedIntoARealStartTime() {
        val remote = buildJsonObject { put("started_at", "2026-11-02T12:00:00"); put("duration_seconds", 1800) }
        val r = SyncMapping.applyReceived(dive { copy(startTime = null, endTime = null) }, remote, 1) { 0L }
        assertNull(r.startTime)
        assertNull(r.endTime)
        // Une plongée créée sur le site à 12:00 garde cette heure.
        assertEquals("12:00", SyncMapping.applyReceived(null, remote, 1) { 5L }.startTime)
    }

    @Test
    fun noTankMeansDefaultTankAndRoundTripIsStable() {
        val r = SyncMapping.applyReceived(null, buildJsonObject { put("started_at", "2026-11-02 08:00:00") }, 1) { 5L }
        assertEquals(listOf(Tank()), r.tanks)
        assertEquals(5L, r.id)
        val d = dive { copy(kind = DiveKind.OTHER, kindOther = "photo", buddy = "Bob", group = listOf(Person("A")), waterTempC = 18.0, notes = "x", computer = "PG", suitType = SuitType.WET, suitThicknessMm = 3.0, tanks = listOf(Tank(volumeLiters = 15.0, pressureStartBar = 200, pressureEndBar = 70, avgDepthMeters = 12.5, usedMinutes = 40))) }
        val sent = SyncMapping.withoutNulls(fields(d))
        val back = SyncMapping.applyReceived(null, sent, d.siteId) { 99L }
        assertEquals(fields(d), fields(back), "ce qui part revient identique")
    }

    @Test
    fun profileRoundTripAndSeriesLengths() {
        val p = DiveProfile(
            intervalSeconds = 10, depthDm = listOf(0, 50, 100, 50, 0), tempDc = listOf(250, 240, 230, 240, 250),
            pressureBar = listOf(200, 190, 180, 170, 160), extraPressureBar = listOf(listOf(210, 210, 200, 200, 190)),
            ndlMin = listOf(99, 40, 20, 40, 99), gasSwitches = listOf(GasSwitch(2, 50, 0)), alarms = listOf(ProfileAlarm(3, "ascent")), diveMode = "opencircuit",
        )
        val body = SyncMapping.profileBody(p)!!
        val samples = body["samples"] as JsonObject
        assertEquals(10.0, body.dbl("interval_seconds"))
        assertEquals(setOf("1", "2"), (samples["pressures"] as JsonObject).keys)
        assertFalse("ppo2_cb" in samples, "séries vides omises")
        val back = SyncMapping.profileFromReceived(buildJsonObject { put("interval_seconds", 10); put("samples", samples) })!!
        assertEquals(p, back)
        // Une série de mauvaise longueur n'est pas envoyée (le serveur refuserait toute la courbe).
        val skewed = SyncMapping.profileBody(p.copy(tempDc = listOf(1, 2)))!!["samples"] as JsonObject
        assertFalse("temp_dc" in skewed)
        assertNull(SyncMapping.profileBody(DiveProfile(10, listOf(0))))
        assertNull(SyncMapping.profileBody(DiveProfile(90, listOf(0, 1, 2))))
    }

    @Test
    fun profileHashChangesWithTheCurve() {
        val a = DiveProfile(10, listOf(0, 50, 0))
        assertEquals(SyncMapping.profileHash(a), SyncMapping.profileHash(a.copy()))
        assertNotEquals(SyncMapping.profileHash(a), SyncMapping.profileHash(a.copy(depthDm = listOf(0, 51, 0))))
    }

    @Test
    fun instantsAreSentInUtcToTheSecondAndComparedAcrossWritings() {
        assertEquals("2026-11-23T10:15:00Z", SyncMapping.apiInstant("2026-11-23T10:15:00.987654Z"))
        assertTrue(SyncMapping.sameInstant("2026-11-23T10:15:00Z", "2026-11-23T10:15:00+00:00"))
        assertFalse(SyncMapping.sameInstant("2026-11-23T10:15:00Z", "2026-11-23T10:15:01Z"))
    }

    @Test
    fun connectLinkAndServerAddress() {
        assertEquals("http://localhost:8080" to "ABCDE23456", parseConnectLink("subatlas://connect?server=http%3A%2F%2Flocalhost%3A8080&code=ABCDE23456"))
        assertNull(parseConnectLink("subatlas://dive?d=x"))
        assertNull(parseConnectLink("subatlas://connect?server=https%3A%2F%2Fa.b"))
        assertEquals("https://subatlas.com", normalizeServer(" subatlas.com/ "))
        assertEquals("http://10.0.2.2:8080", normalizeServer("http://10.0.2.2:8080"))
        assertNull(normalizeServer("ftp://x"))
        assertNull(normalizeServer("  "))
    }
}
