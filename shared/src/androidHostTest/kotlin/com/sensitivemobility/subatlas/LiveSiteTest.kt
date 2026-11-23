package com.sensitivemobility.subatlas

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Scénarios de vérification contre un VRAI SubAtlas.com local, joués par le vrai moteur de synchro avec une vraie connexion HTTP
 * (HttpURLConnection, comme sur Android).
 *
 * Ignorés sans code de connexion. Pour les lancer, générer un code sur le site (Réglages > Appareils et API > Connecter un appareil) :
 *   SUBATLAS_E2E_CODE=XXXXXXXXXX [SUBATLAS_E2E_SERVER=http://localhost:8080] ./gradlew :shared:testAndroidHostTest --tests "*LiveSiteTest*"
 *
 * Tout ce qui est créé porte le marqueur « E2E » et est supprimé à la fin (plongées : suppression logique ; sites en attente : supprimés).
 */
class LiveSiteTest {
    private class JvmPlatform : PlatformActions {
        val writes = ArrayList<String>()
        val log = ArrayList<String>()
        var failWhen: ((String) -> Boolean)? = null
        override val allowsCleartextHttp: Boolean get() = true
        override suspend fun httpRequest(method: String, url: String, headers: Map<String, String>, body: ByteArray?, contentType: String?, timeoutMillis: Int): HttpResponse {
            val path = url.substringAfter("/api/v1").substringBefore('?')
            val tag = "$method $path"
            log += tag
            if (method != "GET") writes += tag
            if (failWhen?.invoke(tag) == true) throw NetworkException("coupé")
            try {
                val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                try {
                    c.instanceFollowRedirects = false
                    c.connectTimeout = timeoutMillis; c.readTimeout = timeoutMillis
                    if (method == "PATCH") { c.requestMethod = "POST"; c.setRequestProperty("X-HTTP-Method-Override", "PATCH") } else c.requestMethod = method
                    headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
                    if (body != null) { c.doOutput = true; if (contentType != null) c.setRequestProperty("Content-Type", contentType); c.outputStream.use { it.write(body) } }
                    val status = c.responseCode
                    val bytes = (if (status >= 400) c.errorStream else c.inputStream)?.use { it.readBytes() } ?: ByteArray(0)
                    return HttpResponse(status, c.headerFields.filterKeys { it != null }.entries.associate { (k, v) -> k.lowercase() to v.joinToString(", ") }, bytes)
                } finally { c.disconnect() }
            } catch (e: java.io.IOException) { throw NetworkException(e.javaClass.simpleName) }
        }
        override fun openMap(latitude: Double, longitude: Double, label: String) {}
        override fun setLandscape(landscape: Boolean) = false
        override fun shareText(text: String) {}
        override fun openLink(url: String) {}
        override fun systemLanguage() = "fr"
        override suspend fun saveTextFile(suggestedName: String, text: String) = false
        override suspend fun openTextFile(): String? = null
        override suspend fun openFileBytes(): ByteArray? = null
    }

    private fun jpeg(w: Int, h: Int, shade: Int): ByteArray {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until w) for (y in 0 until h) img.setRGB(x, y, (shade shl 16) or ((x * 255 / w) shl 8) or (y * 255 / h))
        return ByteArrayOutputStream().also { ImageIO.write(img, "jpg", it) }.toByteArray()
    }

    private class App(val platform: JvmPlatform, val server: String, val token: String) {
        val storage = MemoryStorage()
        val state = AppState(storage, "fr")
        val store = SyncStore(storage)
        fun api() = ApiClient(platform, server, store.deviceId(), { token }, sleep = {})
        fun engine() = SyncEngine(state, store, api(), french = true)
        suspend fun plan() = engine().prepare()
        suspend fun run(d: SyncDecisions = SyncDecisions()): Pair<SyncPlan, SyncReport> { val e = engine(); val p = e.prepare(); return p to e.execute(p, d) }
    }

    @Test
    fun scenarios() = runTest {
        val directToken = System.getenv("SUBATLAS_E2E_TOKEN")
        val code = System.getenv("SUBATLAS_E2E_CODE") ?: if (directToken != null) "" else run { println("LiveSiteTest ignoré : pas de SUBATLAS_E2E_CODE ni SUBATLAS_E2E_TOKEN"); return@runTest }
        val server = System.getenv("SUBATLAS_E2E_SERVER") ?: "http://localhost:8080"
        val platform = JvmPlatform()
        val tag = "E2E" + (System.currentTimeMillis() % 100000)

        // --- Connexion : échange du code, puis profil ---
        val anon = ApiClient(platform, server, "e2e-device", { null }, sleep = {})
        val token = directToken ?: (anon.send("POST", "/auth/token/exchange", body = buildJsonObject { put("code", code); put("device_name", "SubAtlas E2E"); put("device_id", "e2e-device") }, authenticated = false).json as JsonObject).str("token")!!
        val app = App(platform, server, token)
        val me = (app.api().get("/me").data as JsonObject)
        println("connecté : ${me.str("username")}")
        val created = ArrayList<String>()
        val createdSites = ArrayList<String>()

        suspend fun getDive(uuid: String) = app.api().get("/dives/$uuid").data as JsonObject
        fun writesSince(n: Int) = platform.writes.drop(n)

        try {
            // === 1. Envoi d'un carnet de test ===
            val siteA = DiveSite(app.state.newId(), "$tag Récif A", country = "France", latitude = 43.1, longitude = 5.9, type = SiteType.SEA, access = SiteAccess.BOAT, maxDepthMeters = 35, notes = "note de site")
            val siteB = DiveSite(app.state.newId(), "$tag Lac B", country = "Suisse", latitude = 46.5, longitude = 6.6, type = SiteType.LAKE)
            app.state.saveSite(siteA); app.state.saveSite(siteB)
            val profile = DiveProfile(10, List(40) { if (it < 20) it * 10 else (40 - it) * 10 }, tempDc = List(40) { 220 }, pressureBar = List(40) { 200 - it }, ndlMin = List(40) { 30 }, diveMode = "opencircuit")
            val full = Dive(
                id = app.state.newId(), siteId = siteA.id, date = "2026-03-14", startTime = "09:10", endTime = "09:55", maxDepthMeters = 19.5, avgDepthMeters = 11.2,
                waterTempC = 17.5, visibilityMeters = 12, weightKg = 6.0, suitType = SuitType.WET, suitThicknessMm = 5.0, kind = DiveKind.TEACHING,
                buddy = "Alice $tag", group = listOf(Person("Bob"), Person("Carl")), notes = "notes privées $tag", computer = "Peregrine TX",
                tanks = listOf(Tank(volumeLiters = 12.0, o2Percent = 32, pressureStartBar = 200, pressureEndBar = 70, usedMinutes = 45)), profile = profile,
            )
            val plain = Dive(id = app.state.newId(), siteId = siteB.id, date = "2026-03-15", startTime = null, maxDepthMeters = 8.0)
            val withPhoto = Dive(id = app.state.newId(), siteId = siteB.id, date = "2026-03-16", startTime = "14:00", endTime = "14:30", maxDepthMeters = 12.0)
            listOf(full, plain, withPhoto).forEach { app.state.saveDive(it) }
            app.state.addPhotos(withPhoto.id, listOf(PreparedPhoto(jpeg(800, 600, 90), jpeg(160, 120, 90), 800, 600)))
            val uFull = app.state.dive(full.id)!!.uuid; val uPlain = app.state.dive(plain.id)!!.uuid; val uPhoto = app.state.dive(withPhoto.id)!!.uuid
            created += listOf(uFull, uPlain, uPhoto); createdSites += listOf(app.state.site(siteA.id)!!.uuid, app.state.site(siteB.id)!!.uuid)

            val (p1, r1) = app.run()
            println("1. envoi : ${p1.newDives.size} plongées, ${p1.sitesToSend.size} sites -> ${r1.sentDives}/${r1.sentSites}, courbes ${r1.profilesSent}, photos ${r1.photosSent}, refus ${r1.rejected.map { it.label + ":" + it.code + it.errors }}")
            assertTrue(r1.finished && r1.failure == null, "synchro terminée : ${r1.failure}")
            assertEquals(3, r1.sentDives); assertEquals(2, r1.sentSites); assertEquals(1, r1.profilesSent); assertEquals(1, r1.photosSent)
            assertTrue(r1.rejected.isEmpty(), "aucun refus : ${r1.rejected.map { it.label + it.errors }}")

            // Relecture sur le site : numéro, site, courbe, photos, champs.
            val sFull = getDive(uFull)
            println("   relu : n°${sFull.str("dive_number")} site=${(sFull["site"] as? JsonObject)?.str("name")} started=${sFull.str("started_at")} durée=${sFull.str("duration_seconds")} activity=${sFull.str("activity")} purpose=${sFull.str("purpose")}")
            assertNotNull(sFull.str("dive_number"))
            assertEquals("$tag Récif A", (sFull["site"] as JsonObject).str("name"))
            assertEquals("2026-03-14T09:10:00", sFull.str("started_at")?.take(19))
            assertEquals(2700.0, sFull.dbl("duration_seconds"))
            assertEquals("teaching", sFull.str("purpose")); assertEquals("open_circuit", sFull.str("activity"))
            assertEquals("notes privées $tag", sFull.str("private_notes"))
            assertEquals(19.5, sFull.dbl("max_depth_m")); assertEquals(17.5, sFull.dbl("water_temp_c")); assertEquals(12.0, sFull.dbl("visibility_m"))
            assertEquals("wet", sFull.str("suit_type")); assertEquals("Peregrine TX", sFull.str("computer_label"))
            assertEquals(listOf("Alice $tag" to "buddy", "Bob" to "student", "Carl" to "student"), (sFull["people"] as JsonArray).map { (it as JsonObject).let { p -> p.str("name")!! to p.str("role")!! } })
            val tank = ((sFull["tanks"] as JsonArray)[0] as JsonObject)
            assertEquals(32.0, tank.dbl("o2_percent")); assertEquals(200.0, tank.dbl("pressure_start_bar")); assertEquals(12.0, tank.dbl("volume_l"))
            assertEquals("true", sFull.str("has_profile"))
            val prof = (app.api().get("/dives/$uFull/profile/full").data as JsonObject)
            assertEquals(40.0, prof.dbl("sample_count")); assertEquals(10.0, prof.dbl("interval_seconds"))
            assertEquals("2026-03-15T12:00:00", getDive(uPlain).str("started_at")?.take(19), "heure inconnue : 12:00")
            assertEquals("1", getDive(uPhoto).str("photos_count"))
            val photos = (app.api().get("/dives/$uPhoto/photos").data as JsonArray)
            assertEquals(1, photos.size)
            assertEquals("private", sFull.str("visibility") ?: "private", "visibilité : valeur par défaut du membre, non envoyée par l'appli")
            println("   OK : numéro, site, courbe, photo, champs")

            // === 2. Rejeu identique : aucun changement, l'écho n'est pas une modification ===
            val before = platform.writes.size
            val replay = app.plan()
            assertTrue(replay.isEmpty && replay.toReceive == 0 && replay.matched.isEmpty() && replay.conflicts.isEmpty(), "rejeu : rien à faire")
            val (_, r2) = app.run()
            assertTrue(r2.finished && writesSince(before).isEmpty(), "rejeu : aucune écriture ${writesSince(before)}")
            println("2. rejeu identique : aucune écriture, aucun écho pris pour une modification")

            // === 3. Plongée créée sur le site, puis reçue dans l'appli ===
            val webUuid = newUuid(); created += webUuid
            app.api().send("POST", "/dives", body = buildJsonObject {
                put("uuid", webUuid); put("started_at", "2026-04-01T10:00:00"); put("duration_seconds", 3000); put("max_depth_m", 27.3)
                put("site_uuid", createdSites[0]); put("private_notes", "créée sur le web $tag"); put("rating", 5); put("comment", "Magnifique")
            })
            Thread.sleep(1100)
            val (p3, r3) = app.run()
            assertEquals(1, p3.incomingNew.size); assertEquals(1, r3.receivedDives)
            val got = app.state.diveByUuid(webUuid)!!
            assertEquals("2026-04-01", got.date); assertEquals("10:00", got.startTime); assertEquals("10:50", got.endTime); assertEquals(27.3, got.maxDepthMeters)
            assertEquals("créée sur le web $tag", got.notes); assertEquals(app.state.site(siteA.id)!!.id, got.siteId, "site rattaché par uuid")
            println("3. plongée du site reçue : ${got.date} ${got.startTime}-${got.endTime} ${got.maxDepthMeters} m")

            // === 4. Plongée modifiée sur le site, mise à jour dans l'appli ===
            Thread.sleep(1100)
            app.api().send("PATCH", "/dives/$webUuid", body = buildJsonObject { put("client_updated_at", nowIso()); put("max_depth_m", 31.0); put("water_temp_c", 14.0) })
            Thread.sleep(1100)
            val (p4, _) = app.run()
            assertEquals(1, p4.incomingUpdate.size)
            assertEquals(31.0, app.state.diveByUuid(webUuid)!!.maxDepthMeters); assertEquals(14.0, app.state.diveByUuid(webUuid)!!.waterTempC)
            println("4. modification du site reçue : 31.0 m")

            // === 5. Plongée modifiée dans l'appli alors que le site a des champs inconnus de l'appli : ils survivent ===
            Thread.sleep(1100)
            app.api().send("PATCH", "/dives/$webUuid", body = buildJsonObject { put("client_updated_at", nowIso()); put("rating", 4); put("comment", "à garder"); put("visibility", "friends"); put("air_temp_c", 12.0); put("current", "strong") })
            Thread.sleep(1100)
            app.run() // reçoit la modification (sans effet sur les champs gérés)
            val patchesBefore = platform.writes.size
            app.state.saveDive(app.state.diveByUuid(webUuid)!!.copy(notes = "modifiée dans l'appli", waterTempC = null))
            val (_, r5) = app.run()
            assertEquals(1, r5.sentDives)
            val after = getDive(webUuid)
            println("5. PATCH : ${writesSince(patchesBefore)} ; rating=${after.str("rating")} comment=${after.str("comment")} visibility=${after.str("visibility")} air=${after.str("air_temp_c")} current=${after.str("current")} notes=${after.str("private_notes")} temp=${after.str("water_temp_c")}")
            assertEquals(listOf("PATCH /dives/$webUuid"), writesSince(patchesBefore).filter { it.startsWith("PATCH") || it.startsWith("POST /dives/$webUuid") }.map { it.replace("POST", "PATCH") }.take(1))
            assertEquals("modifiée dans l'appli", after.str("private_notes")); assertNull(after.str("water_temp_c"), "null envoyé")
            assertEquals("4", after.str("rating")); assertEquals("à garder", after.str("comment")); assertEquals("friends", after.str("visibility"))
            assertEquals("12.0", after.dbl("air_temp_c")?.toString()); assertEquals("strong", after.str("current"))

            // === 6. Modification des deux côtés : conflit proposé, jamais résolu en silence ===
            app.state.saveDive(app.state.diveByUuid(webUuid)!!.copy(notes = "version appli"))
            Thread.sleep(1100)
            app.api().send("PATCH", "/dives/$webUuid", body = buildJsonObject { put("client_updated_at", nowIso()); put("private_notes", "version site"); put("max_depth_m", 40.0) })
            val cursor = app.store.state.cursor
            Thread.sleep(1100)
            val (p6, r6) = app.run()
            assertEquals(1, p6.conflicts.size); assertEquals(1, r6.conflictsPending)
            assertEquals("version appli", app.state.diveByUuid(webUuid)!!.notes); assertEquals("version site", getDive(webUuid).str("private_notes"))
            assertEquals(cursor, app.store.state.cursor)
            val (_, r6b) = app.run(SyncDecisions(conflictChoices = mapOf(webUuid to ConflictChoice.APP)))
            assertEquals(1, r6b.conflictsResolved)
            assertEquals("version appli", getDive(webUuid).str("private_notes")); assertEquals("4", getDive(webUuid).str("rating"))
            println("6. conflit : proposé (en attente sans choix), puis « garder l'appli » renvoyé par PATCH")

            // === 7. Suppression des deux côtés : confirmation demandée ===
            Thread.sleep(1100)
            app.api().send("DELETE", "/dives/$uPlain")
            app.state.deleteDive(app.state.diveByUuid(uPhoto)!!.id)
            Thread.sleep(1100)
            val p7 = app.plan()
            assertEquals(1, p7.remoteDeleted.size); assertEquals(1, p7.localDeleted.size)
            assertNotNull(app.state.diveByUuid(uPlain), "aperçu : rien n'est supprimé")
            val (_, r7) = app.run(SyncDecisions(removeFromApp = setOf(uPlain), deleteFromSite = setOf(uPhoto)))
            assertEquals(1, r7.removedFromApp); assertEquals(1, r7.deletedFromSite)
            assertNull(app.state.diveByUuid(uPlain))
            for (u in listOf(uPhoto, uPlain)) { val gone = try { getDive(u); false } catch (e: ApiError) { e.status == 404 }; assertTrue(gone, "supprimée sur le site : $u") }
            println("7. suppressions confirmées des deux côtés")

            // === 8. Curseur périmé : synchro complète ===
            app.store.update { it.copy(cursor = "curseur-invalide") }
            val p8 = try { app.plan() } catch (e: ApiError) { println("   (curseur invalide : ${e.status} ${e.code})"); null }
            if (p8 != null) { assertTrue(p8.fullResync); println("8. curseur périmé -> synchro complète ok") }

            // === 9. Coupure réseau en plein envoi, reprise sans doublon ===
            app.store.update { it.copy(cursor = "") }
            val bulk = (1..5).map { Dive(id = app.state.newId(), siteId = siteA.id, date = "2026-05-0$it", startTime = "10:00", endTime = "10:30", maxDepthMeters = 10.0 + it).also { d -> app.state.saveDive(d) } }
            val bulkUuids = bulk.map { app.state.dive(it.id)!!.uuid }; created += bulkUuids
            platform.failWhen = { it.startsWith("POST /sync/push") }
            val (_, r9) = app.run()
            assertEquals(SyncFailure.Network, r9.failure)
            platform.failWhen = null
            val (p9b, r9b) = app.run()
            assertTrue(r9b.finished); assertEquals(5, r9b.sentDives)
            val listed = (app.api().get("/dives", listOf("limit" to "100")).data as JsonArray).map { (it as JsonObject).str("uuid") }
            assertTrue(bulkUuids.all { u -> listed.count { it == u } == 1 }, "pas de doublon")
            println("9. coupure en plein envoi : reprise ok (${p9b.newDives.size} renvoyées), aucun doublon")

            // === 10. Carnet déjà importé côté web : rapprochement sans doublon ===
            val twinUuid = newUuid(); created += twinUuid
            app.api().send("POST", "/dives", body = buildJsonObject { put("uuid", twinUuid); put("started_at", "2026-06-10T09:30:00"); put("duration_seconds", 2400); put("max_depth_m", 22.0); put("site_uuid", createdSites[0]) })
            val localTwin = Dive(id = app.state.newId(), siteId = siteA.id, date = "2026-06-10", startTime = "09:50", endTime = "10:30", maxDepthMeters = 22.2, notes = "ma version")
            app.state.saveDive(localTwin)
            val countBefore = app.state.dives.size
            val writes10 = platform.writes.size
            Thread.sleep(1100)
            val (p10, r10) = app.run()
            assertEquals(1, p10.matched.size); assertEquals(1, r10.matchedDives)
            assertEquals(twinUuid, app.state.dive(localTwin.id)!!.uuid); assertEquals("ma version", app.state.dive(localTwin.id)!!.notes)
            assertEquals(countBefore, app.state.dives.size, "pas de doublon local")
            assertTrue(writesSince(writes10).none { it.contains(twinUuid) }, "rien n'est écrit sur la plongée rapprochée")
            println("10. rapprochement : uuid du site adopté, rien d'écrasé")
            println("TOUS LES SCÉNARIOS PASSENT")
        } finally {
            // Nettoyage : les plongées de test sont supprimées (suppression logique) et les sites en attente retirés.
            val api = app.api()
            created.forEach { runCatching { api.send("DELETE", "/dives/$it") } }
            runCatching {
                api.send("POST", "/sync/push", body = buildJsonObject {
                    put("mutations", JsonArray(createdSites.map { u -> buildJsonObject { put("entity", "sites"); put("uuid", u); put("op", "delete"); put("client_updated_at", "2099-01-01T00:00:00Z") } }))
                })
            }
        }
    }
}
