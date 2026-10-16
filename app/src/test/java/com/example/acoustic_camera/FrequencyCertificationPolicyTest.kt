package com.example.acoustic_camera

import com.example.acoustic_camera.calibration.FrequencyCandidate
import com.example.acoustic_camera.calibration.FrequencyCertificationPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrequencyCertificationPolicyTest {
    @Test
    fun acceptsObservedStable550HzPair() {
        val first = FrequencyCandidate(550.78125, -72.65, 11.60, 1.82, 0.9995, "one")
        val second = FrequencyCandidate(550.78125, -80.63, 12.05, 2.44, 0.9991, "two")

        assertTrue(FrequencyCertificationPolicy.isEligible(first, 81.46))
        assertTrue(FrequencyCertificationPolicy.isEligible(second, 79.97))
        assertTrue(FrequencyCertificationPolicy.compare(first, second).consistent)
    }

    @Test
    fun rejectsDifferentFrequencyResponseAndSameSession() {
        val first = FrequencyCandidate(550.78125, -72.65, 11.60, 1.82, 0.9995, "same")
        val wrongAxis = FrequencyCandidate(550.78125, -34.63, 16.05, 5.90, 0.9947, "other")
        val duplicate = first.copy(axisOffsetDeg = -73.0)

        assertFalse(FrequencyCertificationPolicy.compare(first, wrongAxis).consistent)
        assertFalse(FrequencyCertificationPolicy.compare(first, duplicate).consistent)
    }

    @Test
    fun rejectsSevereTargetFrequencyChannelImbalance() {
        val imbalanced = FrequencyCandidate(
            550.78125, -72.65, 11.60, 1.82, 0.9995, "imbalanced", channelBalance = 0.22,
        )

        assertFalse(FrequencyCertificationPolicy.isEligible(imbalanced, 80.0))
    }
}
