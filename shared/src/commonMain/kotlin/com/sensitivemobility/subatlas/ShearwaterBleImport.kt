package com.sensitivemobility.subatlas

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.abs
import kotlin.math.round
import kotlin.time.Instant

/**
 * Plongées téléchargées en Bluetooth depuis un ordinateur Shearwater, converties en [ImportedDive] : même aperçu, même fusion et
 * même application que les autres imports (voir `AppState.planImport`).
 *
 * Les données de l'ordinateur sont le flux « PNF » décompressé que Shearwater Cloud garde dans son export : enregistrements de 32
 * octets, ceux de type 0x01 sont les échantillons (voir `ShearwaterImport.pnfProfileFromRecords`). Le sommaire de l'appareil donne
 * l'heure (celle de l'horloge de l'ordinateur, lue comme une heure locale), la durée et la profondeur maximale. L'ordinateur ne
 * connaît ni le site, ni les notes, ni le binôme : ils restent à saisir.
 */
internal object ShearwaterBleImport {
    private const val PSI_TO_BAR = 0.0689476

    /** Le nom du modèle d'après l'identifiant lu en `0x8060`. */
    fun modelName(id: Int?): String = when (id) {
        2 -> "Predator"
        3 -> "Petrel"
        4 -> "NERD"
        5 -> "Perdix"
        6 -> "Perdix AI"
        7 -> "NERD 2"
        8 -> "Teric"
        9 -> "Peregrine"
        10 -> "Petrel 3"
        11 -> "Perdix 2"
        12 -> "Tern"
        13 -> "Peregrine TX"
        14 -> "Perdix 3"
        else -> "Shearwater"
    }

    /** « Peregrine TX (977D8B42) » : le même libellé que l'import de l'export Shearwater Cloud. */
    fun computerName(modelId: Int?, serial: String): String = "${modelName(modelId)}${serial.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()}"

    /** Identifiant stable d'une plongée de cet appareil : un second téléchargement ne la refait pas. */
    fun key(serial: String, entry: ShearwaterManifestEntry) = "shearwater-ble:$serial:${entry.fingerprint}"

    /** La date (`yyyy-MM-dd`) et l'heure (`HH:mm`) d'un instant de l'horloge de l'ordinateur. */
    fun dateTime(epochSeconds: Long): Pair<String, String> {
        val t = Instant.fromEpochSeconds(epochSeconds).toLocalDateTime(TimeZone.UTC)
        fun two(n: Int) = n.toString().padStart(2, '0')
        return "${t.year.toString().padStart(4, '0')}-${two(t.monthNumber)}-${two(t.dayOfMonth)}" to "${two(t.hour)}:${two(t.minute)}"
    }

    /**
     * La plongée du sommaire est-elle déjà dans le carnet ? Oui si une plongée porte sa clé, ou si une plongée déjà importée d'un
     * export Shearwater Cloud tombe le même jour, à moins d'une heure, avec la même profondeur maximale (à 0,3 m près).
     */
    fun alreadyImported(entry: ShearwaterManifestEntry, serial: String, dives: List<Dive>): Boolean {
        val key = key(serial, entry)
        if (dives.any { key in it.importKeys() }) return true
        val (date, time) = dateTime(entry.fingerprint)
        val start = minutesOfDay(time) ?: return false
        val depth = entry.maxDepthDm / 10.0
        return dives.any { d ->
            val at = minutesOfDay(d.startTime)
            val max = d.maxDepth
            d.date == date && d.importKey.isNotEmpty() && at != null && abs(at - start) <= 60 && max != null && abs(max - depth) <= 0.3
        }
    }

    /**
     * Les gaz activés dans l'ordinateur (% d'O2 et d'hélium). Il en garde dix en mémoire, cinq en circuit ouvert puis cinq diluants de
     * recycleur, y compris ceux qui sont désactivés : seul le masque des octets 17-18 de l'enregistrement d'ouverture `0x14` dit lesquels
     * servent (bit 0 : le premier). L'O2 est à partir de l'octet 20 de l'enregistrement `0x10` ; l'hélium des deux premiers gaz à ses
     * octets 30-31, celui des huit autres à partir de l'octet 1 de l'enregistrement `0x11`.
     */
    private fun enabledGases(raw: ByteArray): List<Pair<Int, Int>> {
        fun record(type: Int): Int? =
            (0..raw.size - ShearwaterBle.RECORD_SIZE step ShearwaterBle.RECORD_SIZE).firstOrNull { (raw[it].toInt() and 0xFF) == type }
        fun byte(p: Int) = raw[p].toInt() and 0xFF
        val first = record(0x10) ?: return emptyList()
        val second = record(0x11) ?: return emptyList()
        val flags = record(0x14) ?: return emptyList()
        val mask = (byte(flags + 17) shl 8) or byte(flags + 18)
        return (0 until 10).filter { mask and (1 shl it) != 0 }.mapNotNull { k ->
            val o2 = byte(first + 20 + k)
            val he = if (k < 2) byte(first + 30 + k) else byte(second + k - 1)
            (o2 to he).takeIf { o2 in 5..100 && o2 + he <= 100 }
        }
    }

