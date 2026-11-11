package com.sensitivemobility.subatlas

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Une plongée envoyée sous forme de fichier : toutes les données de la plongée (courbe complète, bouteilles, conditions,
 * équipement, notes) et son site. Le binôme l'ouvre avec SubAtlas pour l'ajouter à son carnet.
 */
@Serializable
class DiveFile(
    val format: String = FORMAT,
    val version: Int = 1,
    val site: SharedSite,
    val dive: Dive,
) {
    companion object {
        const val FORMAT = "subatlas-dive"
    }

    /** Résumé affiché avant l'ajout. */
    fun summary() = SharedDive(
        site = site,
        date = dive.date,
        start = dive.startTime,
        end = dive.endTime,
        depth = dive.maxDepth,
        avg = dive.avgDepthMeters,
        tempC = dive.waterTempC,
        visibility = dive.visibilityMeters,
    )
}

object DiveFileCodec {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; encodeDefaults = false }

    /**
     * Contenu du fichier. Le numéro de téléphone et l'e-mail du binôme et des personnes de la palanquée ne sont pas
     * envoyés, ni les identifiants propres au carnet de l'expéditeur (id, source d'import, refus de fusion).
     */
    fun encode(dive: Dive, site: DiveSite): String {
        val clean = dive.copy(
            id = 0,
            siteId = 0,
            buddyFromContacts = false,
            buddyPhone = "",
            buddyEmail = "",
            group = dive.group.map { Person(it.name) },
            importKey = "",
            importModified = emptyMap(),
            noMerge = false,
        )
        val file = DiveFile(site = SharedSite(site.name, site.country, site.latitude, site.longitude, site.type), dive = clean)
        return json.encodeToString(DiveFile.serializer(), file)
    }

    /** Lit un fichier de plongée ; `null` si le contenu n'en est pas un (autre type de fichier, texte illisible). */
    fun decode(bytes: ByteArray): DiveFile? {
        // Un fichier SubAtlas est du JSON : on évite d'essayer de lire un fichier binaire (base Shearwater, par exemple).
        val start = bytes.indexOfFirst { it.toInt() != 0xEF && it.toInt() != 0xBB && it.toInt() != 0xBF && !it.toInt().toChar().isWhitespace() }
        if (start < 0 || bytes[start].toInt().toChar() != '{') return null
        return runCatching { json.decodeFromString(DiveFile.serializer(), bytes.decodeToString()) }.getOrNull()
            ?.takeIf { it.format == DiveFile.FORMAT && it.site.name.isNotBlank() && it.dive.date.isNotBlank() }
    }

    /** Nom du fichier : « SubAtlas-2026-11-01-Tobia-Arbaa.json ». */
    fun fileName(dive: Dive, site: DiveSite): String {
        val place = site.name.map { if (it.isLetterOrDigit()) it else '-' }.joinToString("").replace(Regex("-+"), "-").trim('-')
        return "SubAtlas-" + dive.date + (if (place.isNotEmpty()) "-$place" else "") + ".json"
    }
}
