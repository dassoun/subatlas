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

    fun parse(bytes: ByteArray): List<ShearwaterDive> {
        val db = try {
            SqliteFile(bytes)
        } catch (e: IllegalArgumentException) {
            throw NotShearwaterException()
        }
        if ("dive_details" !in db.tables) throw NotShearwaterException()

        val logs = db.rows("log_data").associateBy { it["log_id"].str() }
        val knownSites = db.rows("dive_site").associateBy { it["Name"].str().trim().lowercase() }

        return db.rows("dive_details").mapNotNull { d ->
            val id = d["DiveId"].str()
            if (id.isBlank()) return@mapNotNull null
            runCatching { convert(id, d, logs[id], knownSites) }.getOrNull()
        }
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
        val duration = d["DiveLengthTime"].dbl()?.toInt()?.takeIf { it > 0 }
        val end = duration?.let { timePlusSeconds(start, it) }

        val calc = log?.get("calculated_values_from_samples").str().json()
        val tankJson = d["TankProfileData"].str().json()
        val avgDepth = calc?.num("AverageDepth")?.takeIf { it > 0 }
            ?: d["AverageDepth"].dbl()?.takeIf { it > 0 }
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
        val meta3 = log?.get("data_bytes_3").let { (it as? ByteArray)?.decodeToString() }.orEmpty().json()
        val imperial = meta3?.num("UnitSystem")?.toInt() == 1

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
            maxDepth = d["Depth"].dbl() ?: log?.get("data_bytes_3").let { (it as? ByteArray)?.decodeToString() }.orEmpty().json()?.num("MaxDepth"),
            avgDepth = avgDepth?.let { round(it * 10) / 10 },
            waterTempC = temp,
            tanks = tanks(tankJson, d["TankSize"].str()),
            weightKg = d["Weight"].dbl(),
            suitType = suit(d["Dress"].str()),
            notes = d["Notes"].str().trim(),
            buddy = d["Buddy"].str().trim(),
            visibilityMeters = d["Visibility"].str().filter { it.isDigit() }.toIntOrNull(),
            computer = listOf(model, serial.takeIf { it.isNotBlank() }?.let { "($it)" }.orEmpty()).filter { it.isNotBlank() }.joinToString(" "),
            profile = (log?.get("data_bytes_1") as? ByteArray)?.let { profile(it, duration, imperial, hasPressure(tankJson)) },
        )
    }

    // ------------------------------------------------------------------ Bouteilles

    private fun hasPressure(tankJson: JsonObject?): Boolean =
        (tankJson?.get("TankData") as? JsonArray)?.firstOrNull()?.asObject()?.get("StartPressurePSI").str().toDoubleOrNull() != null

    /** Les bouteilles équipées d'un capteur (pressions de début et de fin), avec leur mélange ; le volume vient de `TankSize`. */
    private fun tanks(tankJson: JsonObject?, tankSize: String): List<Tank> {
        val volumes = Regex("(\\d+(?:[.,]\\d+)?)\\s*[lL]").findAll(tankSize).mapNotNull { it.groupValues[1].replace(',', '.').toDoubleOrNull() }.toList()
        val data = (tankJson?.get("TankData") as? JsonArray).orEmpty().mapNotNull { it.asObject() }
        val used = data.filter { it.get("StartPressurePSI").str().toDoubleOrNull() != null }
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
                pressureStartBar = t.get("StartPressurePSI").str().toDoubleOrNull()?.let { round(it * PSI_TO_BAR).toInt() },
                pressureEndBar = t.get("EndPressurePSI").str().toDoubleOrNull()?.let { round(it * PSI_TO_BAR).toInt() },
            )
        }
    }

    private fun suit(dress: String): SuitType? {
        val s = dress.lowercase()
        return when {
            s.isBlank() -> null
            "semi" in s -> SuitType.SEMI_DRY
            "dry" in s || "étanche" in s || "etanche" in s -> SuitType.DRY
            else -> SuitType.WET.takeIf { "wet" in s || "shorty" in s || "néopr" in s || "neopr" in s || "humide" in s }
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

    // ------------------------------------------------------------------ Profil (format PNF)

    /**
     * Le blob commence par la taille décompressée (4 octets, petit-boutiste) puis un flux gzip. Il contient des
     * enregistrements de 32 octets ; ceux de type 0x01 sont les échantillons : profondeur (octets 1-2, 1/10 m),
     * température (octet 14) et pression de la bouteille 1 (octets 28-29, en demi-PSI).
     */
    private fun profile(blob: ByteArray, durationSeconds: Int?, imperial: Boolean, withPressure: Boolean): DiveProfile? {
        val raw = try {
            if (blob.size > 2 && blob[0] == 0x1f.toByte() && blob[1] == 0x8b.toByte()) Inflate.gunzip(blob, 0) else Inflate.gunzip(blob, 4)
        } catch (e: Inflate.FormatException) {
            return null
        }
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
