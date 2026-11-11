package com.sensitivemobility.subatlas

/**
 * Ressemblance entre noms de sites, tolérante à l'orthographe : sert à repérer les doublons du carnet et à rattacher un
 * site importé à un site existant (« Abu Soma Garden » ≈ « Abu Soma Gardens » ≈ « abu-soma garden »).
 */

private const val ACCENTED = "àáâãäåçèéêëìíîïñòóôõöùúûüýÿœæß"
private const val PLAIN = "aaaaaaceeeeiiiinooooouuuuyyoas"

/** Minuscules, sans accents ni ponctuation, espaces simples. */
internal fun normalizeSiteName(text: String): String {
    val sb = StringBuilder()
    for (ch in text.lowercase()) {
        val i = ACCENTED.indexOf(ch)
        when {
            i >= 0 && ch == 'œ' -> sb.append("oe")
            i >= 0 && ch == 'æ' -> sb.append("ae")
            i >= 0 && ch == 'ß' -> sb.append("ss")
            i >= 0 -> sb.append(PLAIN[i])
            ch.isLetterOrDigit() -> sb.append(ch)
            else -> sb.append(' ')
        }
    }
    return sb.toString().trim().replace(Regex("\\s+"), " ")
}

/** Mots trop courants pour identifier un site à eux seuls : « Plage » ne suffit pas à dire que deux sites sont les mêmes. */
private val GENERIC_WORDS = setOf(
    "plage", "site", "spot", "epave", "wreck", "reef", "recif", "house", "jetty", "cave", "grotte", "the", "le", "la", "les",
    "du", "de", "des", "el", "al", "bay", "baie", "point", "pointe", "ile", "island", "lac", "lake", "carriere", "quarry",
    "piscine", "pool", "fosse", "pit", "dive", "plongee",
)

/** Distance d'édition (Levenshtein) entre deux chaînes. */
private fun editDistance(a: String, b: String): Int {
    if (a == b) return 0
    if (a.isEmpty()) return b.length
    if (b.isEmpty()) return a.length
    var prev = IntArray(b.length + 1) { it }
    var cur = IntArray(b.length + 1)
    for (i in 1..a.length) {
        cur[0] = i
        for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
        }
        val t = prev; prev = cur; cur = t
    }
    return prev[b.length]
}

private fun ratio(a: String, b: String): Double {
    val longest = maxOf(a.length, b.length)
    return if (longest == 0) 1.0 else 1.0 - editDistance(a, b).toDouble() / longest
}

/** Ressemblance de 0 (rien en commun) à 1 (même nom une fois normalisé). */
internal fun siteNameSimilarity(a: String, b: String): Double {
    val na = normalizeSiteName(a)
    val nb = normalizeSiteName(b)
    if (na.isEmpty() || nb.isEmpty()) return 0.0
    if (na == nb) return 1.0
    // Noms entiers, espaces ignorés : « abu soma garden » ≈ « abusoma garden ».
    var best = ratio(na.replace(" ", ""), nb.replace(" ", ""))
    // Un nom contenu dans l'autre (« Abu Soma » dans « Abu Soma Garden »), mot à mot et sans faute près.
    val ta = na.split(' ')
    val tb = nb.split(' ')
    val (shorter, longer) = if (ta.size <= tb.size) ta to tb else tb to ta
    val distinctive = shorter.any { it.trimEnd('s') !in GENERIC_WORDS && it.length >= 3 }
    if (distinctive && shorter.joinToString("").length >= 5 && shorter.all { w -> longer.any { ratio(w, it) >= 0.8 } }) {
        best = maxOf(best, 0.88)
    }
    return best
}

/** Seuil à partir duquel deux noms sont considérés comme celui du même site. */
internal const val SITE_NAME_THRESHOLD = 0.84

/** Distance au-delà de laquelle deux sites localisés ne sont plus le même endroit, quel que soit leur nom. */
private const val SITE_FAR_METERS = 5000.0

/** Pays compatibles : l'un des deux est vide, ou ils sont identiques à l'accent et à la casse près. */
internal fun countriesCompatible(a: String, b: String): Boolean =
    a.isBlank() || b.isBlank() || normalizeSiteName(a) == normalizeSiteName(b)

internal fun farApart(la1: Double?, lo1: Double?, la2: Double?, lo2: Double?): Boolean =
    la1 != null && lo1 != null && la2 != null && lo2 != null && distanceMeters(la1, lo1, la2, lo2) > SITE_FAR_METERS

/** Les deux sites ont un nom très proche, un pays compatible et ne sont pas manifestement éloignés. */
internal fun likelySameSite(a: DiveSite, b: DiveSite): Boolean =
    countriesCompatible(a.country, b.country) &&
        !farApart(a.latitude, a.longitude, b.latitude, b.longitude) &&
        siteNameSimilarity(a.name, b.name) >= SITE_NAME_THRESHOLD

/** Clé stable d'un couple de sites (pour mémoriser « ce ne sont pas des doublons »). */
internal fun sitePairKey(a: Long, b: Long): String = if (a < b) "$a:$b" else "$b:$a"
