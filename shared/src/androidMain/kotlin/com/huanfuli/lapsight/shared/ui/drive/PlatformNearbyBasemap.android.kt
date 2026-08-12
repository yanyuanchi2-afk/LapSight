package com.huanfuli.lapsight.shared.ui.drive

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.huanfuli.lapsight.shared.session.GeoPointDto

/** Android keeps the existing offline grid until an Android basemap is chosen. */
@Composable
internal actual fun PlatformNearbyBasemap(
    provider: NearbyBasemapProvider,
    centerWgs84: GeoPointDto,
    spanMeters: Double,
    modifier: Modifier,
) = Unit
