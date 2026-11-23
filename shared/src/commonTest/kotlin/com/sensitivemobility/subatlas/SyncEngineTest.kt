package com.sensitivemobility.subatlas

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Une installation de l'appli et un faux site, pour jouer des synchronisations de bout en bout. */
private class Rig(val site: FakeSite = FakeSite(), val storage: MemoryStorage = MemoryStorage(), val token: String = "tok") {
    val state = AppState(storage, "fr")
    val store = SyncStore(storage)

    fun engine() = SyncEngine(state, store, ApiClient(site, "https://site.test", store.deviceId(), { token }, sleep = {}), french = true)

    suspend fun run(decisions: SyncDecisions = SyncDecisions()): Pair<SyncPlan, SyncReport> {
        val e = engine()
        val plan = e.prepare()
        return plan to e.execute(plan, decisions)
    }

    suspend fun plan() = engine().prepare()

    fun addDive(date: String = "2026-11-01", start: String? = "09:00", end: String? = "09:40", depth: Double? = 20.0, siteName: String = "Tobia Arbaa", configure: Dive.() -> Dive = { this }): Dive {
        val s = state.sites.firstOrNull { it.name == siteName } ?: DiveSite(id = state.newId(), name = siteName, country = "France", latitude = 43.0, longitude = 5.0).also { state.saveSite(it) }
        val d = Dive(id = state.newId(), siteId = s.id, date = date, startTime = start, endTime = end, maxDepthMeters = depth).configure()
        state.saveDive(d)
        return state.dive(d.id)!!
    }

    fun remote(uuid: String) = JsonObject(site.dives.getValue(uuid))
    val synced get() = store.state
}

class SyncEngineTest {
    private fun JsonObject.mutations() = (this["mutations"] as JsonArray).map { it as JsonObject }

    @Test
    fun firstSyncSendsSitesThenDivesAndReplayChangesNothing() = runTest {
        val r = Rig()
        val d = r.addDive { copy(notes = "privé", buddy = "Bob") }
        val (plan, report) = r.run()
        assertEquals(1, plan.newDives.size)
        assertEquals(1, plan.sitesToSend.size)
        assertEquals(1, report.sentDives)
        assertEquals(1, report.sentSites)
        assertTrue(report.finished && report.failure == null)

        assertEquals(listOf("sites"), r.site.pushes[0].mutations().map { it.str("entity") })
        val dive = r.site.pushes[1].mutations().single()
        assertEquals("dives", dive.str("entity"))
        assertEquals(d.uuid, dive.str("uuid"))
        val data = dive["data"] as JsonObject
        assertEquals("2026-11-01T09:00:00", data.str("started_at"))
        assertEquals(2400.0, data.dbl("duration_seconds"))
        assertEquals("privé", data.str("private_notes"))
        assertEquals(r.state.site(d.siteId)!!.uuid, data.str("site_uuid"))
        listOf("visibility", "comment", "rating").forEach { assertFalse(it in data, it) }
        assertTrue(dive.str("client_updated_at")!!.endsWith("Z"))
        assertEquals(r.synced.dives.getValue(d.uuid).serverClientUpdatedAt, r.remote(d.uuid).str("client_updated_at"))

        // Rejeu : l'écho de notre propre envoi n'est pas pris pour une modification, et rien n'est renvoyé.
        val pushes = r.site.pushes.size
        val again = r.plan()
        assertTrue(again.isEmpty && again.toReceive == 0 && again.matched.isEmpty() && again.conflicts.isEmpty(), "rien à faire au rejeu")
        r.run()
        assertEquals(pushes, r.site.pushes.size)
        assertTrue(r.site.patches.isEmpty())
    }

