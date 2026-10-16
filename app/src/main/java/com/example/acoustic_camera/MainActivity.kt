package com.example.acoustic_camera

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.acoustic_camera.audio.AudioCapture
import com.example.acoustic_camera.audio.AudioSession
import com.example.acoustic_camera.audio.CaptureLevel
import com.example.acoustic_camera.camera.CameraPreviewView
import com.example.acoustic_camera.calibration.CalibrationGuideController
import com.example.acoustic_camera.motion.OrientationFrame
import com.example.acoustic_camera.motion.OrientationRecorder
import com.example.acoustic_camera.motion.OrientationSummary
import com.example.acoustic_camera.motion.CaptureOrientationPolicy
import com.example.acoustic_camera.ui.CaptureWorkspaceScreen
import com.example.acoustic_camera.ui.CaptureWorkspaceState
import com.example.acoustic_camera.ui.theme.Acoustic_CameraTheme
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MainActivity : ComponentActivity() {
    private lateinit var capture: AudioCapture
    private lateinit var orientationRecorder: OrientationRecorder
    private var cameraView: CameraPreviewView? = null
    private var uiState by mutableStateOf(CaptureWorkspaceState())
    private val calibrationGuide = CalibrationGuideController()
    private var autoStopIssued = false

    private val audioPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCapture()
            else uiState = uiState.copy(message = "需要麦克风权限才能采集音频")
        }

    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) cameraView?.resumeCamera()
            else uiState = uiState.copy(motionStatus = "未授予相机权限")
        }

    private val export =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            val session = uiState.lastSession ?: return@registerForActivityResult
            if (uri == null) return@registerForActivityResult
            uiState = uiState.copy(message = "正在导出音频、时间戳与姿态数据…")
            Thread {
                val result = runCatching {
                    contentResolver.openOutputStream(uri)?.use { stream ->
                        ZipOutputStream(stream).use { zip ->
                            listOf(
                                "audio.wav",
                                "audio_timestamps.csv",
                                "acoustic_features.csv",
                                "orientation.csv",
                                "metadata.json",
                                "calibration_result.json",
                            ).forEach { name ->
                                val file = File(session.directory, name)
                                if (!file.exists()) return@forEach
                                zip.putNextEntry(ZipEntry(name))
                                file.inputStream().use { it.copyTo(zip) }
                                zip.closeEntry()
                            }
                        }
                    } ?: error("无法打开导出位置")
                }
                runOnUiThread {
                    uiState = uiState.copy(
                        message = if (result.isSuccess) {
                            "导出完成 · ZIP 包含音频、姿态、声学特征与初始化结果"
                        } else {
                            "导出失败：" +
                                (result.exceptionOrNull()?.localizedMessage ?: "未知错误")
                        },
                    )
                }
            }.start()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        capture = AudioCapture(this)
        orientationRecorder = OrientationRecorder(this, ::handleOrientation)
        orientationRecorder.startMonitoring()
        uiState = uiState.copy(
            lastSession = loadLastSession(),
            calibrationProfileStatus = loadCalibrationProfileStatus(),
            screenLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE,
            motionTracking = orientationRecorder.isAvailable,
            motionStatus = if (orientationRecorder.isAvailable) {
                "姿态传感器就绪 · 等待相机"
            } else {
                "缺少旋转矢量传感器"
            },
        )
        enableEdgeToEdge()
        setContent {
            Acoustic_CameraTheme {
                CaptureWorkspaceScreen(
                    state = uiState,
                    preview = {
                        AndroidView(
                            factory = { context ->
                                CameraPreviewView(
                                    context = context,
                                    lifecycleOwner = this,
                                    onStatus = ::handleCameraStatus,
                                ).also { view ->
                                    cameraView = view
                                    if (hasCameraPermission()) view.post { view.resumeCamera() }
                                }
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    },
                    onRecord = ::requestRecording,
                    onStop = ::stopCapture,
                    onExport = {
                        uiState.lastSession?.let {
                            export.launch("AcousticLens_IMU_" + it.directory.name + ".zip")
                        }
                    },
                )
            }
        }
        if (!hasCameraPermission()) cameraPermission.launch(Manifest.permission.CAMERA)
    }

    override fun onResume() {
        super.onResume()
        if (hasCameraPermission()) cameraView?.resumeCamera()
    }

    override fun onPause() {
        cameraView?.pauseCamera()
        super.onPause()
    }

    override fun onStop() {
        if (capture.isRecording) stopCapture()
        super.onStop()
    }

    override fun onDestroy() {
        cameraView?.closeCamera()
        orientationRecorder.stopMonitoring()
        super.onDestroy()
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestRecording() {
        if (!uiState.orientationReady) {
            uiState = uiState.copy(
                message = if (uiState.screenLandscape) {
                    "方向不正确 · 请将手机翻转，使充电口位于右手边"
                } else {
                    "竖屏不可初始化 · 请开启自动旋转并横屏，充电口朝右"
                },
            )
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startCapture()
        } else {
            audioPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startCapture() {
        if (uiState.recording || uiState.stopping) return
        orientationRecorder.beginRecording()
        calibrationGuide.cancel()
        autoStopIssued = false
        uiState = uiState.copy(
            recording = true,
            calibrationActive = false,
            calibrationStage = 0,
            calibrationInstruction = "宽带双麦定位中",
            calibrationTargetDegrees = 0f,
            calibrationRelativeDegrees = 0f,
            calibrationHoldProgress = 0f,
            calibrationPlaneDeviationDegrees = 0f,
            calibrationPostureReady = true,
            seconds = 0.0,
            rms = 0f,
            scanSpanDegrees = 0f,
            relativeHeadingDegrees = 0f,
            orientationRows = 0,
            trajectory = emptyList(),
            channelCount = 0,
            lagSamples = null,
            delayConfidence = null,
            channelDifferenceRatio = null,
            directionDegrees = null,
            directionConfidence = 0f,
            directionStatus = "WAITING",
            maxPhysicalLagSamples = null,
            message = "正在获取宽带声源方向 · 请保持充电口朝右",
        )
        runCatching {
            capture.start(
                onLevel = ::handleAudioLevel,
                onFinished = { outcome ->
                    val completed = outcome.mapCatching { session ->
                        val orientation = orientationRecorder.finish(session.directory)
                        updateMetadata(session.directory, orientation)
                        Pair(session, orientation)
                    }
                    if (completed.isFailure) orientationRecorder.discard()
                    runOnUiThread {
                        uiState = completed.fold(
                            onSuccess = { (session, orientation) ->
                                uiState.copy(
                                    recording = false,
                                    stopping = false,
                                    calibrationActive = false,
                                    rms = 0f,
                                    seconds = session.durationSeconds,
                                    lastSession = session,
                                    orientationRows = orientation.rowCount,
                                    scanSpanDegrees = orientation.yawSpanDegrees,
                                    message = if (session.channelCount == 2) {
                                        "宽带定位会话完成 · 可导出结果进行角度误差分析"
                                    } else {
                                        "会话完成 · 设备只返回单声道，无法直接进行双麦定位"
                                    },
                                )
                            },
                            onFailure = { error ->
                                uiState.copy(
                                    recording = false,
                                    stopping = false,
                                    calibrationActive = false,
                                    rms = 0f,
                                    message = "采集失败：" +
                                        (error.localizedMessage ?: "未知错误"),
                                )
                            },
                        )
                    }
                },
            )
        }.onFailure {
            orientationRecorder.discard()
            calibrationGuide.cancel()
            uiState = uiState.copy(
                recording = false,
                stopping = false,
                calibrationActive = false,
                message = "无法启动采集：" + (it.localizedMessage ?: "未知错误"),
            )
        }
    }

    private fun handleAudioLevel(level: CaptureLevel) {
        runOnUiThread {
            if (!uiState.recording) return@runOnUiThread
            uiState = uiState.copy(
                seconds = level.seconds,
                rms = level.rms,
                channelCount = level.channelCount,
                lagSamples = level.lagSamples,
                delayConfidence = level.delayConfidence,
                channelDifferenceRatio = level.differenceRatio,
                directionDegrees = level.directionDegrees,
                directionConfidence = level.directionConfidence,
                directionStatus = level.directionQuality.name,
                maxPhysicalLagSamples = level.maxPhysicalLagSamples,
                message = directionStatusMessage(level.directionQuality.name),
            )
        }
    }

    private fun stopCapture() {
        if (!uiState.recording) return
        if (!autoStopIssued) calibrationGuide.cancel()
        uiState = uiState.copy(
            stopping = true,
            message = "正在保存 WAV、硬件时间戳与 orientation.csv…",
        )
        capture.stop()
    }

    private fun handleCameraStatus(status: String) {
        runOnUiThread {
            uiState = uiState.copy(
                motionStatus = if (orientationRecorder.isAvailable) {
                    status
                } else {
                    "$status · 无旋转矢量"
                },
            )
        }
    }

    private fun handleOrientation(frame: OrientationFrame) {
        runOnUiThread {
            val recording = uiState.recording
            val screenLandscape =
                resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            val chargingPortOnRight = CaptureOrientationPolicy.isChargingPortOnRight(frame.rollDegrees)
            val orientationReady = CaptureOrientationPolicy.isReady(screenLandscape, frame.rollDegrees)
            val guide = if (recording && uiState.calibrationActive) {
                calibrationGuide.update(
                    frame.relativeHeadingDegrees,
                    frame.angularSpeedRadS,
                    frame.callbackMonotonicNs,
                    frame.pitchDegrees,
                    frame.rollDegrees,
                )
            } else {
                null
            }
            uiState = uiState.copy(
                motionTracking = true,
                yawDegrees = frame.yawDegrees,
                cameraHeadingDegrees = frame.cameraHeadingDegrees,
                relativeHeadingDegrees = frame.relativeHeadingDegrees,
                pitchDegrees = frame.pitchDegrees,
                rollDegrees = frame.rollDegrees,
                scanSpanDegrees = if (recording) frame.yawSpanDegrees else uiState.scanSpanDegrees,
                orientationRows = if (recording) uiState.orientationRows + 1 else uiState.orientationRows,
                trajectory = if (recording) {
                    (uiState.trajectory + Pair(frame.yawDegrees, frame.pitchDegrees)).takeLast(360)
                } else {
                    uiState.trajectory
                },
                calibrationStage = guide?.stage ?: uiState.calibrationStage,
                calibrationStageCount = guide?.stageCount ?: uiState.calibrationStageCount,
                calibrationInstruction = guide?.instruction ?: uiState.calibrationInstruction,
                calibrationTargetDegrees = guide?.targetDegrees ?: uiState.calibrationTargetDegrees,
                calibrationRelativeDegrees = guide?.relativeDegrees ?: uiState.calibrationRelativeDegrees,
                calibrationHoldProgress = guide?.holdProgress ?: uiState.calibrationHoldProgress,
                calibrationPlaneDeviationDegrees =
                    guide?.planeDeviationDegrees ?: uiState.calibrationPlaneDeviationDegrees,
                calibrationPostureReady = guide?.postureReady ?: uiState.calibrationPostureReady,
                message = guide?.instruction ?: uiState.message,
                screenLandscape = screenLandscape,
                chargingPortOnRight = chargingPortOnRight,
                orientationReady = orientationReady,
            )
            if (recording && !orientationReady && !uiState.stopping) {
                calibrationGuide.cancel()
                uiState = uiState.copy(
                    stopping = true,
                    message = "设备方向发生变化，本次会话已停止 · 请保持充电口朝右",
                )
                capture.stop()
                return@runOnUiThread
            }
            if (guide?.complete == true && !autoStopIssued) {
                autoStopIssued = true
                stopCapture()
            }
        }
    }

    private fun directionStatusMessage(status: String): String = when (status) {
        "TRACKING" -> "宽带方向已锁定"
        "ACQUIRING" -> "正在累积稳定声学窗口"
        "WEAK_SIGNAL" -> "声音过弱 · 请靠近持续声源"
        "LOW_COHERENCE" -> "声场反射较强 · 当前方向置信度不足"
        "CHANNELS_TOO_SIMILAR" -> "双声道差异过小 · 无法定位"
        "OUT_OF_PHYSICAL_RANGE" -> "时延超出物理基线 · 已拒绝本帧"
        "UNSTABLE" -> "方向快速波动 · 请保持声源或手机稳定"
        else -> "等待宽带声音"
    }

    private fun updateMetadata(directory: File, orientation: OrientationSummary) {
        val file = File(directory, "metadata.json")
        val metadata = JSONObject(file.readText())
        metadata.put("orientation_format_version", 2)
        metadata.put("orientation_row_count", orientation.rowCount)
        metadata.put("orientation_yaw_span_deg", orientation.yawSpanDegrees.toDouble())
        metadata.put("orientation_sensor", orientation.sensorName)
        metadata.put(
            "orientation_timestamp_timebase",
            "SensorEvent.timestamp may use BOOTTIME; callback_monotonic_ns uses System.nanoTime / MONOTONIC",
        )
        metadata.put(
            "orientation_audio_alignment",
            "Use callback_monotonic_ns against AudioTimestamp TIMEBASE_MONOTONIC",
        )
        metadata.put("capture_mode", "guided_front_source_device_initialization")
        metadata.put("required_device_orientation", "landscape_charging_port_right")
        metadata.put("calibration_mode", "per_frequency_cross_session")
        metadata.put("primary_calibration_frequency_hz", 550.78125)
        metadata.put(
            "calibration_assumption",
            "Known reference source is centered only for first-use device initialization; normal localization does not assume a centered source",
        )
        file.writeText(metadata.toString(2))
    }

    private fun loadLastSession(): AudioSession? {
        val directory = File(filesDir, "sessions").listFiles()?.filter { it.isDirectory }
            ?.maxByOrNull { it.name } ?: return null
        return runCatching {
            val metadata = JSONObject(File(directory, "metadata.json").readText())
            val count = metadata.getLong("audio_frame_count")
            val rate = metadata.getInt("sample_rate")
            AudioSession(
                directory = directory,
                durationSeconds = count.toDouble() / rate,
                sampleCount = count,
                sampleRate = rate,
                audioSource = metadata.getString("audio_source"),
                channelCount = metadata.optInt("channel_count", 1),
            )
        }.getOrNull()
    }

    private fun loadCalibrationProfileStatus(): String? {
        val directory = File(filesDir, "device_profiles")
        val active = runCatching {
            JSONObject(File(directory, "active_frequency_profiles.json").readText()).takeIf {
                it.optInt("format_version") == 3
            }?.getJSONObject("bands")?.optJSONObject("550")?.getString("status")
        }.getOrNull()
        if (active == "PASS") return active
        return runCatching {
            JSONObject(File(directory, "pending_frequency_profiles.json").readText()).takeIf {
                it.optInt("format_version") == 3
            }?.getJSONObject("bands")?.optJSONObject("550")?.getString("status")
        }.getOrNull()
    }
}
