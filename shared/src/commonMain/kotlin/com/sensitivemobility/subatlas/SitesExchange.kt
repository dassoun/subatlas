package com.sensitivemobility.subatlas

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Fichier d'échange des sites entre deux installations de l'appli (Réglages > Données). */
@Serializable
class SitesFile(
    val format: String = SitesExchange.FORMAT,
    val version: Int = 1,
    val exported: String = "",
    val sites: List<SharedSite> = emptyList(),
)

/** Ce qu'un import de sites a fait. */
class SitesImportResult(val added: Int, val updated: Int, val unchanged: Int, val ignored: Int)

object SitesExchange {
    const val FORMAT = "subatlas-sites"
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; prettyPrint = true; encodeDefaults = true }

    /** Le fichier : nom, pays, position et type de chaque site (rien qui touche aux plongées). */
    fun encode(sites: List<DiveSite>, date: String): String = json.encodeToString(
        SitesFile.serializer(),
        SitesFile(exported = date, sites = sites.map { SharedSite(it.name, it.country, it.latitude, it.longitude, it.type) }),
    )

    /** Lit un fichier de sites ; `null` si le contenu n'en est pas un. */
    fun decode(text: String): List<SharedSite>? = runCatching { json.decodeFromString(SitesFile.serializer(), text) }
        .getOrNull()?.takeIf { it.format == FORMAT }?.sites
}
