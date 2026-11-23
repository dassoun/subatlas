package com.sensitivemobility.subatlas

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.roundToInt
import kotlin.time.Instant

/**
 * Correspondance entre le carnet de l'appli et l'API de SubAtlas.com, dans les deux sens (fonctions pures, testées).
 *
 * Un « instantané » (`*Fields`) contient TOUTES les clés gérées par l'appli, avec `null` explicite quand la valeur est absente :
 * c'est à la fois ce qui est envoyé et l'état mémorisé à la dernière synchro. La différence entre les deux donne les seuls champs
 * à envoyer par PATCH, valeurs nulles comprises (effacer une valeur dans l'appli l'efface sur le site).
 */
internal object SyncMapping {
    private fun r3(v: Double) = kotlin.math.round(v * 1000.0) / 1000.0

    /** Un nombre dans les bornes de l'API, sinon `null` (« omettre le champ plutôt que de faire refuser la plongée »). */
    private fun num(v: Double?, lo: Double, hi: Double): JsonElement =
        if (v != null && v.isFinite() && v in lo..hi) JsonPrimitive(r3(v)) else JsonNull

    private fun text(v: String?, max: Int): JsonElement = v?.trim()?.takeIf { it.isNotEmpty() }?.take(max)?.let { JsonPrimitive(it) } ?: JsonNull

    fun withoutNulls(o: JsonObject): JsonObject = JsonObject(o.filterValues { it !is JsonNull })

    // --- Sites ---

    fun siteFields(s: DiveSite): JsonObject {
        val lat = s.latitude
        val lon = s.longitude
        val hasPosition = lat != null && lon != null && lat in -90.0..90.0 && lon in -180.0..180.0
        return buildJsonObject {
            put("name", s.name.trim().take(160))
            put("country_code", countryIsoCode(s.country)?.let { JsonPrimitive(it) } ?: JsonNull)
            put("latitude", if (hasPosition) JsonPrimitive(lat) else JsonNull)
            put("longitude", if (hasPosition) JsonPrimitive(lon) else JsonNull)
            put("type", s.type?.let { JsonPrimitive(it.name.lowercase()) } ?: JsonNull)
            put("access", JsonPrimitive(s.access.name.lowercase()))
            put("max_depth_m", num(s.maxDepthMeters?.toDouble(), 0.0, 400.0))
            put("description", text(s.notes, 2000))
        }
    }

    /** Site reçu (`GET /sites/{uuid}` ou champ `site` d'une plongée) -> site local, avec l'identifiant [id]. */
    fun siteFromReceived(r: JsonObject, id: Long, french: Boolean): DiveSite {
        val code = r.str("country_code")
        return DiveSite(
            id = id,
            name = r.str("name").orEmpty().ifBlank { "?" },
            latitude = r.dbl("latitude"),
            longitude = r.dbl("longitude"),
            maxDepthMeters = r.dbl("max_depth_m")?.roundToInt(),
            access = if (r.str("access") == "boat") SiteAccess.BOAT else SiteAccess.SHORE,
            notes = r.str("description").orEmpty(),
            country = code?.let { countryNameFor(it, french) ?: it }.orEmpty(),
            type = r.str("type")?.let { t -> SiteType.entries.firstOrNull { it.name.lowercase() == t } },
            uuid = r.str("uuid").orEmpty(),
        )
    }

    // --- Plongées ---

