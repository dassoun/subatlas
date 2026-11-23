package com.sensitivemobility.subatlas

import kotlin.math.abs
import kotlin.math.round
import kotlin.math.roundToInt

/**
 * Lecture d'un fichier UDDF (Universal Dive Data Format, uddf.org) : le format XML d'échange de carnets de plongée, que lisent
 * et écrivent Subsurface, MacDive, divelogs.de, Shearwater Cloud et beaucoup d'autres.
 *
 * Chaque plongée est convertie en [ShearwaterDive] marquée [ShearwaterDive.fromUddf] : l'aperçu, la fusion avec le carnet et
 * l'application de l'import sont ceux des autres imports (voir `AppState.planShearwaterImport`).
 *
 * Le format est lu avec tolérance : le standard impose des unités SI (profondeurs en m, durées en s, températures en kelvins,
 * pressions en pascals, volumes en m³, fractions de gaz entre 0 et 1), mais les logiciels s'en écartent. Une valeur qui n'a pas
 * la bonne grandeur est donc reconnue et convertie (voir [kelvinToCelsius], [pressureToBar], [volumeToLiters]).
 */
internal object UddfImport {
    class NotUddfException : Exception()

    /** Un point de courbe : temps (s), profondeur (m), température (°C) et pression de la bouteille (bar), quand ils sont connus. */
    class Point(
        val time: Double,
        val depth: Double,
        val temp: Double?,
        val pressuresBar: Map<Int, Double> = emptyMap(),
        /** Temps sans palier restant (`nodecotime`), en secondes. */
        val ndlSeconds: Double? = null,
        /** Profondeur du palier (`decostop`), en mètres ; 0 sans palier ; `null` si le point ne dit rien des paliers. */
        val decoDepth: Double? = null,
        /** PPO2 en bar : mesurée, sinon calculée, sinon de consigne (recycleur). */
        val ppo2Bar: Double? = null,
        val cnsPct: Double? = null,
        /** Nouveau mélange respiré à partir de ce point : (O₂ %, He %). */
        val switchMix: Pair<Int, Int>? = null,
        val alarms: List<String> = emptyList(),
        val heading: Double? = null,
        val gradientFactorPct: Double? = null,
        val pulseBpm: Double? = null,
        val otu: Double? = null,
        val remainingBottomSeconds: Double? = null,
        val diveMode: String = "",
    )

    /** Le texte commence par une déclaration XML ou par la balise `<uddf` : le test peut se faire sur les premiers octets du fichier. */
    fun looksLikeUddf(bytes: ByteArray): Boolean {
        val head = bytes.copyOfRange(0, minOf(bytes.size, 2048)).decodeToString().removePrefix("﻿").trimStart()
        return head.startsWith("<") && "<uddf" in head
    }

    fun parse(bytes: ByteArray): ShearwaterImport.ShearwaterFile {
        if (!looksLikeUddf(bytes)) throw NotUddfException()
        val root = try {
            Xml.parse(bytes.decodeToString().removePrefix("﻿"))
        } catch (e: Xml.XmlException) {
            throw NotUddfException()
        }
        if (root.name != "uddf") throw NotUddfException()

        val generator = root.child("generator")?.str("name").orEmpty()
        // Un export de divelogs.de nomme ses plongées `dive_<numéro>` : ce numéro est celui des fichiers .DLD, dont on reprend la clé
        // pour qu'une plongée déjà importée de l'un ne soit pas refaite depuis l'autre.
        val fromDivelogs = generator.contains("divelogs", ignoreCase = true)

        val sites = root.descendants("site").mapNotNull { n -> n.attrs["id"]?.let { it to n } }.toMap()
        val buddies = root.descendants("buddy").mapNotNull { n -> n.attrs["id"]?.let { it to n } }.toMap()
        val computers = root.descendants("divecomputer").mapNotNull { n -> n.attrs["id"]?.let { it to n } }.toMap()
        val mixes = root.descendants("mix").mapNotNull { n -> n.attrs["id"]?.let { it to n } }.toMap()

        var skipped = 0
        val dives = root.descendants("dive").mapNotNull { node ->
            val dive = runCatching { parseDive(node, sites, buddies, computers, mixes, fromDivelogs) }.getOrNull()
            if (dive == null) skipped++
            dive
        }
        return ShearwaterImport.ShearwaterFile(dives, skipped)
    }

