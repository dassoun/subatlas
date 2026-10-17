package com.sensitivemobility.subatlas

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Version courante du format d'export.
 *
 * Règle : tout changement de la forme du fichier (champ renommé, supprimé, déplacé, sens modifié) incrémente cette
 * version et ajoute dans [BackupCodec.migrations] l'étape qui transforme l'ancien JSON en nouveau. Ajouter simplement un
 * champ optionnel avec une valeur par défaut n'exige pas de nouvelle version. Les anciens exports restent ainsi
 * importables, quelle que soit leur version.
 *
 * Historique :
 *  - 0 : fichier sans champ `version` (structure `{ sites, checklists }`), tolérée à l'import.
 *  - 1 : première version publiée, avec les champs `format`, `version` et `exportedAt`.
 *       Les centres de plongée (clé facultative `centers`) ont été ajoutés ensuite sans changer de version : un fichier
 *       qui n'en contient pas les laisse intacts à l'import ([ImportData.centers] vaut alors `null`).
 *       De même pour les plongées (clé facultative `dives`) ; les coordonnées d'un site sont devenues facultatives.
 */
const val BACKUP_VERSION = 1

private const val BACKUP_FORMAT = "subatlas-backup"

/** Forme du fichier d'export. */
@Serializable
data class Backup(
    val format: String = BACKUP_FORMAT,
    val version: Int = BACKUP_VERSION,
    val exportedAt: String = "",
    val sites: List<DiveSite> = emptyList(),
    val centers: List<DiveCenter> = emptyList(),
    val dives: List<Dive> = emptyList(),
    val checklists: List<Checklist> = emptyList(),
)

/** Contenu lu dans un fichier d'import, déjà migré vers le format courant. */
data class ImportData(
    val sites: List<DiveSite>,
    /** `null` : le fichier ne parle pas des centres de plongée (sauvegarde antérieure à leur ajout). */
    val centers: List<DiveCenter>?,
    /** `null` : le fichier ne parle pas des plongées (sauvegarde antérieure à leur ajout). */
    val dives: List<Dive>?,
    val checklists: List<Checklist>,
    /** Éléments du fichier ignorés car illisibles ou invalides. */
    val skipped: Int,
    val exportedAt: String,
)

enum class BackupError { INVALID_JSON, NOT_A_BACKUP, NEWER_VERSION, UNSUPPORTED_VERSION }

/** Fichier d'import inutilisable ; l'interface traduit [error] en message ([Strings.backupError]). */
class BackupException(val error: BackupError, val version: Int = 0) : Exception(error.name)

object BackupCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        // Une valeur d'énumération inconnue (ajoutée dans une version plus récente) retombe sur la valeur par défaut.
        coerceInputValues = true
        encodeDefaults = true
        prettyPrint = true
    }

    /** Étape N : transforme le JSON du format N en JSON du format N+1. Il doit y en avoir une pour chaque version < courante. */
    private val migrations: Map<Int, (JsonObject) -> JsonObject> = mapOf(
        // 0 → 1 : même structure, seuls les champs d'en-tête sont ajoutés (au décodage, par leurs valeurs par défaut).
        0 to { it },
    )

    fun encode(
        sites: List<DiveSite>,
        centers: List<DiveCenter>,
        dives: List<Dive>,
        checklists: List<Checklist>,
        exportedAt: String,
    ): String =
        json.encodeToString(
            Backup.serializer(),
            Backup(exportedAt = exportedAt, sites = sites, centers = centers, dives = dives, checklists = checklists),
        )

    fun decode(text: String): ImportData {
        val root = try {
            json.parseToJsonElement(text.removePrefix("﻿"))
        } catch (e: Exception) {
            throw BackupException(BackupError.INVALID_JSON)
        }
        var obj = root as? JsonObject ?: throw BackupException(BackupError.NOT_A_BACKUP)

        val format = (obj["format"] as? JsonPrimitive)?.contentOrNull
        if (format != null && format != BACKUP_FORMAT) {
            throw BackupException(BackupError.NOT_A_BACKUP)
        }
        if (format == null && "sites" !in obj && "checklists" !in obj) {
            throw BackupException(BackupError.NOT_A_BACKUP)
        }

        var version = (obj["version"] as? JsonPrimitive)?.intOrNull ?: 0
        if (version > BACKUP_VERSION) {
            throw BackupException(BackupError.NEWER_VERSION, version)
        }
        while (version < BACKUP_VERSION) {
            val step = migrations[version] ?: throw BackupException(BackupError.UNSUPPORTED_VERSION, version)
            obj = step(obj)
            version++
        }

        // Décodage élément par élément : un site ou une liste abîmé n'empêche pas d'importer le reste.
        val rawSites = obj["sites"] as? JsonArray ?: JsonArray(emptyList())
        val rawLists = obj["checklists"] as? JsonArray ?: JsonArray(emptyList())
        val sites = rawSites.mapNotNull { runCatching { json.decodeFromJsonElement(DiveSite.serializer(), it) }.getOrNull() }
            .filter { (it.latitude ?: 0.0) in -90.0..90.0 && (it.longitude ?: 0.0) in -180.0..180.0 }
        val lists = rawLists.mapNotNull { runCatching { json.decodeFromJsonElement(Checklist.serializer(), it) }.getOrNull() }
        val rawCenters = obj["centers"] as? JsonArray
        val centers = rawCenters
            ?.mapNotNull { runCatching { json.decodeFromJsonElement(DiveCenter.serializer(), it) }.getOrNull() }
            ?.filter { it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0 }

        val rawDives = obj["dives"] as? JsonArray
        val dives = rawDives?.mapNotNull { runCatching { json.decodeFromJsonElement(Dive.serializer(), it) }.getOrNull() }

        return ImportData(
            sites = sites,
            centers = centers,
            dives = dives,
            checklists = lists,
            skipped = (rawSites.size - sites.size) + (rawLists.size - lists.size) +
                (if (rawCenters != null && centers != null) rawCenters.size - centers.size else 0) +
                (if (rawDives != null && dives != null) rawDives.size - dives.size else 0),
            exportedAt = (obj["exportedAt"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
        )
    }
}
