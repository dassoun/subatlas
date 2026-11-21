package com.sensitivemobility.subatlas

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.decodeToImageBitmap
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.tan

// Carte des sites : fond OpenStreetMap (tuiles « slippy map » en projection de Mercator), zoom et déplacement au doigt,
// un repère par site. Tout est dessiné ici : aucune bibliothèque de carte, donc le même code sert Android et iOS.

private const val TILE_URL = "https://tile.openstreetmap.org"
private const val TILE_USER_AGENT = "SubAtlas/1.0 (dive log app)"

/** Côté d'une tuile à un niveau de zoom entier, en dp. */
internal const val TILE_DP = 256f
private const val MIN_ZOOM = 2.0
private const val MAX_ZOOM = 18.0

/** Au bout de ce délai sans que la carte Google se soit affichée, on revient à la carte OpenStreetMap. */
private const val GOOGLE_LOAD_TIMEOUT_MS = 15_000L

/** Nombre de tuiles gardées en mémoire (une tuile décodée pèse environ 256 Ko). */
private const val MAX_CACHED_TILES = 120

/** Une tuile du fond de carte : niveau de zoom, colonne et ligne. */
internal data class TileKey(val z: Int, val x: Int, val y: Int)

/**
 * Tuiles déjà décodées, partagées par toutes les ouvertures de la carte et bornées (la moins récemment utilisée part la
 * première). Utilisé depuis le fil principal uniquement.
 */
internal object TileCache {
    private val tiles = LinkedHashMap<TileKey, ImageBitmap>()

    operator fun get(key: TileKey): ImageBitmap? {
        val tile = tiles.remove(key) ?: return null
        tiles[key] = tile
        return tile
    }

    operator fun set(key: TileKey, tile: ImageBitmap) {
        tiles.remove(key)
        tiles[key] = tile
        while (tiles.size > MAX_CACHED_TILES) tiles.remove(tiles.keys.first())
    }
}

/**
 * Vue de la carte : centre en coordonnées Mercator normalisées (0 à 1, indépendantes du zoom) et niveau de zoom
 * (fractionnaire, pour que le pincement soit continu).
 */
internal data class MapCamera(val cx: Double, val cy: Double, val zoom: Double)

/** État de la carte conservé quand on ouvre la fiche d'un site puis qu'on revient : la carte est comme on l'a laissée. */
internal class MapViewState {
    var camera by mutableStateOf<MapCamera?>(null)
    var selectedId by mutableStateOf<Long?>(null)

    /**
     * Choix de l'utilisateur : `true` vue satellite de Google, `false` carte OpenStreetMap, `null` pas encore choisi (l'écran
     * décide à l'ouverture : satellite si Google est utilisable et qu'il y a du réseau, sinon OpenStreetMap).
     */
    var satellite by mutableStateOf<Boolean?>(null)

    /** `true` quand la carte Google ne s'est pas chargée et que l'écran est revenu à la carte OpenStreetMap. */
    var satelliteFailed by mutableStateOf(false)

    fun reset() {
        camera = null
        selectedId = null
        satellite = null
        satelliteFailed = false
    }
}

/** Un site avec sa position en coordonnées Mercator normalisées. */
private class MapPoint(val site: DiveSite, val nx: Double, val ny: Double)

internal fun mercatorX(longitude: Double): Double = (longitude + 180.0) / 360.0

internal fun mercatorY(latitude: Double): Double {
    val rad = latitude.coerceIn(-85.0511, 85.0511) * PI / 180.0
    return (1.0 - ln(tan(rad) + 1.0 / cos(rad)) / PI) / 2.0
}

/** Taille du monde entier en pixels au zoom donné. */
internal fun worldSize(zoom: Double, tilePx: Float): Double = tilePx * 2.0.pow(zoom)

private fun MapPoint.screen(camera: MapCamera, world: Double, viewport: IntSize): Offset = Offset(
    ((nx - camera.cx) * world + viewport.width / 2.0).toFloat(),
    ((ny - camera.cy) * world + viewport.height / 2.0).toFloat(),
)

/**
 * Vue après un geste : déplacement de [pan] pixels et changement d'échelle de [scale] autour de [focus]. Le point de la
 * carte qui était sous [focus] reste sous les doigts.
 */
