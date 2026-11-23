package com.sensitivemobility.subatlas

import kotlin.math.roundToLong

/**
 * Écriture du carnet au format UDDF 3.2 (Universal Dive Data Format) : un fichier XML que lisent Subsurface, MacDive, divelogs.de,
 * Shearwater Cloud et la plupart des carnets de plongée. Unités du standard : mètres, secondes, kelvins, pascals, m³, fractions de gaz.
 *
 * Écrit : sites (nom, position, pays, notes), mélanges de gaz, plongées (date et heure, site, binôme, bouteilles, lestage, ordinateur,
 * courbe de profondeur, de température et de pression, profondeurs max et moyenne, durée, température, visibilité, notes).
 * Non écrit, faute d'équivalent dans le standard : photos, type de plongée, combinaison, palanquée, LND, paliers, PPO2 et CNS de la courbe.
 */
internal object UddfExport {
    fun encode(dives: List<Dive>, sites: List<DiveSite>, nowIso: String): String {
        val ordered = dives.sortedWith(compareBy<Dive> { it.date }.thenBy { it.startTime.orEmpty() })
        val usedSites = sites.filter { s -> ordered.any { it.siteId == s.id } }
        val buddies = ordered.map { it.buddy.trim() }.filter { it.isNotEmpty() }.distinct()
        val computers = ordered.map { it.computer.trim() }.filter { it.isNotEmpty() }.distinct()
        val mixes = ordered.flatMap { it.tanks }.map { it.o2Percent to it.hePercent }.distinct()

        val out = StringBuilder()
        fun line(depth: Int, text: String) {
            repeat(depth) { out.append("  ") }
            out.append(text).append('\n')
        }
        fun element(depth: Int, name: String, value: String) = line(depth, "<$name>${Xml.escape(value)}</$name>")

        out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        line(0, "<uddf version=\"3.2.1\">")
        line(1, "<generator>")
        element(2, "name", "SubAtlas")
        line(2, "<type>logbook</type>")
        element(2, "datetime", nowIso.take(19))
        line(1, "</generator>")

        line(1, "<diver>")
        line(2, "<owner id=\"owner\"/>")
        buddies.forEachIndexed { i, name ->
            line(2, "<buddy id=\"buddy_${i + 1}\">")
            line(3, "<personal>")
            element(4, "firstname", name)
            line(3, "</personal>")
            line(2, "</buddy>")
        }
        line(1, "</diver>")

        if (computers.isNotEmpty()) {
            line(1, "<equipment>")
            computers.forEachIndexed { i, name ->
                line(2, "<divecomputer id=\"computer_${i + 1}\">")
                element(3, "name", name)
                line(2, "</divecomputer>")
            }
            line(1, "</equipment>")
        }

        if (usedSites.isNotEmpty()) {
            line(1, "<divesite>")
            for (s in usedSites) {
                line(2, "<site id=\"site_${s.id}\">")
                element(3, "name", s.name)
                line(3, "<geography>")
                if (s.country.isNotBlank()) {
                    line(4, "<address>")
                    element(5, "country", s.country)
                    line(4, "</address>")
                }
                val la = s.latitude
                val lo = s.longitude
                if (la != null && lo != null) {
                    line(4, "<latitude>${decimal(la, 6)}</latitude>")
                    line(4, "<longitude>${decimal(lo, 6)}</longitude>")
                }
                line(3, "</geography>")
                if (s.notes.isNotBlank()) notes(3, s.notes, ::line, ::element)
                line(2, "</site>")
            }
            line(1, "</divesite>")
        }

        line(1, "<gasdefinitions>")
        for ((o2, he) in mixes) {
            line(2, "<mix id=\"${mixId(o2, he)}\">")
            element(3, "name", mixName(o2, he))
            line(3, "<o2>${decimal(o2 / 100.0, 3)}</o2>")
            line(3, "<he>${decimal(he / 100.0, 3)}</he>")
            line(2, "</mix>")
        }
        line(1, "</gasdefinitions>")

        line(1, "<profiledata>")
        var number = 0
        // Un groupe de plongées successives par jour.
        for ((date, day) in ordered.groupBy { it.date }) {
            line(2, "<repetitiongroup id=\"group_$date\">")
            for (d in day) {
                number++
                dive(d, number, buddies, computers, ::line, ::element)
            }
            line(2, "</repetitiongroup>")
        }
        line(1, "</profiledata>")
        line(0, "</uddf>")
        return out.toString()
    }

