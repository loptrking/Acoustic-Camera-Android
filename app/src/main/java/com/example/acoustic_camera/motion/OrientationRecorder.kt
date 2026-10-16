package com.example.acoustic_camera.motion

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import java.io.File
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt

data class OrientationFrame(
    val sensorTimestampNs: Long,
    val callbackMonotonicNs: Long,
    val qx: Float,
    val qy: Float,
    val qz: Float,
    val qw: Float,
    val yawDegrees: Float,
    val pitchDegrees: Float,
    val rollDegrees: Float,
    val cameraHeadingDegrees: Float?,
    val cameraForwardHorizontalNorm: Float,
    val relativeHeadingDegrees: Float,
    val yawSpanDegrees: Float,
    val angularSpeedRadS: Float,
    val linearAccelerationMS2: Float,
)

data class OrientationSummary(
    val rowCount: Int,
    val yawSpanDegrees: Float,
    val sensorName: String,
)

class OrientationRecorder(
    context: Context,
    private val onFrame: (OrientationFrame) -> Unit,
) : SensorEventListener {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor =
        sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val linearAcceleration = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val lock = Any()
    private val recordedFrames = ArrayList<OrientationFrame>(4096)

    @Volatile private var recording = false
    private var angularSpeed = 0f
    private var linearAccelerationMagnitude = 0f
    private var previousRawYaw: Float? = null
    private var unwrappedYaw = 0f
    private var originYaw: Float? = null
    private var minRelativeYaw = 0f
    private var maxRelativeYaw = 0f
    private var lastUiCallbackNs = 0L

    val isAvailable: Boolean get() = rotationSensor != null
    val sensorName: String get() = rotationSensor?.name ?: "Unavailable"

    fun startMonitoring() {
        rotationSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        gyroscope?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        linearAcceleration?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stopMonitoring() {
        sensorManager.unregisterListener(this)
    }

    fun beginRecording() {
        synchronized(lock) { recordedFrames.clear() }
        previousRawYaw = null
        unwrappedYaw = 0f
        originYaw = null
        minRelativeYaw = 0f
        maxRelativeYaw = 0f
        recording = true
    }

    fun discard() {
        recording = false
        synchronized(lock) { recordedFrames.clear() }
    }

    fun finish(directory: File): OrientationSummary {
        recording = false
        val snapshot = synchronized(lock) { recordedFrames.toList() }
        File(directory, "orientation.csv").bufferedWriter().use { writer ->
            writer.write(
                "sensor_timestamp_ns,callback_monotonic_ns,qx,qy,qz,qw," +
                "yaw_deg,pitch_deg,roll_deg,camera_heading_deg," +
                    "camera_forward_horizontal_norm,relative_heading_deg," +
                    "camera_heading_span_deg,yaw_span_deg," +
                    "angular_speed_rad_s,linear_acceleration_m_s2\n",
            )
            snapshot.forEach { frame ->
                writer.write(
                    String.format(
                        Locale.US,
                        "%d,%d,%.8f,%.8f,%.8f,%.8f,%.5f,%.5f,%.5f,%s,%.6f,%.5f,%.5f,%.5f,%.6f,%.6f\n",
                        frame.sensorTimestampNs,
                        frame.callbackMonotonicNs,
                        frame.qx,
                        frame.qy,
                        frame.qz,
                        frame.qw,
                        frame.yawDegrees,
                        frame.pitchDegrees,
                        frame.rollDegrees,
                        frame.cameraHeadingDegrees?.let { String.format(Locale.US, "%.5f", it) }
                            ?: "",
                        frame.cameraForwardHorizontalNorm,
                        frame.relativeHeadingDegrees,
                        frame.yawSpanDegrees,
                        frame.yawSpanDegrees,
                        frame.angularSpeedRadS,
                        frame.linearAccelerationMS2,
                    ),
                )
            }
        }
        return OrientationSummary(
            rowCount = snapshot.size,
            yawSpanDegrees = snapshot.maxOfOrNull { it.yawSpanDegrees } ?: 0f,
            sensorName = sensorName,
        )
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> {
                angularSpeed = magnitude(event.values)
                return
            }
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                linearAccelerationMagnitude = magnitude(event.values)
                return
            }
            Sensor.TYPE_GAME_ROTATION_VECTOR,
            Sensor.TYPE_ROTATION_VECTOR -> handleRotation(event)
        }
    }

    private fun handleRotation(event: SensorEvent) {
        val quaternion = FloatArray(4)
        SensorManager.getQuaternionFromVector(quaternion, event.values)
        val matrix = FloatArray(9)
        val angles = FloatArray(3)
        SensorManager.getRotationMatrixFromVector(matrix, event.values)
        SensorManager.getOrientation(matrix, angles)
        val rawYaw = radiansToDegrees(angles[0])
        val cameraHeading = CameraHeadingCalculator.fromRotationMatrix(matrix)
        val scanHeading = cameraHeading?.degrees ?: rawYaw
        val previous = previousRawYaw
        if (previous == null) {
            unwrappedYaw = scanHeading
        } else {
            var delta = scanHeading - previous
            if (delta > 180f) delta -= 360f
            if (delta < -180f) delta += 360f
            unwrappedYaw += delta
        }
        previousRawYaw = scanHeading
        val origin = originYaw ?: unwrappedYaw.also { originYaw = it }
        val relativeYaw = unwrappedYaw - origin
        minRelativeYaw = minOf(minRelativeYaw, relativeYaw)
        maxRelativeYaw = maxOf(maxRelativeYaw, relativeYaw)
        val frame = OrientationFrame(
            sensorTimestampNs = event.timestamp,
            callbackMonotonicNs = System.nanoTime(),
            qx = quaternion[1],
            qy = quaternion[2],
            qz = quaternion[3],
            qw = quaternion[0],
            yawDegrees = rawYaw,
            pitchDegrees = radiansToDegrees(angles[1]),
            rollDegrees = radiansToDegrees(angles[2]),
            cameraHeadingDegrees = cameraHeading?.degrees,
            cameraForwardHorizontalNorm = cameraHeading?.horizontalNorm ?: 0f,
            relativeHeadingDegrees = relativeYaw,
            yawSpanDegrees = maxRelativeYaw - minRelativeYaw,
            angularSpeedRadS = angularSpeed,
            linearAccelerationMS2 = linearAccelerationMagnitude,
        )
        if (recording) synchronized(lock) { recordedFrames.add(frame) }
        if (frame.callbackMonotonicNs - lastUiCallbackNs >= UI_CALLBACK_INTERVAL_NS) {
            lastUiCallbackNs = frame.callbackMonotonicNs
            onFrame(frame)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun magnitude(values: FloatArray): Float =
        sqrt(values.take(3).sumOf { it.toDouble() * it.toDouble() }).toFloat()

    private fun radiansToDegrees(value: Float): Float = (value * 180f / PI.toFloat())

    companion object {
        private const val UI_CALLBACK_INTERVAL_NS = 33_000_000L
    }
}
