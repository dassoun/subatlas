package com.sensitivemobility.subatlas

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** Étapes d'une synchronisation, pour la progression affichée. */
enum class SyncPhase { READING, SITE_DETAILS, APPLYING, SENDING_SITES, SENDING_DIVES, PROFILES, PHOTOS, DELETING }

class SyncProgress(val phase: SyncPhase, val done: Int = 0, val total: Int = 0, val waitSeconds: Int = 0)

/** Choix de l'utilisateur pour un conflit : garder la version du site, celle de l'appli, ou décider plus tard (rien n'est modifié). */
enum class ConflictChoice { SITE, APP, LATER }

enum class ConflictKind {
    /** Plongée modifiée dans l'appli ET sur le site depuis la dernière synchro. */
    BOTH_EDITED,

    /** Plongée supprimée dans l'appli, mais modifiée sur le site depuis. */
    DELETED_HERE_EDITED_THERE,
}

/** Plongée telle que le site la renvoie dans le flux de changements. */
internal class RemoteDive(val json: JsonObject) {
    val uuid: String = json.str("uuid").orEmpty()
    val deleted: Boolean = json.str("deleted_at") != null
    val status: String = json.str("status") ?: "complete"
    val clientUpdatedAt: String = json.str("client_updated_at").orEmpty()
    val hasProfile: Boolean = json.str("has_profile") == "true"
    val photosCount: Int = json.dbl("photos_count")?.toInt() ?: 0
    val siteJson: JsonObject? = json["site"] as? JsonObject
    val siteUuid: String? = json.str("site_uuid") ?: siteJson?.str("uuid")
    val startedAt: String = json.str("started_at").orEmpty()
    val date: String get() = startedAt.take(10)
}

internal class SyncConflict(val uuid: String, val dive: Dive?, val remote: RemoteDive, val kind: ConflictKind)

/** Comment le site d'une plongée reçue trouve sa place dans le carnet. */
internal class SiteResolution(
    val remoteUuid: String,
    /** uuid du site conservé (celui du site fusionné si le site d'origine l'a été). */
    val canonicalUuid: String,
    val json: JsonObject,
    /** Site local déjà connu par son uuid. */
    val localId: Long?,
    /** Site local au nom proche, à rattacher (sinon : un site est créé). */
    val link: DiveSite?,
    /** Le rapprochement n'est pas évident (nom proche, pas identique) : à confirmer dans l'aperçu. */
    val needsConfirm: Boolean,
)

internal class LocalDeletion(val uuid: String, val date: String, val siteName: String)

/** Aperçu d'une synchro : tout ce qui a été lu sur le site et ce qui serait fait, sans que rien n'ait été modifié. */
internal class SyncPlan(
    val fullResync: Boolean,
    val cursor: String,
    val incomingNew: List<RemoteDive>,
    val incomingUpdate: List<Pair<Dive, RemoteDive>>,
    /** Modifiée sur le site mais identique dans l'appli : on met seulement l'état de synchro à jour. */
    val refreshOnly: List<Pair<Dive, RemoteDive>>,
    val matched: List<Pair<Dive, RemoteDive>>,
    val conflicts: List<SyncConflict>,
    val remoteDeleted: List<Pair<Dive, RemoteDive>>,
    /** Supprimées sur le site mais modifiées dans l'appli depuis : gardées. */
    val remoteDeletedKept: List<Dive>,
    /** Supprimées des deux côtés : seul l'état de synchro est nettoyé. */
    val remoteGone: List<String>,
    val localDeleted: List<LocalDeletion>,
    val siteResolutions: Map<String, SiteResolution>,
    val newDives: List<Dive>,
    val updatedDives: List<Dive>,
    val sitesToSend: List<DiveSite>,
    val photosToRemove: List<String>,
    val profilesToSend: Int,
    val photosToSend: Int,
    val profilesToReceive: Int,
    val photosToReceive: Int,
) {
    val toSendDives: Int get() = newDives.size + updatedDives.size
    val toReceive: Int get() = incomingNew.size + incomingUpdate.size
    val siteLinksToConfirm: List<SiteResolution> get() = siteResolutions.values.filter { it.link != null && it.needsConfirm }

    /** Rien à faire (hors mise à jour silencieuse de l'état de synchro). */
    val isEmpty: Boolean
        get() = toSendDives == 0 && sitesToSend.isEmpty() && toReceive == 0 && matched.isEmpty() && conflicts.isEmpty() &&
            remoteDeleted.isEmpty() && remoteDeletedKept.isEmpty() && localDeleted.isEmpty() && photosToRemove.isEmpty() &&
            profilesToSend == 0 && photosToSend == 0 && profilesToReceive == 0 && photosToReceive == 0
}

/**
 * Choix faits dans l'aperçu. Une suppression non cochée est refusée : la plongée est gardée, et (côté site comme côté appli) ne
 * sera plus reproposée.
 */
class SyncDecisions(
    /** Sites reçus (uuid du site) dont le rapprochement proposé est refusé : un nouveau site est créé. */
    val declinedSiteLinks: Set<String> = emptySet(),
    /** uuid des plongées supprimées sur le site que l'utilisateur accepte de retirer de l'appli. */
    val removeFromApp: Set<String> = emptySet(),
    /** uuid des plongées supprimées dans l'appli que l'utilisateur accepte de supprimer du site. */
    val deleteFromSite: Set<String> = emptySet(),
    /** Photos retirées dans l'appli : acceptées ou non, en bloc. */
    val removePhotosFromSite: Boolean = true,
    val conflictChoices: Map<String, ConflictChoice> = emptyMap(),
)

/** Une plongée ou un site refusé, avec le motif champ par champ. */
class Rejection(val label: String, val code: String, val errors: Map<String, List<String>> = emptyMap())

sealed interface SyncFailure {
    /** Jeton révoqué ou expiré : l'appli repasse en « déconnecté ». */
    data object Unauthenticated : SyncFailure
    data class RateLimited(val seconds: Int) : SyncFailure
    data object Network : SyncFailure
    data object Insecure : SyncFailure
    data class Api(val code: String, val message: String) : SyncFailure
}

class SyncReport {
    var sentDives = 0
    var sentSites = 0
    var receivedDives = 0
    var matchedDives = 0
    var removedFromApp = 0
    var deletedFromSite = 0
    var keptDeletedOnSite = 0
    var profilesSent = 0
    var profilesReceived = 0
    var photosSent = 0
    var photosReceived = 0
    var photosRemoved = 0
    var conflictsResolved = 0
    var conflictsPending = 0
    var fullResync = false
    val rejected = ArrayList<Rejection>()
    var failure: SyncFailure? = null
    var finished = false
}

/** Résultat d'une lecture du flux : pour les tests et l'aperçu. */
internal class Fetched(val dives: List<RemoteDive>, val cursor: String)

internal fun JsonArray?.objects(): List<JsonObject> = orEmpty().mapNotNull { it as? JsonObject }