    private fun dive(
        d: Dive,
        number: Int,
        buddies: List<String>,
        computers: List<String>,
        line: (Int, String) -> Unit,
        element: (Int, String, String) -> Unit,
    ) {
        line(3, "<dive id=\"dive_${d.id}\">")
        line(4, "<informationbeforedive>")
        line(5, "<divenumber>$number</divenumber>")
        line(5, "<link ref=\"site_${d.siteId}\"/>")
        val buddy = buddies.indexOf(d.buddy.trim())
        if (buddy >= 0) line(5, "<link ref=\"buddy_${buddy + 1}\"/>")
        val start = d.startTime?.takeIf { minutesOfDay(it) != null }
        line(5, "<datetime>${if (start != null) "${d.date}T${start.take(5)}:00" else d.date}</datetime>")
        val weight = d.weightKg
        val computer = computers.indexOf(d.computer.trim())
        if ((weight != null && weight > 0) || computer >= 0) {
            line(5, "<equipmentused>")
            if (computer >= 0) line(6, "<link ref=\"computer_${computer + 1}\"/>")
            if (weight != null && weight > 0) line(6, "<leadquantity>${decimal(weight, 2)}</leadquantity>")
            line(5, "</equipmentused>")
        }
        line(4, "</informationbeforedive>")

        d.tanks.forEachIndexed { i, t ->
            line(4, "<tankdata id=\"tank_${d.id}_${i + 1}\">")
            line(5, "<link ref=\"${mixId(t.o2Percent, t.hePercent)}\"/>")
            t.volumeLiters?.let { line(5, "<tankvolume>${decimal(it / 1000.0, 4)}</tankvolume>") }
            t.pressureStartBar?.let { line(5, "<tankpressurebegin>${it * 100_000L}</tankpressurebegin>") }
            t.pressureEndBar?.let { line(5, "<tankpressureend>${it * 100_000L}</tankpressureend>") }
            line(4, "</tankdata>")
        }

        val p = d.profile
        if (p != null && p.depthDm.size >= 2) {
            line(4, "<samples>")
            // Un point par ligne : une courbe compte des centaines de points, l'indentation alourdirait le fichier pour rien.
            p.depthDm.forEachIndexed { i, depth ->
                val w = StringBuilder("<waypoint><depth>${decimal(depth / 10.0, 1)}</depth><divetime>${i.toLong() * p.intervalSeconds}</divetime>")
                p.tempDc.getOrNull(i)?.let { w.append("<temperature>${decimal(it / 10.0 + 273.15, 2)}</temperature>") }
                p.pressureBar.getOrNull(i)?.takeIf { it > 0 }?.let { w.append("<tankpressure ref=\"tank_${d.id}_1\">${it * 100_000L}</tankpressure>") }
                p.extraPressureBar.forEachIndexed { k, series ->
                    series.getOrNull(i)?.takeIf { it > 0 }?.let { w.append("<tankpressure ref=\"tank_${d.id}_${k + 2}\">${it * 100_000L}</tankpressure>") }
                }
                line(5, w.append("</waypoint>").toString())
            }
            line(4, "</samples>")
        }

        line(4, "<informationafterdive>")
        d.maxDepth?.let { line(5, "<greatestdepth>${decimal(it, 1)}</greatestdepth>") }
        d.avgDepthMeters?.let { line(5, "<averagedepth>${decimal(it, 1)}</averagedepth>") }
        // La durée entre les heures de début et de fin du carnet (la fin se retrouve ainsi exactement à l'import), sinon celle de la courbe.
        val seconds = d.durationMinutes?.takeIf { it > 0 }?.let { it * 60 } ?: p?.durationSeconds?.takeIf { it > 0 }
        seconds?.let { line(5, "<diveduration>$it</diveduration>") }
        val temp = d.waterTempC ?: p?.tempDc?.minOrNull()?.let { it / 10.0 }
        temp?.let { line(5, "<lowesttemperature>${decimal(it + 273.15, 2)}</lowesttemperature>") }
        d.visibilityMeters?.takeIf { it > 0 }?.let { line(5, "<visibility>$it</visibility>") }
        if (d.notes.isNotBlank()) notes(5, d.notes, line, element)
        line(4, "</informationafterdive>")
        line(3, "</dive>")
    }

    private fun notes(depth: Int, text: String, line: (Int, String) -> Unit, element: (Int, String, String) -> Unit) {
        line(depth, "<notes>")
        for (para in text.lines().map { it.trim() }.filter { it.isNotEmpty() }) element(depth + 1, "para", para)
        line(depth, "</notes>")
    }

    private fun mixId(o2: Int, he: Int) = "mix_${o2}_$he"

    private fun mixName(o2: Int, he: Int): String = when {
        he > 0 -> "Trimix $o2/$he"
        o2 == 21 -> "Air"
        o2 == 100 -> "Oxygen"
        else -> "Nitrox $o2"
    }

    /** Nombre décimal avec [decimals] chiffres au plus, sans notation scientifique (que `Double.toString` emploie hors de 10⁻³…10⁷). */
    private fun decimal(v: Double, decimals: Int): String {
        var scale = 1L
        repeat(decimals) { scale *= 10 }
        val scaled = (v * scale).roundToLong()
        val sign = if (scaled < 0) "-" else ""
        val abs = if (scaled < 0) -scaled else scaled
        val whole = abs / scale
        val fraction = (abs % scale).toString().padStart(decimals, '0').trimEnd('0')
        return sign + whole + if (fraction.isEmpty()) "" else ".$fraction"
    }
}