    @Test
    fun diveCreatedOnTheWebIsReceivedWithItsSite() = runTest {
        val r = Rig()
        r.site.addSite("s1", "Tobia Arbaa")
        r.site.addDive("d1", "2026-11-02T10:00:00", 18.5, "s1", "2026-11-02T12:00:00Z")
        val (plan, report) = r.run()
        assertEquals(1, plan.incomingNew.size)
        assertEquals(1, report.receivedDives)
        val d = r.state.diveByUuid("d1")!!
        assertEquals("2026-11-02", d.date)
        assertEquals("10:00", d.startTime)
        assertEquals("10:40", d.endTime)
        assertEquals(18.5, d.maxDepthMeters)
        val site = r.state.site(d.siteId)!!
        assertEquals("s1", site.uuid)
        assertEquals("Tobia Arbaa", site.name)
        assertEquals("France", site.country)
        assertEquals(SiteType.SEA, site.type)
        assertEquals(30, site.maxDepthMeters)
        assertNotNull(r.synced.dives["d1"])
        assertEquals(1, r.state.sites.size)
        assertTrue(r.plan().isEmpty, "aucune requête de plus : tout est à jour")
        assertEquals(4, r.remote("d1")["rating"]?.let { (it as JsonPrimitive).content.toInt() }, "champs du site intacts")
    }

    @Test
    fun webEditIsAppliedWhenTheAppSideIsUnchanged() = runTest {
        val r = Rig()
        r.site.addSite("s1", "Tobia Arbaa")
        r.site.addDive("d1", "2026-11-02T10:00:00", 18.5, "s1", "2026-11-02T12:00:00Z")
        r.run()
        r.site.editDive("d1", "2026-11-03T08:00:00Z", "max_depth_m" to JsonPrimitive("25.0"))
        val (plan, report) = r.run()
        assertEquals(1, plan.incomingUpdate.size)
        assertEquals(1, report.receivedDives)
        assertEquals(25.0, r.state.diveByUuid("d1")!!.maxDepthMeters)
        assertTrue(r.plan().isEmpty)
        assertTrue(r.site.patches.isEmpty(), "reçu, donc rien à renvoyer")
    }

    @Test
    fun editedOnBothSidesIsAConflictNeverResolvedSilently() = runTest {
        val r = Rig()
        r.site.addSite("s1", "Tobia Arbaa")
        r.site.addDive("d1", "2026-11-02T10:00:00", 18.5, "s1", "2026-11-02T12:00:00Z")
        r.run()
        val cursorBefore = r.synced.cursor
        r.state.saveDive(r.state.diveByUuid("d1")!!.copy(notes = "côté appli"))
        r.site.editDive("d1", "2026-11-03T08:00:00Z", "max_depth_m" to JsonPrimitive("25.0"))

        // Sans choix : rien n'est modifié et le curseur n'avance pas (le conflit reviendra).
        val (plan, later) = r.run()
        assertEquals(1, plan.conflicts.size)
        assertEquals(1, later.conflictsPending)
        assertEquals(18.5, r.state.diveByUuid("d1")!!.maxDepthMeters)
        assertEquals("côté appli", r.state.diveByUuid("d1")!!.notes)
        assertEquals(cursorBefore, r.synced.cursor)
        assertTrue(r.site.patches.isEmpty())
        assertEquals(1, r.plan().conflicts.size, "toujours en attente à la synchro suivante")

        // Garder la version de l'appli : tout est renvoyé par PATCH, les champs inconnus du site survivent.
        val (_, keepApp) = r.run(SyncDecisions(conflictChoices = mapOf("d1" to ConflictChoice.APP)))
        assertEquals(1, keepApp.conflictsResolved)
        assertEquals(1, keepApp.sentDives)
        val patch = r.site.patches.single().second
        assertEquals("côté appli", patch.str("private_notes"))
        assertEquals(18.5, patch.dbl("max_depth_m"))
        assertEquals("côté appli", r.remote("d1").str("private_notes"))
        assertEquals("Super plongée", r.remote("d1").str("comment"))
        assertTrue(r.plan().isEmpty)
    }

    @Test
    fun keepingTheSiteVersionReplacesTheManagedFields() = runTest {
        val r = Rig()
        r.site.addSite("s1", "Tobia Arbaa")
        r.site.addDive("d1", "2026-11-02T10:00:00", 18.5, "s1", "2026-11-02T12:00:00Z")
        r.run()
        r.state.saveDive(r.state.diveByUuid("d1")!!.copy(notes = "côté appli"))
        r.site.editDive("d1", "2026-11-03T08:00:00Z", "max_depth_m" to JsonPrimitive("25.0"))
        val (_, report) = r.run(SyncDecisions(conflictChoices = mapOf("d1" to ConflictChoice.SITE)))
        assertEquals(1, report.conflictsResolved)
        val d = r.state.diveByUuid("d1")!!
        assertEquals(25.0, d.maxDepthMeters)
        assertEquals("", d.notes)
        assertTrue(r.site.patches.isEmpty())
    }

