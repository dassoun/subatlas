package com.sensitivemobility.subatlas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** Au bout de ce délai sans que la carte Google se soit affichée, le bandeau revient au plan OpenStreetMap. */
private const val GOOGLE_BANNER_TIMEOUT_MS = 10_000L

/** Zoom du bandeau : l'anse, le port ou la côte autour du site. */
private const val BANNER_ZOOM = 14.0

/** Zoom de repli sans réseau : seules les terres embarquées sont disponibles, on situe le site à l'échelle d'une région. */
private const val BANNER_OFFLINE_ZOOM = 6.0

/**
 * Bandeau de carte d'un site : le plan OpenStreetMap autour du site, un repère à sa position. Il ne se déplace pas ; un appui
 * ouvre le site. Sans réseau (et sans tuiles déjà vues), le bandeau situe le site sur le fond de terres embarqué, qui reste
 * affichable hors ligne.
 */
@Composable
internal fun SiteBanner(
    latitude: Double,
    longitude: Double,
    description: String,
    actions: PlatformActions,
    modifier: Modifier = Modifier,
    type: SiteType? = null,
    onClick: () -> Unit,
) {
    val density = LocalDensity.current.density
    val tilePx = TILE_DP * density
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    // Change à chaque tuile reçue : le dessin se relit alors dans le cache.
    var tick by remember { mutableIntStateOf(0) }
    var offline by remember(latitude, longitude) { mutableStateOf(false) }
    // Vue satellite de Google quand elle est utilisable (clé fournie, services Google, réseau) ; sinon, ou si elle ne s'affiche pas
    // dans les dix secondes, le plan OpenStreetMap.
    val googleAvailable = rememberGoogleMapAvailable()
    var googleFailed by remember(latitude, longitude) { mutableStateOf(false) }
    var googleLoaded by remember(latitude, longitude) { mutableStateOf(false) }
    val useGoogle = remember(latitude, longitude) { googleAvailable && actions.isOnline() } && !googleFailed
    LaunchedEffect(useGoogle, latitude, longitude) {
        if (useGoogle) {
            delay(GOOGLE_BANNER_TIMEOUT_MS)
            if (!googleLoaded) googleFailed = true
        }
    }
    var land by remember { mutableStateOf(WorldLandCache.land) }
    LaunchedEffect(offline) {
        if (offline && land == null) land = withContext(Dispatchers.Default) { loadWorldLand() }
    }
    val landLayer = remember(land) { land?.let { LandLayer(it) } }

    val camera = remember(latitude, longitude, offline) {
        MapCamera(mercatorX(longitude), mercatorY(latitude), if (offline) BANNER_OFFLINE_ZOOM else BANNER_ZOOM)
    }
    val wanted = if (!useGoogle && !offline && viewport.width > 0) visibleTiles(camera, viewport, tilePx) else emptyList()
    LaunchedEffect(wanted) {
        val missing = wanted.filter { TileCache[it] == null }
        if (missing.isEmpty()) return@LaunchedEffect
        val gate = Semaphore(2)
        missing.map { key ->
            launch {
                gate.withPermit {
                    val tile = loadTile(actions, key)
                    if (tile != null) {
                        TileCache[key] = tile
                        tick++
                    }
                }
            }
        }.joinAll()
        // Aucune tuile n'est venue (hors ligne, serveur injoignable) : on se rabat sur le fond embarqué.
        if (wanted.none { TileCache[it] != null }) offline = true
    }

    Box(
        modifier
            .fillMaxWidth()
            .height(150.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MapNeutralColor)
            .semantics { this.contentDescription = description }
            .clickable(onClick = onClick)
            .onSizeChanged { viewport = it },
    ) {
        if (useGoogle) {
            GoogleSiteMap(latitude, longitude, type, onLoaded = { googleLoaded = true }, modifier = Modifier.fillMaxWidth().height(150.dp))
            // Un voile transparent : les appuis sont ceux du bandeau, pas ceux de la carte.
            Box(Modifier.fillMaxSize().clickable(onClick = onClick))
        } else Canvas(Modifier.fillMaxWidth().height(150.dp)) {
            @Suppress("UNUSED_VARIABLE") val redraw = tick
            val showLand = offline && landLayer != null
            drawRect(if (showLand) MapSeaColor else MapNeutralColor)
            if (showLand) landLayer?.draw(this, camera, tilePx)
            drawTiles(camera, wanted, tilePx)
            // La pointe du repère au centre, décalée pour que la goutte entière soit centrée dans le bandeau.
            drawPin(Offset(size.width / 2f, size.height / 2f + 22.dp.toPx()), PinWithDives, big = true, type = type)
        }
        // Mention d'attribution exigée par OpenStreetMap, seulement quand ses tuiles sont affichées.
        if (!useGoogle && !offline) {
            Text(
                LocalStrings.current.mapCredit,
                Modifier
                    .align(Alignment.BottomEnd)
                    .background(Color.White.copy(alpha = 0.75f))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
                color = Color(0xFF333333),
                fontSize = 10.sp,
            )
        }
    }
}
