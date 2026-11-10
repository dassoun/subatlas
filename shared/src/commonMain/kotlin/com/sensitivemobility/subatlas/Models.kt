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
    /** Ancien champ (profondeur max saisie par bouteille) : remplacé par [Dive.maxDepthMeters], conservé à la lecture. */
    val maxDepthMeters: Double? = null,
    /** Profondeur moyenne pendant l'utilisation de cette bouteille (sert au SAC par bouteille). */
    val avgDepthMeters: Double? = null,
    /** Durée d'utilisation de cette bouteille ; utile quand il y en a plusieurs. */
    val usedMinutes: Int? = null,
) {
    /**
     * SAC de cette bouteille (L/min). Sans durée ni profondeur moyenne propres à la bouteille, on prend celles de la
     * plongée (seulement s'il n'y a qu'une bouteille pour la durée).
     */
    fun sacLitersPerMin(fallbackMinutes: Int?, fallbackAvgDepth: Double?): Double? {
        val used = consumedLiters?.takeIf { it > 0 } ?: return null
        val minutes = (usedMinutes ?: fallbackMinutes)?.takeIf { it > 0 } ?: return null
        val avg = avgDepthMeters ?: fallbackAvgDepth ?: return null
        return used / (minutes * (1.0 + avg / 10.0))
    }

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

@Serializable
enum class DiveKind {
    LEARNING,
    EXPLORATION,
    SUPERVISING,
    TEACHING,
    OTHER;

    /** Encadrement et enseignement se font avec une palanquée. */
    val hasGroup: Boolean get() = this == SUPERVISING || this == TEACHING
}

/** Une personne rencontrée en plongée : binôme ou membre d'une palanquée. */
@Serializable
data class Person(
    val name: String,
    /** Vient du répertoire : on a alors de quoi lui proposer la plongée. */
    val fromContacts: Boolean = false,
    val phone: String = "",
    val email: String = "",
)

/** Courbe d'une plongée lue sur un ordinateur de plongée : un point par échantillon, à intervalle régulier. */
@Serializable
data class DiveProfile(
    val intervalSeconds: Int,
    /** Profondeur en décimètres (1/10 m). */
    val depthDm: List<Int>,
    /** Température de l'eau en dixièmes de degré Celsius ; vide si inconnue. */
    val tempDc: List<Int> = emptyList(),
    /** Pression de la bouteille 1 en bar ; vide s'il n'y avait pas de capteur. */
    val pressureBar: List<Int> = emptyList(),
) {
    val durationSeconds: Int get() = (depthDm.size - 1).coerceAtLeast(0) * intervalSeconds

    /**
     * Vitesse de remontée en m/min (positive en remontant, négative en descendant), un point par échantillon. Elle est
     * lissée sur environ 30 s : sur 5 s, le clapot suffit à faire osciller la profondeur de plusieurs mètres par minute.
     */
    fun ascentRates(): List<Double> {
        val n = depthDm.size
        if (n < 2 || intervalSeconds <= 0) return List(n) { 0.0 }
        val k = (30 / intervalSeconds).coerceIn(1, 12)
        return List(n) { i ->
            val j = maxOf(0, i - k)
            if (i == j) 0.0 else (depthDm[j] - depthDm[i]) / 10.0 / ((i - j) * intervalSeconds / 60.0)
        }
    }
}

/** Au-delà de cette vitesse de remontée (m/min), la remontée est jugée un peu rapide (repère courant : 9 m/min). */
internal const val ASCENT_RATE_WARN = 9.0

/** Au-delà, la remontée est jugée trop rapide. */
internal const val ASCENT_RATE_DANGER = 12.0

/** Une plongée réalisée sur un site. `date` : `yyyy-MM-dd` ; heures : `HH:mm`. */
@Serializable
data class Dive(
    val id: Long,
    val siteId: Long,
    val date: String,
    val startTime: String? = null,
    val endTime: String? = null,
    val tanks: List<Tank> = listOf(Tank()),
    /** Profondeur max de la plongée. */
    val maxDepthMeters: Double? = null,
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
    val kind: DiveKind? = null,
    /** Précision libre quand [kind] vaut [DiveKind.OTHER]. */
    val kindOther: String = "",
    /** Personnes encadrées ou enseignées (plongées d'encadrement et d'enseignement). */
    val group: List<Person> = emptyList(),
    /** Ordinateur de plongée dont viennent les données (modèle et numéro de série), si la plongée a été importée. */
    val computer: String = "",
    /** Identifiant de la plongée dans la source importée : évite les doublons si on réimporte le même fichier. */
    val importKey: String = "",
    /** Courbe de la plongée (profondeur, température, pression), si elle a été importée. */
    val profile: DiveProfile? = null,
) {
    /** Profondeur max : celle de la plongée, sinon (anciennes données) la plus grande saisie par bouteille. */
    val maxDepth: Double? get() = maxDepthMeters ?: tanks.mapNotNull { it.maxDepthMeters }.maxOrNull()

    /** Personnes à qui proposer la plongée : le binôme et la palanquée, quand on a de quoi les joindre. */
    val shareRecipients: List<Person>
        get() = listOfNotNull(
            Person(buddy, buddyFromContacts, buddyPhone, buddyEmail)
                .takeIf { buddy.isNotBlank() && (buddyPhone.isNotBlank() || buddyEmail.isNotBlank()) },
        ) + group.filter { it.name.isNotBlank() && (it.phone.isNotBlank() || it.email.isNotBlank()) }

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

/** Heure `HH:mm` + une durée en secondes (au-delà de minuit, on repart de 00:00). */
internal fun timePlusSeconds(time: String?, seconds: Int): String? {
    val start = minutesOfDay(time) ?: return null
    val minutes = (start + seconds / 60) % (24 * 60)
    return "${(minutes / 60).toString().padStart(2, '0')}:${(minutes % 60).toString().padStart(2, '0')}"
}

internal fun minutesOfDay(time: String?): Int? {
    val parts = time?.split(":") ?: return null
    val h = parts.getOrNull(0)?.toIntOrNull() ?: return null
    val m = parts.getOrNull(1)?.toIntOrNull() ?: return null
    return h * 60 + m
}
