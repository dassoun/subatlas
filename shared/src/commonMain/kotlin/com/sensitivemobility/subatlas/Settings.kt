package com.sensitivemobility.subatlas

import kotlinx.serialization.Serializable

@Serializable
enum class ThemeMode { SYSTEM, LIGHT, DARK }

@Serializable
enum class AppLanguage { SYSTEM, FR, EN }

/** Tri de la liste des plongées. */
@Serializable
enum class DiveSort { DATE, SITE, COUNTRY, DEPTH, DURATION, SAC }

/** Sens appliqué la première fois qu'on choisit ce critère ; un second appui inverse le tri. */
internal fun DiveSort.defaultDescending(): Boolean = when (this) {
    DiveSort.DATE, DiveSort.DEPTH, DiveSort.DURATION -> true
    DiveSort.SITE, DiveSort.COUNTRY, DiveSort.SAC -> false
}

/** Préférences de l'utilisateur (enregistrées dans `settings.json`, hors des sauvegardes de données). */
@Serializable
data class Settings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val language: AppLanguage = AppLanguage.SYSTEM,
    val diveSort: DiveSort = DiveSort.DATE,
    val diveSortDescending: Boolean = true,
    /** Couleur de fond des cartes de la liste par type de plongée (nom du [DiveKind] -> index de [DIVE_PALETTE], -1 : aucune). */
    val kindColors: Map<String, Int> = emptyMap(),
    /** Seuils et couleurs de la courbe selon la vitesse de remontée. */
    val ascent: AscentScale = AscentScale(),
    /** Courbes affichées en plus de la profondeur sur le graphique d'une plongée. */
    val chartSeries: Set<ChartSeries> = setOf(ChartSeries.PRESSURE),
)

/** Courbes que le graphique d'une plongée peut superposer à la profondeur (quand l'ordinateur les a enregistrées). */
@Serializable
enum class ChartSeries { PRESSURE, NDL, DECO, CNS, PPO2 }

/**
 * Seuils de vitesse de remontée (m/min) au-delà desquels le tronçon de courbe change de couleur, et couleur de chacun
 * (index de [ASCENT_PALETTE]). Les valeurs par défaut sont des repères de l'appli, non ceux d'un fabricant.
 */
@Serializable
data class AscentScale(
    val yellowAbove: Int = 10,
    val orangeAbove: Int = 15,
    val redAbove: Int = 20,
    val yellowColor: Int = 0,
    val orangeColor: Int = 1,
    val redColor: Int = 2,
)

/** Couleurs proposées pour les seuils de remontée (jaune, orange, rouge d'abord : ce sont les valeurs par défaut). */
internal val ASCENT_PALETTE: List<Long> = listOf(
    0xFFE8D030, // jaune
    0xFFF07F1E, // orange
    0xFFE5484D, // rouge
    0xFF0E9F7E, // vert lagon
    0xFF3E8FCB, // bleu
    0xFF8E6CCF, // violet
    0xFFE0629A, // rose
    0xFF8A9BA3, // gris
)

/** Couleur du tronçon pour cette vitesse de remontée ; `null` tant qu'elle reste sous le premier seuil. */
internal fun AscentScale.colorFor(rate: Double): androidx.compose.ui.graphics.Color? {
    val index = when {
        rate > redAbove -> redColor
        rate > orangeAbove -> orangeColor
        rate > yellowAbove -> yellowColor
        else -> return null
    }
    return ASCENT_PALETTE.getOrNull(index)?.let { androidx.compose.ui.graphics.Color(it) }
}

/** Lot de couleurs proposé pour distinguer les types de plongée. */
internal val DIVE_PALETTE: List<Long> = listOf(
    0xFF3E8FCB, // bleu
    0xFFF2A33A, // ambre
    0xFF0E9F7E, // vert lagon
    0xFF8E6CCF, // violet
    0xFFE0629A, // rose
    0xFFE5484D, // corail
    0xFFD8C23A, // jaune
    0xFF8A9BA3, // gris
)

/** Index de palette par défaut de chaque type (-1 : pas de teinte). */
internal fun DiveKind.defaultColorIndex(): Int = when (this) {
    DiveKind.EXPLORATION -> 0
    DiveKind.LEARNING -> 1
    DiveKind.SUPERVISING -> 2
    DiveKind.TEACHING -> 3
    DiveKind.AUTONOMOUS -> 4
    DiveKind.OTHER -> -1
}

internal fun Settings.colorIndex(kind: DiveKind): Int = kindColors[kind.name] ?: kind.defaultColorIndex()
