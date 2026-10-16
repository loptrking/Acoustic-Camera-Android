package com.example.acoustic_camera

import com.example.acoustic_camera.calibration.CalibrationGuideController
import com.example.acoustic_camera.calibration.CalibrationPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationGuideControllerTest {
    @Test
    fun pausesGuideWhenRotationPlaneDrifts() {
        val controller = CalibrationGuideController()
        val now = 1_000_000_000L
        controller.start(referencePitchDegrees = 1f, referenceRollDegrees = -67f)

        val drifted = controller.update(0f, 0.01f, now, pitchDegrees = 5f, rollDegrees = -67f)

        assertEquals(CalibrationPhase.HOLD_CENTER, drifted.phase)
        assertFalse(drifted.postureReady)
        assertEquals(4f, drifted.planeDeviationDegrees, 0.01f)
    }

    @Test
    fun completesGuidedCenterLeftRightCenterSequence() {
        val controller = CalibrationGuideController()
        var now = 1_000_000_000L
        controller.start()
        controller.update(0f, 0.01f, now)
        now += 2_100_000_000L
        assertEquals(CalibrationPhase.ROTATE_LEFT, controller.update(0f, 0.01f, now).phase)
        assertEquals(CalibrationPhase.HOLD_LEFT, controller.update(-38f, 0.2f, now).phase)
        controller.update(-40f, 0.01f, now)
        now += 1_600_000_000L
        assertEquals(CalibrationPhase.ROTATE_RIGHT, controller.update(-40f, 0.01f, now).phase)
        assertEquals(CalibrationPhase.HOLD_RIGHT, controller.update(38f, 0.2f, now).phase)
        controller.update(40f, 0.01f, now)
        now += 1_600_000_000L
        assertEquals(CalibrationPhase.RETURN_CENTER, controller.update(40f, 0.01f, now).phase)
        assertEquals(CalibrationPhase.HOLD_FINAL, controller.update(2f, 0.2f, now).phase)
        controller.update(0f, 0.01f, now)
        now += 2_100_000_000L
        val completed = controller.update(0f, 0.01f, now)
        assertEquals(CalibrationPhase.COMPLETE, completed.phase)
        assertTrue(completed.complete)
    }
}
