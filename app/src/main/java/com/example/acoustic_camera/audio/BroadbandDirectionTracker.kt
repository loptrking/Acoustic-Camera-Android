package com.example.acoustic_camera.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin

enum class DirectionQuality {
    WAITING,
    WEAK_SIGNAL,
    LOW_COHERENCE,
    CHANNELS_TOO_SIMILAR,
    OUT_OF_PHYSICAL_RANGE,
    ACQUIRING,
    UNSTABLE,
    TRACKING,
}

data class BroadbandDirectionEstimate(
    val angleDegrees: Float? = null,
    val rawAngleDegrees: Float? = null,
    val confidence: Float = 0f,
    val quality: DirectionQuality = DirectionQuality.WAITING,
    val maxPhysicalLagSamples: Float,
    val acceptedWindowCount: Int = 0,
)

/**
 * Converts broadband GCC delay into a robust front-hemisphere horizontal angle.
 * Positive screen angle points right. The sign was measured on HONOR PTP-AN10:
 * charging-port/right incidence produces a negative GCC lag.
 */
class BroadbandDirectionTracker(
    sampleRate: Int = 48_000,
    baselineMeters: Float = 0.142567f,
    speedOfSoundMetersPerSecond: Float = 343f,
    private val historySize: Int = 9,
) {
    val maxPhysicalLagSamples: Float =
        sampleRate * baselineMeters / speedOfSoundMetersPerSecond

    private data class AcceptedSample(
        val lag: Float,
        val peakRatio: Float,
        val rms: Float,
    )

    private val history = ArrayDeque<AcceptedSample>()

    fun reset() = history.clear()

    fun update(
        lagSamples: Float?,
        peakRatio: Float?,
        differenceRatio: Float?,
        rms: Float,
    ): BroadbandDirectionEstimate {
        if (lagSamples == null || peakRatio == null || differenceRatio == null) {
            return rejected(DirectionQuality.WAITING)
        }
        val rawAngle = lagToAngle(lagSamples)
        if (rms < MIN_RMS) return rejected(DirectionQuality.WEAK_SIGNAL, rawAngle)
        if (peakRatio < MIN_PEAK_RATIO) return rejected(DirectionQuality.LOW_COHERENCE, rawAngle)
        if (differenceRatio < MIN_DIFFERENCE_RATIO) {
            return rejected(DirectionQuality.CHANNELS_TOO_SIMILAR, rawAngle)
        }
        if (abs(lagSamples) > maxPhysicalLagSamples * MAX_LAG_TOLERANCE) {
            return rejected(DirectionQuality.OUT_OF_PHYSICAL_RANGE, rawAngle)
        }

        history.addLast(AcceptedSample(lagSamples, peakRatio, rms))
        while (history.size > historySize) history.removeFirst()
        val medianLag = median(history.map { it.lag })
        val filteredAngle = lagToAngle(medianLag)
        if (history.size < MIN_HISTORY) {
            return BroadbandDirectionEstimate(
                angleDegrees = filteredAngle,
                rawAngleDegrees = rawAngle,
                confidence = history.size.toFloat() / MIN_HISTORY * 0.35f,
                quality = DirectionQuality.ACQUIRING,
                maxPhysicalLagSamples = maxPhysicalLagSamples,
                acceptedWindowCount = history.size,
            )
        }

        val lagMad = median(history.map { abs(it.lag - medianLag) })
        val peakScore = ((median(history.map { it.peakRatio }) - MIN_PEAK_RATIO) / 0.35f)
            .coerceIn(0f, 1f)
        val stabilityScore = (1f - lagMad / 4f).coerceIn(0f, 1f)
        val signalScore = ((median(history.map { it.rms }) - MIN_RMS) / 0.02f)
            .coerceIn(0f, 1f)
        val confidence = 0.35f * peakScore + 0.45f * stabilityScore + 0.20f * signalScore
        val quality = when {
            lagMad > 4f -> DirectionQuality.UNSTABLE
            confidence >= MIN_TRACKING_CONFIDENCE -> DirectionQuality.TRACKING
            else -> DirectionQuality.LOW_COHERENCE
        }
        return BroadbandDirectionEstimate(
            angleDegrees = filteredAngle,
            rawAngleDegrees = rawAngle,
            confidence = confidence,
            quality = quality,
            maxPhysicalLagSamples = maxPhysicalLagSamples,
            acceptedWindowCount = history.size,
        )
    }

    private fun rejected(
        quality: DirectionQuality,
        rawAngle: Float? = null,
    ): BroadbandDirectionEstimate = BroadbandDirectionEstimate(
        rawAngleDegrees = rawAngle,
        quality = quality,
        maxPhysicalLagSamples = maxPhysicalLagSamples,
        acceptedWindowCount = history.size,
    )

    private fun lagToAngle(lagSamples: Float): Float =
        (asin((-lagSamples / maxPhysicalLagSamples).coerceIn(-1f, 1f)) * 180f / PI).toFloat()

    private fun median(values: List<Float>): Float {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[middle - 1] + sorted[middle]) / 2f
        } else {
            sorted[middle]
        }
    }

    companion object {
        private const val MIN_RMS = 0.001f
        private const val MIN_PEAK_RATIO = 1.03f
        private const val MIN_DIFFERENCE_RATIO = 0.03f
        private const val MAX_LAG_TOLERANCE = 1.12f
        private const val MIN_HISTORY = 3
        private const val MIN_TRACKING_CONFIDENCE = 0.45f
    }
}
