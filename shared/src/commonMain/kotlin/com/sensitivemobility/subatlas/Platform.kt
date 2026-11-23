package com.sensitivemobility.subatlas

/** Persistance texte simple (un fichier par nom). */
interface Storage {
    fun read(name: String): String?
    fun write(name: String, text: String)

    /**
     * Fichiers binaires (les photos des plongées), rangés dans des sous-dossiers : [name] peut être « photos/123.jpg ».
     * Par défaut, une plateforme sans stockage de fichiers n'en garde aucun (elle ne propose alors pas d'ajouter de photos).
     */
    fun readBytes(name: String): ByteArray? = null

    /** Écrit le fichier [name] ; `false` si l'écriture échoue. */
    fun writeBytes(name: String, bytes: ByteArray): Boolean = false

    /** Supprime le fichier [name] s'il existe. */
    fun delete(name: String) {}
}

/** Une photo choisie, déjà réduite : l'image (JPEG) et sa vignette, avec les dimensions de l'image après rotation. */
class PreparedPhoto(val full: ByteArray, val thumb: ByteArray, val width: Int, val height: Int, val source: String = "")

sealed interface LocationResult {
    data class Success(val latitude: Double, val longitude: Double) : LocationResult
    data object PermissionDenied : LocationResult
    data object Unavailable : LocationResult
}

/** Position GPS courante (gère la demande de permission). */
interface LocationProvider {
    suspend fun currentLocation(): LocationResult
}

/** Un contact du répertoire du téléphone. */
data class PhoneContact(val name: String, val phone: String = "", val email: String = "")

sealed interface ContactSearch {
    data class Found(val contacts: List<PhoneContact>) : ContactSearch
    data object PermissionDenied : ContactSearch
}

/** Recherche dans le répertoire par début de saisie (gère la demande de permission). */
interface ContactsProvider {
    suspend fun search(query: String): ContactSearch

    /** `true` si la plateforme sait ouvrir son sélecteur de contacts (recherche et choix du bon numéro). */
    val canPick: Boolean get() = false

    /** Ouvre le sélecteur de contacts du système et renvoie la personne et le numéro choisis ; `null` si annulé. */
    suspend fun pick(): PhoneContact? = null
}

/** Réponse HTTP complète : le code (y compris 4xx et 5xx), les en-têtes (noms en minuscules) et le corps tel quel. */
class HttpResponse(val status: Int, val headers: Map<String, String>, val body: ByteArray) {
    fun header(name: String): String? = headers[name.lowercase()]
    val text: String get() = body.decodeToString()
}

/** Pas de réponse : pas de réseau, délai dépassé, connexion coupée, ou plateforme qui ne sait pas faire de requête. */
class NetworkException(message: String) : Exception(message)

private val AlwaysForeground = kotlinx.coroutines.flow.MutableStateFlow(true)

/** Actions propres à la plateforme. */
interface PlatformActions {
    fun openMap(latitude: Double, longitude: Double, label: String)

    /**
     * Verrouille l'écran en mode paysage (`true`) ou le libère (`false`). Renvoie `false` si la plateforme ne sait pas le
     * faire : l'appli fait alors pivoter elle-même le contenu qui doit s'afficher en paysage.
     */
    fun setLandscape(landscape: Boolean): Boolean

    /** Ouvre la feuille de partage du système avec ce texte. */
    fun shareText(text: String)

    /** Ouvre un lien (`https:`, `tel:`, `mailto:`) dans l'appli du système qui le gère. */
    fun openLink(url: String)

    /**
     * Ouvre la feuille de partage du système avec ce fichier (WhatsApp, messagerie, e-mail…). Renvoie `false` si la
     * plateforme ne sait pas le faire : l'appli propose alors d'enregistrer le fichier.
     */
    fun shareFile(name: String, mimeType: String, bytes: ByteArray): Boolean = false

    /** Langue du système (code ISO 639, par ex. « fr »). */
    fun systemLanguage(): String

    /** Fait choisir à l'utilisateur où enregistrer ce texte. `false` s'il annule ; lève une exception si l'écriture échoue. */
    suspend fun saveTextFile(suggestedName: String, text: String): Boolean