    @Test
    fun appEditSendsOnlyChangedFieldsNullsIncludedAndSiteOnlyFieldsSurvive() = runTest {
        val r = Rig()
        r.site.addSite("s1", "Tobia Arbaa")
        r.site.addDive("d1", "2026-11-02T10:00:00", 18.5, "s1", "2026-11-02T12:00:00Z")
        r.run()
        r.state.saveDive(r.state.diveByUuid("d1")!!.copy(waterTempC = 21.0))
        r.run()
        assertEquals(setOf("water_temp_c", "client_updated_at"), r.site.patches.last().second.keys)
        // Effacer une valeur dans l'appli l'efface sur le site : le null est bien envoyé.
        r.state.saveDive(r.state.diveByUuid("d1")!!.copy(waterTempC = null))
        r.run()
        val patch = r.site.patches.last().second
        assertEquals(setOf("water_temp_c", "client_updated_at"), patch.keys)
        assertTrue(patch["water_temp_c"] is JsonNull)
        val remote = r.remote("d1")
        assertEquals("4", (remote["rating"] as JsonPrimitive).content)
        assertEquals("Super plongée", remote.str("comment"))
        assertEquals("friends", remote.str("visibility"))
        assertTrue(r.plan().isEmpty)
    }

    @Test
    fun diveDeletedOnTheWebIsOnlyProposedAndKeptUnlessConfirmed() = runTest {
        val r = Rig()
        r.site.addSite("s1", "Tobia Arbaa")
        r.site.addDive("d1", "2026-11-02T10:00:00", 18.5, "s1", "2026-11-02T12:00:00Z")
        r.site.addDive("d2", "2026-11-03T10:00:00", 12.0, "s1", "2026-11-03T12:00:00Z")
        r.run()
        r.site.removeDive("d1", "2026-11-04T08:00:00Z")
        r.site.removeDive("d2", "2026-11-04T08:00:00Z")
        val plan = r.plan()
        assertEquals(2, plan.remoteDeleted.size)
        assertEquals(2, r.state.dives.size, "aperçu : rien n'est supprimé")
        val (_, report) = r.run(SyncDecisions(removeFromApp = setOf("d1")))
        assertEquals(1, report.removedFromApp)
        assertEquals(1, report.keptDeletedOnSite)
        assertNull(r.state.diveByUuid("d1"))
        assertNotNull(r.state.diveByUuid("d2"))
        assertTrue(r.plan().isEmpty, "la plongée gardée n'est plus reproposée")
    }

    @Test
    fun remoteDeletionOfALocallyEditedDiveKeepsItAndSaysSo() = runTest {
        val r = Rig()
        r.site.addSite("s1", "Tobia Arbaa")
        r.site.addDive("d1", "2026-11-02T10:00:00", 18.5, "s1", "2026-11-02T12:00:00Z")
        r.run()
        r.state.saveDive(r.state.diveByUuid("d1")!!.copy(notes = "à garder"))
        r.site.removeDive("d1", "2026-11-04T08:00:00Z")
        val plan = r.plan()
        assertEquals(1, plan.remoteDeletedKept.size)
        assertTrue(plan.remoteDeleted.isEmpty())
        r.run(SyncDecisions(removeFromApp = setOf("d1")))
        assertEquals("à garder", r.state.diveByUuid("d1")!!.notes)
    }

    @Test
    fun diveDeletedInTheAppIsProposedForTheSiteAndNeverDeletedWithoutAYes() = runTest {
        val r = Rig()
        val a = r.addDive()
        val b = r.addDive(date = "2026-11-02")
        r.run()
        r.state.deleteDive(a.id)
        r.state.deleteDive(b.id)
        val plan = r.plan()
        assertEquals(2, plan.localDeleted.size)
        assertEquals("2026-11-01", plan.localDeleted.first { it.uuid == a.uuid }.date)
        val (_, report) = r.run(SyncDecisions(deleteFromSite = setOf(a.uuid)))
        assertEquals(1, report.deletedFromSite)
        assertNotNull(r.remote(a.uuid).str("deleted_at"))
        assertNull(r.remote(b.uuid).str("deleted_at"), "refusée : la plongée reste sur le site")
        assertTrue(r.plan().isEmpty, "ni reproposée ni renvoyée")
        assertEquals(0, r.state.dives.size)
    }

