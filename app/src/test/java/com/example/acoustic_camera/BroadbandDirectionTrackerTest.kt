package com.example.acoustic_camera

import com.example.acoustic_camera.audio.BroadbandDirectionTracker
import com.example.acoustic_camera.audio.DirectionQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BroadbandDirectionTrackerTest {
    @Test
    fun mapsMeasuredLagSignToScreenDirection() {
        val tracker = BroadbandDirectionTracker()
        val maxLag = tracker.maxPhysicalLagSamples

        val right = stableEstimate(tracker, -maxLag * 0.5f)
        tracker.reset()
        val left = stableEstimate(tracker, maxLag * 0.5f)

        assertEquals(30f, right.angleDegrees!!, 0.5f)
        assertEquals(-30f, left.angleDegrees!!, 0.5f)
        assertEquals(DirectionQuality.TRACKING, right.quality)
        assertEquals(DirectionQuality.TRACKING, left.quality)
    }

    @Test
    fun rejectsDelayBeyondMeasuredPhysicalTolerance() {
        val tracker = BroadbandDirectionTracker()
        val result = tracker.update(
            lagSamples = tracker.maxPhysicalLagSamples * 1.2f,
            peakRatio = 2f,
            differenceRatio = 0.8f,
            rms = 0.02f,
        )

        assertEquals(DirectionQuality.OUT_OF_PHYSICAL_RANGE, result.quality)
        assertEquals(null, result.angleDegrees)
    }

    @Test
    fun medianHistorySuppressesSingleDelayOutlier() {
        val tracker = BroadbandDirectionTracker(historySize = 7)
        repeat(3) { tracker.update(-10f, 1.8f, 0.8f, 0.02f) }
        tracker.update(18f, 1.8f, 0.8f, 0.02f)
        val result = tracker.update(-10f, 1.8f, 0.8f, 0.02f)

        assertTrue(result.angleDegrees!! > 25f)
        assertTrue(result.confidence > 0.6f)
    }

    private fun stableEstimate(
        tracker: BroadbandDirectionTracker,
        lag: Float,
    ) = List(5) {
        tracker.update(lag, peakRatio = 1.8f, differenceRatio = 0.8f, rms = 0.02f)
    }.last()
}
