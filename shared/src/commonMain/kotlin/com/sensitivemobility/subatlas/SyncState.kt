package com.sensitivemobility.subatlas

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * État de la synchronisation avec SubAtlas.com. Il vit dans son propre fichier (`nobackup/sync.json`), hors de `dives.json` et
 * de la sauvegarde, et hors des sauvegardes automatiques du système. Le jeton d'accès n'y est JAMAIS : il est dans un stockage
 * secret à part (voir [SecretStore]).
 *
 * Les plongées et sites sont désignés par leur uuid, qui est aussi celui du serveur : à l'envoi on le fournit, à la réception
 * ou au rapprochement l'élément local l'adopte. Une entrée dans [dives] ou [sites] signifie « le serveur connaît cet élément ».
 */
@Serializable
data class SyncState(
    /** Adresse du serveur, sans barre finale (« https://subatlas.com »). */
    val server: String = "",
    /** Identifiant de cette installation (uuid v4), créé une fois ; envoyé dans `X-Device-Id`. */
    val deviceId: String = "",
    /** Nom d'utilisateur du compte connecté (`GET /me`) ; vide tant que l'appli n'est pas connectée. */
    val account: String = "",
    /** Dernier compte connecté : à la reconnexion au même compte, on garde l'état ; avec un autre compte, on repart de zéro. */
    val lastAccount: String = "",
    /** Dernière synchro menée à son terme (instant ISO 8601). */
    val lastSync: String = "",
    /** Curseur opaque de `/sync/changes`, mémorisé tel quel pour le compte [account] ; vide : prochaine synchro complète. */
    val cursor: String = "",
    val dives: Map<String, SyncedDive> = emptyMap(),
    val sites: Map<String, SyncedSite> = emptyMap(),
    /** Photos : identifiant dans l'appli ([DivePhoto.id]) → photo du site. Une photo qui y figure n'est jamais renvoyée. */
    val photos: Map<String, SyncedPhoto> = emptyMap(),
    /** Photo en cours d'envoi (id dans l'appli → uuid de plongée) : si l'appli est coupée au mauvais moment, on la retrouve sur le site au lieu de l'envoyer deux fois. */
    val pendingPhotos: Map<String, String> = emptyMap(),
    /** Plongées que l'utilisateur a choisi de garder dans l'appli alors qu'elles sont supprimées du site : plus jamais synchronisées. */
    val detached: Set<String> = emptySet(),
    /** Plongées supprimées dans l'appli, en attente de confirmation de leur suppression sur le site (clé : uuid). */
    val tombstones: Map<String, Tombstone> = emptyMap(),
) {
    val connected: Boolean get() = account.isNotBlank()
}

/** Ce que l'on sait d'une plongée connue du serveur, à la fin de la dernière synchro réussie de cette plongée. */
@Serializable
data class SyncedDive(
    /** `client_updated_at` tel que le serveur le garde : une plongée reçue qui le porte est notre propre écho. */
    val serverClientUpdatedAt: String = "",
    /** [Dive.updatedAt] local au moment de cette synchro. */
    val localUpdatedAt: String = "",
    /**
     * Valeur de chaque champ géré par l'appli (voir `SyncMapping`) à cette synchro, au format JSON de l'API : la différence avec
     * l'état courant donne les seuls champs à envoyer par PATCH, et dit si la plongée locale a été modifiée depuis.
     */
    val fields: JsonObject = JsonObject(emptyMap()),
    /** Empreinte de la courbe déjà envoyée ou reçue ; vide : pas de courbe côté serveur. */
    val profileHash: String = "",
)

@Serializable
data class SyncedSite(
    val serverClientUpdatedAt: String = "",
    val localUpdatedAt: String = "",
    val fields: JsonObject = JsonObject(emptyMap()),
    /** Le serveur a refusé toute modification (site déjà validé) : on ne réessaie pas. */
    val locked: Boolean = false,
)

@Serializable
data class SyncedPhoto(val serverUuid: String, val diveUuid: String)

@Serializable
data class Tombstone(
    /** Quand la plongée a été constatée supprimée dans l'appli. */
    val deletedAt: String,
    /** L'utilisateur a refusé de supprimer la plongée du site : on ne le reproposera pas. */
    val declined: Boolean = false,
)

/** Lecture et écriture de [SyncState] dans le fichier d'état de synchro. */
class SyncStore(private val storage: Storage) {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; encodeDefaults = true }

    var state: SyncState = load()
        private set

    private var unsaved = false

    private fun load(): SyncState =
        storage.read(FILE)?.let { runCatching { json.decodeFromString(SyncState.serializer(), it) }.getOrNull() } ?: SyncState()

    /** Modifie l'état et l'enregistre aussitôt (une coupure en pleine synchro ne perd rien de ce qui est déjà confirmé). */
    fun update(transform: (SyncState) -> SyncState): SyncState {
        state = transform(state)
        flush(force = true)
        return state
    }

    /** Modifie l'état en mémoire seulement : pour les longues séries, suivies d'un [flush] tous les quelques éléments. */
    fun mutate(transform: (SyncState) -> SyncState) {
        state = transform(state)
        unsaved = true
    }

    fun flush(force: Boolean = false) {
        if (!unsaved && !force) return
        storage.write(FILE, json.encodeToString(SyncState.serializer(), state))
        unsaved = false
    }

    /** Identifiant de cette installation : créé à la première demande, puis conservé. */
    fun deviceId(): String = state.deviceId.ifBlank { newUuid().also { id -> update { it.copy(deviceId = id) } } }

    companion object {
        /** Le préfixe « nobackup/ » demande au stockage de ne pas inclure ce fichier dans les sauvegardes automatiques. */
        const val FILE = "nobackup/sync.json"
    }
}

/**
 * Stockage du jeton d'accès. L'implémentation Android le chiffre avec une clé du Keystore, dans un fichier exclu des sauvegardes.
 * Le jeton n'est jamais journalisé ni exporté.
 */
interface SecretStore {
    fun get(): String?
    fun set(token: String)
    fun clear()
}
