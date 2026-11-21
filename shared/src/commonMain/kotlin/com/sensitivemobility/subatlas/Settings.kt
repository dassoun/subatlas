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
    /** Couples de sites que l'utilisateur a déclarés distincts (« id:id »), à ne plus proposer comme doublons. */
    val distinctSites: Set<String> = emptySet(),
    /** Colore la courbe de l'image de plongée à partager selon la vitesse de remontée (désactivé par défaut). */
    val shareAscentColors: Boolean = false,
    /** Unités affichées et saisies (métrique par défaut) ; les données restent en métrique. */
    val units: UnitSystem = UnitSystem.METRIC,
    /** Ordinateurs de plongée associés à l'appli (voir docs/import-ordinateur-plongee.md). */
    val computers: List<DiveComputer> = emptyList(),
)

/** Un ordinateur de plongée associé : de quoi le retrouver et le reconnaître sans nouvelle recherche. */
@Serializable
data class DiveComputer(
    /** Adresse Bluetooth. */
    val address: String,
    /** Nom Bluetooth vu à la recherche (par exemple « Peregrine »). */
    val name: String,
    /** Modèle lu dans l'appareil (par exemple « Peregrine TX »). */
    val model: String,
    val serial: String,
    val firmware: String = "",
    /** Famille de protocole : [SHEARWATER]. */
    val family: String = SHEARWATER,
    /** Importer les nouvelles plongées sans demander de confirmation quand l'ordinateur est détecté. */
    val autoImport: Boolean = false,
) {
    /** « Peregrine TX (977D8B42) · V104 Classic ». */
    fun label(): String = listOf("$model${serial.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()}", firmware.takeIf { it.isNotBlank() })
        .filterNotNull().joinToString(" · ")

    companion object {
        const val SHEARWATER = "shearwater"
    }
}

/** Courbes que le graphique d'une plongée peut superposer à la profondeur (quand l'ordinateur les a enregistrées). */
@Serializable
enum class ChartSeries { PRESSURE, NDL, DECO, CNS, PPO2 }

/** Un seuil de vitesse de remontée : au-delà de [above] m/min, le tronçon de courbe prend la couleur [color] (index de [ASCENT_PALETTE]). */
@Serializable
data class AscentLevel(val above: Int, val color: Int)

/** Seuils proposés au départ : jaune, orange, rouge. Ce sont des repères de l'appli, non ceux d'un fabricant. */
internal val DEFAULT_ASCENT_LEVELS: List<AscentLevel> = listOf(AscentLevel(10, 0), AscentLevel(15, 1), AscentLevel(20, 2))

/** Vitesse maximale réglable d'un seuil (m/min). */
internal const val MAX_ASCENT_RATE = 99

/** Nombre maximal de seuils : un par couleur de la palette, pour que chacun reste reconnaissable. */
internal const val MAX_ASCENT_LEVELS = 8

/**
 * Seuils de vitesse de remontée au-delà desquels le tronçon de courbe change de couleur : [levels], du plus bas au plus haut
 * (valeurs toutes différentes). Libre à l'utilisateur d'en ajouter, d'en retirer ou de n'en garder aucun.
 *
 * Les six champs suivants sont les anciens réglages à trois niveaux fixes : ils ne servent qu'à relire un `settings.json`
 * enregistré avant l'arrivée de la liste (voir [migrated]) et ne sont plus jamais réécrits.
 */
@Serializable
data class AscentScale(
    val levels: List<AscentLevel> = DEFAULT_ASCENT_LEVELS,
    val yellowAbove: Int? = null,
    val orangeAbove: Int? = null,
    val redAbove: Int? = null,
    val yellowColor: Int? = null,
    val orangeColor: Int? = null,
    val redColor: Int? = null,
) {
    /**
     * Échelle prête à l'emploi : reprend les anciens réglages à trois niveaux s'ils sont présents, puis remet la liste en
     * ordre (valeurs entre 1 et [MAX_ASCENT_RATE], sans doublon, couleurs dans la palette). À appeler à la lecture des réglages.
     */
    fun migrated(): AscentScale {
        val legacy = listOf(yellowAbove, orangeAbove, redAbove, yellowColor, orangeColor, redColor).any { it != null }
        val source = if (legacy) {
            listOf(
                AscentLevel(yellowAbove ?: 10, yellowColor ?: 0),
                AscentLevel(orangeAbove ?: 15, orangeColor ?: 1),
                AscentLevel(redAbove ?: 20, redColor ?: 2),
            )
        } else {
            levels
        }
        val clean = source
            .map { AscentLevel(it.above.coerceIn(1, MAX_ASCENT_RATE), it.color.coerceIn(0, ASCENT_PALETTE.lastIndex)) }
            .sortedBy { it.above }
            .distinctBy { it.above }
            .take(MAX_ASCENT_LEVELS)
        return AscentScale(levels = clean)
    }
}

/** Valeur du seuil [index] changée de [delta], sans franchir ses voisins : la liste reste triée et sans doublon. */
internal fun AscentScale.stepped(index: Int, delta: Int): AscentScale {
    val current = levels.getOrNull(index) ?: return this
    val low = (levels.getOrNull(index - 1)?.above ?: 0) + 1
    val high = levels.getOrNull(index + 1)?.above?.minus(1) ?: MAX_ASCENT_RATE
    return copy(levels = levels.toMutableList().also { it[index] = current.copy(above = (current.above + delta).coerceIn(low, high)) })
}

/** Couleur du seuil [index] changée. */
internal fun AscentScale.recolored(index: Int, color: Int): AscentScale {
    val current = levels.getOrNull(index) ?: return this
    return copy(levels = levels.toMutableList().also { it[index] = current.copy(color = color) })
}

/** Seuil [index] retiré. */
internal fun AscentScale.removed(index: Int): AscentScale = copy(levels = levels.filterIndexed { i, _ -> i != index })

/** `true` s'il reste de la place pour un seuil de plus (nombre maximal pas atteint, et de la marge au-dessus du dernier). */
internal fun AscentScale.canAdd(): Boolean = levels.size < MAX_ASCENT_LEVELS && (levels.lastOrNull()?.above ?: 0) < MAX_ASCENT_RATE

/** Un seuil de plus, 5 m/min au-dessus du dernier (10 s'il n'y en a aucun), avec la première couleur de la palette encore libre. */
internal fun AscentScale.added(): AscentScale {
    val above = ((levels.lastOrNull()?.above ?: 5) + 5).coerceAtMost(MAX_ASCENT_RATE)
    val color = ASCENT_PALETTE.indices.firstOrNull { c -> levels.none { it.color == c } } ?: (levels.size % ASCENT_PALETTE.size)
    return copy(levels = levels + AscentLevel(above, color))
}

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

/** Couleur du tronçon pour cette vitesse de remontée : celle du plus haut seuil dépassé ; `null` sous le premier seuil. */
internal fun AscentScale.colorFor(rate: Double): androidx.compose.ui.graphics.Color? {
    val level = levels.filter { rate > it.above }.maxByOrNull { it.above } ?: return null
    return ASCENT_PALETTE.getOrNull(level.color)?.let { androidx.compose.ui.graphics.Color(it) }
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
