package com.sensitivemobility.subatlas

import kotlin.math.round

/**
 * Le format d'une plongée d'un Mares de la famille « Sirius », d'après `mares_iconhd_parser.c` de libdivecomputer (variante
 * « Genius »). Une plongée est un **en-tête** (entiers petit-boutistes) suivi du **profil** : un type et une version, puis des
 * enregistrements délimités par un nom de quatre lettres (`DPRS` : une mesure toutes les 5 s, `AIRS` : la pression d'une bouteille…),
 * chacun protégé par une somme de contrôle.
 */
internal object MaresDive {
    private const val INTERVAL_SECONDS = 5

    /** Un gaz de l'en-tête et les pressions de la bouteille de même rang (0 : pas d'émetteur). */
    class Gas(
        val o2Percent: Int,
        val hePercent: Int,
        /** 0 : désactivé, 1 : prêt, 2 : utilisé, 3 : écarté. */
        val state: Int,
        val startBar: Double,
        val endBar: Double,
        val volume: Int,
    )

    /** Ce que dit l'en-tête : de quoi lister la plongée sans lire son profil. */
    class Header(
        /** Taille de l'en-tête en octets : le profil la suit. */
        val size: Int,
        /** L'horodatage brut (4 octets) : il identifie la plongée. */
        val fingerprint: Long,
        val date: String,
        val time: String,
        val sampleCount: Int,
        val settings: Long,
        val major: Int,
        val maxDepth: Double,
        val avgDepth: Double,
        /** Volumes en litres (sinon l'ordinateur est réglé en unités impériales). */
        val metric: Boolean,
        val gases: List<Gas>,
    ) {
        /** Délai en surface au bout duquel la plongée se termine, compris dans les mesures (réglable depuis le firmware 1.2). */
        fun surfaceSeconds(profileMajor: Int?): Int =
            (if ((profileMajor ?: major) >= 1) ((settings shr 13) and 0x3F).toInt() else 3) * 60

        fun durationSeconds(profileMajor: Int? = null): Int = maxOf(0, sampleCount * INTERVAL_SECONDS - surfaceSeconds(profileMajor))
    }

    /** L'en-tête lu au début de [data] ; `null` si ce n'est pas un format connu (type 1, version 2.0 au plus). */
    fun header(data: ByteArray): Header? {
        if (data.size < 0x20) return null
        val type = u16(data, 0)
        val minor = byte(data, 2)
        val major = byte(data, 3)
        if (type != 1 || major * 256 + minor > 2 * 256) return null
        // La variante « Horizon » intercale 8 octets ; à partir de la version 1, l'en-tête en compte 16 de plus à la fin.
        val extra = if (byte(data, 0x10) == 1) 8 else 0
        val size = 0xB8 + extra + if (major >= 1) 16 else 0
        if (data.size < size) return null
        val stamp = u32(data, 0x08)
        val year = ((stamp shr 20) and 0xFFF).toInt()
        val month = ((stamp shr 16) and 0x0F).toInt()
        val day = ((stamp shr 11) and 0x1F).toInt()
        val hour = (stamp and 0x1F).toInt()
        val minute = ((stamp shr 5) and 0x3F).toInt()
        if (month !in 1..12 || day !in 1..31 || hour > 23 || minute > 59) return null
        val gases = (0 until 5).map { i ->
            val o = 0x54 + extra + i * 20
            val mix = u32(data, o)
            Gas(
                o2Percent = (mix and 0x7F).toInt(),
                hePercent = ((mix shr 14) and 0x7F).toInt(),
                state = ((mix shr 21) and 0x03).toInt(),
                startBar = u16(data, o + 4) / 100.0,
                endBar = u16(data, o + 6) / 100.0,
                volume = u16(data, o + 8),
            )
        }
        fun two(n: Int) = n.toString().padStart(2, '0')
        return Header(
            size = size,
            fingerprint = stamp,
            date = "${year.toString().padStart(4, '0')}-${two(month)}-${two(day)}",
            time = "${two(hour)}:${two(minute)}",
            sampleCount = u16(data, 0x20 + extra),
            settings = u32(data, 0x0C),
            major = major,
            maxDepth = u16(data, 0x22 + extra) / 10.0,
            avgDepth = u16(data, 0x24 + extra) / 10.0,
            metric = byte(data, 0x34 + extra) != 0,
            gases = gases,
        )
    }

