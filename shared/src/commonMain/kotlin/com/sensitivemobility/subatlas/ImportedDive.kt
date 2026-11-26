package com.sensitivemobility.subatlas

/**
 * Une plongée lue dans une source extérieure (export Shearwater Cloud, Divelogs, UDDF, ordinateur de plongée), avant d'être ajoutée au
 * carnet (voir `AppState.planImport` et `AppState.applyImport`).
 */
internal class ImportedDive(
    /** Identifiant stable, préfixé par la source (`shearwater:`, `mares-ble:`…) : réimporter ne crée pas de doublon. */
    val key: String,
    /** Date de dernière modification de la plongée dans Shearwater Cloud (« 2026-11-03 07:15:13 »), vide si absente. */
    val modified: String,
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
    /** Plongée lue dans un export Dive Logs Data (divelogs.de) : même aperçu et même fusion, avec ses propres règles (voir `mergeDivelogs`). */
    val fromDivelogs: Boolean = false,
    /** Plongée lue dans un fichier UDDF : mêmes règles de fusion que Divelogs (rien d'écrasé, courbe déjà importée gardée). */
    val fromUddf: Boolean = false,
    /** Plongée téléchargée en Bluetooth depuis l'ordinateur (Shearwater, Mares…) : mêmes règles de fusion que Divelogs. */
    val fromDevice: Boolean = false,
    /** Le nom du site créé quand la source n'en connaît aucun (un ordinateur ignore où l'on plonge). */
    val fallbackSiteName: String = "Shearwater",
) {
    /**
     * Une source qui complète le carnet (Divelogs, UDDF, ordinateur en Bluetooth) plutôt qu'un export Shearwater Cloud : sa fusion ne
     * touche pas à ce qui y est, et peut rejoindre une plongée déjà importée d'ailleurs.
     */
    val fromLogbook: Boolean get() = fromDivelogs || fromUddf || fromDevice
}

/** Plongées lues dans un fichier, et nombre de plongées du fichier qu'on n'a pas pu lire. */
internal class ImportedFile(val dives: List<ImportedDive>, val skipped: Int)
