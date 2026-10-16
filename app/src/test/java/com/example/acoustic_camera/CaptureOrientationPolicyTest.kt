package com.example.acoustic_camera

import com.example.acoustic_camera.motion.CaptureOrientationPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureOrientationPolicyTest {
    @Test
    fun acceptsOnlyChargingPortRightLandscape() {
        assertTrue(CaptureOrientationPolicy.isReady(screenLandscape = true, rollDegrees = -90f))
        assertFalse(CaptureOrientationPolicy.isReady(screenLandscape = true, rollDegrees = 90f))
        assertFalse(CaptureOrientationPolicy.isReady(screenLandscape = false, rollDegrees = -90f))
        assertFalse(CaptureOrientationPolicy.isReady(screenLandscape = true, rollDegrees = -20f))
    }
}