    private class Sample(val depthDm: Int, val tempDc: Int, val gas: Int, val ndl: Int, val stopM: Int, var pressureBar: Double = 0.0)

    /**
     * Convertit une plongée (en-tête et profil à la suite) en plongée à importer ; `null` si elle est illisible : format inconnu,
     * enregistrement abîmé (somme de contrôle) ou aucune mesure sous l'eau.
     */
    fun toDive(data: ByteArray, serial: String, model: String): ImportedDive? {
        val h = header(data) ?: return null
        if (data.size < h.size + 4) return null
        val profileType = u16(data, h.size)
        val profileMajor = byte(data, h.size + 3)
        if (profileType > 1) return null

        val samples = ArrayList<Sample>()
        var o = h.size + 4
        while (o < data.size) {
            if (o + 10 > data.size) return null
            val name = data.copyOfRange(o, o + 4).decodeToString()
            val length = RECORD_SIZES[name] ?: return null
            if (o + length > data.size) return null
            if (data.copyOfRange(o + length - 4, o + length).decodeToString() != name) return null
            if (u16(data, o + length - 6) != crc16(data, o + 4, length - 10)) return null
            val body = o + 4
            when (name) {
                "DPRS" -> {
                    val misc = u32(data, body + 0x14)
                    val inStop = (misc shr 18) and 1L == 1L
                    samples += Sample(
                        depthDm = u16(data, body),
                        tempDc = signed(u16(data, body + 4)),
                        gas = ((misc shr 6) and 0x0F).toInt(),
                        ndl = if (inStop) 0 else u16(data, body + 0x0A),
                        stopM = if (inStop) ((misc shr 19) and 0x7F).toInt() else 0,
                    )
                }
                "SDPT" -> {
                    val misc = u32(data, body + 0x18)
                    val deco = u32(data, body + 0x1C)
                    val inStop = (misc shr 10) and 1L == 1L
                    samples += Sample(
                        depthDm = u16(data, body + 2),
                        tempDc = signed(u16(data, body + 6)),
                        gas = ((misc shr 6) and 0x0F).toInt(),
                        ndl = if (inStop) 0 else (deco and 0xFF).toInt(),
                        stopM = if (inStop) ((deco shr 3) and 0x7F).toInt() else 0,
                    )
                }
                // La pression est celle de la bouteille dont on respire à la dernière mesure.
                "AIRS" -> samples.lastOrNull()?.pressureBar = u16(data, body) / 100.0
            }
            o += length
        }

        // On garde la plongée, avec un point de surface de part et d'autre (les mesures continuent en surface avant la fin).
        val first = samples.indexOfFirst { it.depthDm >= 3 }
        if (first < 0) return null
        val last = samples.indexOfLast { it.depthDm >= 3 }
        val immersed = samples.subList(first, last + 1)
        val kept = samples.subList(maxOf(0, first - 1), minOf(samples.size, last + 2))

        // Une bouteille par gaz : d'abord ceux qu'on a respirés, dans l'ordre, puis les autres gaz activés (bouteille emportée).
        val breathed = immersed.map { it.gas }.distinct().filter { it in h.gases.indices }
        val unused = h.gases.indices.filter { it !in breathed && h.gases[it].state in 1..2 }
        val tanks = (breathed + unused).mapNotNull { i ->
            val g = h.gases[i]
            if (g.o2Percent !in 5..100 || g.o2Percent + g.hePercent > 100) return@mapNotNull null
            val used = immersed.filter { it.gas == i }
            Tank(
                volumeLiters = g.volume.takeIf { h.metric && g.startBar > 0 && it in 1..40 }?.toDouble(),
                o2Percent = g.o2Percent,
                hePercent = g.hePercent,
                pressureStartBar = g.startBar.takeIf { it > 0 }?.let { round(it).toInt() },
                pressureEndBar = g.endBar.takeIf { g.startBar > 0 && it > 0 && it < 360 }?.let { round(it).toInt() },
                avgDepthMeters = if (breathed.size > 1 && used.isNotEmpty()) round(used.sumOf { it.depthDm }.toDouble() / used.size) / 10 else null,
                usedMinutes = if (breathed.size > 1 && used.isNotEmpty()) maxOf(1, round(used.size * INTERVAL_SECONDS / 60.0).toInt()) else null,
            )
        }

        var pressure = kept.firstOrNull { it.pressureBar > 0 }?.pressureBar ?: 0.0
        val pressures = if (pressure > 0) {
            kept.map { if (it.pressureBar > 0) pressure = it.pressureBar; round(pressure).toInt() }
        } else {
            emptyList()
        }
        val switches = ArrayList<GasSwitch>()
        var gas = -1
        kept.forEachIndexed { i, s ->
            if (s.gas != gas && s.gas in h.gases.indices) {
                gas = s.gas
                switches += GasSwitch(i, h.gases[gas].o2Percent, h.gases[gas].hePercent)
            }
        }

        val duration = h.durationSeconds(profileMajor.takeIf { profileType == 0 })
        return ImportedDive(
            key = key(serial, h),
            modified = "",
            date = h.date,
            startTime = h.time,
            endTime = timePlusSeconds(h.time, duration),
            durationSeconds = duration,
            siteName = "",
            country = "",
            siteNotes = "",
            latitude = null,
            longitude = null,
            maxDepth = h.maxDepth,
            avgDepth = h.avgDepth.takeIf { it > 0 },
            waterTempC = round(immersed.map { it.tempDc }.average()) / 10,
            tanks = tanks,
            weightKg = null,
            suitType = null,
            notes = "",
            buddy = "",
            visibilityMeters = null,
            computer = "Mares $model" + serial.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty(),
            profile = DiveProfile(
                intervalSeconds = INTERVAL_SECONDS,
                depthDm = kept.map { it.depthDm },
                tempDc = kept.map { it.tempDc },
                pressureBar = pressures,
                ndlMin = kept.map { minOf(it.ndl, 99) },
                decoStopM = kept.map { it.stopM },
                // Un seul gaz : rien à signaler sur la courbe.
                gasSwitches = if (switches.size > 1) switches else emptyList(),
            ),
            fromDevice = true,
            fallbackSiteName = "Mares",
        )
    }

    /** Identifiant stable d'une plongée de cet appareil : un second téléchargement ne la refait pas. */
    fun key(serial: String, header: Header) = "mares-ble:$serial:${header.fingerprint}"

    private val RECORD_SIZES = mapOf("DSTR" to 58, "TISS" to 138, "DPRS" to 34, "SDPT" to 78, "AIRS" to 16, "DEND" to 162)

    /** CRC-16/CCITT (polynôme 0x1021, valeur de départ 0). */
    fun crc16(data: ByteArray, from: Int, length: Int): Int {
        var crc = 0
        for (i in from until from + length) {
            crc = crc xor (byte(data, i) shl 8)
            repeat(8) { crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1 }
            crc = crc and 0xFFFF
        }
        return crc
    }

    private fun byte(d: ByteArray, p: Int) = d[p].toInt() and 0xFF
    private fun u16(d: ByteArray, p: Int) = byte(d, p) or (byte(d, p + 1) shl 8)
    private fun u32(d: ByteArray, p: Int) = u16(d, p).toLong() or (u16(d, p + 2).toLong() shl 16)
    private fun signed(v: Int) = if (v >= 0x8000) v - 0x10000 else v
}
