package com.sensitivemobility.subatlas

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Page web (dossier `docs/`, publiée avec GitHub Pages) vers laquelle pointe le lien de partage. Un lien https est
 * cliquable partout ; la page ouvre l'appli si elle est installée, sinon propose de la télécharger. La plongée voyage
 * dans la partie après `#`, qui n'est jamais envoyée à un serveur.
 * À adapter si le dépôt ou l'hébergement change (nom de domaine personnel, par exemple).
 */
const val SHARE_PAGE_URL = "https://dassoun.github.io/subatlas/dive.html"

/** Ce qui est partagé avec un binôme : le contexte commun de la plongée, pas ses bouteilles ni son équipement. */
@Serializable
data class SharedDive(
    val v: Int = 1,
    val site: SharedSite,
    val date: String,
    val start: String? = null,
    val end: String? = null,
    val depth: Double? = null,
    val avg: Double? = null,
    val tempC: Double? = null,
    val visibility: Int? = null,
    /** Courbe de profondeur, sous-échantillonnée pour garder un lien court ; absente si la plongée n'a pas de courbe. */
    val profile: SharedProfile? = null,
)

/** Courbe partagée : intervalle entre deux points (s) et profondeurs en dm, en écarts successifs (zigzag + varint), en Base64. */
@Serializable
data class SharedProfile(val iv: Int, val d: String)

@Serializable
data class SharedSite(
    val name: String,
    val country: String = "",
    val lat: Double? = null,
    val lon: Double? = null,
    val type: SiteType? = null,
)

object DiveLink {
    private const val PREFIX = "subatlas://dive?d="
    // Le lien peut être celui de l'appli (subatlas://dive?d=…) ou celui de la page web (…/dive.html#…).
    private val pattern = Regex("(?:subatlas://dive\\?d=|dive\\.html#)([A-Za-z0-9_-]+)")
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; encodeDefaults = false }

    /** Lien cliquable à envoyer : la page web, qui ouvre l'appli ou propose de la télécharger. */
    fun webLink(dive: Dive, site: DiveSite): String = SHARE_PAGE_URL + "#" + payload(dive, site)

    /** Lien direct vers l'appli (schéma `subatlas://`). */
    fun encode(dive: Dive, site: DiveSite): String = PREFIX + payload(dive, site)

    @OptIn(ExperimentalEncodingApi::class)
    private fun payload(dive: Dive, site: DiveSite): String {
        val shared = SharedDive(
            site = SharedSite(site.name, site.country, site.latitude, site.longitude, site.type),
            date = dive.date,
            start = dive.startTime,
            end = dive.endTime,
            depth = dive.maxDepth,
            avg = dive.avgDepthMeters,
            tempC = dive.waterTempC,
            visibility = dive.visibilityMeters,
            profile = dive.profile?.toShared(),
        )
        val bytes = json.encodeToString(SharedDive.serializer(), shared).encodeToByteArray()
        return Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(bytes)
    }

    /** Retrouve un lien de plongée dans un texte quelconque (lien seul ou message collé en entier). */
    @OptIn(ExperimentalEncodingApi::class)
    fun decode(text: String): SharedDive? {
        val payload = pattern.find(text)?.groupValues?.get(1) ?: return null
        return runCatching {
            val bytes = Base64.UrlSafe.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL).decode(payload)
            json.decodeFromString(SharedDive.serializer(), bytes.decodeToString())
        }.getOrNull()?.takeIf { it.site.name.isNotBlank() && it.date.isNotBlank() }
    }
}

/** Lien reçu de l'extérieur (ouvert depuis un message) ; consommé par l'écran principal. */
object IncomingLink {
    var pending: String? by mutableStateOf(null)
}

/** Fichier reçu d'une autre appli (partage ou « Ouvrir avec ») ; consommé par l'écran principal. */
object IncomingFile {
    var pending: ByteArray? by mutableStateOf(null)
}

/** Encodage pour pourcent (UTF-8) d'un texte placé dans un lien `sms:` ou `mailto:`. */
internal fun urlEncode(text: String): String = buildString {
    for (b in text.encodeToByteArray()) {
        val c = b.toInt() and 0xFF
        val ch = c.toChar()
        if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '-' || ch == '_' || ch == '.' || ch == '~') {
            append(ch)
        } else {
            append('%')
            append("0123456789ABCDEF"[c shr 4])
            append("0123456789ABCDEF"[c and 15])
        }
    }
}

/** Intervalle visé entre deux points de la courbe partagée (s) : assez fin pour la forme et la vitesse de remontée lissée sur 30 s. */
private const val SHARE_PROFILE_SECONDS = 10

/** Nombre de points maximal de la courbe partagée, pour qu'un lien reste d'une taille raisonnable (SMS, WhatsApp). */
private const val SHARE_PROFILE_MAX_POINTS = 500

/**
 * Courbe allégée pour le lien : un point toutes les ~10 s (jamais plus de 500), seule la profondeur est gardée. Les
 * écarts d'un point au suivant sont petits, donc codés sur un octet la plupart du temps (zigzag + varint).
 */
@OptIn(ExperimentalEncodingApi::class)
internal fun DiveProfile.toShared(): SharedProfile? {
    val n = depthDm.size
    if (n < 2 || intervalSeconds <= 0) return null
    val step = maxOf((SHARE_PROFILE_SECONDS + intervalSeconds - 1) / intervalSeconds, (n + SHARE_PROFILE_MAX_POINTS - 1) / SHARE_PROFILE_MAX_POINTS, 1)
    val out = ArrayList<Byte>()
    var previous = 0
    var i = 0
    while (i < n) {
        val delta = depthDm[i] - previous
        previous = depthDm[i]
        var z = (delta shl 1) xor (delta shr 31)
        while (z >= 0x80) {
            out.add(((z and 0x7F) or 0x80).toByte())
            z = z ushr 7
        }
        out.add(z.toByte())
        i += step
    }
    return SharedProfile(intervalSeconds * step, Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(out.toByteArray()))
}

/** Reconstitue la courbe d'un lien ; `null` si elle est illisible. */
@OptIn(ExperimentalEncodingApi::class)
internal fun SharedProfile.toProfile(): DiveProfile? = runCatching {
    val bytes = Base64.UrlSafe.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL).decode(d)
    val depths = ArrayList<Int>()
    var previous = 0
    var z = 0
    var shift = 0
    for (b in bytes) {
        val v = b.toInt() and 0xFF
        z = z or ((v and 0x7F) shl shift)
        if (v and 0x80 != 0) {
            shift += 7
        } else {
            previous += (z ushr 1) xor -(z and 1)
            depths.add(previous)
            z = 0
            shift = 0
        }
    }
    if (iv <= 0 || depths.size < 2 || depths.any { it < 0 || it > 3000 }) null else DiveProfile(iv, depths)
}.getOrNull()