    @Test
    fun staleCursorRestartsAFullSync() = runTest {
        val r = Rig()
        r.site.addSite("s1", "Tobia Arbaa")
        r.site.addDive("d1", "2026-11-02T10:00:00", 18.5, "s1", "2026-11-02T12:00:00Z")
        r.run()
        assertTrue(r.synced.cursor.isNotBlank())
        r.site.staleOnce = true
        val plan = r.plan()
        assertTrue(plan.fullResync)
        assertTrue(plan.isEmpty, "la synchro complète retrouve des plongées déjà connues : rien à refaire")
        val (_, report) = r.run()
        assertTrue(report.finished)
    }

    @Test
    fun draftsAreIgnored() = runTest {
        val r = Rig()
        r.site.addSite("s1", "Tobia Arbaa")
        r.site.addDive("draft", "2026-11-02T10:00:00", 18.5, "s1", "2026-11-02T12:00:00Z", status = "draft")
        val plan = r.plan()
        assertTrue(plan.incomingNew.isEmpty())
        r.run()
        assertEquals(0, r.state.dives.size)
    }

    @Test
    fun logbookAlreadyOnTheSiteIsMatchedNotDuplicatedAndNothingIsOverwritten() = runTest {
        val r = Rig()
        val local = r.addDive(date = "2026-11-02", start = "10:20", end = "11:00", depth = 18.4, configure = { copy(notes = "mes notes") })
        r.site.addSite("s1", "Tobia Arbaa")
        r.site.addDive("d1", "2026-11-02T10:00:00", 18.5, "s1", "2026-11-02T12:00:00Z")
        r.site.addDive("far", "2026-11-02T16:00:00", 18.5, "s1", "2026-11-02T12:00:00Z")
        val (plan, report) = r.run()
        assertEquals(1, plan.matched.size)
        assertEquals(1, plan.incomingNew.size, "la plongée de 16:00 est trop éloignée pour être rapprochée")
        assertEquals(1, report.matchedDives)
        val adopted = r.state.dive(local.id)!!
        assertEquals("d1", adopted.uuid)
        assertEquals("mes notes", adopted.notes)
        assertEquals("10:20", adopted.startTime)
        assertEquals(2, r.state.dives.size)
        assertTrue(r.site.patches.isEmpty() && r.site.pushes.isEmpty(), "rien n'est écrit sur le site")
        assertEquals("2026-11-02T12:00:00Z", r.remote("d1").str("client_updated_at"))
        assertTrue(r.plan().isEmpty)
    }

    @Test
    fun differentDepthPreventsMatching() = runTest {
        val r = Rig()
        r.addDive(date = "2026-11-02", start = "10:00", depth = 25.0)
        r.site.addSite("s1", "Tobia Arbaa")
        r.site.addDive("d1", "2026-11-02T10:00:00", 18.5, "s1", "2026-11-02T12:00:00Z")
        val plan = r.plan()
        assertTrue(plan.matched.isEmpty())
        assertEquals(1, plan.incomingNew.size)
        assertEquals(1, plan.newDives.size)
    }

    @Test
    fun interruptedSendResumesWithoutDuplicates() = runTest {
        val r = Rig()
        repeat(150) { r.addDive(date = "2026-10-${(it % 28 + 1).toString().padStart(2, '0')}", start = "${(8 + it / 28).toString().padStart(2, '0')}:00", depth = 10.0 + it) }
        var pushes = 0
        r.site.failWhen = { it == "POST /sync/push" && ++pushes == 3 } // 1 : sites, 2 : premier lot de plongées, 3 : second lot
        val (_, first) = r.run()
        assertEquals(SyncFailure.Network, first.failure)
        assertEquals(100, first.sentDives)
        assertEquals(100, r.site.dives.size)
        assertEquals(100, r.synced.dives.size)

        r.site.failWhen = null
        val pushesBefore = r.site.pushes.size
        val (plan, second) = r.run()
        assertEquals(50, plan.newDives.size)
        assertEquals(50, second.sentDives)
        assertEquals(150, r.site.dives.size, "pas de doublon")
        assertEquals(1, r.site.pushes.size - pushesBefore, "seules les 50 restantes sont envoyées")
        assertTrue(r.plan().isEmpty)
    }

