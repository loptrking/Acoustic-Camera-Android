package com.example.acoustic_camera.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

data class AudioSession(
    val directory: File,
    val durationSeconds: Double,
    val sampleCount: Long,
    val sampleRate: Int,
    val audioSource: String,
    val channelCount: Int,
)

data class CaptureLevel(
    val rms: Float,
    val seconds: Double,
    val channelCount: Int,
    val lagSamples: Float? = null,
    val delayConfidence: Float? = null,
    val differenceRatio: Float? = null,
    val directionDegrees: Float? = null,
    val directionConfidence: Float = 0f,
    val directionQuality: DirectionQuality = DirectionQuality.WAITING,
    val maxPhysicalLagSamples: Float? = null,
)

private data class RecorderChoice(
    val recorder: AudioRecord,
    val channelCount: Int,
    val channelMask: Int,
    val bufferBytes: Int,
)

/** A single foreground capture. Stereo is attempted first and mono is the safe fallback. */
class AudioCapture(private val context: Context) {
    private val running = AtomicBoolean(false)
    @Volatile private var worker: Thread? = null
    @Volatile private var recorder: AudioRecord? = null

    val isRecording: Boolean get() = running.get()

    @SuppressLint("MissingPermission")
    fun start(onLevel: (CaptureLevel) -> Unit, onFinished: (Result<AudioSession>) -> Unit) {
        check(worker == null) { "A recording is already active" }
        val sampleRate = 48_000
        val source = MediaRecorder.AudioSource.CAMCORDER
        val sourceName = "CAMCORDER"
        val choice = createRecorder(sampleRate, source)
        val sessionName = SimpleDateFormat("yyyy-MM-dd_HHmmss_SSS", Locale.US).format(Date())
        val directory = File(context.filesDir, "sessions/$sessionName")
        if (!directory.mkdirs()) {
            choice.recorder.release()
            error("Cannot create session directory")
        }
        recorder = choice.recorder
        running.set(true)
        worker = Thread({
            val outcome = runCatching {
                capture(choice, directory, sampleRate, sourceName, onLevel)
            }
            running.set(false)
            recorder = null
            worker = null
            onFinished(outcome)
        }, "AcousticLens-AudioCapture").also { it.start() }
    }

    fun stop() {
        running.set(false)
        runCatching { recorder?.stop() }
    }

