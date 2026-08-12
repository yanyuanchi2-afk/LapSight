package com.huanfuli.lapsight.shared.ui.drive

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.huanfuli.lapsight.shared.LocationSample
import com.huanfuli.lapsight.shared.session.GeoPointDto

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
 * become an alternate source of truth for marking/timing.
 */
@Composable
internal expect fun PlatformNearbyBasemap(
    center: GeoPointDto,
    spanMeters: Double,
    modifier: Modifier,
)