    private fun parseDive(
        node: XmlNode,
        sites: Map<String, XmlNode>,
        buddies: Map<String, XmlNode>,
        computers: Map<String, XmlNode>,
        mixes: Map<String, XmlNode>,
        fromDivelogs: Boolean,
    ): ShearwaterDive? {
        val before = node.child("informationbeforedive")
        val after = node.child("informationafterdive")

        val (date, time) = dateAndTime(before) ?: return null
        val refs = before?.all("link")?.mapNotNull { it.attrs["ref"] }.orEmpty() +
            before?.child("equipmentused")?.all("link")?.mapNotNull { it.attrs["ref"] }.orEmpty()

        val duration = after?.num("diveduration")?.roundToInt()?.takeIf { it > 0 }
        val points = points(node.child("samples"), node.all("tankdata").map { it.attrs["id"].orEmpty() }, mixes)
        val avgDepth = positive(after?.num("averagedepth"))?.let { round(it * 10) / 10 }
        val maxDepth = positive(after?.num("greatestdepth"))?.let { round(it * 10) / 10 }
            ?: points.maxOfOrNull { it.depth }?.takeIf { it > 0 }?.let { round(it * 10) / 10 }

        // Le site : celui que la plongée désigne, avec sa position et son pays.
        val site = refs.firstNotNullOfOrNull { sites[it] }
        val geography = site?.child("geography")
        val address = geography?.child("address")
        var country = address?.str("country").orEmpty()
        var city = address?.str("city").orEmpty()
        if (country.isBlank()) {
            // « France Toulon » : le pays d'abord, puis la ville (usage de divelogs.de).
            val (c, t) = DivelogsImport.splitLocation(geography?.str("location").orEmpty())
            country = c
            if (city.isBlank()) city = t
        }
        val siteNotes = listOf(city, site?.child("notes")?.let { paragraphs(it) }.orEmpty()).filter { it.isNotBlank() }.joinToString("\n")

        val lat = geography?.num("latitude")
        val lon = geography?.num("longitude")
        // Un site sans position est écrit en 0 ; (0 ; 0) n'est pas un site de plongée.
        val located = lat != null && lon != null && !(lat == 0.0 && lon == 0.0)

        val tanks = node.all("tankdata").mapNotNull { tank(it, mixes) }
        val tempC = after?.num("lowesttemperature")?.let { kelvinToCelsius(it) }
            ?: points.mapNotNull { it.temp }.takeIf { it.isNotEmpty() }?.min()
        val buddy = refs.mapNotNull { buddies[it] }.map { personName(it) }.filter { it.isNotBlank() }.joinToString(", ")
        val computer = refs.mapNotNull { computers[it] }.map { personName(it) }.firstOrNull { it.isNotBlank() }.orEmpty()
        val id = node.attrs["id"]?.takeIf { it.isNotBlank() } ?: before?.str("divenumber") ?: "$date $time"
        val key = if (fromDivelogs && id.startsWith("dive_")) "divelogs:${id.removePrefix("dive_")}" else "uddf:$id"

        return ShearwaterDive(
            key = key,
            modified = "",
            date = date,
            startTime = time,
            endTime = time?.let { s -> duration?.let { timePlusSeconds(s, it) } },
            durationSeconds = duration,
            siteName = site?.str("name").orEmpty(),
            country = country,
            siteNotes = siteNotes,
            latitude = if (located) lat else null,
            longitude = if (located) lon else null,
            maxDepth = maxDepth,
            avgDepth = avgDepth,
            waterTempC = tempC?.let { round(it * 10) / 10 },
            tanks = tanks,
            weightKg = positive(before?.child("equipmentused")?.num("leadquantity")),
            suitType = null,
            notes = after?.child("notes")?.let { paragraphs(it) }.orEmpty(),
            buddy = buddy,
            visibilityMeters = positive(after?.num("visibility"))?.roundToInt(),
            computer = computer,
            profile = profileFrom(points),
            fromDivelogs = false,
            fromUddf = true,
        )
    }

