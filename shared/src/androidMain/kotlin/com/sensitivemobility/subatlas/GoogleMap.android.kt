package com.sensitivemobility.subatlas

import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.CameraPositionState
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapType
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.MarkerComposable
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberUpdatedMarkerState
import kotlinx.coroutines.launch
import kotlin.math.floor

private const val API_KEY_META = "com.google.android.geo.API_KEY"

/** Clé de l'API Google Maps inscrite dans le manifeste (voir androidApp/build.gradle.kts) ; vide si non fournie. */
private fun manifestApiKey(context: android.content.Context): String = runCatching {
    val info = if (Build.VERSION.SDK_INT >= 33) {
        context.packageManager.getApplicationInfo(
            context.packageName, PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong()),
        )
    } else {
        @Suppress("DEPRECATION")
        context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
    }
    info.metaData?.getString(API_KEY_META).orEmpty()
}.getOrDefault("").trim()

@Composable
internal actual fun rememberGoogleMapAvailable(): Boolean {
    val context = LocalContext.current
    return remember {
        manifestApiKey(context).isNotEmpty() &&
            GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
    }
}

@Composable
internal actual fun GoogleSatelliteMap(
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
) {
    val camera = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(LatLng(initialLatitude, initialLongitude), initialZoom.toFloat())
    }
    val report by rememberUpdatedState(onCameraChanged)
    val select by rememberUpdatedState(onSelect)
    val loaded by rememberUpdatedState(onMapLoaded)
    val scope = rememberCoroutineScope()
    // La position est transmise quand la carte s'arrête, et une dernière fois à sa fermeture.
    LaunchedEffect(camera.isMoving) {
        if (!camera.isMoving) camera.reportTo(report)
    }
    DisposableEffect(Unit) {
        onDispose { camera.reportTo(report) }
    }

    // Le regroupement se recalcule par quart de niveau de zoom : pas à chaque image d'un pincement. On arrondit vers le bas,
    // du côté prudent : un groupe peut se former un peu tôt, mais deux repères ne se chevauchent jamais.
    val zoomStep by remember { derivedStateOf { floor(camera.position.zoom * 4.0) / 4.0 } }
    val groups = remember(pins, selectedId, zoomStep) { groupPins(pins, zoomStep, selectedId) }

    GoogleMap(
        modifier = modifier,
        cameraPositionState = camera,
        // Vue satellite avec routes et noms : sans identifiant de carte (Map ID), donc sans facturation « Dynamic Maps ».
        properties = MapProperties(mapType = MapType.HYBRID),
        uiSettings = MapUiSettings(zoomControlsEnabled = true, mapToolbarEnabled = false, myLocationButtonEnabled = false),
        contentPadding = PaddingValues(bottom = bottomInset.dp),
        onMapClick = { select(null) },
        onMapLoaded = { loaded() },
    ) {
        groups.forEach { group ->
            // La clé suit la composition du groupe : un groupe qui change devient un nouveau repère, à sa nouvelle position.
            key(group.key) {
                if (group.isSingle) {
                    val pin = group.pins.first()
                    val selected = pin.id == selectedId
                    MarkerComposable(
                        selected, pin.withDives, pin.type?.ordinal ?: -1,
                        state = rememberUpdatedMarkerState(position = LatLng(pin.latitude, pin.longitude)),
                        zIndex = if (selected) 2f else 1f,
                        onClick = {
                            select(pin.id)
                            true
                        },
                    ) {
                        PinIcon(pinColor(pin.withDives, selected), selected, pin.type)
                    }
                } else {
                    MarkerComposable(
                        group.pins.size,
                        state = rememberUpdatedMarkerState(position = LatLng(group.latitude, group.longitude)),
                        // Un groupe est un disque : il se pose sur son centre, pas sur une pointe.
                        anchor = Offset(0.5f, 0.5f),
                        zIndex = 0f,
                        onClick = {
                            scope.launch { camera.zoomInto(group) }
                            true
                        },
                    ) {
                        ClusterIcon(group.pins.size, if (group.pins.any { it.withDives }) PinWithDives else PinWithoutDives)
                    }
                }
            }
        }
    }
}

@Composable
internal actual fun GoogleSiteMap(latitude: Double, longitude: Double, type: SiteType?, onLoaded: () -> Unit, modifier: Modifier) {
    val position = LatLng(latitude, longitude)
    val camera = rememberCameraPositionState { this.position = CameraPosition.fromLatLngZoom(position, 15f) }
    GoogleMap(
        modifier = modifier,
        cameraPositionState = camera,
        // Mode lite : une image de la carte, sans geste ni zoom ; les appuis sont ceux du bandeau qui la porte.
        googleMapOptionsFactory = { com.google.android.gms.maps.GoogleMapOptions().liteMode(true).mapType(MapType.HYBRID.value) },
        properties = MapProperties(mapType = MapType.HYBRID),
        uiSettings = MapUiSettings(
            zoomControlsEnabled = false, mapToolbarEnabled = false, myLocationButtonEnabled = false,
            scrollGesturesEnabled = false, zoomGesturesEnabled = false, rotationGesturesEnabled = false, tiltGesturesEnabled = false,
        ),
        onMapLoaded = onLoaded,
    ) {
        MarkerComposable(type?.ordinal ?: -1, state = rememberUpdatedMarkerState(position = position)) {
            PinIcon(PinWithDives, true, type)
        }
    }
}

private fun CameraPositionState.reportTo(report: (Double, Double, Double) -> Unit) {
    val p = position
    report(p.target.latitude, p.target.longitude, p.zoom.toDouble())
}

/** Zoome sur les sites d'un groupe pour le défaire ; si ils sont tous au même endroit, zoome simplement fort dessus. */
private suspend fun CameraPositionState.zoomInto(group: PinGroup) {
    val bounds = LatLngBounds.Builder().apply { group.pins.forEach { include(LatLng(it.latitude, it.longitude)) } }.build()
    val spanLat = bounds.northeast.latitude - bounds.southwest.latitude
    val spanLon = bounds.northeast.longitude - bounds.southwest.longitude
    if (spanLat < 1e-5 && spanLon < 1e-5) {
        animate(CameraUpdateFactory.newLatLngZoom(LatLng(group.latitude, group.longitude), (position.zoom + 3f).coerceAtMost(19f)))
    } else {
        animate(CameraUpdateFactory.newLatLngBounds(bounds, 160))
    }
}

/** Le même repère en goutte que sur la carte OpenStreetMap, avec sa pointe au bas de l'icône. */
@Composable
private fun PinIcon(color: Color, big: Boolean, type: SiteType?) {
    val r = pinRadiusDp(big)
    Canvas(Modifier.size(width = (2 * r + 8).dp, height = (2.9f * r + 8).dp)) {
        drawPin(Offset(size.width / 2, size.height - 2.dp.toPx()), color, big, type)
    }
}

/** Disque portant le nombre de sites regroupés, cerclé de blanc comme les repères. */
@Composable
private fun ClusterIcon(count: Int, color: Color) {
    Box(
        Modifier.size(46.dp).background(Color.White, CircleShape).padding(3.dp).background(color, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            count.toString(),
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = if (count > 99) 12.sp else 16.sp,
        )
    }
}