    /** Fait choisir un fichier texte à l'utilisateur et renvoie son contenu ; `null` s'il annule. */
    suspend fun openTextFile(): String?

    /**
     * Requête HTTP GET : renvoie le corps de la réponse (texte UTF-8), ou `null` si la requête échoue (pas de réseau,
     * code d'erreur, délai dépassé) ou si la plateforme ne sait pas le faire.
     */
    suspend fun httpGet(url: String, userAgent: String): String? = null

    /**
     * Requête HTTP quelconque, pour la synchronisation avec SubAtlas.com. Renvoie la réponse quel que soit son code (une erreur
     * 401, 422 ou 429 est une réponse comme une autre) ; lève [NetworkException] seulement si aucune réponse n'est obtenue.
     * Les redirections ne sont PAS suivies : l'appelant décide (le jeton ne doit pas partir vers un autre serveur).
     * [method] : GET, POST, PUT, PATCH ou DELETE. [contentType] n'a de sens qu'avec un [body].
     */
    suspend fun httpRequest(
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        contentType: String? = null,
        timeoutMillis: Int = 20_000,
    ): HttpResponse = throw NetworkException("requête HTTP non disponible sur cette plateforme")

    /** Stockage du jeton d'accès à SubAtlas.com, chiffré si la plateforme le permet ; `null` : la synchronisation n'est pas proposée. */
    val secretStore: SecretStore? get() = null

    /** Nom de cet appareil, tel que le site l'affiche dans « Appareils et API ». */
    fun deviceName(): String = "SubAtlas"

    /** `true` si les adresses `http://` (sans chiffrement) sont acceptées pour la synchronisation : seulement dans une version de développement. */
    val allowsCleartextHttp: Boolean get() = false

    /**
     * `true` si le téléphone a une connexion internet qui fonctionne. Sert à choisir la carte à l'ouverture (satellite avec
     * réseau, plan sans). Par défaut `true` : une plateforme qui ne sait pas le dire laisse le choix à l'utilisateur.
     */
    fun isOnline(): Boolean = true

    /** Comme [httpGet], mais renvoie le corps tel quel (octets) : sert aux tuiles du fond de carte. */
    suspend fun httpGetBytes(url: String, userAgent: String): ByteArray? = null

    /** Fait choisir un fichier quelconque à l'utilisateur et renvoie ses octets ; `null` s'il annule. */
    suspend fun openFileBytes(): ByteArray?

    /** `true` si la plateforme sait faire choisir des photos dans la galerie et les réduire (voir [pickPhotos]). */
    val canPickPhotos: Boolean get() = false

    /**
     * Ouvre la galerie du système (sélection multiple) et renvoie les photos choisies, déjà réduites : une photo
     * d'appareil (4000 pixels et plus, plusieurs Mo) est ramenée à 1600 pixels de côté au plus, en JPEG, avec une vignette,
     * son orientation appliquée. Liste vide si l'utilisateur annule ou si rien n'a pu être lu.
     */
    suspend fun pickPhotos(): List<PreparedPhoto> = emptyList()

    /**
     * Supprime de la galerie du téléphone la photo d'origine [source] (l'adresse que [PreparedPhoto.source] a gardée). Le
     * système demande à l'utilisateur de confirmer. Renvoie `true` si la photo est bien supprimée ; `false` si l'utilisateur
     * refuse, si la photo n'est plus accessible ou si la plateforme ne sait pas le faire : rien n'est alors supprimé.
     */
    suspend fun deletePhotoOriginal(source: String): Boolean = false

    /** `true` tant que l'appli est ouverte et visible : la détection d'un ordinateur de plongée ne guette que dans ce cas. */
    val appInForeground: kotlinx.coroutines.flow.StateFlow<Boolean> get() = AlwaysForeground

    /** Garde l'écran allumé (`true`) ou rend la main au système (`false`) : un téléchargement Bluetooth dure plusieurs minutes. */
    fun setKeepScreenOn(on: Boolean) {}

    /** Bluetooth LE (téléchargement des plongées d'un ordinateur) ; `null` si la plateforme ne le propose pas. */
    val ble: BleTransport? get() = null
}