    /** Date `yyyy-MM-dd` et heure `HH:mm` (absente si seule la date est connue) de la plongée. */
    private fun dateAndTime(before: XmlNode?): Pair<String, String?>? {
        val text = before?.str("datetime")
        if (text != null && text.length >= 10 && text[4] == '-' && text[7] == '-') {
            val time = text.takeIf { it.length >= 16 && (it[10] == 'T' || it[10] == ' ') && it[13] == ':' }?.substring(11, 16)
            return text.substring(0, 10) to time
        }
        // UDDF 2 : <date><year/><month/><day/></date> et <time><hour/><minute/></time>.
        val d = before?.child("date") ?: return null
        val year = d.str("year")?.toIntOrNull() ?: return null
        val month = d.str("month")?.toIntOrNull() ?: return null
        val day = d.str("day")?.toIntOrNull() ?: return null
        val t = before.child("time")
        val time = t?.let { n -> n.str("hour")?.toIntOrNull()?.let { h -> "${h.toString().padStart(2, '0')}:${(n.str("minute")?.toIntOrNull() ?: 0).toString().padStart(2, '0')}" } }
        return "${year.toString().padStart(4, '0')}-${month.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')}" to time
    }

    private fun tank(node: XmlNode, mixes: Map<String, XmlNode>): Tank? {
        val volume = volumeToLiters(node.num("tankvolume"))
        val start = pressureToBar(node.num("tankpressurebegin"))
        val end = pressureToBar(node.num("tankpressureend"))
        val mix = node.child("link")?.attrs?.get("ref")?.let { mixes[it] }
        val o2 = mix?.num("o2")?.let { fractionToPercent(it) }
        val he = mix?.num("he")?.let { fractionToPercent(it) } ?: 0
        if (volume == null && start == null && end == null && o2 == null) return null
        return Tank(volumeLiters = volume, o2Percent = o2 ?: 21, hePercent = he, pressureStartBar = start, pressureEndBar = end)
    }

    /**
     * Les points de la courbe. La pression de chaque bouteille est rangée par numéro de bouteille : celui de la bouteille que
     * désigne `ref` (l'ordre des `tankdata`), sinon l'ordre des éléments `tankpressure` du point.
     */
    private fun points(samples: XmlNode?, tankIds: List<String>, mixes: Map<String, XmlNode>): List<Point> = samples?.all("waypoint").orEmpty().mapNotNull { w ->
        val depth = w.num("depth") ?: return@mapNotNull null
        val time = w.num("divetime") ?: return@mapNotNull null
        val pressures = mutableMapOf<Int, Double>()
        w.all("tankpressure").forEachIndexed { order, n ->
            val bar = pressureToBar(n.text.trim().replace(',', '.').toDoubleOrNull())?.toDouble() ?: return@forEachIndexed
            val index = n.attrs["ref"]?.let { tankIds.indexOf(it) }?.takeIf { it >= 0 } ?: order
            pressures.putIfAbsent(index, bar)
        }
        // Paliers : `<decostop decodepth="6" duration="120"/>` (UDDF 3.2), ou la profondeur en texte (UDDF 3.0 et avant) ; le plus profond.
        val stops = w.all("decostop").mapNotNull { n -> (n.attrs["decodepth"]?.toDoubleOrNull() ?: n.text.trim().toDoubleOrNull())?.takeIf { it > 0 } }
        val deco = if (stops.isNotEmpty()) stops.max() else if (w.num("nodecotime") != null) 0.0 else null
        val ppo2 = (w.num("measuredpo2") ?: w.num("calculatedpo2") ?: w.num("setpo2"))?.let { if (it > 1000) it / 100_000.0 else it }?.takeIf { it > 0 }
        Point(
            time, depth, w.num("temperature")?.let { kelvinToCelsius(it) }, pressures,
            ndlSeconds = w.num("nodecotime")?.takeIf { it >= 0 },
            decoDepth = deco,
            ppo2Bar = ppo2,
            cnsPct = w.num("cns")?.takeIf { it >= 0 },
            switchMix = w.child("switchmix")?.attrs?.get("ref")?.let { mixes[it] }?.let { m ->
                m.num("o2")?.let { o2 -> fractionToPercent(o2) to (m.num("he")?.let { fractionToPercent(it) } ?: 0) }
            },
            alarms = w.all("alarm").map { it.text.trim().ifEmpty { it.attrs["type"].orEmpty() } }.filter { it.isNotEmpty() },
            heading = w.num("heading")?.takeIf { it in 0.0..360.0 },
            gradientFactorPct = w.num("gradientfactor")?.takeIf { it >= 0 }?.let { if (it <= 1) it * 100 else it },
            // Le standard compte en hertz ; un logiciel qui écrit des battements par minute donne des valeurs bien plus grandes.
            pulseBpm = w.num("pulserate")?.takeIf { it > 0 }?.let { if (it < 5) it * 60 else it },
            otu = w.num("otu")?.takeIf { it >= 0 },
            remainingBottomSeconds = w.num("remainingbottomtime")?.takeIf { it >= 0 },
            diveMode = w.child("divemode")?.let { it.attrs["type"] ?: it.text.trim() }.orEmpty(),
        )
    }

