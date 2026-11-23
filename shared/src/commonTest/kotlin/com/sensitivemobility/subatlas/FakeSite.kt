package com.sensitivemobility.subatlas

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Un SubAtlas.com minimal, en mémoire, qui se comporte comme le vrai pour ce que l'appli utilise : flux de changements avec curseur,
 * `/sync/push` (un upsert REMPLACE la plongée entière, comme le vrai), PATCH (fusion), DELETE logique, courbes, photos, sites.
 * Il garde des champs que l'appli ne connaît pas (`rating`, `comment`, `visibility`…) pour vérifier qu'ils survivent.
 */
class FakeSite : PlatformActions {
    val dives = LinkedHashMap<String, MutableMap<String, JsonElement>>()
    val sites = LinkedHashMap<String, JsonObject>()
    /** Sites proposés par l'appli que le serveur remplace par un site existant (uuid proposé -> uuid existant). */
    val siteAlias = HashMap<String, String>()
    val profiles = HashMap<String, JsonObject>()
    val photos = LinkedHashMap<String, MutableList<JsonObject>>()
    val photoBytes = HashMap<String, ByteArray>()
    val log = ArrayList<String>()
    val pushes = ArrayList<JsonObject>()
    val patches = ArrayList<Pair<String, JsonObject>>()
    var staleOnce = false
    /** Fait échouer (coupure réseau) la requête dont « METHOD chemin » satisfait ce test. */
    var failWhen: ((String) -> Boolean)? = null
    private var seq = 0
    private val seqOf = HashMap<String, Int>()
    private var photoCounter = 0
    private val json = Json { ignoreUnknownKeys = true }

    override val allowsCleartextHttp: Boolean get() = true

    // --- données du site, comme si elles venaient du formulaire web ---

    fun addSite(uuid: String, name: String, country: String = "FR", lat: Double? = 43.0, lon: Double? = 5.0, status: String = "approved") {
        sites[uuid] = buildJsonObject {
            put("uuid", uuid); put("name", name); put("country_code", country)
            put("latitude", lat?.let { JsonPrimitive(it) } ?: JsonNull); put("longitude", lon?.let { JsonPrimitive(it) } ?: JsonNull)
            put("type", "sea"); put("access", "boat"); put("max_depth_m", 30); put("description", "Un site"); put("status", status)
            put("client_updated_at", "2026-01-01T00:00:00+00:00")
        }
    }

    fun addDive(uuid: String, startedAt: String, depth: Double?, siteUuid: String?, cua: String, status: String = "complete", extra: Map<String, JsonElement> = emptyMap()) {
        val d = LinkedHashMap<String, JsonElement>()
        d["uuid"] = JsonPrimitive(uuid)
        d["started_at"] = JsonPrimitive(startedAt)
        d["duration_seconds"] = JsonPrimitive(2400)
        d["max_depth_m"] = depth?.let { JsonPrimitive(it.toString()) } ?: JsonNull
        d["site_uuid"] = siteUuid?.let { JsonPrimitive(it) } ?: JsonNull
        d["rating"] = JsonPrimitive(4)
        d["comment"] = JsonPrimitive("Super plongée")
        d["visibility"] = JsonPrimitive("friends")
        d["status"] = JsonPrimitive(status)
        d["client_updated_at"] = JsonPrimitive(cua)
        d["deleted_at"] = JsonNull
        d.putAll(extra)
        dives[uuid] = d
        touch(uuid)
    }

    fun editDive(uuid: String, cua: String, vararg changes: Pair<String, JsonElement>) {
        dives.getValue(uuid).apply { changes.forEach { (k, v) -> put(k, v) }; put("client_updated_at", JsonPrimitive(cua)) }
        touch(uuid)
    }

    fun removeDive(uuid: String, cua: String) {
        dives.getValue(uuid)["deleted_at"] = JsonPrimitive(cua)
        dives.getValue(uuid)["client_updated_at"] = JsonPrimitive(cua)
        touch(uuid)
    }

    fun addPhoto(diveUuid: String, width: Int = 1600, height: Int = 1200): String {
        val uuid = "photo-${++photoCounter}"
        photos.getOrPut(diveUuid) { ArrayList() } += buildJsonObject {
            put("uuid", uuid); put("width", width); put("height", height)
            put("url", "http://localhost:8080/api/v1/photos/$uuid/photo"); put("thumb_url", "http://localhost:8080/api/v1/photos/$uuid/thumb")
        }
        photoBytes["/photos/$uuid/photo"] = byteArrayOf(1, 2, 3, uuid.length.toByte())
        photoBytes["/photos/$uuid/thumb"] = byteArrayOf(9)
        return uuid
    }

