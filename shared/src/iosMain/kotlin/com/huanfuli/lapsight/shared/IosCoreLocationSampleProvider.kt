package com.huanfuli.lapsight.shared

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.CoreLocation.CLActivityTypeAutomotiveNavigation
import platform.CoreLocation.CLLocation
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.CoreLocation.kCLDistanceFilterNone
import platform.CoreLocation.kCLLocationAccuracyBestForNavigation
import platform.Foundation.NSError
import platform.Foundation.timeIntervalSince1970
import platform.darwin.NSObject

/**
 * Core Location-backed phone GPS feed for iOS.
 *
 * Core Location owns acquisition only. The shared lap/session pipeline consumes
 * the same [LocationSampleProvider] contract used by Android and replay feeds.
 */
@OptIn(ExperimentalForeignApi::class)
class IosCoreLocationSampleProvider : LocationSampleProvider {

    private val manager = CLLocationManager()
    private val locationDelegate = IosCoreLocationDelegate(
        onLocations = ::handleLocations,
        onAuthorizationChanged = ::handleAuthorizationChanged,
        onError = ::handleError,
    )
    private val queue = ArrayDeque<LocationSample>()
    private val mutablePermissionGranted = MutableStateFlow(isAuthorized(manager.authorizationStatus))
    private var running = false
    private var firstTimestampSeconds: Double? = null
    private var lastElapsedMillis: Long? = null

    val permissionGranted: StateFlow<Boolean> = mutablePermissionGranted.asStateFlow()

    val isSupported: Boolean
        get() = manager.locationServicesEnabled

    init {
        manager.delegate = locationDelegate
        manager.desiredAccuracy = kCLLocationAccuracyBestForNavigation
        manager.distanceFilter = kCLDistanceFilterNone
        manager.activityType = CLActivityTypeAutomotiveNavigation
        manager.pausesLocationUpdatesAutomatically = false
    }

    override val isRunning: Boolean
        get() = running

    fun requestPermission() {
        manager.requestWhenInUseAuthorization()
    }

    override fun start() {
        if (running) return
        refreshAuthorization()
        if (!isSupported || !mutablePermissionGranted.value) return

        running = true
        firstTimestampSeconds = null
        lastElapsedMillis = null
        queue.clear()
        manager.startUpdatingLocation()
    }

    override fun stop() {
        if (!running) return
        running = false
        manager.stopUpdatingLocation()
    }

    override fun reset() {
        stop()
        firstTimestampSeconds = null
        lastElapsedMillis = null
        queue.clear()
    }

    override fun nextSample(): LocationSample? = queue.removeFirstOrNull()

    override fun drainPending(): List<LocationSample> {
        if (queue.isEmpty()) return emptyList()
        val drained = queue.toList()
        queue.clear()
        return drained
    }

    private fun handleLocations(didUpdateLocations: List<*>) {
        if (!running) return
        didUpdateLocations.forEach { value ->
            val location = value as? CLLocation ?: return@forEach
            enqueue(location)
        }
    }

    private fun handleAuthorizationChanged() {
        refreshAuthorization()
        if (!mutablePermissionGranted.value) {
            stop()
        }
    }

    private fun handleError() {
        refreshAuthorization()
        if (!mutablePermissionGranted.value) {
            stop()
        }
    }

    private fun refreshAuthorization() {
        mutablePermissionGranted.value = isAuthorized(manager.authorizationStatus)
    }

    private fun enqueue(location: CLLocation) {
        val timestampSeconds = location.timestamp.timeIntervalSince1970
        val startSeconds = firstTimestampSeconds ?: timestampSeconds.also {
            firstTimestampSeconds = it
        }
        val rawElapsedMillis = ((timestampSeconds - startSeconds) * MILLIS_PER_SECOND)
            .toLong()
            .coerceAtLeast(0L)
        val elapsedMillis = lastElapsedMillis
            ?.let { rawElapsedMillis.coerceAtLeast(it + 1L) }
            ?: rawElapsedMillis
        lastElapsedMillis = elapsedMillis

        val latitude = location.coordinate.useContents { latitude }
        val longitude = location.coordinate.useContents { longitude }
        val horizontalAccuracy = location.horizontalAccuracy.takeIf { it >= 0.0 }
        val verticalAccuracy = location.verticalAccuracy.takeIf { it >= 0.0 }
        val speed = location.speed.takeIf { it >= 0.0 }
        val heading = location.course.takeIf { it >= 0.0 }
        val speedAccuracy = location.speedAccuracy.takeIf { it >= 0.0 }

        while (queue.size >= MAX_QUEUE_SIZE) {
            queue.removeFirst()
        }
        queue.addLast(
            LocationSample(
                elapsedMillis = elapsedMillis,
                latitude = latitude,
                longitude = longitude,
                horizontalAccuracyMeters = horizontalAccuracy,
                speedMetersPerSecond = speed,
                headingDegrees = heading,
                altitudeMeters = location.altitude.takeIf { verticalAccuracy != null },
                source = LocationSource.PhoneGps,
                speedAccuracyMetersPerSecond = speedAccuracy,
                verticalAccuracyMeters = verticalAccuracy,
            ),
        )
    }

}

@OptIn(ExperimentalForeignApi::class)
private class IosCoreLocationDelegate(
    private val onLocations: (List<*>) -> Unit,
    private val onAuthorizationChanged: () -> Unit,
    private val onError: () -> Unit,
) : NSObject(), CLLocationManagerDelegateProtocol {

    override fun locationManager(
        manager: CLLocationManager,
        didUpdateLocations: List<*>,
    ) {
        onLocations(didUpdateLocations)
    }

    override fun locationManagerDidChangeAuthorization(manager: CLLocationManager) {
        onAuthorizationChanged()
    }

    override fun locationManager(
        manager: CLLocationManager,
        didFailWithError: NSError,
    ) {
        onError()
    }
}

private const val MAX_QUEUE_SIZE = 1_000
private const val MILLIS_PER_SECOND = 1_000.0

private fun isAuthorized(status: Int): Boolean =
    status == kCLAuthorizationStatusAuthorizedWhenInUse ||
        status == kCLAuthorizationStatusAuthorizedAlways
