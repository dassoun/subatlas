package com.sensitivemobility.subatlas

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlin.math.abs
import kotlin.math.round

/** Une plongée lue dans un export Shearwater Cloud, avant d'être ajoutée au carnet. */
internal class ShearwaterDive(
    /** Identifiant stable (`shearwater:` + identifiant Shearwater) : réimporter le même fichier ne crée pas de doublon. */
    val key: String,
    val date: String,
    val startTime: String?,
    val endTime: String?,
    val durationSeconds: Int?,
    val siteName: String,
    val country: String,
    val siteNotes: String,
    val latitude: Double?,
    val longitude: Double?,
    val maxDepth: Double?,
    val avgDepth: Double?,
    val waterTempC: Double?,
    val tanks: List<Tank>,
    val weightKg: Double?,
    val suitType: SuitType?,
    val notes: String,
    val buddy: String,
    val visibilityMeters: Int?,
    val computer: String,
    val profile: DiveProfile?,
)

/**
 * Lecture d'un export « Shearwater Cloud » (fichier `.db` SQLite). Les métadonnées sont dans `dive_details`, le profil
 * (un échantillon toutes les quelques secondes) dans `log_data`, au format binaire Shearwater « PNF » compressé en gzip.
 */
internal object ShearwaterImport {
    class NotShearwaterException : Exception()

    private const val PSI_TO_BAR = 0.0689476
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Plongées lues, et nombre de plongées du fichier qu'on n'a pas pu lire. */
    class ShearwaterFile(val dives: List<ShearwaterDive>, val skipped: Int)

    fun parse(bytes: ByteArray): ShearwaterFile {
        val db = try {
            SqliteFile(bytes)
        } catch (e: IllegalArgumentException) {
            throw NotShearwaterException()
        }
        if ("dive_details" !in db.tables) throw NotShearwaterException()

        val logs = db.rows("log_data").associateBy { it["log_id"].str() }
        val knownSites = db.rows("dive_site").associateBy { it["Name"].str().trim().lowercase() }

        var skipped = 0
        val dives = db.rows("dive_details").mapNotNull { d ->
            val id = d["DiveId"].str()
            val dive = if (id.isBlank()) null else runCatching { convert(id, d, logs[id], knownSites) }.getOrNull()
            if (dive == null) skipped++
            dive
        }
        return ShearwaterFile(dives, skipped)
    }

    private fun convert(
        id: String,
        d: Map<String, Any?>,
        log: Map<String, Any?>?,
        knownSites: Map<String, Map<String, Any?>>,
    ): ShearwaterDive? {
        val dateTime = d["DiveDate"].str().trim().replace('T', ' ')
        if (dateTime.length < 10) return null
        val date = dateTime.substring(0, 10)
        val start = dateTime.takeIf { it.length >= 16 }?.substring(11, 16)

        val format = log?.get("format").str()
        val blob = log?.get("data_bytes_1") as? ByteArray
        // Les plongées saisies à la main dans un autre logiciel (puis importées) ont un journal UDDF ; les plongées de
        // l'ordinateur ont un journal binaire Shearwater « PNF ».
        val uddf = if (format == "uddf") blob?.let { uddf(it) } else null

        val duration = d["DiveLengthTime"].dbl()?.toInt()?.takeIf { it > 0 } ?: uddf?.durationSeconds?.takeIf { it > 0 }
        val end = duration?.let { timePlusSeconds(start, it) }

        val calc = log?.get("calculated_values_from_samples").str().json()
        val tankJson = d["TankProfileData"].str().json()
        val avgDepth = calc?.num("AverageDepth")?.takeIf { it > 0 }
            ?: d["AverageDepth"].dbl()?.takeIf { it > 0 }
            ?: uddf?.avgDepth
            ?: (tankJson?.get("GasProfiles") as? JsonArray)?.firstOrNull()?.asObject()?.num("AverageDepthInMeters")
        val temp = (calc?.num("AverageTemp")?.takeIf { it > 0 } ?: d["AverageTemp"].dbl()?.takeIf { it > 0 })
            ?.let { round(it * 10) / 10 }

        val site = d["Site"].str().trim()
        val location = d["Location"].str().trim()
        val (country, rest) = splitCountry(location)
        val known = knownSites[site.lowercase()]
        val fileName = d["FileName"].str()
        val serial = d["SerialNumber"].str().ifBlank { fileName.substringAfter('[', "").substringBefore(']') }
        val model = fileName.substringBefore('[').trim()
        val meta3 = (log?.get("data_bytes_3") as? ByteArray)?.decodeToString().orEmpty().json()
        val imperial = meta3?.num("UnitSystem")?.toInt() == 1
        val tanks = tanks(tankJson, d["TankSize"].str())

        return ShearwaterDive(
            key = "shearwater:$id",
            date = date,
            startTime = start,
            endTime = end,
            durationSeconds = duration,
            siteName = site,
            country = country.ifBlank { known?.get("Country").str() },
            siteNotes = rest,
            latitude = (known?.get("Latitude") as? Number)?.toDouble()?.takeIf { it != 0.0 },
            longitude = (known?.get("Longitude") as? Number)?.toDouble()?.takeIf { it != 0.0 },
            maxDepth = (d["Depth"].dbl() ?: meta3?.num("MaxDepth") ?: uddf?.greatestDepth)?.let { round(it * 10) / 10 },
            avgDepth = avgDepth?.let { round(it * 10) / 10 },
            waterTempC = temp,
            tanks = tanks,
            weightKg = d["Weight"].dbl()?.takeIf { it > 0 },
            suitType = suit(d["Dress"].str()),
            notes = d["Notes"].str().trim(),
            buddy = d["Buddy"].str().trim(),
            visibilityMeters = d["Visibility"].str().filter { it.isDigit() }.toIntOrNull(),
            computer = listOf(model, serial.takeIf { it.isNotBlank() }?.let { "($it)" }.orEmpty()).filter { it.isNotBlank() }.joinToString(" "),
            profile = when {
                blob == null -> null
                format == "uddf" -> uddf?.let { uddfProfile(it) }
                else -> pnfProfile(blob, duration, imperial, tanks.any { it.pressureStartBar != null })
            },
        )
    }

