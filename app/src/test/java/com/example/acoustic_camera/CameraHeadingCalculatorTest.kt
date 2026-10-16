package com.example.acoustic_camera

import com.example.acoustic_camera.motion.CameraHeadingCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraHeadingCalculatorTest {
    @Test
    fun derivesNorthAndEastFromBackCameraAxis() {
        val north = floatArrayOf(
            1f, 0f, 0f,
            0f, 0f, -1f,
            0f, 1f, 0f,
        )
        val east = floatArrayOf(
            0f, 0f, -1f,
            1f, 0f, 0f,
            0f, -1f, 0f,
        )

        assertEquals(0f, requireNotNull(CameraHeadingCalculator.fromRotationMatrix(north)).degrees, 0.01f)
        assertEquals(90f, requireNotNull(CameraHeadingCalculator.fromRotationMatrix(east)).degrees, 0.01f)
    }

    @Test
    fun rejectsCameraPointingVertically() {
        assertNull(
            CameraHeadingCalculator.fromRotationMatrix(
                floatArrayOf(
                    1f, 0f, 0f,
                    0f, 1f, 0f,
                    0f, 0f, 1f,
                ),
            ),
        )
    }
}
