package com.example.acoustic_camera.pose

data class PoseFrame(
    val frameTimestampNs: Long,
    val androidCameraTimestampNs: Long,
    val callbackMonotonicNs: Long,
    val x: Float,
    val y: Float,
    val z: Float,
    val qx: Float,
    val qy: Float,
    val qz: Float,
    val qw: Float,
    val trackingState: String,
)

data class PoseSummary(
    val rowCount: Int,
    val trackingRowCount: Int,
    val apertureMeters: Float,
)
