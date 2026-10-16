package com.example.acoustic_camera.motion

import kotlin.math.atan2
import kotlin.math.sqrt

data class CameraHeading(
    val degrees: Float,
    val horizontalNorm: Float,
)

object CameraHeadingCalculator {
    /**
     * Derives the back-camera optical axis from a device-to-world rotation matrix.
     * Android device coordinates use +Z out of the display, so the back camera looks along -Z.
     * World X/Y are treated as east/north for a compass-style heading.
     */
    fun fromRotationMatrix(matrix: FloatArray): CameraHeading? {
        require(matrix.size >= 9)
        val forwardEast = -matrix[2]
        val forwardNorth = -matrix[5]
        val horizontalNorm = sqrt(
            forwardEast * forwardEast + forwardNorth * forwardNorth,
        )
        if (horizontalNorm < 0.15f) return null
        var degrees = Math.toDegrees(
            atan2(forwardEast.toDouble(), forwardNorth.toDouble()),
        ).toFloat()
        if (degrees < -180f) degrees += 360f
        if (degrees >= 180f) degrees -= 360f
        return CameraHeading(degrees, horizontalNorm)
    }
}
