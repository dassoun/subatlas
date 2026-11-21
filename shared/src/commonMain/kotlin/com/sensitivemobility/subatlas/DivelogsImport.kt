package com.sensitivemobility.subatlas

import kotlin.math.round
import kotlin.math.roundToInt

/**
 * Lecture d'un export « Dive Logs Data » de divelogs.de (fichier `.DLD`) : une archive ZIP qui contient un fichier XML par plongée
 * (date, heure, site, profondeurs, bouteilles, notes, position GPS du site et, souvent, les échantillons de la courbe).
 *
 * Chaque plongée est convertie en [ShearwaterDive] marquée [ShearwaterDive.fromDivelogs] : l'aperçu, la fusion avec le carnet et
 * l'application de l'import sont ceux de l'import Shearwater (voir `AppState.planShearwaterImport`).
 */
internal object DivelogsImport {
    class NotDivelogsException : Exception()

    /** Un fichier ZIP commence par « PK » : c'est ce qui distingue un export Dive Logs Data d'un export Shearwater (SQLite). */
    fun looksLikeZip(bytes: ByteArray): Boolean = bytes.size > 3 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()

    fun parse(bytes: ByteArray): ShearwaterImport.ShearwaterFile {
        val entries = try {
            ZipReader.entries(bytes)
        } catch (e: Exception) {
            throw NotDivelogsException()
        }
        val xmls = entries.filter { it.first.endsWith(".xml", ignoreCase = true) }
            .map { it.second.decodeToString() }
            .filter { "<DIVELOGSDATA>" in it }
        if (xmls.isEmpty()) throw NotDivelogsException()
        var skipped = 0
        val dives = xmls.mapNotNull { xml -> parseDive(xml).also { if (it == null) skipped++ } }
        return ShearwaterImport.ShearwaterFile(dives, skipped)
    }

    /** « France Toulon » -> (« France », « Toulon ») : le pays d'abord (un à trois mots), puis la ville. */
    fun splitLocation(location: String): Pair<String, String> {
        val words = location.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val countryWords = (minOf(3, words.size) downTo 1).firstOrNull { n -> countryFlag(words.take(n).joinToString(" ")) != null } ?: minOf(1, words.size)
        return words.take(countryWords).joinToString(" ") to words.drop(countryWords).joinToString(" ")
    }

    private fun parseDive(xml: String): ShearwaterDive? {
        val id = tag(xml, "DIVELOGSID")?.takeIf { it.isNotBlank() } ?: tag(xml, "DIVELOGSDIVENUMBER") ?: return null
        // « 29.04.2015 » -> « 2015-04-29 »
        val dmy = (tag(xml, "DATE") ?: return null).split('.')
        if (dmy.size != 3) return null
        val date = "${dmy[2]}-${dmy[1].padStart(2, '0')}-${dmy[0].padStart(2, '0')}"
        val start = tag(xml, "TIME")?.take(5)?.takeIf { it.length == 5 && it[2] == ':' }
        val duration = tag(xml, "DIVETIMESEC")?.toIntOrNull()?.takeIf { it > 0 }

        // « France Toulon » : le pays d'abord (un à trois mots), puis la ville, que le carnet garde dans les notes du site.
        val (country, city) = splitLocation(tag(xml, "LOCATION").orEmpty())

        val temps = tempsAndDepths(xml)
        val waterTemp = number(xml, "WATERTEMPMAXDEPTH") ?: number(xml, "WATERTEMPATEND")

        val header = xml.substringBefore("<SAMPLE>")
        val extraTanks = Regex("<TANK>(.*?)</TANK>", RegexOption.DOT_MATCHES_ALL).findAll(header).map { it.groupValues[1] }.toList()
        val mainBlock = header.substringBefore("<ADDITIONALTANKS>")
        val tanks = listOfNotNull(tank(mainBlock)) + extraTanks.mapNotNull { tank(it) }

        val computer = tag(xml, "COMPUTERMODEL").orEmpty().trim().takeIf { !it.contains("Dummy", ignoreCase = true) }.orEmpty()
        return ShearwaterDive(
            key = "divelogs:$id",
            modified = "",
            date = date,
            startTime = start,
            endTime = start?.let { s -> duration?.let { timePlusSeconds(s, it) } },
            durationSeconds = duration,
            siteName = tag(xml, "SITE").orEmpty().trim(),
            country = country,
            siteNotes = city,
            latitude = number(xml, "LAT"),
            longitude = number(xml, "LNG"),
            maxDepth = number(xml, "MAXDEPTH")?.let { round(it * 10) / 10 },
            avgDepth = number(xml, "MEANDEPTH")?.let { round(it * 10) / 10 },
            waterTempC = waterTemp ?: temps.second.firstOrNull(),
            tanks = tanks,
            weightKg = number(xml, "WEIGHT"),
            suitType = null,
            notes = tag(xml, "LOGNOTES").orEmpty().trim(),
            buddy = tag(xml, "PARTNER").orEmpty().trim(),
            visibilityMeters = tag(xml, "WATERVIZIBILITY")?.let { Regex("(\\d+)\\s*m").find(it)?.groupValues?.get(1)?.toIntOrNull() },
            computer = computer,
            profile = profile(xml, temps),
            fromDivelogs = true,
        )
    }