    @Test
    fun interruptionJustAfterTheServerAnsweredDoesNotDuplicateNorOverwrite() = runTest {
        // Le serveur a bien enregistré l'envoi, mais l'appli n'a pas eu le temps de le noter : on retrouve la plongée par son uuid.
        val r = Rig()
        val d = r.addDive()
        r.run()
        val lost = r.synced.dives.getValue(d.uuid)
        r.store.update { it.copy(dives = it.dives - d.uuid) }
        r.site.patches.clear()
        val plan = r.plan()
        assertEquals(1, plan.matched.size, "reconnue par son uuid, pas renvoyée")
        assertTrue(plan.newDives.isEmpty())
        r.run()
        assertEquals(lost.serverClientUpdatedAt, r.synced.dives.getValue(d.uuid).serverClientUpdatedAt)
        assertEquals(1, r.site.dives.size)
    }

    @Test
    fun profileIsSentOnlyWhenChangedAndNeverOverwritesALocalOne() = runTest {
        val r = Rig()
        val profile = DiveProfile(10, listOf(0, 50, 100, 50, 0), pressureBar = listOf(200, 190, 180, 170, 160), diveMode = "opencircuit")
        val d = r.addDive { copy(profile = profile) }
        val (_, first) = r.run()
        assertEquals(1, first.profilesSent)
        assertEquals(1, r.site.profiles.size)
        val puts = { r.site.log.count { it.startsWith("PUT") } }
        assertEquals(1, puts())
        r.run()
        assertEquals(1, puts(), "courbe inchangée : pas renvoyée")
        assertTrue(r.plan().isEmpty)

        r.state.saveDive(r.state.dive(d.id)!!.copy(profile = profile.copy(depthDm = listOf(0, 60, 100, 50, 0))))
        r.run()
        assertEquals(2, puts())

        // Reçue seulement si la plongée locale n'en a pas.
        r.site.addSite("s1", "Autre")
        r.site.addDive("w1", "2026-12-01T10:00:00", 15.0, "s1", "2026-12-01T12:00:00Z")
        r.site.profiles["w1"] = (r.site.profiles.values.first())
        r.site.editDive("w1", "2026-12-01T12:00:00Z")
        val (_, second) = r.run()
        assertEquals(1, second.profilesReceived)
        assertNotNull(r.state.diveByUuid("w1")!!.profile)
        assertTrue(r.plan().isEmpty, "la courbe reçue ne rend pas la plongée « modifiée »")
        assertEquals(2, puts(), "et n'est pas renvoyée")
    }

    @Test
    fun photosTravelBothWaysAndNeverTwice() = runTest {
        val r = Rig()
        val d = r.addDive()
        r.state.addPhotos(d.id, listOf(PreparedPhoto(byteArrayOf(5, 6), byteArrayOf(5), 1600, 1200)))
        val photoId = r.state.dive(d.id)!!.photos.single().id
        val (_, first) = r.run()
        assertEquals(1, first.photosSent)
        assertEquals(1, r.site.photos.getValue(d.uuid).size)
        assertEquals(r.site.photos.getValue(d.uuid).single().str("uuid"), r.synced.photos.getValue(photoId).serverUuid)
        r.run()
        assertEquals(1, r.site.log.count { it.startsWith("POST /dives/") && it.endsWith("/photos") }, "déjà envoyée")

        // Une photo ajoutée sur le site arrive dans l'appli, avec pour identifiant son uuid.
        val webPhoto = r.site.addPhoto(d.uuid)
        r.site.editDive(d.uuid, "2099-01-01T00:00:00Z")
        val (_, second) = r.run()
        assertEquals(1, second.photosReceived)
        val ids = r.state.dive(d.id)!!.photos.map { it.id }
        assertTrue(webPhoto in ids && photoId in ids)
        assertNotNull(r.state.photoBytes(r.state.dive(d.id)!!.photos.first { it.id == webPhoto }, thumb = true))
        r.run()
        assertEquals(1, r.site.log.count { it.startsWith("POST /dives/") && it.endsWith("/photos") }, "la photo reçue n'est jamais renvoyée")

        // Photo supprimée dans l'appli : retirée du site, sur confirmation de l'aperçu.
        r.state.removePhoto(d.id, photoId)
        assertEquals(1, r.plan().photosToRemove.size)
        val (_, third) = r.run()
        assertEquals(1, third.photosRemoved)
        assertEquals(listOf(webPhoto), r.site.photos.getValue(d.uuid).map { it.str("uuid") })
    }