    fun diveFields(d: Dive, siteUuid: String?): JsonObject {
        val startMin = minutesOfDay(d.startTime)
        val time = if (startMin != null && startMin in 0 until 24 * 60) hhmm(startMin) else "12:00"
        val minutes = d.durationMinutes?.takeIf { it > 0 }
        val duration = (minutes?.times(60) ?: d.profile?.durationSeconds?.takeIf { it > 0 })?.takeIf { it in 1..172_800 }
        val people = buildList {
            d.buddy.trim().takeIf { it.isNotEmpty() }?.let { add(it.take(120) to "buddy") }
            val role = if (d.kind == DiveKind.TEACHING) "student" else "team"
            d.group.mapNotNull { it.name.trim().takeIf { n -> n.isNotEmpty() } }.forEach { add(it.take(120) to role) }
        }.take(20)
        val tanks = d.tanks.filterNot { it.isBlankTank() }.take(8)
        return buildJsonObject {
            put("started_at", "${d.date}T$time:00")
            put("duration_seconds", duration?.let { JsonPrimitive(it) } ?: JsonNull)
            put("purpose", d.kind?.let { JsonPrimitive(it.name.lowercase()) } ?: JsonNull)
            put("purpose_other", if (d.kind == DiveKind.OTHER) text(d.kindOther, 120) else JsonNull)
            put("site_uuid", siteUuid?.takeIf { it.isNotBlank() }?.let { JsonPrimitive(it) } ?: JsonNull)
            put("max_depth_m", num(d.maxDepth, 0.0, 400.0))
            put("avg_depth_m", num(d.avgDepthMeters, 0.0, 400.0))
            put("water_temp_c", num(d.waterTempC, -5.0, 45.0))
            put("visibility_m", num(d.visibilityMeters?.toDouble(), 0.0, 200.0))
            put("weight_kg", num(d.weightKg, 0.0, 99.0))
            put("suit_type", d.suitType?.let { JsonPrimitive(it.name.lowercase()) } ?: JsonNull)
            put("suit_thickness_mm", num(d.suitThicknessMm, 0.0, 12.0))
            put("computer_label", text(d.computer, 120))
            put("private_notes", text(d.notes, 10_000))
            put("people", buildJsonArray {
                people.forEach { (name, role) -> add(buildJsonObject { put("name", name); put("role", role) }) }
            })
            put("tanks", buildJsonArray { tanks.forEach { add(tankJson(it)) } })
            // Le mode de plongée n'est connu que par la courbe ; sans courbe, on laisse l'activité telle que le site l'a.
            d.profile?.let { put("activity", if (it.diveMode.contains("closed", ignoreCase = true)) "ccr" else "open_circuit") }
        }
    }

    private fun Tank.isBlankTank() = volumeLiters == null && pressureStartBar == null && pressureEndBar == null &&
        avgDepthMeters == null && usedMinutes == null && o2Percent == 21 && hePercent == 0

    private fun tankJson(t: Tank): JsonObject = buildJsonObject {
        put("role", "bottom")
        put("volume_l", num(t.volumeLiters, 0.5, 100.0))
        put("o2_percent", JsonPrimitive(t.o2Percent.coerceIn(0, 100)))
        put("he_percent", JsonPrimitive(t.hePercent.coerceIn(0, 100)))
        put("pressure_start_bar", num(t.pressureStartBar?.toDouble(), 0.0, 1000.0))
        put("pressure_end_bar", num(t.pressureEndBar?.toDouble(), 0.0, 1000.0))
        put("avg_depth_m", num(t.avgDepthMeters, 0.0, 400.0))
        put("used_minutes", t.usedMinutes?.takeIf { it in 0..65_000 }?.let { JsonPrimitive(it) } ?: JsonNull)
    }

    private fun hhmm(minutes: Int) = "${(minutes / 60).toString().padStart(2, '0')}:${(minutes % 60).toString().padStart(2, '0')}"