    private fun touch(uuid: String) { seqOf[uuid] = ++seq }

    private fun resource(uuid: String): JsonObject {
        val d = dives.getValue(uuid)
        val site = d["site_uuid"]?.let { (it as? JsonPrimitive)?.takeIf { p -> p !is JsonNull }?.content }?.let { sites[it] }
        return JsonObject(
            d + mapOf(
                "site" to (site?.let { buildJsonObject { put("uuid", it.str("uuid").orEmpty()); put("name", it.str("name").orEmpty()); put("country_code", it.str("country_code")?.let(::JsonPrimitive) ?: JsonNull) } } ?: JsonNull),
                "has_profile" to JsonPrimitive(profiles.containsKey(uuid)),
                "photos_count" to JsonPrimitive(photos[uuid]?.size ?: 0),
                "dive_number" to JsonPrimitive(1),
            ),
        )
    }

    // --- HTTP ---

    private fun reply(status: Int, body: JsonElement? = null, headers: Map<String, String> = emptyMap()) =
        HttpResponse(status, headers, body?.toString()?.encodeToByteArray() ?: ByteArray(0))

    private fun error(status: Int, code: String, errors: Map<String, List<String>> = emptyMap()) = reply(
        status,
        buildJsonObject {
            put("message", code); put("code", code)
            put("errors", JsonObject(errors.mapValues { (_, v) -> JsonArray(v.map(::JsonPrimitive)) }))
        },
    )

    override suspend fun httpRequest(method: String, url: String, headers: Map<String, String>, body: ByteArray?, contentType: String?, timeoutMillis: Int): HttpResponse {
        val path = url.substringAfter("/api/v1").substringBefore('?')
        val query = url.substringAfter('?', "").split('&').filter { it.contains('=') }.associate { it.substringBefore('=') to urlDecode(it.substringAfter('=')) }
        log += "$method $path"
        if (failWhen?.invoke("$method $path") == true) throw NetworkException("coupé")
        if (headers["Authorization"] != "Bearer tok") return error(401, "unauthenticated")
        val parts = path.trim('/').split('/')
        val payload = body?.takeIf { contentType?.startsWith("application/json") == true }?.let { json.parseToJsonElement(it.decodeToString()) as? JsonObject }
        return when {
            method == "GET" && path == "/sync/changes" -> changes(query)
            method == "POST" && path == "/sync/push" -> push(payload!!)
            method == "GET" && parts[0] == "sites" && parts.size == 2 -> sites[parts[1]]?.let { reply(200, buildJsonObject { put("data", it) }) } ?: error(404, "not_found")
            parts[0] == "dives" && parts.size == 2 && method == "PATCH" -> patch(parts[1], payload!!)
            parts[0] == "dives" && parts.size == 2 && method == "DELETE" -> {
                if (!dives.containsKey(parts[1])) error(404, "not_found") else { removeDive(parts[1], "2099-01-01T00:00:00Z"); reply(204) }
            }
            parts[0] == "dives" && parts.size == 3 && parts[2] == "profile" && method == "PUT" -> { profiles[parts[1]] = payload!!; touch(parts[1]); reply(200, buildJsonObject { put("data", payload) }) }
            parts[0] == "dives" && parts.size == 4 && parts[2] == "profile" && parts[3] == "full" ->
                profiles[parts[1]]?.let { reply(200, buildJsonObject { put("data", it) }) } ?: error(404, "not_found")
            parts[0] == "dives" && parts.size == 3 && parts[2] == "photos" && method == "GET" ->
                reply(200, buildJsonObject { put("data", JsonArray(photos[parts[1]].orEmpty())) })
            parts[0] == "dives" && parts.size == 3 && parts[2] == "photos" && method == "POST" -> {
                val uuid = addPhoto(parts[1])
                reply(201, buildJsonObject { put("data", JsonArray(listOf(photos.getValue(parts[1]).last { it.str("uuid") == uuid }))) })
            }
            parts[0] == "photos" && parts.size == 3 && method == "GET" -> photoBytes[path]?.let { HttpResponse(200, emptyMap(), it) } ?: error(404, "not_found")
            parts[0] == "photos" && parts.size == 2 && method == "DELETE" -> {
                photos.values.forEach { l -> l.removeAll { it.str("uuid") == parts[1] } }
                reply(204)
            }
            else -> error(404, "not_found")
        }
    }

