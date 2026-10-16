package com.example.acoustic_camera.motion

object CaptureOrientationPolicy {
    /** Correct landscape posture: phone top/earpiece on the left, charging port on the right. */
    fun isChargingPortOnRight(rollDegrees: Float): Boolean = rollDegrees in -135f..-45f

    fun isReady(screenLandscape: Boolean, rollDegrees: Float): Boolean =
        screenLandscape && isChargingPortOnRight(rollDegrees)
}