    /**
     * Plongée reçue appliquée à la plongée locale [local] (ou nouvelle plongée si `null`) : seuls les champs gérés par l'appli sont
     * remplacés ; tout le reste de la plongée locale (courbe, photos, clés d'import, contacts du binôme…) est conservé.
     */
    fun applyReceived(local: Dive?, r: JsonObject, siteId: Long, newId: () -> Long): Dive {
        val startedAt = r.str("started_at").orEmpty()
        val date = startedAt.take(10)
        val rawTime = startedAt.drop(11).take(5)
        // Une plongée envoyée sans heure part à 12:00 : on ne fait pas de cette heure de repli une vraie heure de début.
        val startTime = when {
            minutesOfDay(rawTime) == null -> local?.startTime
            local != null && local.startTime == null && rawTime == "12:00" -> null
            else -> rawTime
        }
        val duration = r.dbl("duration_seconds")?.roundToInt()
        val endTime = if (duration != null && duration > 0) timePlusSeconds(startTime, duration) else null
        val kind = r.str("purpose")?.let { p -> DiveKind.entries.firstOrNull { it.name.lowercase() == p } }
        val people = (r["people"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val buddyPerson = people.firstOrNull { it.str("role") == "buddy" }
        val buddyName = buddyPerson?.str("name").orEmpty()
        val others = people.filter { it !== buddyPerson }.mapNotNull { it.str("name")?.takeIf { n -> n.isNotBlank() } }
        val sameBuddy = local != null && buddyName.isNotBlank() && local.buddy == buddyName
        val tanks = (r["tanks"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.map { t ->
            Tank(
                volumeLiters = t.dbl("volume_l"),
                o2Percent = t.dbl("o2_percent")?.roundToInt() ?: 21,
                hePercent = t.dbl("he_percent")?.roundToInt() ?: 0,
                pressureStartBar = t.dbl("pressure_start_bar")?.roundToInt(),
                pressureEndBar = t.dbl("pressure_end_bar")?.roundToInt(),
                avgDepthMeters = t.dbl("avg_depth_m"),
                usedMinutes = t.dbl("used_minutes")?.roundToInt(),
            )
        }
        val base = local ?: Dive(id = newId(), siteId = siteId, date = date)
        return base.copy(
            siteId = siteId,
            date = date.ifBlank { base.date },
            startTime = startTime,
            endTime = endTime,
            kind = kind,
            kindOther = if (kind == DiveKind.OTHER) r.str("purpose_other").orEmpty() else "",
            maxDepthMeters = r.dbl("max_depth_m"),
            avgDepthMeters = r.dbl("avg_depth_m"),
            waterTempC = r.dbl("water_temp_c"),
            visibilityMeters = r.dbl("visibility_m")?.roundToInt(),
            weightKg = r.dbl("weight_kg"),
            suitType = r.str("suit_type")?.let { t -> SuitType.entries.firstOrNull { it.name.lowercase() == t } },
            suitThicknessMm = r.dbl("suit_thickness_mm"),
            computer = r.str("computer_label").orEmpty(),
            notes = r.str("private_notes").orEmpty(),
            buddy = buddyName,
            buddyFromContacts = sameBuddy && base.buddyFromContacts,
            buddyPhone = if (sameBuddy) base.buddyPhone else "",
            buddyEmail = if (sameBuddy) base.buddyEmail else "",
            group = others.map { n -> base.group.firstOrNull { it.name == n } ?: Person(n) },
            tanks = tanks.ifEmpty { listOf(Tank()) },
        )
    }

    // --- Courbes ---

    /** Corps du `PUT /dives/{uuid}/profile`, ou `null` si la courbe ne peut pas être envoyée (trop courte, trop longue, intervalle). */
    fun profileBody(p: DiveProfile): JsonObject? {
        val n = p.depthDm.size
        if (n < 2 || n > 60_000 || p.intervalSeconds !in 1..60) return null
        fun series(v: List<Int>, lo: Int, hi: Int): JsonArray? =
            if (v.size == n) buildJsonArray { v.forEach { add(JsonPrimitive(it.coerceIn(lo, hi))) } } else null
        val samples = buildJsonObject {
            put("depth_dm", series(p.depthDm, 0, 4000)!!)
            fun optional(key: String, v: List<Int>) = series(v, -2000, 100_000)?.let { put(key, it) }
            optional("temp_dc", p.tempDc)
            val pressures = buildList { add(p.pressureBar); addAll(p.extraPressureBar) }
            val sent = pressures.mapIndexedNotNull { i, v -> series(v, 0, 1000)?.let { (i + 1).toString() to it } }.take(8)
            if (sent.isNotEmpty()) put("pressures", buildJsonObject { sent.forEach { (k, v) -> put(k, v) } })
            optional("ndl_min", p.ndlMin)
            optional("deco_stop_m", p.decoStopM)
            optional("tts_min", p.ttsMin)
            optional("ppo2_cb", p.ppo2Cb)
            optional("cns_pct", p.cnsPct)
            optional("heading_deg", p.headingDeg)
            optional("gf_pct", p.gfPct)
            optional("pulse_bpm", p.pulseBpm)
            optional("otu", p.otu)
            optional("remaining_bottom_min", p.remainingBottomMin)
            val switches = p.gasSwitches.filter { it.index in 0 until n && it.o2Percent in 0..100 && it.hePercent in 0..100 && it.o2Percent + it.hePercent <= 100 }.take(500)
            if (switches.isNotEmpty()) put("gas_switches", buildJsonArray {
                switches.forEach { add(buildJsonObject { put("index", it.index); put("o2_percent", it.o2Percent); put("he_percent", it.hePercent) }) }
            })
            val alarms = p.alarms.filter { it.index in 0 until n && it.text.isNotBlank() }.take(500)
            if (alarms.isNotEmpty()) put("alarms", buildJsonArray {
                alarms.forEach { add(buildJsonObject { put("index", it.index); put("text", it.text.trim().take(60)) }) }
            })
            p.diveMode.trim().takeIf { it.isNotEmpty() }?.let { put("dive_mode", it.take(40)) }
        }
        return buildJsonObject { put("interval_seconds", p.intervalSeconds); put("samples", samples) }
    }

    /** Empreinte de la courbe telle qu'elle serait envoyée : sert à ne renvoyer que ce qui a changé. */
    fun profileHash(p: DiveProfile): String = profileBody(p)?.toString()?.let(::fnv64).orEmpty()

    /** Courbe reçue (`GET /dives/{uuid}/profile/full`, partie `data`) -> courbe de l'appli ; `null` si elle est inutilisable. */
    fun profileFromReceived(data: JsonObject): DiveProfile? {
        val interval = data.dbl("interval_seconds")?.roundToInt() ?: return null
        val s = data["samples"] as? JsonObject ?: return null
        fun ints(key: String): List<Int> = (s[key] as? JsonArray).orEmpty().map { (it as? JsonPrimitive)?.content?.toDoubleOrNull()?.roundToInt() ?: 0 }
        val depth = ints("depth_dm")
        if (depth.size < 2 || interval < 1) return null
        fun same(v: List<Int>) = if (v.size == depth.size) v else emptyList()
        val pressures = (s["pressures"] as? JsonObject).orEmpty()
            .mapNotNull { (k, v) -> k.toIntOrNull()?.let { it to same((v as? JsonArray).orEmpty().map { x -> (x as? JsonPrimitive)?.content?.toDoubleOrNull()?.roundToInt() ?: 0 }) } }
            .sortedBy { it.first }
        val first = pressures.firstOrNull { it.first == 1 }?.second.orEmpty()
        val extra = pressures.filter { it.first > 1 }.map { it.second }
        return DiveProfile(
            intervalSeconds = interval,
            depthDm = depth,
            tempDc = same(ints("temp_dc")),
            pressureBar = first,
            extraPressureBar = extra.takeIf { l -> l.all { it.isNotEmpty() } }.orEmpty(),
            ndlMin = same(ints("ndl_min")),
            decoStopM = same(ints("deco_stop_m")),
            ttsMin = same(ints("tts_min")),
            ppo2Cb = same(ints("ppo2_cb")),
            cnsPct = same(ints("cns_pct")),
            gasSwitches = (s["gas_switches"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.mapNotNull {
                val i = it.dbl("index")?.roundToInt() ?: return@mapNotNull null
                GasSwitch(i, it.dbl("o2_percent")?.roundToInt() ?: 21, it.dbl("he_percent")?.roundToInt() ?: 0)
            },
            alarms = (s["alarms"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.mapNotNull {
                val i = it.dbl("index")?.roundToInt() ?: return@mapNotNull null
                ProfileAlarm(i, it.str("text").orEmpty())
            },
            headingDeg = same(ints("heading_deg")),
            gfPct = same(ints("gf_pct")),
            pulseBpm = same(ints("pulse_bpm")),
            otu = same(ints("otu")),
            remainingBottomMin = same(ints("remaining_bottom_min")),
            diveMode = (s["dive_mode"] as? JsonPrimitive)?.content.orEmpty(),
        )
    }

    private fun fnv64(text: String): String {
        var h = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
        for (b in text.encodeToByteArray()) {
            h = h xor (b.toLong() and 0xFF)
            h *= 0x100000001b3L
        }
        return h.toULong().toString(16)
    }

    // --- Dates ---

    /** Instant au format attendu par l'API : UTC, ISO 8601 avec « Z », à la seconde. */
    fun apiInstant(iso: String): String =
        (parseInstant(iso) ?: parseInstant(nowIso())!!).let { Instant.fromEpochSeconds(it.epochSeconds).toString() }

    /** Deux écritures de la même date (« …Z » et « …+00:00 » par exemple) sont égales. */
    fun sameInstant(a: String, b: String): Boolean {
        val x = parseInstant(a)
        val y = parseInstant(b)
        return if (x != null && y != null) x.epochSeconds == y.epochSeconds else a == b
    }
}

internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
internal fun JsonObject.dbl(key: String): Double? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.toDoubleOrNull()
