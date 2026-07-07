package com.huanfuli.lapsight.shared.external

enum class ExternalGnssProtocol {
    Nmea0183,
    RaceBox,
    Unknown,
}

enum class ExternalGnssTransport {
    Replay,
    Ble,
    BluetoothSerial,
    Tcp,
    Unknown,
}

enum class ExternalGnssHardwareValidationStatus {
    Unknown,
    Unverified,
    Verified,
}

data class ExternalGnssReceiverIdentity(
    val protocol: ExternalGnssProtocol,
    val displayName: String,
    val modelName: String? = null,
    val deviceAddress: String? = null,
    val firmwareVersion: String? = null,
    val hardwareValidationStatus: ExternalGnssHardwareValidationStatus =
        ExternalGnssHardwareValidationStatus.Unverified,
)

enum class ExternalGnssConnectionPhase {
    Disconnected,
    Scanning,
    Connecting,
    Connected,
    Reconnecting,
    Failed,
}

data class ExternalGnssConnectionState(
    val phase: ExternalGnssConnectionPhase,
    val protocol: ExternalGnssProtocol = ExternalGnssProtocol.Unknown,
    val transport: ExternalGnssTransport = ExternalGnssTransport.Unknown,
    val receiver: ExternalGnssReceiverIdentity? = null,
    val message: String? = null,
    val hardwareValidationStatus: ExternalGnssHardwareValidationStatus =
        receiver?.hardwareValidationStatus ?: ExternalGnssHardwareValidationStatus.Unknown,
)

enum class ExternalGnssFixType {
    NoFix,
    Gps,
    DifferentialGps,
    Precise,
    RtkFixed,
    RtkFloat,
    Estimated,
    Unknown,
}

data class ExternalGnssFixQuality(
    val isValid: Boolean,
    val fixType: ExternalGnssFixType = if (isValid) {
        ExternalGnssFixType.Gps
    } else {
        ExternalGnssFixType.NoFix
    },
    val satellitesInUse: Int? = null,
    val hdop: Double? = null,
    val horizontalAccuracyMeters: Double? = null,
    val verticalAccuracyMeters: Double? = null,
    val speedAccuracyMetersPerSecond: Double? = null,
    val headingAccuracyDegrees: Double? = null,
    val usesDualFrequency: Boolean? = null,
)

data class ExternalGnssSourceMetadata(
    val protocol: ExternalGnssProtocol,
    val transport: ExternalGnssTransport = ExternalGnssTransport.Unknown,
    val receiver: ExternalGnssReceiverIdentity? = null,
    val sentenceId: String? = null,
    val updateRateHz: Double? = null,
    val hardwareValidationStatus: ExternalGnssHardwareValidationStatus =
        receiver?.hardwareValidationStatus ?: ExternalGnssHardwareValidationStatus.Unverified,
)

data class ExternalGnssVector3(
    val x: Double,
    val y: Double,
    val z: Double,
)

data class ExternalGnssTelemetryMetadata(
    val accelerationMetersPerSecondSquared: ExternalGnssVector3? = null,
    val gyroRadiansPerSecond: ExternalGnssVector3? = null,
    val vehicleSpeedMetersPerSecond: Double? = null,
    val throttlePercent: Double? = null,
    val brakePressure: Double? = null,
)

data class ExternalGnssFixSnapshot(
    val elapsedMillis: Long?,
    val latitude: Double?,
    val longitude: Double?,
    val speedMetersPerSecond: Double?,
    val headingDegrees: Double?,
    val altitudeMeters: Double?,
    val quality: ExternalGnssFixQuality,
    val source: ExternalGnssSourceMetadata,
    val telemetry: ExternalGnssTelemetryMetadata? = null,
) {
    val hasUsableLocation: Boolean
        get() = quality.isValid && latitude != null && longitude != null
}
