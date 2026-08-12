package com.huanfuli.lapsight.shared.ui.drive

import com.huanfuli.lapsight.shared.LocationSample
import com.huanfuli.lapsight.shared.LocationSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlatformNearbyBasemapTest {

    @Test
    fun providerIdsAreStableAndUnknownValuesFallBack() {
        assertEquals(
            NearbyBasemapProvider.GoogleMaps,
            NearbyBasemapProvider.fromStableId("google-maps"),
        )
        assertEquals(
            NearbyBasemapProvider.AMap,
            NearbyBasemapProvider.fromStableId("amap"),
        )
        assertEquals(
            NearbyBasemapProvider.PlatformDefault,
            NearbyBasemapProvider.fromStableId("future-provider"),
        )
    }

    @Test
    fun validFixBecomesBasemapCenter() {
        val center = sample(latitude = 40.7128, longitude = -74.0060).toNearbyBasemapCenter()

        assertEquals(40.7128, center?.latitude)
        assertEquals(-74.0060, center?.longitude)
    }

    @Test
    fun nonFiniteFixIsRejected() {
        assertNull(sample(latitude = Double.NaN, longitude = -74.0).toNearbyBasemapCenter())
        assertNull(sample(latitude = 40.0, longitude = Double.POSITIVE_INFINITY).toNearbyBasemapCenter())
    }

    @Test
    fun outOfRangeFixIsRejected() {
        assertNull(sample(latitude = 90.1, longitude = 0.0).toNearbyBasemapCenter())
        assertNull(sample(latitude = 0.0, longitude = -180.1).toNearbyBasemapCenter())
    }

    private fun sample(latitude: Double, longitude: Double): LocationSample = LocationSample(
        elapsedMillis = 1_000L,
        latitude = latitude,
        longitude = longitude,
        horizontalAccuracyMeters = 5.0,
        speedMetersPerSecond = 0.0,
        headingDegrees = 0.0,
        altitudeMeters = 0.0,
        source = LocationSource.PhoneGps,
    )
}
