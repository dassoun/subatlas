package com.sensitivemobility.subatlas

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.abs

/** Plongées reçues et locales rapprochées si elles sont du même jour, à cette tolérance de début et de profondeur max. */
internal const val MATCH_MINUTES = 60
internal const val MATCH_DEPTH_METERS = 0.3

/**
 * Une synchronisation avec SubAtlas.com. Deux temps :
 *  1. [prepare] lit le site (GET seulement) et construit l'aperçu, sans rien modifier ;
 *  2. [execute], après confirmation : applique la réception, envoie (sites, plongées), puis les courbes, les photos, les suppressions.
 *
 * Tout ce qui est confirmé par le site est écrit tout de suite dans l'état de synchro : une coupure n'oblige qu'à relancer, sans doublon
 * (les uuid sont stables et le serveur ignore un envoi déjà appliqué).
 */
internal class SyncEngine(
    private val state: AppState,
    private val store: SyncStore,
    private val client: ApiClient,
    private val french: Boolean,
    private val onProgress: (SyncProgress) -> Unit = {},
) {
    private val s get() = store.state

    // =====================================================================================================================
    // 1. Lecture et aperçu
    // =====================================================================================================================

    suspend fun prepare(): SyncPlan {
        var full = s.cursor.isBlank()
        val fetched = try {
            fetchChanges(s.cursor)
        } catch (e: ApiError) {
            // Curseur périmé : on repart d'une synchro complète.
            if (e.code == "gone" && s.cursor.isNotBlank()) { full = true; fetchChanges("") } else throw e
        }
        val remotes = fetched.dives.filter { it.status == "complete" }

        val incomingNew = ArrayList<RemoteDive>()
        val incomingUpdate = ArrayList<Pair<Dive, RemoteDive>>()
        val refreshOnly = ArrayList<Pair<Dive, RemoteDive>>()
        val matched = ArrayList<Pair<Dive, RemoteDive>>()
        val conflicts = ArrayList<SyncConflict>()
        val remoteDeleted = ArrayList<Pair<Dive, RemoteDive>>()
        val remoteDeletedKept = ArrayList<Dive>()
        val remoteGone = ArrayList<String>()

        val taken = HashSet<Long>() // plongées locales déjà rapprochées
        val fuzzyPool = state.dives.filter { it.uuid !in s.dives && it.uuid !in s.detached }.toMutableList()
        val resolutionsNeeded = LinkedHashMap<String, RemoteDive>()

        for (r in remotes.sortedBy { it.startedAt }) {
            val uuid = r.uuid
            if (uuid in s.detached) continue
            val synced = s.dives[uuid]
            val local = state.diveByUuid(uuid)
            if (r.deleted) {
                when {
                    local != null && synced != null && isDirty(local, synced) -> remoteDeletedKept += local
                    local != null -> remoteDeleted += local to r
                    synced != null -> remoteGone += uuid
                }
                continue
            }
            if (synced != null) {
                if (local == null) {
                    if (!MappingSame.instant(r.clientUpdatedAt, synced.serverClientUpdatedAt)) {
                        conflicts += SyncConflict(uuid, null, r, ConflictKind.DELETED_HERE_EDITED_THERE)
                    }
                } else if (!MappingSame.instant(r.clientUpdatedAt, synced.serverClientUpdatedAt)) {
                    val dirty = isDirty(local, synced)
                    val after = SyncMapping.applyReceived(local, r.json, local.siteId) { 0L }
                    val unchanged = SyncMapping.diveFields(after, r.siteUuid) == fieldsOf(local)
                    when {
                        unchanged -> refreshOnly += local to r
                        dirty -> conflicts += SyncConflict(uuid, local, r, ConflictKind.BOTH_EDITED)
                        else -> { incomingUpdate += local to r; r.siteUuid?.let { resolutionsNeeded.putIfAbsent(it, r) } }
                    }
                }
                continue
            }
            // Inconnue du fichier de synchro : notre propre envoi interrompu, ou plongée du carnet déjà importée sur le site.
            if (local != null) { matched += local to r; taken += local.id; fuzzyPool.removeAll { it.id == local.id }; continue }
            val twin = findMatch(r, fuzzyPool)
            if (twin != null) { matched += twin to r; taken += twin.id; fuzzyPool.removeAll { it.id == twin.id }; continue }
            incomingNew += r
            r.siteUuid?.let { resolutionsNeeded.putIfAbsent(it, r) }
        }

        // Sites des plongées à recevoir : par uuid, sinon rapprochés par nom, sinon créés.
        val resolutions = LinkedHashMap<String, SiteResolution>()
        var index = 0
        for ((siteUuid, r) in resolutionsNeeded) {
            onProgress(SyncProgress(SyncPhase.SITE_DETAILS, ++index, resolutionsNeeded.size))
            resolutions[siteUuid] = resolveSite(siteUuid, r.siteJson)
        }

        // À envoyer.
        val matchedIds = matched.map { it.first.id }.toSet()
        val conflictUuids = conflicts.map { it.uuid }.toSet()
        val blocked = conflictUuids + remoteDeleted.map { it.first.uuid } + remoteDeletedKept.map { it.uuid }
        val newDives = state.dives.filter { it.uuid !in s.dives && it.uuid !in s.detached && it.id !in matchedIds }
        val updatedDives = state.dives.filter { d ->
            d.uuid !in blocked && d.uuid !in s.detached && s.dives[d.uuid]?.let { isDirty(d, it) } == true
        }
        val linkTargets = resolutions.values.mapNotNull { it.link?.id }.toSet()
        // Le site d'une plongée mise à jour n'est envoyé que si le PATCH le change (sinon le site garde le sien).
        val referenced = (newDives + updatedDives.filter { d ->
            fieldsOf(d)["site_uuid"] != s.dives[d.uuid]?.fields?.get("site_uuid")
        }).map { it.siteId }.toSet()
        val sitesToSend = state.sites.filter { site ->
            if (site.id in linkTargets) return@filter false
            val entry = s.sites[site.uuid]
            when {
                entry == null -> site.id in referenced
                entry.locked -> false
                else -> SyncMapping.siteFields(site) != entry.fields
            }
        }

        val localUuids = state.dives.map { it.uuid }.toSet()
        val localDeleted = s.dives.keys
            .filter { it !in localUuids && it !in s.detached && s.tombstones[it]?.declined != true && it !in conflictUuids && it !in remoteGone }
            .map { uuid ->
                val f = s.dives[uuid]!!.fields
                LocalDeletion(uuid, f.str("started_at").orEmpty().take(10), state.siteByUuid(f.str("site_uuid").orEmpty())?.name.orEmpty())
            }

        val localPhotoIds = state.dives.flatMap { d -> d.photos.map { it.id } }.toSet()
        val photosToRemove = s.photos.filter { (id, p) -> id !in localPhotoIds && p.diveUuid in localUuids && p.diveUuid !in s.detached }.keys.toList()

        val syncable = state.dives.filter { it.uuid !in s.detached && it.uuid !in blocked }
        val profilesToSend = syncable.count { d ->
            val p = d.profile ?: return@count false
            val entry = s.dives[d.uuid]
            val twin = matched.firstOrNull { it.first.id == d.id }
            if (twin != null) !twin.second.hasProfile && SyncMapping.profileBody(p) != null
            else SyncMapping.profileBody(p) != null && SyncMapping.profileHash(p) != entry?.profileHash.orEmpty()
        }
        val photosToSend = syncable.sumOf { d -> d.photos.count { it.id !in s.photos } }
        val receiving = incomingNew.map { null as Dive? to it } + incomingUpdate + matched + refreshOnly
        val profilesToReceive = receiving.count { (d, r) -> r.hasProfile && d?.profile == null }
        val photosToReceive = receiving.sumOf { (d, r) ->
            val known = d?.photos?.count { it.id in s.photos } ?: 0
            (r.photosCount - known).coerceAtLeast(0)
        }

        return SyncPlan(
            fullResync = full, cursor = fetched.cursor,
            incomingNew = incomingNew, incomingUpdate = incomingUpdate, refreshOnly = refreshOnly, matched = matched,
            conflicts = conflicts, remoteDeleted = remoteDeleted, remoteDeletedKept = remoteDeletedKept, remoteGone = remoteGone,
            localDeleted = localDeleted, siteResolutions = resolutions,
            newDives = newDives, updatedDives = updatedDives, sitesToSend = sitesToSend, photosToRemove = photosToRemove,
            profilesToSend = profilesToSend, photosToSend = photosToSend,
            profilesToReceive = profilesToReceive, photosToReceive = photosToReceive,
        )
    }

    /** Lit toutes les pages du flux (200 plongées à la fois) à partir de [since] (vide : synchro complète). */
    internal suspend fun fetchChanges(since: String): Fetched {
        val byUuid = LinkedHashMap<String, RemoteDive>()
        var cursor = since
        var pages = 0
        while (pages++ < 5000) {
            val query = buildList {
                add("entities" to "dives")
                add("limit" to "200")
                if (cursor.isNotBlank()) add("since" to cursor)
            }
            val body = client.get("/sync/changes", query).json as? JsonObject ?: throw ApiError(200, "bad_response", "réponse illisible")
            val dives = ((body["changes"] as? JsonObject)?.get("dives") as? JsonArray).objects()
            dives.map(::RemoteDive).filter { it.uuid.isNotBlank() }.forEach { byUuid[it.uuid] = it }
            cursor = body.str("cursor") ?: cursor
            onProgress(SyncProgress(SyncPhase.READING, byUuid.size))
            if (body.str("has_more") != "true") break
        }
        return Fetched(byUuid.values.toList(), cursor)
    }

    private fun fieldsOf(d: Dive) = SyncMapping.diveFields(d, state.site(d.siteId)?.uuid)

    private fun isDirty(d: Dive, synced: SyncedDive) = fieldsOf(d) != synced.fields

    /** La plongée locale sans uuid serveur qui correspond à la plongée reçue : même jour, début à 60 min près, profondeur max à 0,3 m près. */
    internal fun findMatch(r: RemoteDive, pool: List<Dive>): Dive? {
        val rStart = minutesOfDay(r.startedAt.drop(11).take(5)) ?: return null
        val rDepth = r.json.dbl("max_depth_m")
        return pool
            .filter { it.date == r.date }
            .mapNotNull { d ->
                val start = minutesOfDay(d.startTime) ?: return@mapNotNull null
                val gap = abs(start - rStart)
                val depth = d.maxDepth
                val sameDepth = if (depth == null || rDepth == null) depth == null && rDepth == null else abs(depth - rDepth) <= MATCH_DEPTH_METERS
                if (gap <= MATCH_MINUTES && sameDepth) d to gap else null
            }
            .minByOrNull { it.second }?.first
    }

    /** Où ranger le site d'une plongée reçue : site local connu par uuid, site local de même nom (rapprochement), ou nouveau site. */
    private suspend fun resolveSite(remoteUuid: String, embedded: JsonObject?): SiteResolution {
        val known = state.siteByUuid(remoteUuid)
        if (known != null) return SiteResolution(remoteUuid, remoteUuid, embedded ?: JsonObject(emptyMap()), known.id, null, false)
        // Le détail complet (type, accès, profondeur, description) vient de GET /sites/{uuid} ; un site fusionné répond par une redirection.
        var json = embedded ?: JsonObject(emptyMap())
        var canonical = remoteUuid
        try {
            val r = client.get("/sites/$remoteUuid")
            (r.data as? JsonObject)?.let { json = it; canonical = it.str("uuid") ?: remoteUuid }
        } catch (e: ApiError) {
            if (e.code == "unauthenticated" || e.code == "rate_limited" || e.status >= 500) throw e
        }
        state.siteByUuid(canonical)?.let { return SiteResolution(remoteUuid, canonical, json, it.id, null, false) }
        val name = json.str("name").orEmpty()
        val country = json.str("country_code")?.let { countryNameFor(it, french) ?: it }.orEmpty()
        val lat = json.dbl("latitude")
        val lon = json.dbl("longitude")
        val candidates = state.sites.filter { it.uuid !in s.sites }
        val exact = candidates.firstOrNull { it.name.equals(name, ignoreCase = true) && countriesCompatible(it.country, country) && !farApart(it.latitude, it.longitude, lat, lon) }
        if (exact != null) return SiteResolution(remoteUuid, canonical, json, null, exact, false)
        val similar = candidates
            .filter { countriesCompatible(it.country, country) && !farApart(it.latitude, it.longitude, lat, lon) && siteNameSimilarity(it.name, name) >= SITE_NAME_THRESHOLD }
            .maxByOrNull { siteNameSimilarity(it.name, name) }
        return SiteResolution(remoteUuid, canonical, json, null, similar, similar != null)
    }

    // =====================================================================================================================
    // 2. Application
    // =====================================================================================================================

    private lateinit var report: SyncReport
    private val siteIdByRemote = HashMap<String, Long>()
    private val touched = ArrayList<String>() // plongées reçues, pour les courbes et les photos

    /** Applique l'aperçu confirmé. Ne lève pas d'exception : une interruption est notée dans [SyncReport.failure]. */
    suspend fun execute(plan: SyncPlan, decisions: SyncDecisions): SyncReport {
        report = SyncReport().also { it.fullResync = plan.fullResync }
        try {
            applyReception(plan, decisions)
            sendSites(plan)
            // « Garder celle de l'appli » : la plongée est renvoyée dans cette même synchro.
            val keptApp = plan.conflicts.filter { decisions.conflictChoices[it.uuid] == ConflictChoice.APP && it.dive != null }.mapNotNull { state.diveByUuid(it.uuid) }
            sendDives(plan, keptApp)
            transferProfiles(plan)
            transferPhotos(plan, decisions)
            deleteOnSite(plan, decisions)
            // Le curseur n'avance que si plus rien ne reste à revoir : un conflit reporté revient à la prochaine synchro.
            if (report.conflictsPending == 0 && report.rejected.none { it.code == "reception" }) store.mutate { it.copy(cursor = plan.cursor) }
            store.mutate { it.copy(lastSync = nowIso()) }
            report.finished = true
        } catch (e: ApiError) {
            report.failure = when {
                e.code == "unauthenticated" -> SyncFailure.Unauthenticated
                e.code == "rate_limited" -> SyncFailure.RateLimited(e.retryAfterSeconds ?: 60)
                else -> SyncFailure.Api(e.code, e.message)
            }
        } catch (e: NetworkException) {
            report.failure = SyncFailure.Network
        } catch (e: InsecureServerException) {
            report.failure = SyncFailure.Insecure
        } finally {
            state.persistDivesAndSites()
            store.flush(force = true)
        }
        return report
    }

    private fun label(d: Dive) = d.date + " " + (state.site(d.siteId)?.name ?: "?")

    private fun applyReception(plan: SyncPlan, decisions: SyncDecisions) {
        val total = plan.incomingNew.size + plan.incomingUpdate.size + plan.refreshOnly.size + plan.matched.size + plan.conflicts.size +
            plan.remoteDeleted.size + plan.remoteGone.size
        var done = 0
        fun tick() { onProgress(SyncProgress(SyncPhase.APPLYING, ++done, total)); if (done % 25 == 0) { state.persistDivesAndSites(); store.flush() } }

        // Sites : adoptés, rapprochés ou créés.
        for (res in plan.siteResolutions.values) siteIdByRemote[res.remoteUuid] = materialize(res, res.remoteUuid in decisions.declinedSiteLinks)

        for (r in plan.incomingNew) {
            val siteId = r.siteUuid?.let { siteIdByRemote[it] } ?: 0L
            val saved = applyRemote(null, r, siteId)
            setEntry(saved, r, profileHash = "")
            touched += saved.uuid
            report.receivedDives++
            tick()
        }
        for ((local, r) in plan.incomingUpdate) {
            val saved = applyRemote(local, r, r.siteUuid?.let { siteIdByRemote[it] } ?: local.siteId)
            setEntry(saved, r, profileHash = s.dives[saved.uuid]?.profileHash.orEmpty())
            touched += saved.uuid
            report.receivedDives++
            tick()
        }
        for ((local, r) in plan.refreshOnly) {
            setEntry(local, r, profileHash = s.dives[local.uuid]?.profileHash.orEmpty())
            touched += local.uuid
            tick()
        }
        for ((local, r) in plan.matched) {
            // Rapprochée : elle adopte l'uuid du site, rien n'est écrasé ni d'un côté ni de l'autre.
            val adopted = local.copy(uuid = r.uuid)
            state.saveDive(adopted, persist = false, touch = false)
            val hash = if (r.hasProfile && adopted.profile != null) SyncMapping.profileHash(adopted.profile) else ""
            setEntry(adopted, r, profileHash = hash)
            touched += adopted.uuid
            report.matchedDives++
            tick()
        }
        for (c in plan.conflicts) {
            when (decisions.conflictChoices[c.uuid] ?: ConflictChoice.LATER) {
                ConflictChoice.SITE -> {
                    val r = c.remote
                    if (c.dive != null) {
                        val saved = applyRemote(c.dive, r, r.siteUuid?.let { siteIdByRemote[it] } ?: c.dive.siteId)
                        setEntry(saved, r, profileHash = s.dives[saved.uuid]?.profileHash.orEmpty())
                        touched += saved.uuid
                    } else {
                        // Supprimée ici, modifiée là-bas : on garde la version du site, la plongée revient dans l'appli.
                        val saved = applyRemote(null, r, r.siteUuid?.let { siteIdByRemote[it] } ?: 0L)
                        setEntry(saved, r, profileHash = "")
                        touched += saved.uuid
                    }
                    report.conflictsResolved++
                }
                ConflictChoice.APP -> {
                    val r = c.remote
                    if (c.dive != null) {
                        // La version de l'appli l'emporte entièrement : tout est renvoyé par PATCH (état synchronisé vide), avec une date neuve.
                        state.saveDive(c.dive, persist = false, touch = true)
                        val kept = state.dive(c.dive.id)!!
                        store.mutate { st ->
                            st.copy(dives = st.dives + (kept.uuid to SyncedDive(r.clientUpdatedAt, kept.updatedAt, JsonObject(emptyMap()), s.dives[kept.uuid]?.profileHash.orEmpty())))
                        }
                    } else {
                        // Supprimée ici, modifiée là-bas, on garde la suppression : elle sera proposée comme les autres.
                        store.mutate { st -> st.copy(dives = st.dives + (c.uuid to st.dives.getValue(c.uuid).copy(serverClientUpdatedAt = r.clientUpdatedAt))) }
                    }
                    report.conflictsResolved++
                }
                ConflictChoice.LATER -> report.conflictsPending++
            }
            tick()
        }
        for ((local, r) in plan.remoteDeleted) {
            if (local.uuid in decisions.removeFromApp) {
                state.deleteDive(local.id)
                store.mutate { st -> st.copy(dives = st.dives - local.uuid, photos = st.photos.filterValues { it.diveUuid != local.uuid }) }
                report.removedFromApp++
            } else {
                // Gardée dans l'appli, à la demande de l'utilisateur : elle ne sera plus synchronisée.
                store.mutate { st -> st.copy(detached = st.detached + local.uuid, dives = st.dives - local.uuid) }
                report.keptDeletedOnSite++
            }
            tick()
        }
        for (d in plan.remoteDeletedKept) {
            store.mutate { st -> st.copy(detached = st.detached + d.uuid, dives = st.dives - d.uuid) }
            report.keptDeletedOnSite++
        }
        for (uuid in plan.remoteGone) {
            store.mutate { st -> st.copy(dives = st.dives - uuid, tombstones = st.tombstones - uuid, photos = st.photos.filterValues { it.diveUuid != uuid }) }
            tick()
        }
        state.persistDivesAndSites()
        store.flush()
    }

    /** Le site local d'une plongée reçue : crée, rattache ou retrouve, et note l'état de synchro du site (jamais modifié par l'appli). */
    private fun materialize(res: SiteResolution, declined: Boolean): Long {
        res.localId?.let { return it }
        val link = res.link?.takeIf { !declined }
        val saved: DiveSite = if (link != null) {
            val j = res.json
            val filled = link.copy(
                uuid = res.canonicalUuid,
                latitude = link.latitude ?: j.dbl("latitude").takeIf { j.dbl("longitude") != null },
                longitude = link.longitude ?: j.dbl("longitude").takeIf { j.dbl("latitude") != null },
                country = link.country.ifBlank { j.str("country_code")?.let { countryNameFor(it, french) ?: it }.orEmpty() },
                type = link.type ?: SyncMapping.siteFromReceived(j, link.id, french).type,
                maxDepthMeters = link.maxDepthMeters ?: j.dbl("max_depth_m")?.toInt(),
                notes = link.notes.ifBlank { j.str("description").orEmpty() },
            )
            state.saveSite(filled, persist = false, touch = true)
            state.site(link.id)!!
        } else {
            val created = SyncMapping.siteFromReceived(res.json, state.newId(), french).copy(uuid = res.canonicalUuid)
            state.saveSite(created, persist = false, touch = false)
            state.site(created.id)!!
        }
        store.mutate { st ->
            st.copy(sites = st.sites + (saved.uuid to SyncedSite(res.json.str("client_updated_at").orEmpty(), saved.updatedAt, SyncMapping.siteFields(saved), locked = true)))
        }
        return saved.id
    }

    private fun applyRemote(local: Dive?, r: RemoteDive, siteId: Long): Dive {
        val d = SyncMapping.applyReceived(local, r.json, siteId) { state.newId() }.copy(uuid = r.uuid, updatedAt = nowIso())
        state.saveDive(d, persist = false, touch = false)
        return state.dive(d.id)!!
    }

    private fun setEntry(d: Dive, r: RemoteDive, profileHash: String) {
        store.mutate { st ->
            st.copy(
                dives = st.dives + (d.uuid to SyncedDive(r.clientUpdatedAt, d.updatedAt, fieldsOf(d), profileHash)),
                tombstones = st.tombstones - d.uuid,
            )
        }
    }

    // ----- envoi -----

    private class PushResult(val status: String, val code: String?, val errors: Map<String, List<String>>, val resource: JsonObject?, val serverUuid: String?)

    private fun mutation(entity: String, uuid: String, clientUpdatedAt: String, op: String, data: JsonObject?) = buildJsonObject {
        put("entity", entity)
        put("uuid", uuid)
        put("op", op)
        put("client_updated_at", clientUpdatedAt)
        if (data != null) put("data", data)
    }

    private suspend fun pushBatch(mutations: List<JsonObject>): Map<String, PushResult> {
        val body = buildJsonObject {
            put("device_id", store.deviceId())
            put("mutations", JsonArray(mutations))
        }
        val results = (client.send("POST", "/sync/push", body = body, timeoutMillis = 60_000).data as? JsonArray).objects()
        return results.associate { r ->
            r.str("uuid").orEmpty() to PushResult(
                status = r.str("status").orEmpty(),
                code = r.str("code"),
                errors = (r["errors"] as? JsonObject).orEmpty().mapValues { (_, v) -> (v as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content } },
                resource = r["resource"] as? JsonObject,
                serverUuid = r.str("server_uuid"),
            )
        }
    }

    private suspend fun sendSites(plan: SyncPlan) {
        var done = 0
        for (chunk in plan.sitesToSend.chunked(100)) {
            val sent = chunk.map { site -> site to SyncMapping.apiInstant(site.updatedAt) }
            val results = pushBatch(sent.map { (site, at) -> mutation("sites", site.uuid, at, "upsert", SyncMapping.withoutNulls(SyncMapping.siteFields(site))) })
            for ((site, at) in sent) {
                val res = results[site.uuid]
                when (res?.status) {
                    "applied" -> {
                        val serverUuid = res.serverUuid ?: site.uuid
                        var current = state.site(site.id) ?: site
                        if (serverUuid != site.uuid) {
                            // Le serveur a remplacé ce site par un site existant très ressemblant : on adopte son uuid.
                            current = current.copy(uuid = serverUuid)
                            state.saveSite(current, persist = false, touch = false)
                            current = state.site(site.id)!!
                        }
                        store.mutate { st ->
                            st.copy(sites = st.sites - site.uuid + (current.uuid to SyncedSite(res.resource?.str("client_updated_at") ?: at, current.updatedAt, SyncMapping.siteFields(current), locked = serverUuid != site.uuid)))
                        }
                        report.sentSites++
                    }
                    "conflict" -> { report.conflictsPending++; report.rejected += Rejection(site.name, "conflict") }
                    "rejected" -> {
                        report.rejected += Rejection(site.name, res.code ?: "rejected", res.errors)
                        // Site déjà validé : la synchro ne peut plus le modifier, inutile de réessayer.
                        if (res.code == "forbidden") {
                            store.mutate { st -> st.copy(sites = st.sites + (site.uuid to SyncedSite("", site.updatedAt, SyncMapping.siteFields(site), locked = true))) }
                        }
                    }
                    else -> report.rejected += Rejection(site.name, "no_result")
                }
                onProgress(SyncProgress(SyncPhase.SENDING_SITES, ++done, plan.sitesToSend.size))
            }
            state.persistDivesAndSites()
            store.flush()
        }
    }

    private suspend fun sendDives(plan: SyncPlan, keptApp: List<Dive>) {
        val updates = plan.updatedDives + keptApp
        val total = plan.newDives.size + updates.size
        var done = 0
        for (chunk in plan.newDives.chunked(100)) {
            val sent = chunk.map { d -> Triple(d, SyncMapping.apiInstant(d.updatedAt), fieldsOf(d)) }
            val results = pushBatch(sent.map { (d, at, f) -> mutation("dives", d.uuid, at, "upsert", SyncMapping.withoutNulls(f)) })
            for ((d, at, fields) in sent) {
                val res = results[d.uuid]
                when (res?.status) {
                    "applied" -> {
                        store.mutate { st -> st.copy(dives = st.dives + (d.uuid to SyncedDive(res.resource?.str("client_updated_at") ?: at, d.updatedAt, fields, ""))) }
                        report.sentDives++
                    }
                    "conflict" -> { report.conflictsPending++; report.rejected += Rejection(label(d), "conflict") }
                    "rejected" -> report.rejected += Rejection(label(d), res.code ?: "rejected", res.errors)
                    else -> report.rejected += Rejection(label(d), "no_result")
                }
                onProgress(SyncProgress(SyncPhase.SENDING_DIVES, ++done, total))
            }
            store.flush()
        }
        for (d in updates) {
            val entry = s.dives[d.uuid] ?: continue
            val now = fieldsOf(d)
            val patch = JsonObject(now.filter { (k, v) -> entry.fields[k] != v })
            val at = SyncMapping.apiInstant(d.updatedAt)
            val body = JsonObject(patch + ("client_updated_at" to JsonPrimitive(at)))
            try {
                val resp = client.send("PATCH", "/dives/${d.uuid}", body = body)
                val resource = resp.data as? JsonObject
                store.mutate { st -> st.copy(dives = st.dives + (d.uuid to entry.copy(serverClientUpdatedAt = resource?.str("client_updated_at") ?: at, localUpdatedAt = d.updatedAt, fields = now))) }
                report.sentDives++
            } catch (e: ApiError) {
                if (e.code == "unauthenticated" || e.code == "rate_limited" || e.status >= 500) throw e
                report.rejected += Rejection(label(d), e.code, e.errors)
            }
            onProgress(SyncProgress(SyncPhase.SENDING_DIVES, ++done, total))
            if (done % 20 == 0) store.flush()
        }
        store.flush()
    }

    // ----- courbes -----

    private suspend fun transferProfiles(plan: SyncPlan) {
        val toReceive = (plan.incomingNew.map { it } + plan.incomingUpdate.map { it.second } + plan.matched.map { it.second } + plan.refreshOnly.map { it.second } +
            plan.conflicts.filter { it.uuid in touched }.map { it.remote }).filter { it.hasProfile }
        var done = 0
        val total = toReceive.size + plan.newDives.size + plan.updatedDives.size + plan.matched.size
        // Reçues : seulement si la plongée locale n'a pas de courbe (une courbe locale n'est jamais remplacée).
        for (r in toReceive) {
            val d = state.diveByUuid(r.uuid)
            if (d != null && d.profile == null && r.uuid in touched) {
                try {
                    val data = client.get("/dives/${r.uuid}/profile/full").data as? JsonObject
                    val profile = data?.let(SyncMapping::profileFromReceived)
                    if (profile != null) {
                        val withProfile = d.copy(profile = profile)
                        state.saveDive(withProfile, persist = false, touch = false)
                        val saved = state.dive(d.id)!!
                        val entry = s.dives[saved.uuid]
                        if (entry != null) {
                            // L'activité et la durée dépendent de la courbe : on les remet à jour dans l'état synchronisé, sans masquer le reste.
                            val now = fieldsOf(saved)
                            val merged = JsonObject(entry.fields + now.filterKeys { it == "activity" || it == "duration_seconds" })
                            store.mutate { st -> st.copy(dives = st.dives + (saved.uuid to entry.copy(fields = merged, profileHash = SyncMapping.profileHash(profile)))) }
                        }
                        report.profilesReceived++
                    }
                } catch (e: ApiError) {
                    if (e.code == "unauthenticated" || e.code == "rate_limited" || e.status >= 500) throw e
                }
            }
            onProgress(SyncProgress(SyncPhase.PROFILES, ++done, total))
        }
        // Envoyées : seulement si elles ont changé depuis la dernière fois (empreinte).
        for (d in state.dives) {
            val entry = s.dives[d.uuid] ?: continue
            val profile = d.profile ?: continue
            if (d.uuid in s.detached) continue
            val twin = plan.matched.firstOrNull { it.first.id == d.id }
            if (twin != null && twin.second.hasProfile) continue
            val body = SyncMapping.profileBody(profile) ?: continue
            val hash = SyncMapping.profileHash(profile)
            if (hash == entry.profileHash) continue
            if (d.uuid in plan.conflicts.map { it.uuid }) continue
            try {
                client.send("PUT", "/dives/${d.uuid}/profile", body = body, timeoutMillis = 90_000)
                store.mutate { st -> st.copy(dives = st.dives + (d.uuid to entry.copy(profileHash = hash))) }
                report.profilesSent++
            } catch (e: ApiError) {
                if (e.code == "unauthenticated" || e.code == "rate_limited" || e.status >= 500) throw e
                report.rejected += Rejection(label(d) + " (courbe)", e.code, e.errors)
            }
            onProgress(SyncProgress(SyncPhase.PROFILES, ++done, total))
        }
        store.flush()
    }

    // ----- photos -----

    private suspend fun transferPhotos(plan: SyncPlan, decisions: SyncDecisions) {
        var done = 0
        val total = plan.photosToReceive + plan.photosToSend + plan.photosToRemove.size
        val blocked = plan.conflicts.map { it.uuid }.toSet()

        // Reprise : un envoi interrompu au mauvais moment est retrouvé sur le site au lieu d'être refait.
        for ((photoId, diveUuid) in s.pendingPhotos.toMap()) {
            val photo = state.diveByUuid(diveUuid)?.photos?.firstOrNull { it.id == photoId }
            if (photo != null) {
                val listed = listPhotos(diveUuid)
                val known = s.photos.values.map { it.serverUuid }.toSet()
                val found = listed.firstOrNull { it.str("uuid") !in known && it.dbl("width")?.toInt() == photo.width && it.dbl("height")?.toInt() == photo.height }
                found?.str("uuid")?.let { u -> store.mutate { st -> st.copy(photos = st.photos + (photoId to SyncedPhoto(u, diveUuid))) } }
            }
            store.mutate { st -> st.copy(pendingPhotos = st.pendingPhotos - photoId) }
        }
        store.flush()

        // Reçues : les photos du site que l'appli ne connaît pas.
        for (r in (plan.incomingNew + plan.incomingUpdate.map { it.second } + plan.matched.map { it.second } + plan.refreshOnly.map { it.second })) {
            if (r.uuid !in touched || r.photosCount == 0) continue
            val d = state.diveByUuid(r.uuid) ?: continue
            if (d.photos.count { it.id in s.photos } >= r.photosCount) continue
            val known = s.photos.values.map { it.serverUuid }.toSet()
            for (p in listPhotos(r.uuid)) {
                val uuid = p.str("uuid") ?: continue
                if (uuid in known) continue
                val full = download(p.str("url"))
                if (full == null) { report.rejected += Rejection(label(d) + " (photo)", "download"); continue }
                val thumb = download(p.str("thumb_url")) ?: full
                val photo = DivePhoto(id = uuid, width = p.dbl("width")?.toInt() ?: 0, height = p.dbl("height")?.toInt() ?: 0)
                if (state.addSyncedPhoto(d.id, photo, full, thumb)) {
                    store.mutate { st -> st.copy(photos = st.photos + (uuid to SyncedPhoto(uuid, d.uuid))) }
                    report.photosReceived++
                }
                onProgress(SyncProgress(SyncPhase.PHOTOS, ++done, total))
            }
            store.flush()
        }
        state.persistDivesAndSites()

        // Envoyées : les photos de l'appli pas encore sur le site.
        for (d in state.dives) {
            if (d.uuid in blocked || d.uuid in s.detached || s.dives[d.uuid] == null) continue
            for (p in d.photos) {
                if (p.id in s.photos) continue
                val bytes = state.photoBytes(p, thumb = false) ?: continue
                store.update { st -> st.copy(pendingPhotos = st.pendingPhotos + (p.id to d.uuid)) }
                try {
                    val body = Multipart.build(listOf(MultipartPart("photo", bytes, "photo.jpg", "image/jpeg")))
                    val resp = client.postMultipart("/dives/${d.uuid}/photos", body)
                    val uuid = ((resp.data as? JsonArray).objects().firstOrNull())?.str("uuid")
                    if (uuid != null) {
                        store.update { st -> st.copy(photos = st.photos + (p.id to SyncedPhoto(uuid, d.uuid)), pendingPhotos = st.pendingPhotos - p.id) }
                        report.photosSent++
                    }
                } catch (e: ApiError) {
                    store.update { st -> st.copy(pendingPhotos = st.pendingPhotos - p.id) }
                    if (e.code == "unauthenticated" || e.code == "rate_limited" || e.status >= 500) throw e
                    report.rejected += Rejection(label(d) + " (photo)", e.code, e.errors)
                }
                onProgress(SyncProgress(SyncPhase.PHOTOS, ++done, total))
            }
        }

        // Retirées dans l'appli : retirées du site, sur confirmation de l'aperçu.
        if (decisions.removePhotosFromSite) {
            for (photoId in plan.photosToRemove) {
                val entry = s.photos[photoId] ?: continue
                try {
                    client.send("DELETE", "/photos/${entry.serverUuid}")
                } catch (e: ApiError) {
                    if (e.code == "unauthenticated" || e.code == "rate_limited" || e.status >= 500) throw e
                    if (e.status != 404) { report.rejected += Rejection("photo", e.code, e.errors); continue }
                }
                store.mutate { st -> st.copy(photos = st.photos - photoId) }
                report.photosRemoved++
                onProgress(SyncProgress(SyncPhase.PHOTOS, ++done, total))
            }
            store.flush()
        }
    }

    private suspend fun listPhotos(diveUuid: String): List<JsonObject> =
        try { (client.get("/dives/$diveUuid/photos").data as? JsonArray).objects() } catch (e: ApiError) {
            if (e.code == "unauthenticated" || e.code == "rate_limited" || e.status >= 500) throw e
            emptyList()
        }

    private suspend fun download(url: String?): ByteArray? =
        if (url.isNullOrBlank()) null else try { client.getBytes(url).body.takeIf { it.isNotEmpty() } } catch (e: ApiError) {
            if (e.code == "unauthenticated" || e.code == "rate_limited" || e.status >= 500) throw e
            null
        }

    // ----- suppressions -----

    private suspend fun deleteOnSite(plan: SyncPlan, decisions: SyncDecisions) {
        var done = 0
        for (del in plan.localDeleted) {
            if (del.uuid !in decisions.deleteFromSite) {
                // Refusée : la plongée reste sur le site, on ne la reproposera pas.
                store.mutate { st -> st.copy(tombstones = st.tombstones + (del.uuid to Tombstone(nowIso(), declined = true))) }
                continue
            }
            try {
                client.send("DELETE", "/dives/${del.uuid}")
            } catch (e: ApiError) {
                if (e.code == "unauthenticated" || e.code == "rate_limited" || e.status >= 500) throw e
                if (e.status != 404 && e.status != 410) { report.rejected += Rejection(del.date + " " + del.siteName, e.code, e.errors); continue }
            }
            store.mutate { st -> st.copy(dives = st.dives - del.uuid, tombstones = st.tombstones - del.uuid, photos = st.photos.filterValues { it.diveUuid != del.uuid }) }
            report.deletedFromSite++
            onProgress(SyncProgress(SyncPhase.DELETING, ++done, plan.localDeleted.size))
        }
        store.flush()
    }
}

/** Comparaison de dates sans dépendre de leur écriture (alias court pour la lisibilité de [SyncEngine]). */
private object MappingSame {
    fun instant(a: String, b: String) = SyncMapping.sameInstant(a, b)
}
