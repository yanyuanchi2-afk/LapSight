package com.huanfuli.lapsight.shared.ui.drive

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import com.huanfuli.lapsight.shared.session.GeoPointDto
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreLocation.CLLocationCoordinate2DMake
import platform.MapKit.MKCoordinateRegionMakeWithDistance
import platform.MapKit.MKMapView
import kotlin.math.abs

/**
 * Apple Maps road basemap for the stationary Drive preview.
 *
 * LapSight's shared canvas remains above this view and draws the authoritative
 * accuracy circle, position/heading marker, selected course, and scale. MapKit
 * supplies tiles only and never owns location acquisition.
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalForeignApi::class)
@Composable
internal actual fun PlatformNearbyBasemap(
    center: GeoPointDto,
    spanMeters: Double,
    modifier: Modifier,
) {
    val regionState = remember { AppliedMapRegion() }

    UIKitView(
        factory = {
            MKMapView().apply {
                showsCompass = false
                showsScale = false
                showsTraffic = false
                showsUserLocation = false
                pitchEnabled = false
                rotateEnabled = false
                scrollEnabled = false
                zoomEnabled = false
            }
        },
        modifier = modifier,
        update = { mapView ->
            if (regionState.shouldApply(center, spanMeters)) {
                val coordinate = CLLocationCoordinate2DMake(
                    latitude = center.latitude,
                    longitude = center.longitude,
                )
                mapView.setRegion(
                    region = MKCoordinateRegionMakeWithDistance(
                        centerCoordinate = coordinate,
                        latitudinalMeters = spanMeters,
                        longitudinalMeters = spanMeters,
                    ),
                    animated = false,
                )
                regionState.markApplied(center, spanMeters)
            }
        },
        properties = UIKitInteropProperties(
            interactionMode = null,
            isNativeAccessibilityEnabled = false,
        ),
    )
}

/** Prevents the 30 fps marker-smoothing recomposition from resetting MapKit. */
private class AppliedMapRegion {
    private var latitude: Double? = null
    private var longitude: Double? = null
    private var spanMeters: Double? = null

    fun shouldApply(center: GeoPointDto, requestedSpanMeters: Double): Boolean =
        latitude == null || longitude == null || spanMeters == null ||
            abs(latitude!! - center.latitude) > COORDINATE_EPSILON ||
            abs(longitude!! - center.longitude) > COORDINATE_EPSILON ||
            abs(spanMeters!! - requestedSpanMeters) > SPAN_EPSILON_METERS

    fun markApplied(center: GeoPointDto, requestedSpanMeters: Double) {
        latitude = center.latitude
        longitude = center.longitude
        spanMeters = requestedSpanMeters
    }
}

private const val COORDINATE_EPSILON = 1e-7
private const val SPAN_EPSILON_METERS = 0.5