    // ------------------------------------------------------------------ Bouteilles

    /** Les volumes cités dans `TankSize` (« 12L alu », « 15 », « 12 / 12 »), en litres ; les pieds cubes sont ignorés. */
    private fun volumes(tankSize: String): List<Double> =
        Regex("(\\d+(?:[.,]\\d+)?)\\s*([a-zA-Z]*)").findAll(tankSize).mapNotNull { m ->
            val unit = m.groupValues[2].lowercase()
            val v = m.groupValues[1].replace(',', '.').toDoubleOrNull()
            if (unit.startsWith("cu") || unit.startsWith("cf") || v == null || v !in 1.0..40.0) null else v
        }.toList()

    /**
     * Les bouteilles équipées d'un capteur allumé, avec leurs pressions (PSI converties en bar) et leur mélange ; le volume
     * vient de `TankSize`. Les capteurs éteints, à 0 PSI, sont ignorés. Sans capteur, on garde une bouteille avec le mélange.
     */
    private fun tanks(tankJson: JsonObject?, tankSize: String): List<Tank> {
        val volumes = volumes(tankSize)
        val data = (tankJson?.get("TankData") as? JsonArray).orEmpty().mapNotNull { it.asObject() }
        val used = data.filter {
            val on = (it["DiveTransmitter"] as? JsonObject)?.get("IsOn").str() == "true"
            on && (it["StartPressurePSI"].str().toDoubleOrNull() ?: 0.0) > 0
        }
        fun gas(o: JsonObject?): Pair<Int, Int> {
            val g = o?.get("GasProfile")?.asObject()
            return (g?.num("O2Percent")?.toInt() ?: 21) to (g?.num("HePercent")?.toInt() ?: 0)
        }
        if (used.isEmpty()) {
            val g = (tankJson?.get("GasProfiles") as? JsonArray)?.firstOrNull()?.asObject()
            return listOf(Tank(volumeLiters = volumes.firstOrNull(), o2Percent = g?.num("O2Percent")?.toInt() ?: 21, hePercent = g?.num("HePercent")?.toInt() ?: 0))
        }
        return used.mapIndexed { i, t ->
            val (o2, he) = gas(t)
            Tank(
                volumeLiters = volumes.getOrNull(i) ?: volumes.firstOrNull(),
                o2Percent = o2,
                hePercent = he,
                pressureStartBar = t["StartPressurePSI"].str().toDoubleOrNull()?.let { round(it * PSI_TO_BAR).toInt() },
                pressureEndBar = t["EndPressurePSI"].str().toDoubleOrNull()?.let { round(it * PSI_TO_BAR).toInt() },
            )
        }
    }