    /** Les paragraphes `<para>` d'un élément `<notes>` (ou son texte seul), un par ligne. */
    private fun paragraphs(notes: XmlNode): String {
        val paras = notes.all("para").map { it.text.trim() }.filter { it.isNotEmpty() }
        return (if (paras.isEmpty()) notes.text.trim() else paras.joinToString("\n")).replace("�", "")
    }

    /** Nom d'une personne (`firstname` + `lastname`, ou `name`) ou d'un appareil (`name`, ou `model`). */
    private fun personName(n: XmlNode): String {
        val personal = n.child("personal") ?: n
        val full = listOfNotNull(personal.str("firstname"), personal.str("middlename"), personal.str("lastname")).joinToString(" ")
        return full.ifBlank { n.str("name") ?: n.str("model").orEmpty() }
    }

    private fun positive(v: Double?): Double? = v?.takeIf { it > 0 }

    /** Les kelvins du standard ; une valeur de moins de 200 est déjà en degrés Celsius (certains logiciels l'écrivent ainsi). */
    fun kelvinToCelsius(v: Double): Double = if (v > 200) v - 273.15 else v

    /** Les pascals du standard (jusqu'à 3×10⁷) ; une valeur de moins de 1000 est déjà en bar. */
    fun pressureToBar(v: Double?): Int? = v?.takeIf { it > 0 }?.let { (if (it > 1000) it / 100_000.0 else it).roundToInt() }?.takeIf { it > 0 }

    /** Les m³ du standard (0,012 pour un bloc de 12 L) ; une valeur d'un ou plus est déjà en litres. */
    fun volumeToLiters(v: Double?): Double? = v?.takeIf { it > 0 }?.let { round((if (it < 1) it * 1000 else it) * 10) / 10 }

    /** La fraction de gaz du standard (0,32) ; une valeur supérieure à 1 est déjà en pourcentage. */
    fun fractionToPercent(v: Double): Int = (if (v > 1) v else v * 100).roundToInt()

