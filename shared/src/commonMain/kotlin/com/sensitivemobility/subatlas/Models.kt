package com.sensitivemobility.subatlas

import kotlinx.serialization.Serializable

@Serializable
data class DiveSite(
    val id: Long,
    val name: String,
    /** Facultatives : un site créé depuis une plongée peut être localisé plus tard. */
    val latitude: Double? = null,
    val longitude: Double? = null,
    val maxDepthMeters: Int? = null,
    val access: SiteAccess = SiteAccess.SHORE,
    val notes: String = "",
    val country: String = "",
    val type: SiteType? = null,
)

/** « 🇫🇷 France · Site » : le pays (avec son drapeau) vient avant le nom du site. */
internal fun DiveSite.fullName(): String = if (country.isBlank()) name else "${countryText()} · $name"

@Serializable
enum class SiteType { SEA, LAKE, RIVER, QUARRY, PIT, CAVE, POOL, OTHER }

/** Structure commerciale avec laquelle on peut plonger (club, école, centre). */
@Serializable
data class DiveCenter(
    val id: Long,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val email: String = "",
    val phone: String = "",
    val website: String = "",
    val notes: String = "",
    val street: String = "",
    val addressLine2: String = "",
    val postalCode: String = "",
    val city: String = "",
    val country: String = "",
) {
    /** « 13007 Marseille » (l'un ou l'autre selon ce qui est renseigné). */
    val cityLine: String get() = listOf(postalCode.trim(), city.trim()).filter { it.isNotEmpty() }.joinToString(" ")

    /** Lignes de l'adresse postale, dans l'ordre d'écriture : rue, complément, code postal et ville, pays (avec drapeau). */
    val addressLines: List<String>
        get() = listOf(
            street.trim(),
            addressLine2.trim(),
            cityLine,
            listOfNotNull(countryFlag(country), country.trim().takeIf { it.isNotEmpty() }).joinToString(" "),
        ).filter { it.isNotEmpty() }
}

@Serializable
enum class SiteAccess { SHORE, BOAT }

@Serializable
enum class ItemState {
    /** Pas encore vérifié. */
    TODO,
    YES,
    NO,
    /** Absent, mais ce n'est pas bloquant. */
    NO_BUT_OK,
}

@Serializable
data class ChecklistItem(
    val id: Long,
    val text: String,
    val state: ItemState = ItemState.TODO,
)

enum class ListStatus { OK, KO, IN_PROGRESS }

@Serializable
data class Checklist(
    val id: Long,
    val title: String,
    val items: List<ChecklistItem> = emptyList(),
) {
    /** KO dès qu'un item est « Non » ; OK quand tout est vérifié sans « Non ». */
    val status: ListStatus
        get() = when {
            items.any { it.state == ItemState.NO } -> ListStatus.KO
            items.isNotEmpty() && items.none { it.state == ItemState.TODO } -> ListStatus.OK
            else -> ListStatus.IN_PROGRESS
        }
}

/** Une bouteille utilisée pendant une plongée. */
@Serializable
data class Tank(
    val volumeLiters: Double? = null,
    /** Mélange : 21 % d'oxygène et 0 % d'hélium = air. */
    val o2Percent: Int = 21,
    val hePercent: Int = 0,
    val pressureStartBar: Int? = null,
    val pressureEndBar: Int? = null,
    val maxDepthMeters: Double? = null,
) {
    /** Gaz consommé en litres (détente à pression ambiante), si le volume et les deux pressions sont connus. */
    val consumedLiters: Double?
        get() {
            val v = volumeLiters ?: return null
            val a = pressureStartBar ?: return null
            val b = pressureEndBar ?: return null
            return v * (a - b)
        }
}

@Serializable
enum class SuitType {
    WET,
    SEMI_DRY,
    DRY;

    val hasThickness: Boolean get() = this != DRY
}

/** Une plongée réalisée sur un site. `date` : `yyyy-MM-dd` ; heures : `HH:mm`. */
@Serializable
data class Dive(
    val id: Long,
    val siteId: Long,
    val date: String,
    val startTime: String? = null,
    val endTime: String? = null,
    val tanks: List<Tank> = listOf(Tank()),
    /** Profondeur moyenne de la plongée, avec les bouteilles et la durée : permet de calculer le SAC. */
    val avgDepthMeters: Double? = null,
    val waterTempC: Double? = null,
    val visibilityMeters: Int? = null,
    val weightKg: Double? = null,
    /** Texte libre des anciennes versions ; remplacé par [suitType] et [suitThicknessMm], conservé à la lecture. */
    val suit: String = "",
    val suitType: SuitType? = null,
    /** Épaisseur en mm ; demandée seulement pour une combinaison humide ou semi-étanche. */
    val suitThicknessMm: Double? = null,
    val buddy: String = "",
    /** Renseignés quand le binôme vient du répertoire : sert à lui proposer de partager la plongée. */
    val buddyFromContacts: Boolean = false,
    val buddyPhone: String = "",
    val buddyEmail: String = "",
    val notes: String = "",
) {
    val maxDepthMeters: Double? get() = tanks.mapNotNull { it.maxDepthMeters }.maxOrNull()

    /**
     * SAC (consommation à la surface, en L/min) = gaz consommé / (durée × pression absolue moyenne), avec une pression
     * absolue de 1 + profondeur moyenne / 10 bar. Il faut la durée, la profondeur moyenne et, pour au moins une bouteille,
     * le volume et les deux pressions.
     */
    val sacLitersPerMin: Double?
        get() {
            val minutes = durationMinutes?.takeIf { it > 0 } ?: return null
            val avg = avgDepthMeters ?: return null
            val used = tanks.mapNotNull { it.consumedLiters }.takeIf { it.isNotEmpty() }?.sum()?.takeIf { it > 0 } ?: return null
            return used / (minutes * (1.0 + avg / 10.0))
        }

    /** Durée en minutes ; une fin « avant » le début est lue comme passant minuit. */
    val durationMinutes: Int?
        get() {
            val a = minutesOfDay(startTime) ?: return null
            val b = minutesOfDay(endTime) ?: return null
            return if (b >= a) b - a else b + 24 * 60 - a
        }
}

internal fun minutesOfDay(time: String?): Int? {
    val parts = time?.split(":") ?: return null
    val h = parts.getOrNull(0)?.toIntOrNull() ?: return null
    val m = parts.getOrNull(1)?.toIntOrNull() ?: return null
    return h * 60 + m
}