    /** Convertit une plongée téléchargée (enregistrements décompressés) ; `null` si elle ne contient pas d'échantillons. */
    fun toDive(entry: ShearwaterManifestEntry, raw: ByteArray, serial: String, modelId: Int?): ImportedDive? {
        if (raw.size < ShearwaterBle.RECORD_SIZE) return null
        // Octet 8 de l'enregistrement d'ouverture : 0 = unités métriques, 1 = impériales.
        val imperial = raw[8].toInt() == 1
        val samples = ArrayList<Int>() // positions des échantillons (type 0x01)
        var o = 0
        while (o + ShearwaterBle.RECORD_SIZE <= raw.size) {
            if (raw[o].toInt() == 0x01) samples += o
            o += ShearwaterBle.RECORD_SIZE
        }
        if (samples.size < 2) return null

        fun u16(p: Int) = ((raw[p].toInt() and 0xFF) shl 8) or (raw[p + 1].toInt() and 0xFF)
        fun depthM(i: Int): Double = u16(samples[i] + 1) / 10.0 * if (imperial) 0.3048 else 1.0
        val first = (0 until samples.size).firstOrNull { depthM(it) >= 0.3 } ?: return null
        val last = (0 until samples.size).last { depthM(it) >= 0.3 }
        val immersed = first..last

        val duration = entry.durationSeconds.toInt().takeIf { it > 0 }
        val (date, start) = dateTime(entry.fingerprint)
        // Profondeur moyenne : celle de Shearwater, la moyenne de tous les échantillons de profondeur non nulle.
        val avgDepth = samples.indices.map { depthM(it) }.filter { it > 0.0 }.average()
        val temps = immersed.map { raw[samples[it] + 14].toInt().let { t -> if (imperial) (t - 32) / 1.8 else t.toDouble() } }
        val profile = ShearwaterImport.pnfProfileFromRecords(raw, duration, imperial, withPressure = true)
        val interval = profile?.intervalSeconds ?: 5

        // Bouteilles : une par gaz. Les mélanges respirés (octets 8 et 9 de chaque échantillon : % d'O2 et d'hélium), dans l'ordre où ils servent :
        // le premier est celui de la bouteille équipée de l'émetteur (pression de début et de fin si l'émetteur était allumé : octets 28-29, en
        // demi-PSI, 0xFFF0 et plus : absent), les suivants (bouteille de déco, relais) n'ont pas de pression mais leur durée d'utilisation et
        // leur profondeur moyenne. Un mélange respiré moins de 10 s est un parasite, pas une bouteille. S'y ajoutent les gaz **activés** dans
        // l'ordinateur qui n'ont pas été respirés : une bouteille de déco ou de secours emportée mais pas utilisée a aussi sa bouteille.
        fun pressureBar(i: Int): Int? = u16(samples[i] + 28).takeIf { it in 1 until 0xFFF0 }?.let { round(it * 2 * PSI_TO_BAR).toInt() }
        class Use(var samples: Int = 0, var depthSum: Double = 0.0)
        val uses = LinkedHashMap<Pair<Int, Int>, Use>()
        for (i in immersed) {
            val mix = (raw[samples[i] + 8].toInt() and 0xFF) to (raw[samples[i] + 9].toInt() and 0xFF)
            if (mix.first !in 5..100) continue
            val use = uses.getOrPut(mix) { Use() }
            use.samples++
            use.depthSum += depthM(i)
        }
        val mixes = uses.entries.filter { it.value.samples * interval >= 10 }.map { it.key }
        val unused = enabledGases(raw).filter { it !in mixes }.distinct()
        val firstPressure = immersed.firstNotNullOfOrNull { pressureBar(it) }
        val lastPressure = immersed.reversed().firstNotNullOfOrNull { pressureBar(it) }
        val tanks = (mixes.ifEmpty { listOf(21 to 0) }).mapIndexed { i, mix ->
            val use = uses[mix]
            Tank(
                o2Percent = mix.first,
                hePercent = mix.second,
                pressureStartBar = if (i == 0) firstPressure else null,
                pressureEndBar = if (i == 0) lastPressure else null,
                avgDepthMeters = if (i > 0 && use != null && use.samples > 0) round(use.depthSum / use.samples * 10) / 10 else null,
                usedMinutes = if (i > 0 && use != null) (use.samples * interval / 60.0).let { round(it).toInt() }.coerceAtLeast(1) else null,
            )
        } + unused.map { (o2, he) -> Tank(o2Percent = o2, hePercent = he) }

        return ImportedDive(
            key = key(serial, entry),
            modified = "",
            date = date,
            startTime = start,
            endTime = duration?.let { timePlusSeconds(start, it) },
            durationSeconds = duration,
            siteName = "",
            country = "",
            siteNotes = "",
            latitude = null,
            longitude = null,
            maxDepth = (entry.maxDepthDm / 10.0 * if (imperial) 0.3048 else 1.0).let { round(it * 10) / 10 },
            avgDepth = round(avgDepth * 10) / 10,
            waterTempC = round(temps.average() * 10) / 10,
            tanks = tanks,
            weightKg = null,
            suitType = null,
            notes = "",
            buddy = "",
            visibilityMeters = null,
            computer = computerName(modelId, serial),
            profile = profile,
            fromDevice = true,
        )
    }
}