private fun MapCamera.transformed(pan: Offset, focus: Offset, scale: Float, viewport: IntSize, tilePx: Float): MapCamera {
    val newZoom = (zoom + log2(scale.toDouble())).coerceIn(MIN_ZOOM, MAX_ZOOM)
    val oldWorld = worldSize(zoom, tilePx)
    val newWorld = worldSize(newZoom, tilePx)
    val underX = cx + (focus.x - viewport.width / 2.0) / oldWorld
    val underY = cy + (focus.y - viewport.height / 2.0) / oldWorld
    return MapCamera(
        (underX - (focus.x + pan.x - viewport.width / 2.0) / newWorld).coerceIn(0.0, 1.0),
        (underY - (focus.y + pan.y - viewport.height / 2.0) / newWorld).coerceIn(0.0, 1.0),
        newZoom,
    )
}

/** Vue qui montre tous les sites d'un coup. */
private fun fitCamera(points: List<MapPoint>, viewport: IntSize, tilePx: Float): MapCamera {
    val minX = points.minOf { it.nx }
    val maxX = points.maxOf { it.nx }
    val minY = points.minOf { it.ny }
    val maxY = points.maxOf { it.ny }
    // De la marge autour : les repères se dressent au-dessus de leur point et ne doivent pas toucher le bord.
    val zoomX = if (maxX > minX) log2(viewport.width * 0.7 / (tilePx * (maxX - minX))) else MAX_ZOOM
    val zoomY = if (maxY > minY) log2(viewport.height * 0.6 / (tilePx * (maxY - minY))) else MAX_ZOOM
    return MapCamera((minX + maxX) / 2, (minY + maxY) / 2, minOf(zoomX, zoomY, 13.0).coerceIn(MIN_ZOOM, MAX_ZOOM))
}

/** Tuiles qui couvrent la vue, au niveau de zoom entier le plus proche en dessous. */
internal fun visibleTiles(camera: MapCamera, viewport: IntSize, tilePx: Float): List<TileKey> {
    val z = floor(camera.zoom).toInt().coerceIn(0, 19)
    val world = worldSize(camera.zoom, tilePx)
    val tileSize = world / (1 shl z)
    val originX = camera.cx * world - viewport.width / 2.0
    val originY = camera.cy * world - viewport.height / 2.0
    val last = (1 shl z) - 1
    val x0 = floor(originX / tileSize).toInt().coerceIn(0, last)
    val x1 = floor((originX + viewport.width) / tileSize).toInt().coerceIn(0, last)
    val y0 = floor(originY / tileSize).toInt().coerceIn(0, last)
    val y1 = floor((originY + viewport.height) / tileSize).toInt().coerceIn(0, last)
    return buildList { for (y in y0..y1) for (x in x0..x1) add(TileKey(z, x, y)) }
}

