package com.example.acoustic_camera.pose

import java.io.File
import java.util.Locale
import kotlin.math.sqrt

class PoseRecorder {
    private val samples = mutableListOf<PoseFrame>()
    private var active = false

    @Synchronized
    fun begin() {
        samples.clear()
        active = true
    }

    @Synchronized
    fun add(frame: PoseFrame) {
        if (!active) return
        if (samples.lastOrNull()?.frameTimestampNs == frame.frameTimestampNs) return
        samples += frame
    }

    @Synchronized
    fun discard() {
        active = false
        samples.clear()
    }

    @Synchronized
    fun finish(directory: File): PoseSummary {
        active = false
        val snapshot = samples.toList()
        samples.clear()
        val output = File(directory, "pose.csv")
        output.bufferedWriter().use { writer ->
            writer.write(
                "frame_timestamp_ns,android_camera_timestamp_ns,callback_monotonic_ns," +
                    "x,y,z,qx,qy,qz,qw,tracking_state\n"
            )
            snapshot.forEach { frame ->
                writer.write(
                    String.format(
                        Locale.US,
                        "%d,%d,%d,%.9f,%.9f,%.9f,%.9f,%.9f,%.9f,%.9f,%s\n",
                        frame.frameTimestampNs,
                        frame.androidCameraTimestampNs,
                        frame.callbackMonotonicNs,
                        frame.x,
                        frame.y,
                        frame.z,
                        frame.qx,
                        frame.qy,
                        frame.qz,
                        frame.qw,
                        frame.trackingState,
                    )
                )
            }
        }
        val tracking = snapshot.filter { it.trackingState == "TRACKING" }
        val first = tracking.firstOrNull()
        val aperture = if (first == null) 0f else tracking.maxOf { frame ->
            val dx = frame.x - first.x
            val dy = frame.y - first.y
            val dz = frame.z - first.z
            sqrt(dx * dx + dy * dy + dz * dz)
        }
        return PoseSummary(snapshot.size, tracking.size, aperture)
    }
}
