package com.sensitivemobility.subatlas

/** Persistance texte simple (un fichier par nom). */
interface Storage {
    fun read(name: String): String?
    fun write(name: String, text: String)
}

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

    /** Fait choisir un fichier quelconque à l'utilisateur et renvoie ses octets ; `null` s'il annule. */
    suspend fun openFileBytes(): ByteArray?
}
