package com.example.acoustic_camera.calibration

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

data class FrequencyCandidate(
    val frequencyHz: Double,
    val axisOffsetDeg: Double,
    val effectiveBaselineCm: Double,
    val residualRmsDeg: Double,
    val score: Double,
    val sessionName: String,
    val channelBalance: Double = 1.0,
)

data class FrequencyComparison(
    val axisDifferenceDeg: Double,
    val baselineRelativeDifference: Double,
    val consistent: Boolean,
)

object FrequencyCertificationPolicy {
    const val PRIMARY_FREQUENCY_HZ = 550.78125
    const val MIN_SCORE = 0.98
    const val MAX_RESIDUAL_DEG = 10.0
    const val MIN_BASELINE_CM = 2.0
    const val MAX_BASELINE_CM = 18.0
    const val MIN_HEADING_SPAN_DEG = 60.0
    const val MAX_AXIS_DIFFERENCE_DEG = 10.0
    const val MAX_BASELINE_RELATIVE_DIFFERENCE = 0.30
    const val MIN_CHANNEL_BALANCE = 0.30

    fun isEligible(candidate: FrequencyCandidate, headingSpanDeg: Double): Boolean =
        headingSpanDeg >= MIN_HEADING_SPAN_DEG &&
            candidate.score >= MIN_SCORE &&
            candidate.residualRmsDeg <= MAX_RESIDUAL_DEG &&
            candidate.effectiveBaselineCm in MIN_BASELINE_CM..MAX_BASELINE_CM &&
            candidate.channelBalance >= MIN_CHANNEL_BALANCE

    fun compare(previous: FrequencyCandidate, current: FrequencyCandidate): FrequencyComparison {
        val axisDifference = circularDistance(previous.axisOffsetDeg, current.axisOffsetDeg)
        val baselineDifference = abs(previous.effectiveBaselineCm - current.effectiveBaselineCm) /
            ((previous.effectiveBaselineCm + current.effectiveBaselineCm) / 2.0).coerceAtLeast(0.01)
        return FrequencyComparison(
            axisDifferenceDeg = axisDifference,
            baselineRelativeDifference = baselineDifference,
            consistent = previous.sessionName != current.sessionName &&
                axisDifference <= MAX_AXIS_DIFFERENCE_DEG &&
                baselineDifference <= MAX_BASELINE_RELATIVE_DIFFERENCE,
        )
    }

    fun merge(previous: FrequencyCandidate, current: FrequencyCandidate): FrequencyCandidate {
        val first = previous.axisOffsetDeg * PI / 180.0
        val second = current.axisOffsetDeg * PI / 180.0
        val mergedAxis = atan2(sin(first) + sin(second), cos(first) + cos(second)) * 180.0 / PI
        return current.copy(
            axisOffsetDeg = wrapDegrees(mergedAxis),
            effectiveBaselineCm =
                (previous.effectiveBaselineCm + current.effectiveBaselineCm) / 2.0,
            residualRmsDeg = (previous.residualRmsDeg + current.residualRmsDeg) / 2.0,
            score = (previous.score + current.score) / 2.0,
            channelBalance = minOf(previous.channelBalance, current.channelBalance),
        )
    }

    fun key(frequencyHz: Double): String = frequencyHz.toInt().toString()

    private fun circularDistance(first: Double, second: Double): Double =
        abs(wrapDegrees(first - second))

    private fun wrapDegrees(value: Double): Double =
        ((value + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
}