    private fun suit(dress: String): SuitType? {
        val s = dress.lowercase()
        return when {
            s.isBlank() -> null
            "semi" in s -> SuitType.SEMI_DRY
            "dry" in s || "étanche" in s || "etanche" in s -> SuitType.DRY
            else -> SuitType.WET.takeIf {
                "wet" in s || "shorty" in s || "skin" in s || "néopr" in s || "neopr" in s || "humide" in s
            }
        }
    }

    /** « Egypte Safaga » -> pays « Egypte » et reste « Safaga » (le pays est le plus long début reconnu avec un drapeau). */
    private fun splitCountry(location: String): Pair<String, String> {
        val words = location.split(Regex("[\\s,]+")).filter { it.isNotEmpty() }
        for (n in words.size downTo 1) {
            val candidate = words.take(n).joinToString(" ")
            if (countryFlag(candidate) != null) return candidate to words.drop(n).joinToString(" ")
        }
        return "" to location
    }

    // ------------------------------------------------------------------ Journal UDDF (plongées saisies dans un autre logiciel)

    /** Décompresse un blob de journal : taille sur 4 octets puis flux gzip (ou gzip seul). */
    private fun unzip(blob: ByteArray): ByteArray? = try {
        if (blob.size > 2 && blob[0] == 0x1f.toByte() && blob[1] == 0x8b.toByte()) Inflate.gunzip(blob, 0) else Inflate.gunzip(blob, 4)
    } catch (e: Inflate.FormatException) {
        null
    }

    private class Uddf(
        /** (temps en s, profondeur en m, température en °C ou null). */
        val points: List<Triple<Double, Double, Double?>>,
        val greatestDepth: Double?,
        val avgDepth: Double?,
        val durationSeconds: Int?,
    )

    private fun tag(text: String, name: String): String? =
        Regex("<$name>\\s*([^<]*?)\\s*</$name>").find(text)?.groupValues?.get(1)?.takeIf { it.isNotEmpty() }

    private fun uddf(blob: ByteArray): Uddf? {
        val xml = unzip(blob)?.decodeToString() ?: return null
        val points = Regex("<waypoint>(.*?)</waypoint>", RegexOption.DOT_MATCHES_ALL).findAll(xml).mapNotNull { m ->
            val w = m.groupValues[1]
            val depth = tag(w, "depth")?.toDoubleOrNull() ?: return@mapNotNull null
            val time = tag(w, "divetime")?.toDoubleOrNull() ?: return@mapNotNull null
            // UDDF exprime les températures en kelvins.
            val temp = tag(w, "temperature")?.toDoubleOrNull()?.let { if (it > 200) it - 273.15 else it }
            Triple(time, depth, temp)
        }.toList()
        return Uddf(
            points = points,
            greatestDepth = tag(xml, "greatestdepth")?.toDoubleOrNull(),
            avgDepth = tag(xml, "averagedepth")?.toDoubleOrNull(),
            durationSeconds = tag(xml, "diveduration")?.toDoubleOrNull()?.toInt(),
        )
    }

    /**
     * Les journaux UDDF d'une plongée saisie à la main ne contiennent que quatre points (un trapèze) : on n'en fait pas de
     * courbe, qui serait fausse. Un vrai profil (plus de douze points) est rééchantillonné à intervalle régulier.
     */
    private fun uddfProfile(u: Uddf): DiveProfile? {
        val pts = u.points.sortedBy { it.first }
        if (pts.size < 12) return null
        val total = pts.last().first
        if (total <= 0) return null
        val deltas = pts.zipWithNext { a, b -> b.first - a.first }.filter { it > 0 }.sorted()
        val median = deltas.getOrNull(deltas.size / 2) ?: return null
        var interval = listOf(1, 2, 3, 4, 5, 10, 15, 20, 30, 60).minByOrNull { abs(it - median) } ?: 10
        while (total / interval > 1500) interval *= 2 // une plongée très longue : on garde la courbe légère
        val hasTemp = pts.count { it.third != null } >= pts.size / 2

        fun at(t: Double, value: (Triple<Double, Double, Double?>) -> Double?): Double? {
            var i = pts.indexOfLast { it.first <= t }
            if (i < 0) i = 0
            val a = pts[i]
            val b = pts.getOrNull(i + 1) ?: return value(a)
            val va = value(a) ?: return value(b)
            val vb = value(b) ?: return va
            return if (b.first == a.first) va else va + (vb - va) * ((t - a.first) / (b.first - a.first))
        }

        val count = (total / interval).toInt() + 1
        val times = List(count) { it * interval.toDouble() }
        return DiveProfile(
            intervalSeconds = interval,
            depthDm = times.map { round((at(it) { p -> p.second } ?: 0.0) * 10).toInt() },
            tempDc = if (hasTemp) times.map { round((at(it) { p -> p.third } ?: 0.0) * 10).toInt() } else emptyList(),
        )
    }

