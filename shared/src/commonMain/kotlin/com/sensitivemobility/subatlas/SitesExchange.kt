package com.sensitivemobility.subatlas

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Fichier d'échange des sites entre deux installations de l'appli (Réglages > Données). */
@Serializable
class SitesFile(
    val format: String = SitesExchange.FORMAT,
    val version: Int = 1,
    val exported: String = "",
    val sites: List<ExchangedSite> = emptyList(),
)

/**
 * Un site du fichier d'échange : tous les champs qui décrivent le site. Ni `id` (numéro propre à chaque installation) ni
 * `updatedAt` (date de modification locale, utilisée par la synchro). Les champs ajoutés ont une valeur par défaut :
 * les fichiers d'avant (nom, pays, position, type) restent lisibles.
 */
@Serializable
class ExchangedSite(
    val name: String,
    val country: String = "",
    val lat: Double? = null,
    val lon: Double? = null,
    val type: SiteType? = null,
    val maxDepthMeters: Int? = null,
    val access: SiteAccess? = null,
    val notes: String = "",
    val uuid: String = "",
)

/** Ce qu'un import de sites a fait. */
class SitesImportResult(val added: Int, val updated: Int, val unchanged: Int, val ignored: Int)

object SitesExchange {
    const val FORMAT = "subatlas-sites"
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; prettyPrint = true; encodeDefaults = true }

    /** Le fichier : tous les champs de chaque site (rien qui touche aux plongées). */
    fun encode(sites: List<DiveSite>, date: String): String = json.encodeToString(
        SitesFile.serializer(),
        SitesFile(
            exported = date,
            sites = sites.map {
                ExchangedSite(it.name, it.country, it.latitude, it.longitude, it.type, it.maxDepthMeters, it.access, it.notes, it.uuid)
            },
        ),
    )

    /** Lit un fichier de sites ; `null` si le contenu n'en est pas un. */
    fun decode(text: String): List<ExchangedSite>? = runCatching { json.decodeFromString(SitesFile.serializer(), text.removePrefix("﻿")) }
        .getOrNull()?.takeIf { it.format == FORMAT }?.sites
}
