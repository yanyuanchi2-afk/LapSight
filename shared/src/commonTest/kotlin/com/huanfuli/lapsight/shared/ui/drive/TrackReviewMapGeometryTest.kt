package com.huanfuli.lapsight.shared.ui.drive

import com.huanfuli.lapsight.shared.lap.GeoPoint
import com.huanfuli.lapsight.shared.lap.LocalPoint
import com.huanfuli.lapsight.shared.lap.LocalProjection
import com.huanfuli.lapsight.shared.session.GeoPointDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrackReviewMapGeometryTest {

    @Test
    fun emptyOrInvalidGeometryHasNoViewport() {
        assertNull(trackReviewMapViewport(emptyList(), viewAspectRatio = 4.0 / 3.0))
        assertNull(
            trackReviewMapViewport(
                listOf(GeoPointDto(Double.NaN, -74.0), GeoPointDto(91.0, -74.0)),
                viewAspectRatio = 4.0 / 3.0,
            ),
        )
    }

    @Test
    fun shortGeometryUsesMinimumReadableSpan() {
        val viewport = trackReviewMapViewport(
            listOf(GeoPointDto(40.0, -74.0)),
            viewAspectRatio = 4.0 / 3.0,
        )

        assertNotNull(viewport)
        assertEquals(100.0, viewport.spanMeters)
        assertEquals(40.0, viewport.centerWgs84.latitude, 1e-9)
        assertEquals(-74.0, viewport.centerWgs84.longitude, 1e-9)
    }

    @Test
    fun wideCourseFitsUsingMapAspectRatio() {
        val projection = LocalProjection(GeoPoint(40.0, -74.0))
        val west = projection.toGeo(LocalPoint(-200.0, 0.0))
        val east = projection.toGeo(LocalPoint(200.0, 0.0))
        val viewport = trackReviewMapViewport(
            pointsWgs84 = listOf(
                GeoPointDto(west.latitude, west.longitude),
                GeoPointDto(east.latitude, east.longitude),
            ),
            viewAspectRatio = 2.0,
        )

        assertNotNull(viewport)
        assertTrue(viewport.spanMeters >= 260.0)
        assertTrue(viewport.spanMeters <= 270.0)
        assertEquals(40.0, viewport.centerWgs84.latitude, 1e-6)
        assertEquals(-74.0, viewport.centerWgs84.longitude, 1e-6)
    }
}
