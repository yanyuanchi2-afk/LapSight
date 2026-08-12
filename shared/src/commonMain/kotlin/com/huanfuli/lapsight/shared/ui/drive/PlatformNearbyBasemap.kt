package com.huanfuli.lapsight.shared.ui.drive

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.huanfuli.lapsight.shared.LocationSample
import com.huanfuli.lapsight.shared.session.GeoPointDto

/**
 * Stable provider identifiers for future basemap selection and persistence.
 *
 * [PlatformDefault] is the only implemented choice today (MapKit on iOS).
 * Google Maps and AMap are reserved integration points; adding either SDK
 * should only require a platform renderer and a settings selection.
 */
internal enum class NearbyBasemapProvider(val stableId: String) {
    PlatformDefault("platform-default"),
    GoogleMaps("google-maps"),
    AMap("amap"),
    ;

    companion object {
        fun fromStableId(stableId: String?): NearbyBasemapProvider =
            entries.firstOrNull { it.stableId == stableId } ?: PlatformDefault
    }
}

/**
 * Converts an authoritative LapSight fix into a coordinate safe for a native
 * platform map. Invalid receiver data stays out of platform map APIs rather
 * than producing an undefined region or a native exception.
 */
internal fun LocationSample.toNearbyBasemapCenter(): GeoPointDto? =
    if (
        latitude.isFinite() && longitude.isFinite() &&
        latitude in -90.0..90.0 && longitude in -180.0..180.0
    ) {
        GeoPointDto(latitude = latitude, longitude = longitude)
    } else {
        null
    }

/**
 * Platform road-map layer underneath LapSight's shared course/GPS overlays.
 *
 * The platform view is display-only and receives the already-selected
 * [LocationSample] coordinate. It must not start a second location provider or
 * become an alternate source of truth for marking/timing. [centerWgs84] always
 * uses the GPS WGS-84 datum; a provider such as AMap must perform any required
 * display-coordinate conversion inside its platform renderer.
 */
@Composable
internal expect fun PlatformNearbyBasemap(
    provider: NearbyBasemapProvider,
    centerWgs84: GeoPointDto,
    spanMeters: Double,
    modifier: Modifier,
)