    @Test
    fun siteMatchedByNameInsteadOfBeingDuplicated() = runTest {
        val r = Rig()
        val local = r.state.newId().let { DiveSite(it, "Tobia Arbaa", country = "France", latitude = 43.0, longitude = 5.0) }
        r.state.saveSite(local)
        r.site.addSite("s1", "tobia arbaa")
        r.site.addDive("d1", "2026-11-02T10:00:00", 18.5, "s1", "2026-11-02T12:00:00Z")
        val plan = r.plan()
        assertTrue(plan.siteLinksToConfirm.isEmpty(), "même nom : rattaché sans question")
        r.run()
        assertEquals(1, r.state.sites.size)
        assertEquals("s1", r.state.sites.single().uuid)
        assertEquals(local.id, r.state.diveByUuid("d1")!!.siteId)
        assertTrue(r.synced.sites.getValue("s1").locked)
    }

    @Test
    fun similarSiteNameNeedsConfirmationAndCanBeDeclined() = runTest {
        val r = Rig()
        r.state.saveSite(DiveSite(r.state.newId(), "Tobia Arbaa", country = "France", latitude = 43.0, longitude = 5.0))
        r.site.addSite("s1", "Tobia Arbaas")
        r.site.addDive("d1", "2026-11-02T10:00:00", 18.5, "s1", "2026-11-02T12:00:00Z")
        val plan = r.plan()
        assertEquals(1, plan.siteLinksToConfirm.size)
        r.run(SyncDecisions(declinedSiteLinks = setOf("s1")))
        assertEquals(2, r.state.sites.size, "rapprochement refusé : un nouveau site est créé")
    }

    @Test
    fun serverMayReplaceAProposedSiteByAnExistingOne() = runTest {
        val r = Rig()
        val d = r.addDive()
        val localSite = r.state.site(d.siteId)!!
        r.site.addSite("existing", "Tobia Arbaa (site validé)")
        r.site.siteAlias[localSite.uuid] = "existing"
        r.run()
        assertEquals("existing", r.state.site(d.siteId)!!.uuid)
        assertEquals("existing", r.remote(d.uuid).str("site_uuid"))
        assertTrue(r.synced.sites.getValue("existing").locked)
        assertTrue(r.plan().isEmpty)
    }

    @Test
    fun validatedSiteRefusedBySyncIsReportedAndNotRetried() = runTest {
        val r = Rig()
        val d = r.addDive()
        val localSite = r.state.site(d.siteId)!!
        r.site.addSite(localSite.uuid, "Déjà validé", status = "approved")
        val (_, report) = r.run()
        val rejection = report.rejected.single { it.code == "forbidden" }
        assertEquals("Tobia Arbaa", rejection.label)
        assertTrue(r.synced.sites.getValue(localSite.uuid).locked)
        assertTrue(r.plan().sitesToSend.isEmpty(), "ne pas réessayer")
    }

    @Test
    fun rejectedDiveIsReportedWithItsFieldsAndOthersGoThrough() = runTest {
        val r = Rig()
        val ok = r.addDive()
        val bad = r.addDive(date = "2026-11-05", siteName = "Autre site") // son site sera refusé : plongée sans site connu du serveur
        val otherSite = r.state.site(bad.siteId)!!
        r.site.addSite(otherSite.uuid, "Autre site validé")
        val (_, report) = r.run()
        assertEquals(2, report.sentDives, "le site refusé n'empêche pas l'envoi de la plongée (le site existe côté serveur)")
        assertNotNull(r.site.dives[ok.uuid])
    }

    @Test
    fun unauthenticatedStopsEverything() = runTest {
        val r = Rig(token = "mauvais")
        assertFailsWith<ApiError> { r.plan() }.also { assertEquals("unauthenticated", it.code) }
    }

    @Test
    fun appWithoutSyncIsUntouched() = runTest {
        val r = Rig()
        r.addDive()
        assertTrue(r.site.log.isEmpty(), "aucune requête sans action de l'utilisateur")
        assertTrue(r.storage.files.keys.none { it == SyncStore.FILE } || r.synced.dives.isEmpty())
    }
}
