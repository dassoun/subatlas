package com.sensitivemobility.subatlas

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Adresse de téléchargement de l'appli, ajoutée au message de partage pour qui ne l'a pas encore.
 * À remplacer par les vraies adresses (Google Play / App Store) une fois l'appli publiée.
 */
const val APP_DOWNLOAD_URL = "https://play.google.com/store/apps/details?id=com.sensitivemobility.subatlas"

/** Ce qui est partagé avec un binôme : le contexte commun de la plongée, pas ses bouteilles ni son équipement. */
@Serializable
data class SharedDive(
    val v: Int = 1,
    val site: SharedSite,
    val date: String,
    val start: String? = null,
    val end: String? = null,
    val depth: Double? = null,
    val tempC: Double? = null,
    val visibility: Int? = null,
)

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
    private val pattern = Regex("subatlas://dive\\?d=([A-Za-z0-9_-]+)")
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; encodeDefaults = false }

    @OptIn(ExperimentalEncodingApi::class)
    fun encode(dive: Dive, site: DiveSite): String {
        val shared = SharedDive(
            site = SharedSite(site.name, site.country, site.latitude, site.longitude, site.type),
            date = dive.date,
            start = dive.startTime,
            end = dive.endTime,
            depth = dive.maxDepthMeters,
            tempC = dive.waterTempC,
            visibility = dive.visibilityMeters,
        )
        val bytes = json.encodeToString(SharedDive.serializer(), shared).encodeToByteArray()
        return PREFIX + Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(bytes)
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