    /**
     * Rééchantillonne les points à intervalle régulier. Un journal saisi à la main ne contient que quelques points (un trapèze) : on
     * n'en fait pas de courbe, qui serait fausse. Un vrai profil (douze points ou plus) est gardé.
     */
    fun profileFrom(source: List<Point>): DiveProfile? {
        val pts = source.sortedBy { it.time }
        if (pts.size < 12) return null
        val total = pts.last().time
        if (total <= 0) return null
        val deltas = pts.zipWithNext { a, b -> b.time - a.time }.filter { it > 0 }.sorted()
        val median = deltas.getOrNull(deltas.size / 2) ?: return null
        var interval = listOf(1, 2, 3, 4, 5, 10, 15, 20, 30, 60).minByOrNull { abs(it - median) } ?: 10
        while (total / interval > 1500) interval *= 2 // une plongée très longue : on garde la courbe légère

        // Une grandeur mesurée seulement à certains points (la pression, parfois un point sur quatre) : la courbe relie les
        // mesures, et reste à la première avant elle et à la dernière après elle. Moins de deux mesures : pas de courbe.
        fun sparse(times: List<Double>, value: (Point) -> Double?): List<Double> {
            val known = pts.mapNotNull { p -> value(p)?.let { p.time to it } }
            if (known.size < 2) return emptyList()
            return times.map { t ->
                val next = known.indexOfFirst { it.first >= t }
                when {
                    next < 0 -> known.last().second
                    next == 0 -> known.first().second
                    else -> {
                        val (ta, va) = known[next - 1]
                        val (tb, vb) = known[next]
                        if (tb == ta) vb else va + (vb - va) * ((t - ta) / (tb - ta))
                    }
                }
            }
        }

        fun at(t: Double, value: (Point) -> Double?): Double? {
            var i = pts.indexOfLast { it.time <= t }
            if (i < 0) i = 0
            val a = pts[i]
            val b = pts.getOrNull(i + 1) ?: return value(a)
            val va = value(a) ?: return value(b)
            val vb = value(b) ?: return va
            return if (b.time == a.time) va else va + (vb - va) * ((t - a.time) / (b.time - a.time))
        }

        val count = (total / interval).toInt() + 1
        val times = List(count) { it * interval.toDouble() }
        // Les paliers tiennent jusqu'au point suivant (une marche, pas une pente) ; sans information, on suppose « hors palier ».
        val decoSteps = pts.filter { it.decoDepth != null }
        val deco = if (decoSteps.any { (it.decoDepth ?: 0.0) > 0 }) times.map { t ->
            round((decoSteps.lastOrNull { it.time <= t } ?: decoSteps.first()).decoDepth!!).toInt()
        } else emptyList()
        // LND : en minutes, plafonné à 99 ; en palier, il est nul.
        val ndl = sparse(times) { it.ndlSeconds }.mapIndexed { i, sec ->
            if (deco.isNotEmpty() && deco[i] > 0) 0 else (sec / 60).toInt().coerceIn(0, 99)
        }
        // Une grandeur qui tient jusqu'à la mesure suivante (le cap, qu'on ne moyenne pas entre 350° et 10°).
        fun held(value: (Point) -> Double?): List<Int> {
            val known = pts.mapNotNull { p -> value(p)?.let { p.time to it } }
            if (known.isEmpty()) return emptyList()
            return times.map { t -> round((known.lastOrNull { it.first <= t } ?: known.first()).second).toInt() }
        }
        fun indexOf(time: Double) = round(time / interval).toInt().coerceIn(0, count - 1)
        var lastMix: Pair<Int, Int>? = null
        val gasSwitches = pts.mapNotNull { p -> p.switchMix?.takeIf { it != lastMix }?.also { lastMix = it }?.let { GasSwitch(indexOf(p.time), it.first, it.second) } }
        val alarms = pts.flatMap { p -> p.alarms.map { ProfileAlarm(indexOf(p.time), it) } }.distinct()
        val tanks = pts.flatMap { it.pressuresBar.keys }.distinct().sorted()
        val series = tanks.map { k -> sparse(times) { it.pressuresBar[k] }.map { round(it).toInt() } }.filter { it.isNotEmpty() }
        return DiveProfile(
            intervalSeconds = interval,
            depthDm = times.map { round((at(it) { p -> p.depth } ?: 0.0) * 10).toInt() },
            tempDc = sparse(times) { it.temp }.map { round(it * 10).toInt() },
            pressureBar = series.firstOrNull().orEmpty(),
            extraPressureBar = series.drop(1),
            ndlMin = ndl,
            decoStopM = deco,
            ppo2Cb = sparse(times) { it.ppo2Bar }.map { round(it * 100).toInt() },
            cnsPct = sparse(times) { it.cnsPct }.map { round(it).toInt() },
            gasSwitches = gasSwitches,
            alarms = alarms,
            headingDeg = held { it.heading }.map { it % 360 },
            gfPct = sparse(times) { it.gradientFactorPct }.map { round(it).toInt() },
            pulseBpm = sparse(times) { it.pulseBpm }.map { round(it).toInt() },
            otu = sparse(times) { it.otu }.map { round(it).toInt() },
            remainingBottomMin = sparse(times) { it.remainingBottomSeconds }.map { (it / 60).toInt() },
            diveMode = pts.firstOrNull { it.diveMode.isNotBlank() }?.diveMode.orEmpty(),
        )
    }
}