    /** Un nombre strictement positif (le fichier écrit 0 ou 0.0 pour « non renseigné »). */
    private fun number(xml: String, name: String): Double? = tag(xml, name)?.toDoubleOrNull()?.takeIf { it != 0.0 }

    private fun tank(block: String): Tank? {
        val size = number(block, "CYLINDERSIZE")
        val start = number(block, "CYLINDERSTARTPRESSURE")?.roundToInt()
        val end = number(block, "CYLINDERENDPRESSURE")?.roundToInt()
        if (size == null && start == null && end == null) return null
        val he = number(block, "HEPCT")?.roundToInt() ?: 0
        return Tank(
            volumeLiters = size,
            o2Percent = number(block, "O2PCT")?.roundToInt() ?: 21,
            hePercent = he,
            pressureStartBar = start,
            pressureEndBar = end,
        )
    }

    /** Profondeurs (en mètres) et températures de l'eau relevées dans les échantillons : la température n'y est notée qu'en passant. */
    private fun tempsAndDepths(xml: String): Pair<List<Double>, List<Double>> {
        val depths = ArrayList<Double>()
        val temps = ArrayList<Double>()
        var pos = 0
        while (true) {
            val a = xml.indexOf("<SAMPLE>", pos)
            if (a < 0) break
            val b = xml.indexOf("</SAMPLE>", a)
            if (b < 0) break
            pos = b + 9
            val block = xml.substring(a, b)
            depths += tag(block, "DEPTH")?.toDoubleOrNull() ?: continue
            tag(block, "TEMP")?.toDoubleOrNull()?.let { temps += it }
        }
        return depths to temps
    }

    private fun profile(xml: String, samples: Pair<List<Double>, List<Double>>): DiveProfile? {
        val interval = tag(xml, "SAMPLEINTERVAL")?.toIntOrNull()?.takeIf { it > 0 } ?: return null
        val depths = samples.first
        if (depths.size < 2) return null
        // Température : celle du dernier échantillon qui en portait une, reportée sur les suivants (et sur les premiers).
        val perSample = ArrayList<Int?>()
        var pos = 0
        while (true) {
            val a = xml.indexOf("<SAMPLE>", pos)
            if (a < 0) break
            val b = xml.indexOf("</SAMPLE>", a)
            if (b < 0) break
            pos = b + 9
            val block = xml.substring(a, b)
            if (tag(block, "DEPTH")?.toDoubleOrNull() == null) continue
            perSample += tag(block, "TEMP")?.toDoubleOrNull()?.let { (it * 10).roundToInt() }
        }
        val firstKnown = perSample.firstOrNull { it != null }
        var last = firstKnown
        val tempDc = if (firstKnown == null) emptyList() else perSample.map { t -> (t ?: last)!!.also { last = it } }
        return DiveProfile(interval, depths.map { (it * 10).roundToInt() }, tempDc = tempDc)
    }

    // --- XML minimal : les fichiers Dive Logs Data n'utilisent que des balises simples, sans attributs ---

    /** Contenu de la première balise `<NAME>…</NAME>` (CDATA retiré, entités décodées) ; `null` si absente ou vide (`<NAME/>`). */
    private fun tag(xml: String, name: String): String? {
        val open = xml.indexOf("<$name>")
        if (open < 0) return null
        val from = open + name.length + 2
        val end = xml.indexOf("</$name>", from)
        if (end < 0) return null
        val raw = xml.substring(from, end).trim()
        return if (raw.startsWith("<![CDATA[") && raw.endsWith("]]>")) raw.removePrefix("<![CDATA[").removeSuffix("]]>") else decodeEntities(raw)
    }

    private fun decodeEntities(text: String): String {
        if ('&' !in text) return text
        return text.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
            .replace(Regex("&#(\\d+);")) { it.groupValues[1].toInt().toChar().toString() }
            .replace("&amp;", "&")
    }
}

/** Lecture d'une archive ZIP (méthodes « stockée » et « deflate ») par son répertoire central, en Kotlin pur. */
internal object ZipReader {
    /** Les fichiers de l'archive : (nom, contenu). */
    fun entries(src: ByteArray): List<Pair<String, ByteArray>> {
        fun u16(p: Int) = (src[p].toInt() and 0xFF) or ((src[p + 1].toInt() and 0xFF) shl 8)
        fun u32(p: Int) = u16(p) or (u16(p + 2) shl 16)
        var eocd = src.size - 22
        while (eocd >= 0 && u32(eocd) != 0x06054b50) eocd--
        if (eocd < 0) throw Inflate.FormatException("not a zip file")
        val count = u16(eocd + 10)
        var p = u32(eocd + 16)
        val out = ArrayList<Pair<String, ByteArray>>(count)
        repeat(count) {
            if (u32(p) != 0x02014b50) throw Inflate.FormatException("bad zip directory")
            val method = u16(p + 10)
            val compressed = u32(p + 20)
            val nameLength = u16(p + 28)
            val extraLength = u16(p + 30)
            val commentLength = u16(p + 32)
            val local = u32(p + 42)
            val name = src.decodeToString(p + 46, p + 46 + nameLength)
            p += 46 + nameLength + extraLength + commentLength
            val data = local + 30 + u16(local + 26) + u16(local + 28)
            when (method) {
                0 -> out += name to src.copyOfRange(data, data + compressed)
                8 -> out += name to Inflate.inflate(src, data)
            }
        }
        return out
    }
}
