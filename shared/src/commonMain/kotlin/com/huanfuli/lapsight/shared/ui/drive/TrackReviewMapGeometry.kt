package com.huanfuli.lapsight.shared.ui.drive

import com.huanfuli.lapsight.shared.lap.GeoPoint
import com.huanfuli.lapsight.shared.lap.LocalProjection
import com.huanfuli.lapsight.shared.session.GeoPointDto

/** Geographic camera shared by the native basemap and Compose review overlay. */
internal data class TrackReviewMapViewport(
    val centerWgs84: GeoPointDto,
    val spanMeters: Double,
)

/**
 * Fits recorded review geometry into a non-interactive map with a stable margin.
 * Invalid receiver points are ignored so one corrupt fix cannot blank the review.
 */
internal fun trackReviewMapViewport(
    pointsWgs84: List<GeoPointDto>,
    viewAspectRatio: Double,
    minimumSpanMeters: Double = REVIEW_MAP_MINIMUM_SPAN_METERS,
    paddingFraction: Double = REVIEW_MAP_PADDING_FRACTION,
): TrackReviewMapViewport? {
    val points = pointsWgs84.filter { point ->
        point.latitude.isFinite() && point.longitude.isFinite() &&
            point.latitude in -90.0..90.0 && point.longitude in -180.0..180.0
    }
    if (points.isEmpty()) return null

    val origin = GeoPoint(points.first().latitude, points.first().longitude)
    val projection = LocalProjection(origin)
    val local = points.map { projection.toLocal(GeoPoint(it.latitude, it.longitude)) }
    val minX = local.minOf { it.x }
    val maxX = local.maxOf { it.x }
    val minY = local.minOf { it.y }
    val maxY = local.maxOf { it.y }
    val center = projection.toGeo(
        com.huanfuli.lapsight.shared.lap.LocalPoint(
            x = (minX + maxX) / 2.0,
            y = (minY + maxY) / 2.0,
        ),
    )
    val aspect = viewAspectRatio.takeIf { it.isFinite() && it > 0.0 } ?: 1.0
    val usableFraction = (1.0 - paddingFraction.coerceIn(0.0, 0.4) * 2.0)
        .coerceAtLeast(0.2)
    val requiredVerticalSpan = maxOf(maxY - minY, (maxX - minX) / aspect)
    val span = (requiredVerticalSpan / usableFraction)
        .coerceAtLeast(minimumSpanMeters.coerceAtLeast(1.0))

    return TrackReviewMapViewport(
        centerWgs84 = GeoPointDto(center.latitude, center.longitude),
        spanMeters = span,
    )
}

private const val REVIEW_MAP_MINIMUM_SPAN_METERS = 100.0
private const val REVIEW_MAP_PADDING_FRACTION = 0.12
