package com.huanfuli.lapsight.shared

/**
 * Pure roll-up of GPS quality over a list of [LocationSample]s.
 *
 * Used by Review and Export surfaces (SESS-02) to summarize capture quality
 * without re-walking raw samples in the UI. This is plain shared Kotlin with no
 * Compose, platform, or serialization dependency.
 *
 * @property sampleCount number of samples summarized.
 * @property durationMillis elapsed time spanned by the samples (last - first).
 * @property averageUpdateRateHz mean sample rate over [durationMillis].
 * @property bestAccuracyMeters smallest reported horizontal accuracy, or null.
 * @property worstAccuracyMeters largest reported horizontal accuracy, or null.
 * @property degradedSampleCount samples whose accuracy exceeds the degraded
 *   threshold (worse than the threshold) or that report no accuracy at all.
 * @property sources the distinct [LocationSource]s present in the samples.
 */
data class GpsQualitySummary(
    val sampleCount: Int,
    val durationMillis: Long,
    val averageUpdateRateHz: Double,
    val bestAccuracyMeters: Double?,
    val worstAccuracyMeters: Double?,
    val degradedSampleCount: Int,
    val sources: Set<LocationSource>,
) {
    companion object {
        /** Accuracy worse than this (in meters) marks a sample as degraded. */
        const val DEFAULT_DEGRADED_ACCURACY_METERS: Double = 25.0

        private val EMPTY = GpsQualitySummary(
            sampleCount = 0,
            durationMillis = 0L,
            averageUpdateRateHz = 0.0,
            bestAccuracyMeters = null,
            worstAccuracyMeters = null,
            degradedSampleCount = 0,
            sources = emptySet(),
        )

        fun from(
            samples: List<LocationSample>,
            degradedAccuracyMeters: Double = DEFAULT_DEGRADED_ACCURACY_METERS,
        ): GpsQualitySummary {
            if (samples.isEmpty()) return EMPTY

            val duration = samples.last().elapsedMillis - samples.first().elapsedMillis
            // NMEA receivers report one fix through several sentences (for
            // example RMC, GGA and EPE). They share the same UTC epoch and must
            // count as one receiver update, not three UI samples.
            val fixEpochCount = samples.asSequence()
                .map { it.elapsedMillis }
                .distinct()
                .count()
            val rate = if (duration > 0L && fixEpochCount > 1) {
                (fixEpochCount - 1) * 1_000.0 / duration
            } else {
                0.0
            }

            val accuracies = samples.mapNotNull { it.horizontalAccuracyMeters }
            val best = accuracies.minOrNull()
            val worst = accuracies.maxOrNull()

            val degraded = samples.count { sample ->
                val accuracy = sample.horizontalAccuracyMeters
                accuracy == null || accuracy > degradedAccuracyMeters
            }

            return GpsQualitySummary(
                sampleCount = samples.size,
                durationMillis = duration,
                averageUpdateRateHz = rate,
                bestAccuracyMeters = best,
                worstAccuracyMeters = worst,
                degradedSampleCount = degraded,
                sources = samples.map { it.source }.toSet(),
            )
        }
    }
}