    private fun changes(query: Map<String, String>): HttpResponse {
        if (staleOnce && query.containsKey("since")) { staleOnce = false; return error(410, "gone") }
        val since = query["since"]?.toIntOrNull() ?: 0
        val list = dives.keys.filter { (seqOf[it] ?: 0) > since }.map { resource(it) }
        return reply(200, buildJsonObject {
            put("cursor", seq.toString()); put("has_more", false); put("server_time", "now")
            put("changes", buildJsonObject { put("dives", JsonArray(list)) })
        })
    }

    private fun time(iso: String) = parseInstant(iso)!!.epochSeconds

    private fun push(payload: JsonObject): HttpResponse {
        pushes += payload
        val results = ArrayList<JsonElement>()
        val mutations = (payload["mutations"] as JsonArray).map { it as JsonObject }
        for (m in mutations.sortedBy { if (it.str("entity") == "sites") 0 else 1 }) {
            val uuid = m.str("uuid")!!
            val cua = m.str("client_updated_at")!!
            val data = m["data"] as? JsonObject
            if (m.str("entity") == "sites") {
                val target = siteAlias[uuid] ?: uuid
                val existing = sites[target]
                if (existing != null && target != uuid) {
                    results += buildJsonObject { put("entity", "sites"); put("uuid", uuid); put("status", "applied"); put("server_uuid", target); put("resource", existing) }
                } else if (existing != null && existing.str("status") != "pending") {
                    results += buildJsonObject { put("entity", "sites"); put("uuid", uuid); put("status", "rejected"); put("code", "forbidden"); put("errors", buildJsonObject { put("uuid", JsonArray(listOf(JsonPrimitive("interdit")))) }) }
                } else {
                    val res = buildJsonObject { data!!.forEach { (k, v) -> put(k, v) }; put("uuid", uuid); put("status", "pending"); put("client_updated_at", cua) }
                    sites[uuid] = res
                    results += buildJsonObject { put("entity", "sites"); put("uuid", uuid); put("status", "applied"); put("server_uuid", uuid); put("resource", res) }
                }
            } else {
                val site = data?.str("site_uuid")
                val d = data!!
                val problems = HashMap<String, List<String>>()
                if (d.str("started_at")?.matches(Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2})?""")) != true) problems["started_at"] = listOf("format")
                if (site != null && sites[site] == null) problems["site_uuid"] = listOf("site inconnu")
                if (problems.isNotEmpty()) {
                    results += buildJsonObject {
                        put("entity", "dives"); put("uuid", uuid); put("status", "rejected"); put("code", "validation_failed")
                        put("errors", JsonObject(problems.mapValues { JsonArray(it.value.map(::JsonPrimitive)) }))
                    }
                    continue
                }
                val existing = dives[uuid]
                val version = existing?.get("client_updated_at")?.let { (it as JsonPrimitive).content }
                when {
                    existing != null && version != null && time(cua) == time(version) -> results += applied(uuid)
                    existing != null && version != null && time(cua) < time(version) ->
                        results += buildJsonObject { put("entity", "dives"); put("uuid", uuid); put("status", "conflict"); put("resource", resource(uuid)) }
                    else -> {
                        // Un upsert REMPLACE la plongée : ce que l'appli ne connaît pas (note, commentaire, visibilité…) est perdu, comme sur le vrai site.
                        val fresh = LinkedHashMap<String, JsonElement>()
                        fresh["uuid"] = JsonPrimitive(uuid)
                        d.forEach { (k, v) -> fresh[k] = v }
                        fresh["status"] = JsonPrimitive("complete"); fresh["deleted_at"] = JsonNull
                        fresh["client_updated_at"] = JsonPrimitive(cua)
                        fresh["visibility"] = JsonPrimitive("private")
                        dives[uuid] = fresh
                        touch(uuid)
                        results += applied(uuid)
                    }
                }
            }
        }
        return reply(200, buildJsonObject { put("data", JsonArray(results)); put("summary", buildJsonObject {}) })
    }

    private fun applied(uuid: String) = buildJsonObject { put("entity", "dives"); put("uuid", uuid); put("status", "applied"); put("resource", resource(uuid)) }

    private fun patch(uuid: String, body: JsonObject): HttpResponse {
        val d = dives[uuid] ?: return error(404, "not_found")
        patches += uuid to body
        body.forEach { (k, v) -> d[k] = v }
        touch(uuid)
        return reply(200, buildJsonObject { put("data", resource(uuid)) })
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
