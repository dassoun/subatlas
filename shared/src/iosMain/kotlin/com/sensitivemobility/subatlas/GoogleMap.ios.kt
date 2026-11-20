package com.sensitivemobility.subatlas

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

// La carte Google n'est pas encore branchée sur iPhone (SDK Google Maps pour iOS à intégrer) : l'écran carte garde la carte
// OpenStreetMap et n'affiche pas le bouton « satellite ».

@Composable
internal actual fun rememberGoogleMapAvailable(): Boolean = false

@Composable
internal actual fun GoogleSiteMap(latitude: Double, longitude: Double, type: SiteType?, onLoaded: () -> Unit, modifier: Modifier) {
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
}