    // ------------------------------------------------------------------ Profil (format PNF)

    /**
     * Le blob commence par la taille décompressée (4 octets, petit-boutiste) puis un flux gzip. Il contient des
     * enregistrements de 32 octets ; ceux de type 0x01 sont les échantillons : profondeur (octets 1-2, 1/10 m),
     * température (octet 14) et pression de la bouteille 1 (octets 28-29, en demi-PSI).
     */
    private fun pnfProfile(blob: ByteArray, durationSeconds: Int?, imperial: Boolean, withPressure: Boolean): DiveProfile? {
        val raw = unzip(blob) ?: return null
        val depth = ArrayList<Double>()
        val temp = ArrayList<Double>()
        val pressure = ArrayList<Double>()
        var o = 0
        while (o + 32 <= raw.size) {
            if (raw[o].toInt() == 0x01) {
                val d = (((raw[o + 1].toInt() and 0xFF) shl 8) or (raw[o + 2].toInt() and 0xFF)) / 10.0
                depth += if (imperial) d * 0.3048 else d
                val t = raw[o + 14].toInt() // signé
                temp += if (imperial) (t - 32) / 1.8 else t.toDouble()
                val p = ((raw[o + 28].toInt() and 0xFF) shl 8) or (raw[o + 29].toInt() and 0xFF)
                pressure += if (p < 0xFFF0) p * 2 * PSI_TO_BAR else -1.0
            }
            o += 32
        }
        if (depth.size < 2) return null

        // On garde la plongée, avec un point de surface de part et d'autre.
        val first = depth.indexOfFirst { it >= 0.3 }
        val last = depth.indexOfLast { it >= 0.3 }
        if (first < 0) return null
        val from = maxOf(0, first - 1)
        val to = minOf(depth.size - 1, last + 1)

        // Intervalle entre échantillons : durée de la plongée / nombre d'échantillons sous l'eau, arrondi à une valeur usuelle.
        val interval = if (durationSeconds != null) {
            val raw5 = durationSeconds.toDouble() / (last - first + 1)
            listOf(1, 2, 3, 4, 5, 6, 10, 12, 15, 20, 30, 60).minByOrNull { abs(it - raw5) } ?: 10
        } else {
            10
        }

        val slice = from..to
        val pressures = pressure.slice(slice)
        return DiveProfile(
            intervalSeconds = interval,
            depthDm = depth.slice(slice).map { round(it * 10).toInt() },
            tempDc = temp.slice(slice).map { round(it * 10).toInt() },
            pressureBar = if (withPressure && pressures.any { it > 0 }) {
                var prev = pressures.firstOrNull { it > 0 } ?: 0.0
                pressures.map { if (it > 0) { prev = it; round(it).toInt() } else round(prev).toInt() }
            } else {
                emptyList()
            },
        )
    }

    // ------------------------------------------------------------------ Outils

    private fun Any?.str(): String = when (this) {
        null -> ""
        is ByteArray -> decodeToString()
        is Double -> if (this % 1.0 == 0.0) toLong().toString() else toString()
        else -> toString()
    }

    private fun Any?.dbl(): Double? =
        (this as? Number)?.toDouble() ?: str().trim().replace(',', '.').toDoubleOrNull()

    private fun JsonElement?.str(): String = (this as? JsonPrimitive)?.content.orEmpty()

    private fun JsonElement.asObject(): JsonObject? = this as? JsonObject

    private fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.intOrNull?.toDouble() }

    private fun String.json(): JsonObject? =
        if (isBlank()) null else runCatching { json.parseToJsonElement(this).jsonObject }.getOrNull()
}