/** Télécharge et décode une tuile ; `null` si le téléchargement ou le décodage échoue. */
internal suspend fun loadTile(actions: PlatformActions, key: TileKey): ImageBitmap? {
    val bytes = actions.httpGetBytes("$TILE_URL/${key.z}/${key.x}/${key.y}.png", TILE_USER_AGENT) ?: return null
    return try {
        withContext(Dispatchers.Default) { bytes.decodeToImageBitmap() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
}

internal val PinWithDives = Color(0xFF0B6E99)
internal val PinWithoutDives = Color(0xFF7A8B94)
internal val PinSelected = StatusKo

/** Rayon (en dp) de la tête d'un repère : un peu plus grand pour le site sélectionné. */
internal fun pinRadiusDp(big: Boolean): Float = if (big) 14f else 11f

/**
 * Repère en goutte dont la pointe est en [tip] ; [big] pour le site sélectionné. La tête porte le pictogramme du type du
 * site ([type]), ou un simple point blanc quand on ne le connaît pas.
 */
internal fun DrawScope.drawPin(tip: Offset, color: Color, big: Boolean, type: SiteType? = null) {
    val r = pinRadiusDp(big).dp.toPx()
    val head = Offset(tip.x, tip.y - r * 1.9f)
    val body = Path().apply {
        moveTo(tip.x, tip.y)
        lineTo(head.x - r * 0.85f, head.y + r * 0.55f)
        lineTo(head.x + r * 0.85f, head.y + r * 0.55f)
        close()
    }
    // Liseré blanc : le repère reste lisible sur les fonds sombres comme sur les clairs.
    drawPath(body, Color.White, style = Stroke(width = 3.dp.toPx(), join = StrokeJoin.Round))
    drawCircle(Color.White, r + 1.5.dp.toPx(), head)
    drawPath(body, color)
    drawCircle(color, r, head)
    if (type != null) {
        val side = r * 1.3f
        withTransform({ translate(head.x - side / 2f, head.y - side / 2f) }) { drawSiteType(type, Color.White, side, side) }
    } else {
        drawCircle(Color.White, r * 0.38f, head)
    }
}

/**
 * Carte des [sites] (ceux qui ont une position). Un appui sur un repère en affiche la fiche résumée, un appui sur celle-ci
 * ouvre le site.
 */
@Composable
internal fun SitesMapScreen(
    sites: List<DiveSite>,
    diveCounts: Map<Long, Int>,
    view: MapViewState,
    actions: PlatformActions,
    pad: PaddingValues,
    onOpen: (Long) -> Unit,
) {
    val s = LocalStrings.current
    val density = LocalDensity.current.density
    val tilePx = TILE_DP * density
    val points = remember(sites) {
        sites.mapNotNull { site ->
            val la = site.latitude
            val lo = site.longitude
            if (la == null || lo == null) null else MapPoint(site, mercatorX(lo), mercatorY(la))
        }
    }
    val pointsNow by rememberUpdatedState(points)
    // Les mêmes repères pour la carte Google et pour la carte OpenStreetMap, regroupés de la même façon.
    val pins = remember(points, diveCounts) {
        points.map { GooglePin(it.site.id, it.site.latitude ?: 0.0, it.site.longitude ?: 0.0, (diveCounts[it.site.id] ?: 0) > 0, it.site.type) }
    }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    // Change à chaque tuile reçue : le dessin se relit alors dans le cache.
    var tick by remember { mutableIntStateOf(0) }
    var tilesFailed by remember { mutableStateOf(false) }
    // Terres embarquées, dessinées sous les tuiles : le fond de carte reste utile sans réseau.
    var land by remember { mutableStateOf(WorldLandCache.land) }
    LaunchedEffect(Unit) {
        if (land == null) land = withContext(Dispatchers.Default) { loadWorldLand() }
    }
    val landLayer = remember(land) { land?.let { LandLayer(it) } }

    val camera = view.camera
    val googleAvailable = rememberGoogleMapAvailable()
    // À l'ouverture : satellite si Google est utilisable et qu'il y a du réseau, sinon le plan (OpenStreetMap et son fond embarqué).
    val autoSatellite = remember { googleAvailable && actions.isOnline() }
    val satellite = googleAvailable && (view.satellite ?: autoSatellite) && camera != null
    // Si la carte Google ne s'affiche jamais (clé désactivée ou refusée, quota, réseau absent), on revient au plan.
    var googleLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(satellite) {
        if (satellite) {
            googleLoaded = false
            delay(GOOGLE_LOAD_TIMEOUT_MS)
            if (!googleLoaded) {
                view.satellite = false
                view.satelliteFailed = true
            }
        }
    }
    val selectedSite = view.selectedId?.let { id -> points.firstOrNull { it.site.id == id }?.site }
    // Regroupement des sites proches (groupPins, comme sur la carte Google), recalculé par quart de niveau de zoom : pas à
    // chaque image d'un pincement. Le site sélectionné reste toujours seul.
    val zoomStep = camera?.let { floor(it.zoom * 4.0) / 4.0 }
    val groups = remember(pins, view.selectedId, zoomStep, satellite) {
        if (zoomStep == null || satellite) emptyList() else groupPins(pins, zoomStep, view.selectedId)
    }
    val groupsNow by rememberUpdatedState(groups)
    val measurer = rememberTextMeasurer()
    // Déplacement animé de la vue (appui sur un groupe) ; un geste de l'utilisateur l'interrompt.
    val scope = rememberCoroutineScope()
    val flight = remember { Flight() }
    fun flyTo(target: MapCamera) {
        flight.job?.cancel()
        val from = view.camera ?: return
        flight.job = scope.launch {
            animate(0f, 1f, animationSpec = tween(350, easing = FastOutSlowInEasing)) { t, _ ->
                view.camera = MapCamera(
                    from.cx + (target.cx - from.cx) * t,
                    from.cy + (target.cy - from.cy) * t,
                    from.zoom + (target.zoom - from.zoom) * t,
                )
            }
        }
    }
    // En vue satellite, c'est Google qui fournit le fond : on ne télécharge pas de tuiles OpenStreetMap.
    val wanted = if (!satellite && camera != null && viewport.width > 0) visibleTiles(camera, viewport, tilePx) else emptyList()
    LaunchedEffect(wanted) {
        val missing = wanted.filter { TileCache[it] == null }
        if (missing.isEmpty()) return@LaunchedEffect
        // Un instant de calme : pendant un déplacement rapide, on ne télécharge pas les tuiles qu'on ne fait que traverser.
        delay(80)
        // Deux téléchargements à la fois : par égard pour le serveur de tuiles d'OpenStreetMap (sa politique n'impose pas de chiffre).
        val gate = Semaphore(2)
        missing.forEach { key ->
            launch {
                gate.withPermit {
                    val tile = loadTile(actions, key)
                    if (tile != null) {
                        TileCache[key] = tile
                        tilesFailed = false
                        tick++
                    } else {
                        tilesFailed = true
                    }
                }
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .padding(pad)
            .clipToBounds()
            .background(MapSeaColor)
            .onSizeChanged {
                viewport = it
                if (view.camera == null && it.width > 0 && it.height > 0 && pointsNow.isNotEmpty()) {
                    view.camera = fitCamera(pointsNow, it, tilePx)
                }
            }
            .pointerInput(tilePx, satellite) {
                // En vue satellite, les gestes sont ceux de la carte Google.
                if (satellite) return@pointerInput
                detectTransformGestures { centroid, pan, zoomChange, _ ->
                    flight.job?.cancel()
                    val c = view.camera ?: return@detectTransformGestures
                    view.camera = c.transformed(pan, centroid, zoomChange, size, tilePx)
                }
            }
            .pointerInput(tilePx, satellite) {
                if (satellite) return@pointerInput
                detectTapGestures(
                    onDoubleTap = { at ->
                        val c = view.camera ?: return@detectTapGestures
                        view.camera = c.transformed(Offset.Zero, at, 2f, size, tilePx)
                    },
                    onTap = { at ->
                        val c = view.camera ?: return@detectTapGestures
                        val world = worldSize(c.zoom, tilePx)
                        // Le repère le plus proche du doigt. Un repère seul se dresse au-dessus de son point : on mesure depuis
                        // le milieu de sa tête ; un groupe est un disque, on mesure depuis son centre.
                        var hit: PinGroup? = null
                        var hitDistance = Float.MAX_VALUE
                        for (g in groupsNow) {
                            val pos = screenOf(mercatorX(g.longitude), mercatorY(g.latitude), c, world, size)
                            val centre = if (g.isSingle) Offset(pos.x, pos.y - 17f * density) else pos
                            val distance = (at - centre).getDistance()
                            if (distance < (if (g.isSingle) 26f else 30f) * density && distance < hitDistance) {
                                hit = g
                                hitDistance = distance
                            }
                        }
                        when {
                            hit == null -> view.selectedId = null
                            hit.isSingle -> view.selectedId = hit.pins.first().id
                            // Un appui sur un groupe zoome pour le défaire.
                            else -> flyTo(clusterCamera(hit, c, size, tilePx))
                        }
                    },
                )
            },
    ) {
        if (satellite) {
            GoogleSatelliteMap(
                pins = pins,
                selectedId = view.selectedId,
                initialLatitude = latitudeOf(camera.cy),
                initialLongitude = longitudeOf(camera.cx),
                initialZoom = camera.zoom,
                // La fiche du site recouvre le bas de la carte : le logo et les boutons de Google restent au-dessus.
                bottomInset = if (selectedSite != null) 168 else 0,
                onSelect = { view.selectedId = it },
                onCameraChanged = { lat, lon, zoom ->
                    view.camera = MapCamera(mercatorX(lon), mercatorY(lat), zoom.coerceIn(MIN_ZOOM, MAX_ZOOM))
                },
                onMapLoaded = { googleLoaded = true },
                modifier = Modifier.fillMaxSize(),
            )
        } else if (camera != null) {
            Canvas(Modifier.fillMaxSize()) {
                @Suppress("UNUSED_VARIABLE") val redraw = tick
                val world = worldSize(camera.zoom, tilePx)
                // Mer et terres embarquées tant qu'elles sont assez précises ; au-delà, un fond neutre (les tuiles, si on a du réseau).
                val showLand = landLayer != null && camera.zoom <= LAND_MAX_ZOOM
                drawRect(if (showLand) MapSeaColor else MapNeutralColor)
                if (showLand) landLayer.draw(this, camera, tilePx)
                drawTiles(camera, wanted, tilePx)
                // Les groupes au fond, puis les repères seuls, le site sélectionné en dernier par-dessus ses voisins.
                val ordered = groups.sortedBy { if (!it.isSingle) 0 else if (it.pins.first().id == view.selectedId) 2 else 1 }
                for (g in ordered) {
                    val pos = screenOf(mercatorX(g.longitude), mercatorY(g.latitude), camera, world, viewport)
                    if (pos.x < -60f || pos.x > size.width + 60f || pos.y < -60f || pos.y > size.height + 90f) continue
                    if (g.isSingle) {
                        val pin = g.pins.first()
                        val selected = pin.id == view.selectedId
                        drawPin(pos, pinColor(pin.withDives, selected), selected, pin.type)
                    } else {
                        drawCluster(pos, g.pins.size, if (g.pins.any { it.withDives }) PinWithDives else PinWithoutDives, measurer)
                    }
                }
            }
        }

        Column(Modifier.align(Alignment.TopEnd).padding(12.dp)) {
            // Bascule entre la carte OpenStreetMap et la vue satellite de Google (absente si elle n'est pas disponible).
            if (googleAvailable) {
                ZoomButton(if (satellite) Picto.MAP else Picto.SATELLITE, if (satellite) s.mapPlanView else s.mapSatelliteView) {
                    view.satellite = !satellite
                    view.satelliteFailed = false
                }
            }
            // La carte Google a ses propres boutons de zoom.
            if (!satellite) {
                ZoomButton(Picto.PLUS, s.mapZoomIn) { view.camera = view.camera?.transformed(Offset.Zero, Offset(viewport.width / 2f, viewport.height / 2f), 2f, viewport, tilePx) }
                ZoomButton(Picto.MINUS, s.mapZoomOut) { view.camera = view.camera?.transformed(Offset.Zero, Offset(viewport.width / 2f, viewport.height / 2f), 0.5f, viewport, tilePx) }
            }
        }

        Column(
            Modifier.align(Alignment.TopStart).padding(start = 12.dp, top = 12.dp, end = 72.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!satellite && view.satelliteFailed) MapNotice(s.mapSatelliteFailed)
            if (!satellite && tilesFailed) MapNotice(s.mapOffline)
        }

        // Mention d'attribution exigée par OpenStreetMap (la carte Google affiche la sienne).
        if (!satellite) {
            Text(
                s.mapCredit,
                Modifier
                    .align(Alignment.BottomEnd)
                    .background(Color.White.copy(alpha = 0.75f))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
                color = Color(0xFF333333),
                fontSize = 10.sp,
            )
        }

        val selected = selectedSite
        if (selected != null) {
            val dives = diveCounts[selected.id] ?: 0
            Card(
                onClick = { onOpen(selected.id) },
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 28.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
            ) {
                Column(Modifier.padding(16.dp)) {
                    SiteLine(selected, MaterialTheme.typography.titleMedium)
                    Text(
                        listOfNotNull(
                            if (dives > 0) s.diveCount(dives) else s.mapNoDive,
                            s.access(selected.access),
                            selected.maxDepthMeters?.let { s.maxDepthShort(LocalUnits.current.depth.text(it.toDouble())) },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        s.mapOpenSite + " ›",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }
}

/** Animation de vue en cours (appui sur un groupe de sites), pour pouvoir l'interrompre. */
private class Flight {
    var job: Job? = null
}

/** Position à l'écran d'un point en coordonnées Mercator normalisées. */
private fun screenOf(nx: Double, ny: Double, camera: MapCamera, world: Double, viewport: IntSize): Offset = Offset(
    ((nx - camera.cx) * world + viewport.width / 2.0).toFloat(),
    ((ny - camera.cy) * world + viewport.height / 2.0).toFloat(),
)

/**
 * Vue qui défait un groupe de sites : elle cadre ses sites, au moins un niveau de zoom plus près que [current] (sinon le
 * groupe se reformerait tel quel) ; si tous sont au même endroit, elle zoome simplement fort dessus.
 */
private fun clusterCamera(group: PinGroup, current: MapCamera, viewport: IntSize, tilePx: Float): MapCamera {
    val xs = group.pins.map { mercatorX(it.longitude) }
    val ys = group.pins.map { mercatorY(it.latitude) }
    val minX = xs.min()
    val maxX = xs.max()
    val minY = ys.min()
    val maxY = ys.max()
    val cx = (minX + maxX) / 2
    val cy = (minY + maxY) / 2
    if (maxX - minX < 3e-8 && maxY - minY < 3e-8) return MapCamera(cx, cy, (current.zoom + 3).coerceAtMost(MAX_ZOOM))
    // De la marge autour (60 dp environ) : le repère d'un site se dresse au-dessus de son point.
    val margin = 60f * tilePx / TILE_DP
    val availableW = max(1f, viewport.width - 2 * margin)
    val availableH = max(1f, viewport.height - 2 * margin)
    val zoomX = if (maxX > minX) log2(availableW / (tilePx * (maxX - minX))) else MAX_ZOOM
    val zoomY = if (maxY > minY) log2(availableH / (tilePx * (maxY - minY))) else MAX_ZOOM
    return MapCamera(cx, cy, max(min(zoomX, zoomY), current.zoom + 1.0).coerceIn(MIN_ZOOM, MAX_ZOOM))
}

/** Disque portant le nombre de sites regroupés, cerclé de blanc : le même dessin que sur la carte Google. */
private fun DrawScope.drawCluster(center: Offset, count: Int, color: Color, measurer: TextMeasurer) {
    drawCircle(Color.White, 23.dp.toPx(), center)
    drawCircle(color, 20.dp.toPx(), center)
    val label = measurer.measure(
        count.toString(),
        TextStyle(color = Color.White, fontWeight = FontWeight.Bold, fontSize = if (count > 99) 12.sp else 16.sp),
    )
    drawText(label, topLeft = Offset(center.x - label.size.width / 2f, center.y - label.size.height / 2f))
}

/** Bandeau d'information posé sur la carte. */
@Composable
private fun MapNotice(text: String) {
    Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f), shape = RoundedCornerShape(8.dp)) {
        Text(text, Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ZoomButton(icon: Picto, description: String, onClick: () -> Unit) {
    FilledTonalIconButton(onClick = onClick, modifier = Modifier.padding(bottom = 8.dp).size(44.dp).semantics { contentDescription = description }) {
        Pictogram(icon, size = 24.dp)
    }
}

/**
 * Dessine les tuiles de [wanted]. Une tuile pas encore reçue est remplacée par la partie correspondante de sa tuile
 * « parente » (un niveau de zoom plus large) si on l'a déjà : l'image reste floue un instant plutôt que vide.
 */
internal fun DrawScope.drawTiles(camera: MapCamera, wanted: List<TileKey>, tilePx: Float) {
    val world = worldSize(camera.zoom, tilePx)
    val originX = camera.cx * world - size.width / 2.0
    val originY = camera.cy * world - size.height / 2.0
    for (key in wanted) {
        val tileSize = world / (1 shl key.z)
        val left = key.x * tileSize - originX
        val top = key.y * tileSize - originY
        // Arrondis communs aux tuiles voisines : pas de fine ligne vide entre elles.
        val x0 = left.roundToInt()
        val y0 = top.roundToInt()
        val dst = IntSize((left + tileSize).roundToInt() - x0, (top + tileSize).roundToInt() - y0)
        val tile = TileCache[key]
        if (tile != null) {
            drawImage(tile, srcSize = IntSize(tile.width, tile.height), dstOffset = IntOffset(x0, y0), dstSize = dst, filterQuality = FilterQuality.Medium)
            continue
        }
        val parent = if (key.z > 0) TileCache[TileKey(key.z - 1, key.x / 2, key.y / 2)] else null
        if (parent != null) {
            val halfW = parent.width / 2
            val halfH = parent.height / 2
            drawImage(
                parent,
                srcOffset = IntOffset((key.x % 2) * halfW, (key.y % 2) * halfH),
                srcSize = IntSize(halfW, halfH),
                dstOffset = IntOffset(x0, y0),
                dstSize = dst,
                filterQuality = FilterQuality.Medium,
            )
        }
    }
}
