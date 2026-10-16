package com.example.acoustic_camera

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import com.example.acoustic_camera.audio.BroadbandDirectionEstimate
import com.example.acoustic_camera.audio.BroadbandDirectionTracker
import com.example.acoustic_camera.audio.DirectionQuality
import com.example.acoustic_camera.audio.GccPhatEstimator
import com.example.acoustic_camera.motion.CaptureOrientationPolicy
import com.example.acoustic_camera.motion.OrientationFrame
import com.example.acoustic_camera.motion.OrientationRecorder
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.sqrt

/** Eight-second stereo routing probe. It stores aggregate metrics only, never raw audio. */
class MicRoutingDiagnosticActivity : Activity() {
    @Volatile private var latestOrientation: OrientationFrame? = null
    private lateinit var orientationRecorder: OrientationRecorder

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        orientationRecorder = OrientationRecorder(this) { frame -> latestOrientation = frame }
        orientationRecorder.startMonitoring()
        Thread(::runProbe, "AcousticLens-MicRoutingProbe").start()
    }

    @SuppressLint("MissingPermission")
    private fun runProbe() {
        val destination = File(filesDir, "mic_routing_diagnostic.json")
        val report = runCatching {
            check(checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                "RECORD_AUDIO permission is not granted"
            }
            val sampleRate = 48_000
            val channelMask = AudioFormat.CHANNEL_IN_STEREO
            val requestedSourceName = intent.getStringExtra("source") ?: "VOICE_RECOGNITION"
            val requestedSource = when (requestedSourceName) {
                "CAMCORDER" -> MediaRecorder.AudioSource.CAMCORDER
                "MIC" -> MediaRecorder.AudioSource.MIC
                else -> MediaRecorder.AudioSource.VOICE_RECOGNITION
            }
            val minimum = AudioRecord.getMinBufferSize(
                sampleRate,
                channelMask,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            check(minimum > 0) { "Stereo 48 kHz input is unavailable" }
            val recorder = AudioRecord.Builder()
                .setAudioSource(requestedSource)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelMask)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(minimum * 2, 16_384))
                .build()
            check(recorder.state == AudioRecord.STATE_INITIALIZED && recorder.channelCount == 2) {
                "Stereo AudioRecord initialization failed"
            }

            val samples = ShortArray(8_192)
            var frames = 0L
            var sumLeft = 0.0
            var sumRight = 0.0
            var sumLeftSquared = 0.0
            var sumRightSquared = 0.0
            var sumCross = 0.0
            var peakLeft = 0
            var peakRight = 0
            val lags = mutableListOf<Double>()
            val peakRatios = mutableListOf<Double>()
            val lowBandLags = mutableListOf<Double>()
            val lowBandPeakRatios = mutableListOf<Double>()
            val midBandLags = mutableListOf<Double>()
            val midBandPeakRatios = mutableListOf<Double>()
            val trackedDirections = mutableListOf<Double>()
            val trackedConfidences = mutableListOf<Double>()
            val directionTracker = BroadbandDirectionTracker(sampleRate = sampleRate)
            var latestDirection = BroadbandDirectionEstimate(
                maxPhysicalLagSamples = directionTracker.maxPhysicalLagSamples,
            )
            var activeMicrophones = JSONArray()
            val startedNs = System.nanoTime()
            try {
                recorder.startRecording()
                check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    "Microphone did not enter recording state"
                }
                activeMicrophones = microphoneArray(recorder)
                while (System.nanoTime() - startedNs < PROBE_DURATION_NS) {
                    val read = recorder.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING)
                    check(read >= 0) { "Audio read failed: $read" }
                    val complete = read - read % 2
                    var blockSumSquared = 0.0
                    var index = 0
                    while (index < complete) {
                        val left = samples[index].toDouble()
                        val right = samples[index + 1].toDouble()
                        sumLeft += left
                        sumRight += right
                        sumLeftSquared += left * left
                        sumRightSquared += right * right
                        sumCross += left * right
                        blockSumSquared += left * left + right * right
                        peakLeft = maxOf(peakLeft, kotlin.math.abs(samples[index].toInt()))
                        peakRight = maxOf(peakRight, kotlin.math.abs(samples[index + 1].toInt()))
                        frames += 1
                        index += 2
                    }
                    val estimate = GccPhatEstimator.estimate(samples, complete, sampleRate)
                    val lowBandEstimate = GccPhatEstimator.estimate(
                        samples,
                        complete,
                        sampleRate,
                        lowFrequencyHz = 500,
                        highFrequencyHz = 1_200,
                        phaseFrequenciesHz = intArrayOf(),
                    )
                    val midBandEstimate = GccPhatEstimator.estimate(
                        samples,
                        complete,
                        sampleRate,
                        lowFrequencyHz = 500,
                        highFrequencyHz = 2_500,
                        phaseFrequenciesHz = intArrayOf(),
                    )
                    estimate?.let {
                        lags += estimate.lagSamples.toDouble()
                        peakRatios += estimate.peakRatio.toDouble()
                    }
                    lowBandEstimate?.let {
                        lowBandLags += it.lagSamples.toDouble()
                        lowBandPeakRatios += it.peakRatio.toDouble()
                    }
                    midBandEstimate?.let {
                        midBandLags += it.lagSamples.toDouble()
                        midBandPeakRatios += it.peakRatio.toDouble()
                    }
                    val blockRms = if (complete > 0) {
                        sqrt(blockSumSquared / complete) / 32768.0
                    } else {
                        0.0
                    }
                    latestDirection = directionTracker.update(
                        lagSamples = estimate?.lagSamples,
                        peakRatio = estimate?.peakRatio,
                        differenceRatio = estimate?.differenceRatio,
                        rms = blockRms.toFloat(),
                    )
                    if (latestDirection.quality == DirectionQuality.TRACKING) {
                        latestDirection.angleDegrees?.let { trackedDirections += it.toDouble() }
                        trackedConfidences += latestDirection.confidence.toDouble()
                    }
                    val currentMicrophones = microphoneArray(recorder)
                    if (currentMicrophones.length() >= activeMicrophones.length()) {
                        activeMicrophones = currentMicrophones
                    }
                }
            } finally {
                runCatching { recorder.stop() }
                recorder.release()
            }
            check(frames > 0) { "No audio frames captured" }
            val meanLeft = sumLeft / frames
            val meanRight = sumRight / frames
            val varianceLeft = (sumLeftSquared / frames - meanLeft * meanLeft).coerceAtLeast(0.0)
            val varianceRight = (sumRightSquared / frames - meanRight * meanRight).coerceAtLeast(0.0)
            val covariance = sumCross / frames - meanLeft * meanRight
            val correlation = covariance / sqrt(varianceLeft * varianceRight).coerceAtLeast(1e-12)
            val orientation = latestOrientation
            JSONObject().apply {
                put("status", "PASS")
                put("raw_audio_saved", false)
                put("duration_seconds", frames.toDouble() / sampleRate)
                put("sample_rate_hz", sampleRate)
                put("channel_count", 2)
                put("audio_source", requestedSourceName)
                put("active_microphones", activeMicrophones)
                put("left_rms", sqrt(sumLeftSquared / frames) / 32768.0)
                put("right_rms", sqrt(sumRightSquared / frames) / 32768.0)
                put("left_peak", peakLeft / 32768.0)
                put("right_peak", peakRight / 32768.0)
                put("stereo_correlation", correlation)
                put("median_gcc_lag_samples", median(lags))
                put("median_gcc_peak_ratio", median(peakRatios))
                put("gcc_lag_mad_samples", medianAbsoluteDeviation(lags))
                put("gcc_window_count", lags.size)
                put("low_band_hz", "500-1200")
                put("low_band_median_gcc_lag_samples", median(lowBandLags))
                put("low_band_gcc_lag_mad_samples", medianAbsoluteDeviation(lowBandLags))
                put("low_band_median_gcc_peak_ratio", median(lowBandPeakRatios))
                put("mid_band_hz", "500-2500")
                put("mid_band_median_gcc_lag_samples", median(midBandLags))
                put("mid_band_gcc_lag_mad_samples", medianAbsoluteDeviation(midBandLags))
                put("mid_band_median_gcc_peak_ratio", median(midBandPeakRatios))
                put("direction_model", "broadband_gcc_phat_front_hemisphere")
                put("direction_sign", "positive_screen_right = negative_gcc_lag")
                put("max_physical_lag_samples", directionTracker.maxPhysicalLagSamples)
                put("direction_quality_last", latestDirection.quality.name)
                put("direction_degrees_last", latestDirection.angleDegrees)
                put("direction_confidence_last", latestDirection.confidence)
                put("median_tracked_direction_degrees", median(trackedDirections))
                put("median_tracked_direction_confidence", median(trackedConfidences))
                put("direction_tracking_window_count", trackedDirections.size)
                put("orientation_sensor", orientationRecorder.sensorName)
                put("device_yaw_degrees", orientation?.yawDegrees)
                put("camera_heading_degrees", orientation?.cameraHeadingDegrees)
                put("relative_heading_degrees", orientation?.relativeHeadingDegrees)
                put("yaw_span_degrees", orientation?.yawSpanDegrees)
                put("device_roll_degrees", orientation?.rollDegrees)
                put("device_pitch_degrees", orientation?.pitchDegrees)
                put("angular_speed_rad_s_last", orientation?.angularSpeedRadS)
                put("linear_acceleration_m_s2_last", orientation?.linearAccelerationMS2)
                put(
                    "charging_port_on_screen_right",
                    orientation?.let { CaptureOrientationPolicy.isChargingPortOnRight(it.rollDegrees) },
                )
            }
        }.getOrElse { error ->
            JSONObject().apply {
                put("status", "ERROR")
                put("raw_audio_saved", false)
                put("error", error.localizedMessage ?: error.javaClass.simpleName)
            }
        }
        destination.writeText(report.toString(2))
        runOnUiThread {
            orientationRecorder.stopMonitoring()
            finish()
        }
    }

    private fun microphoneArray(recorder: AudioRecord): JSONArray = JSONArray().apply {
        runCatching { recorder.activeMicrophones }.getOrDefault(emptyList()).forEach { microphone ->
            put(JSONObject().apply {
                put("id", microphone.id)
                put("address", microphone.address)
                put("group", microphone.group)
                put("index_in_group", microphone.indexInTheGroup)
                put(
                    "channel_mapping",
                    JSONArray().apply {
                        microphone.channelMapping.forEach { mapping ->
                            put(JSONArray().put(mapping.first).put(mapping.second))
                        }
                    },
                )
            })
        }
    }

    private fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[middle - 1] + sorted[middle]) / 2.0
        } else {
            sorted[middle]
        }
    }

    private fun medianAbsoluteDeviation(values: List<Double>): Double? {
        val center = median(values) ?: return null
        return median(values.map { kotlin.math.abs(it - center) })
    }

    companion object {
        private const val PROBE_DURATION_NS = 8_000_000_000L
    }
}