    @SuppressLint("MissingPermission")
    private fun createRecorder(sampleRate: Int, source: Int): RecorderChoice {
        val candidates = listOf(
            AudioFormat.CHANNEL_IN_STEREO to 2,
            AudioFormat.CHANNEL_IN_MONO to 1,
        )
        for ((channelMask, requestedChannels) in candidates) {
            val minimum = AudioRecord.getMinBufferSize(
                sampleRate,
                channelMask,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minimum <= 0) continue
            val bufferBytes = maxOf(minimum * 2, 8192 * requestedChannels)
            val candidate = runCatching {
                AudioRecord.Builder()
                    .setAudioSource(source)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(sampleRate)
                            .setChannelMask(channelMask)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .build(),
                    )
                    .setBufferSizeInBytes(bufferBytes)
                    .build()
            }.getOrNull() ?: continue
            if (candidate.state == AudioRecord.STATE_INITIALIZED &&
                candidate.channelCount == requestedChannels
            ) {
                return RecorderChoice(candidate, requestedChannels, channelMask, bufferBytes)
            }
            candidate.release()
        }
        error("48 kHz PCM input is not supported")
    }

    private fun capture(
        choice: RecorderChoice,
        directory: File,
        sampleRate: Int,
        sourceName: String,
        onLevel: (CaptureLevel) -> Unit,
    ): AudioSession {
        val record = choice.recorder
        val channelCount = choice.channelCount
        val wavFile = File(directory, "audio.wav")
        val timingFile = File(directory, "audio_timestamps.csv")
        val featureFile = File(directory, "acoustic_features.csv")
        val samples = ShortArray(4096 * channelCount)
        val bytes = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        var frameCount = 0L
        var startedNano = 0L
        var latestEstimate: GccPhatEstimate? = null
        val directionTracker = BroadbandDirectionTracker(sampleRate = sampleRate)
        var latestDirection = BroadbandDirectionEstimate(
            maxPhysicalLagSamples = directionTracker.maxPhysicalLagSamples,
        )
        var activeMicrophones = JSONArray()
        try {
            RandomAccessFile(wavFile, "rw").use { wav ->
                wav.setLength(0)
                writeWavHeader(wav, sampleRate, channelCount, 0)
                timingFile.bufferedWriter().use { timing ->
                    featureFile.bufferedWriter().use { features ->
                    timing.write(
                        "first_frame,frame_count,read_end_monotonic_ns," +
                            "audio_frame_position,audio_monotonic_ns\n",
                    )
                    features.write(
                        "center_frame,read_end_monotonic_ns,gcc_lag_samples," +
                            "gcc_peak_ratio,channel_difference_ratio," +
                            "broadband_direction_deg,broadband_direction_confidence," +
                            "broadband_direction_status,max_physical_lag_samples," +
                            "phase_550_frequency_hz,phase_550_rad,cross_550,balance_550," +
                            "phase_700_frequency_hz,phase_700_rad,cross_700,balance_700," +
                            "phase_850_frequency_hz,phase_850_rad,cross_850,balance_850," +
                            "phase_1000_frequency_hz,phase_1000_rad,cross_1000,balance_1000," +
                            "phase_1150_frequency_hz,phase_1150_rad,cross_1150,balance_1150\n",
                    )
                    record.startRecording()
                    check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        "Microphone did not start"
                    }
                    startedNano = System.nanoTime()
                    activeMicrophones = JSONArray().apply {
                        runCatching { record.activeMicrophones }.getOrDefault(emptyList()).forEach { microphone ->
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
                    val timestamp = AudioTimestamp()
                    while (running.get()) {
                        val read = record.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING)
                        if (read < 0) {
                            if (!running.get()) break
                            error("Audio read failed: $read")
                        }
                        val completeSamples = read - read % channelCount
                        if (completeSamples == 0) continue
                        bytes.clear()
                        var sumSquares = 0.0
                        for (index in 0 until completeSamples) {
                            bytes.putShort(samples[index])
                            val normalized = samples[index] / 32768.0
                            sumSquares += normalized * normalized
                        }
                        wav.write(bytes.array(), 0, completeSamples * 2)
                        if (channelCount == 2) {
                            latestEstimate = GccPhatEstimator.estimate(
                                interleaved = samples,
                                sampleCount = completeSamples,
                                sampleRate = sampleRate,
                            )
                        }
                        val levelRms = sqrt(sumSquares / completeSamples).toFloat()
                        latestDirection = directionTracker.update(
                            lagSamples = latestEstimate?.lagSamples,
                            peakRatio = latestEstimate?.peakRatio,
                            differenceRatio = latestEstimate?.differenceRatio,
                            rms = levelRms,
                        )
                        val framesRead = completeSamples / channelCount
                        val readEnd = System.nanoTime()
                        val validTime = record.getTimestamp(
                            timestamp,
                            AudioTimestamp.TIMEBASE_MONOTONIC,
                        ) == AudioRecord.SUCCESS
                        timing.write("$frameCount,$framesRead,$readEnd,")
                        if (validTime) {
                            timing.write("${timestamp.framePosition},${timestamp.nanoTime}")
                        } else {
                            timing.write(",")
                        }
                        timing.newLine()
                        features.write(
                            buildString {
                                append(frameCount + framesRead / 2)
                                append(',')
                                append(readEnd)
                                append(',')
                                append(latestEstimate?.lagSamples ?: "")
                                append(',')
                                append(latestEstimate?.peakRatio ?: "")
                                append(',')
                                append(latestEstimate?.differenceRatio ?: "")
                                append(',')
                                append(latestDirection.angleDegrees ?: "")
                                append(',')
                                append(latestDirection.confidence)
                                append(',')
                                append(latestDirection.quality.name)
                                append(',')
                                append(latestDirection.maxPhysicalLagSamples)
                                latestEstimate?.bandPhases?.forEach { band ->
                                    append(',')
                                    append(band.frequencyHz)
                                    append(',')
                                    append(band.phaseRadians)
                                    append(',')
                                    append(band.crossMagnitude)
                                    append(',')
                                    append(band.channelMagnitudeRatio)
                                }
                                append('\n')
                            },
                        )
                        frameCount += framesRead
                        onLevel(
                            CaptureLevel(
                                rms = levelRms,
                                seconds = frameCount.toDouble() / sampleRate,
                                channelCount = channelCount,
                                lagSamples = latestEstimate?.lagSamples,
                                delayConfidence = latestEstimate?.peakRatio,
                                differenceRatio = latestEstimate?.differenceRatio,
                                directionDegrees = latestDirection.angleDegrees,
                                directionConfidence = latestDirection.confidence,
                                directionQuality = latestDirection.quality,
                                maxPhysicalLagSamples = latestDirection.maxPhysicalLagSamples,
                            ),
                        )
                    }
                    }
                }
                writeWavHeader(
                    wav,
                    sampleRate,
                    channelCount,
                    frameCount * channelCount * 2,
                )
            }
        } finally {
            runCatching { record.stop() }
            record.release()
        }
        val metadata = JSONObject().apply {
            put("format_version", 3)
            put("device_model", Build.MANUFACTURER + " " + Build.MODEL)
            put("android_version", Build.VERSION.RELEASE)
            put("sample_rate", sampleRate)
            put("channel_count", channelCount)
            put("channel_mask", choice.channelMask)
            put("audio_encoding", "PCM_16BIT_LE")
            put("audio_source", sourceName)
            put("active_microphones", activeMicrophones)
            put("microphone_baseline_m", 0.142567)
            put("direction_model", "broadband_gcc_phat_front_hemisphere")
            put("direction_sign", "positive_screen_right = negative_gcc_lag")
            put("max_physical_lag_samples", directionTracker.maxPhysicalLagSamples)
            put("audio_timestamp_timebase", "MONOTONIC / System.nanoTime")
            put("session_start_monotonic_ns", startedNano)
            put("audio_frame_count", frameCount)
            put("gcc_phat_lag_samples_last", latestEstimate?.lagSamples ?: JSONObject.NULL)
            put("gcc_phat_peak_ratio_last", latestEstimate?.peakRatio ?: JSONObject.NULL)
            put("stereo_difference_ratio_last", latestEstimate?.differenceRatio ?: JSONObject.NULL)
            put("phase_feature_frequencies_hz", "550,700,850,1000,1150 requested")
            put("agc_enabled", JSONObject.NULL)
            put("noise_suppression_enabled", JSONObject.NULL)
            put("echo_cancellation_enabled", JSONObject.NULL)
            put(
                "notes",
                if (channelCount == 2) {
                    "CAMCORDER stereo capture; active physical microphones are recorded above."
                } else {
                    "Stereo input unavailable; fell back to mono."
                },
            )
        }
        File(directory, "metadata.json").writeText(metadata.toString(2))
        return AudioSession(
            directory = directory,
            durationSeconds = frameCount.toDouble() / sampleRate,
            sampleCount = frameCount,
            sampleRate = sampleRate,
            audioSource = sourceName,
            channelCount = channelCount,
        )
    }

    private fun writeWavHeader(
        file: RandomAccessFile,
        sampleRate: Int,
        channelCount: Int,
        dataBytes: Long,
    ) {
        require(dataBytes <= Int.MAX_VALUE - 36) { "WAV recording exceeds 2 GB" }
        val blockAlign = channelCount * 2
        file.seek(0)
        file.writeBytes("RIFF")
        file.writeInt(Integer.reverseBytes((36 + dataBytes).toInt()))
        file.writeBytes("WAVEfmt ")
        file.writeInt(Integer.reverseBytes(16))
        file.writeShort(java.lang.Short.reverseBytes(1.toShort()).toInt())
        file.writeShort(java.lang.Short.reverseBytes(channelCount.toShort()).toInt())
        file.writeInt(Integer.reverseBytes(sampleRate))
        file.writeInt(Integer.reverseBytes(sampleRate * blockAlign))
        file.writeShort(java.lang.Short.reverseBytes(blockAlign.toShort()).toInt())
        file.writeShort(java.lang.Short.reverseBytes(16.toShort()).toInt())
        file.writeBytes("data")
        file.writeInt(Integer.reverseBytes(dataBytes.toInt()))
    }
}
