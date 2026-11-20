package com.sensitivemobility.subatlas

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sinh

// Vue satellite : la carte de Google, intégrée à l'écran carte. Elle ne remplace pas la carte OpenStreetMap (qui marche sans
// clé et garde son fond embarqué hors ligne) : on passe de l'une à l'autre avec un bouton. Le code propre à Google vit dans
// androidMain (SDK Maps pour Android, clé fournie par l'application) ; sur iPhone elle n'est pas disponible pour l'instant.

/** Un repère de la carte Google. */
internal class GooglePin(val id: Long, val latitude: Double, val longitude: Double, val withDives: Boolean, val type: SiteType? = null)

/**
 * Un repère affiché sur la carte : un site seul ([pins] n'a qu'un élément) ou un groupe de sites proches, dessiné avec leur
 * nombre. Sa position est le centre des sites regroupés.
 */
internal class PinGroup(val pins: List<GooglePin>, val latitude: Double, val longitude: Double) {
    val isSingle: Boolean get() = pins.size == 1

    /** Identité du groupe : change quand sa composition change, ce qui renouvelle son repère. */
    val key: String get() = pins.joinToString(",") { it.id.toString() }
}

/**
 * Regroupe les sites qui se chevaucheraient à l'écran au zoom [zoom] : deux sites distants de moins de [radiusDp] (en dp,
 * sur l'écran) sont dans le même groupe, et la proximité se transmet (si A est proche de B et B proche de C, A, B et C
 * forment un groupe : ce sont les composantes connexes). Le résultat ne dépend donc ni de l'ordre des sites ni d'un
 * regroupement « préféré » : deux groupes distincts sont toujours à au moins [radiusDp] l'un de l'autre, leurs repères ne se
 * chevauchent pas. Et en zoomant, les groupes ne font que se scinder (jamais de regroupement différent entre deux zooms).
 * Le site [selectedId] reste toujours seul, pour qu'on le voie. Le centre d'un groupe est la moyenne de ses sites.
 */
internal fun groupPins(pins: List<GooglePin>, zoom: Double, selectedId: Long?, radiusDp: Double = 44.0): List<PinGroup> {
    // Largeur du monde en dp à ce zoom (tuiles de 256 dp, comme la carte Google).
    val world = 256.0 * 2.0.pow(zoom)
    val items = pins.sortedBy { it.id }
    val xs = DoubleArray(items.size) { mercatorX(items[it].longitude) * world }
    val ys = DoubleArray(items.size) { mercatorY(items[it].latitude) * world }

    // Union-find : chaque site pointe vers le représentant de son groupe.
    val parent = IntArray(items.size) { it }
    fun find(i: Int): Int {
        var r = i
        while (parent[r] != r) {
            parent[r] = parent[parent[r]]
            r = parent[r]
        }
        return r
    }
    for (i in items.indices) {
        if (items[i].id == selectedId) continue
        for (j in i + 1 until items.size) {
            if (items[j].id == selectedId) continue
            if (hypot(xs[i] - xs[j], ys[i] - ys[j]) < radiusDp) {
                val a = find(i)
                val b = find(j)
                if (a != b) parent[b] = a
            }
        }
    }

    val members = LinkedHashMap<Int, MutableList<Int>>()
    for (i in items.indices) members.getOrPut(find(i)) { mutableListOf() }.add(i)
    return members.values.map { idx ->
        PinGroup(
            idx.map { items[it] },
            latitudeOf(idx.sumOf { ys[it] } / idx.size / world),
            longitudeOf(idx.sumOf { xs[it] } / idx.size / world),
        )
    }
}

/** `true` si cette plateforme sait afficher la carte Google *et* qu'elle est utilisable (clé fournie, services Google présents). */
@Composable
internal expect fun rememberGoogleMapAvailable(): Boolean

/**
 * Carte Google en vue satellite avec les noms de lieux. Démarre centrée sur ([initialLatitude], [initialLongitude]) au
 * zoom [initialZoom] (même échelle que la carte OpenStreetMap) ; [onCameraChanged] reçoit la position quand elle cesse de
 * bouger et quand la carte est fermée, pour que l'autre carte reprenne au même endroit. Un appui sur un repère appelle
 * [onSelect] avec son identifiant, un appui ailleurs avec `null`. Les sites proches sont regroupés en un repère portant leur
 * nombre (voir [groupPins]) ; un appui sur un groupe zoome pour le défaire. [onMapLoaded] est appelée quand la carte a fini
 * de s'afficher : si elle ne l'est jamais, c'est que la carte Google ne se charge pas (clé refusée, pas de réseau).
 *
 * @param bottomInset espace laissé libre en bas (en dp) : le logo Google doit rester visible, même sous la fiche du site.
 */
@Composable
internal expect fun GoogleSatelliteMap(
    pins: List<GooglePin>,
    selectedId: Long?,
    initialLatitude: Double,
    initialLongitude: Double,
    initialZoom: Double,
    bottomInset: Int,
    onSelect: (Long?) -> Unit,
    onCameraChanged: (latitude: Double, longitude: Double, zoom: Double) -> Unit,
    onMapLoaded: () -> Unit,
    modifier: Modifier,
)

/**
 * Petite carte Google fixe (vue satellite avec noms, « mode lite » : une image, sans geste) centrée sur un site, avec son repère.
 * Sert au bandeau de la fiche d'une plongée. [onLoaded] est appelée quand l'image est affichée : si elle ne l'est jamais, l'appelant
 * se rabat sur la carte OpenStreetMap.
 */
@Composable
internal expect fun GoogleSiteMap(latitude: Double, longitude: Double, type: SiteType?, onLoaded: () -> Unit, modifier: Modifier)

/** Latitude (degrés) d'une ordonnée Mercator normalisée : l'inverse de [mercatorY]. */
internal fun latitudeOf(mercatorY: Double): Double = atan(sinh(PI * (1.0 - 2.0 * mercatorY))) * 180.0 / PI

/** Longitude (degrés) d'une abscisse Mercator normalisée : l'inverse de [mercatorX]. */
internal fun longitudeOf(mercatorX: Double): Double = mercatorX * 360.0 - 180.0

/** Couleur d'un repère : celle de la carte OpenStreetMap, pour que les deux vues se ressemblent. */
internal fun pinColor(withDives: Boolean, selected: Boolean): Color = when {
    selected -> PinSelected
    withDives -> PinWithDives
    else -> PinWithoutDives
}
