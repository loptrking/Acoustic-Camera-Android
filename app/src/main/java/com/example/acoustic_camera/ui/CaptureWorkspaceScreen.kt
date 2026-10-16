package com.example.acoustic_camera.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.acoustic_camera.audio.AudioSession
import kotlin.math.roundToInt

private val WorkspaceInk = Color(0xFF06131A)
private val WorkspacePanel = Color(0xFF10232D)
private val WorkspaceAqua = Color(0xFF74E4CF)
private val WorkspaceWarm = Color(0xFFFFB982)
private val WorkspaceMuted = Color(0xFF96ABB4)

data class CaptureWorkspaceState(
    val recording: Boolean = false,
    val stopping: Boolean = false,
    val seconds: Double = 0.0,
    val rms: Float = 0f,
    val message: String = "准备开始双通道与姿态可行性采集",
    val lastSession: AudioSession? = null,
    val motionStatus: String = "等待相机权限",
    val motionTracking: Boolean = false,
    val yawDegrees: Float = 0f,
    val cameraHeadingDegrees: Float? = null,
    val relativeHeadingDegrees: Float = 0f,
    val pitchDegrees: Float = 0f,
    val rollDegrees: Float = 0f,
    val scanSpanDegrees: Float = 0f,
    val orientationRows: Int = 0,
    val trajectory: List<Pair<Float, Float>> = emptyList(),
    val channelCount: Int = 0,
    val lagSamples: Float? = null,
    val delayConfidence: Float? = null,
    val channelDifferenceRatio: Float? = null,
    val directionDegrees: Float? = null,
    val directionConfidence: Float = 0f,
    val directionStatus: String = "WAITING",
    val maxPhysicalLagSamples: Float? = null,
    val calibrationActive: Boolean = false,
    val calibrationStage: Int = 0,
    val calibrationStageCount: Int = 7,
    val calibrationInstruction: String = "准备开始设备初始化",
    val calibrationTargetDegrees: Float = 0f,
    val calibrationRelativeDegrees: Float = 0f,
    val calibrationHoldProgress: Float = 0f,
    val calibrationPlaneDeviationDegrees: Float = 0f,
    val calibrationPostureReady: Boolean = true,
    val calibrationProfileStatus: String? = null,
    val calibrationFrequencyHz: Float? = null,
    val calibrationBaselineCm: Float? = null,
    val calibrationAxisOffsetDeg: Float? = null,
    val calibrationResidualDeg: Float? = null,
    val screenLandscape: Boolean = false,
    val chargingPortOnRight: Boolean = false,
    val orientationReady: Boolean = false,
)

@Composable
fun CaptureWorkspaceScreen(
    state: CaptureWorkspaceState,
    preview: @Composable () -> Unit,
    onRecord: () -> Unit,
    onStop: () -> Unit,
    onExport: () -> Unit,
) {
    if (state.screenLandscape) {
        LandscapeWorkspace(state, preview, onRecord, onStop, onExport)
    } else {
        PortraitWorkspace(state, preview, onRecord, onStop, onExport)
    }
}

@Composable
private fun PortraitWorkspace(
    state: CaptureWorkspaceState,
    preview: @Composable () -> Unit,
    onRecord: () -> Unit,
    onStop: () -> Unit,
    onExport: () -> Unit,
) {
    Scaffold(
        containerColor = WorkspaceInk,
        bottomBar = {
            ActionPanel(state, onRecord, onStop, onExport)
        },
    ) { innerPadding ->
        Column(
            Modifier.fillMaxSize()
                .padding(innerPadding)
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF0B222D), WorkspaceInk, WorkspaceInk)
                    )
                )
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(20.dp))
            Header()
            Spacer(Modifier.height(14.dp))
            OrientationRequirement(state)
            Spacer(Modifier.height(22.dp))
            CameraWorkspace(state, preview, Modifier.height(360.dp))
            Spacer(Modifier.height(14.dp))
            GuidedCalibrationPanel(state)
            Spacer(Modifier.height(14.dp))
            CaptureMetrics(state)
            Spacer(Modifier.height(22.dp))
            AudioPanel(state)
            Spacer(Modifier.height(16.dp))
            StatusPanel(state)
            Spacer(Modifier.height(18.dp))
            Text(
                "旋转扫描仅使用手机相机、麦克风与惯性传感器，不依赖 ARCore。",
                color = Color(0xFF5F7782),
                fontSize = 10.sp,
                lineHeight = 15.sp,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun LandscapeWorkspace(
    state: CaptureWorkspaceState,
    preview: @Composable () -> Unit,
    onRecord: () -> Unit,
    onStop: () -> Unit,
    onExport: () -> Unit,
) {
    Row(
        Modifier.fillMaxSize()
            .background(Brush.horizontalGradient(listOf(Color(0xFF0B222D), WorkspaceInk)))
            .padding(horizontal = 18.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1.18f).fillMaxSize()) {
            Header()
            Spacer(Modifier.height(10.dp))
            OrientationRequirement(state)
            Spacer(Modifier.height(10.dp))
            CameraWorkspace(state, preview, Modifier.weight(1f))
        }
        Column(
            Modifier.weight(0.82f).fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            GuidedCalibrationPanel(state)
            CaptureMetrics(state)
            AudioPanel(state)
            StatusPanel(state)
            ActionPanel(state, onRecord, onStop, onExport, embedded = true)
            Text(
                "固定横屏 · 充电口朝右 · 不依赖 ARCore",
                color = Color(0xFF5F7782),
                fontSize = 10.sp,
            )
            Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
private fun ActionPanel(
    state: CaptureWorkspaceState,
    onRecord: () -> Unit,
    onStop: () -> Unit,
    onExport: () -> Unit,
    embedded: Boolean = false,
) {
    Surface(
        color = if (embedded) WorkspacePanel else WorkspaceInk,
        shadowElevation = if (embedded) 0.dp else 14.dp,
        shape = if (embedded) RoundedCornerShape(18.dp) else RoundedCornerShape(0.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = if (embedded) 12.dp else 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = if (state.recording) onStop else onRecord,
                enabled = !state.stopping && (state.recording || state.orientationReady),
                modifier = Modifier.fillMaxWidth().height(if (embedded) 48.dp else 58.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (state.recording) WorkspaceWarm else WorkspaceAqua,
                    contentColor = WorkspaceInk,
                ),
            ) {
                Text(
                    when {
                        state.stopping -> "正在封装会话…"
                        state.recording -> "手动停止并保存"
                        !state.screenLandscape -> "请横屏使用"
                        !state.chargingPortOnRight -> "请翻转手机 · 充电口朝右"
                        else -> "开始宽带声源定位"
                    },
                    fontSize = if (embedded) 14.sp else 17.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            AnimatedVisibility(!state.recording && state.lastSession != null) {
                OutlinedButton(
                    onClick = onExport,
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text("导出上一次会话 · ZIP", color = WorkspaceAqua)
                }
            }
        }
    }
}

@Composable
private fun OrientationRequirement(state: CaptureWorkspaceState) {
    Surface(
        color = if (state.orientationReady) WorkspaceAqua.copy(alpha = 0.13f)
        else WorkspaceWarm.copy(alpha = 0.16f),
        shape = RoundedCornerShape(15.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (state.orientationReady) "✓" else "↻", color = if (state.orientationReady) WorkspaceAqua else WorkspaceWarm)
            Spacer(Modifier.width(9.dp))
            Text(
                when {
                    state.orientationReady -> "方向正确 · 横屏且充电口位于右手边"
                    !state.screenLandscape -> "竖屏不可采集 · 开启自动旋转后将充电口转到右手边"
                    else -> "横屏方向相反 · 请翻转 180°，让充电口位于右手边"
                },
                color = Color.White,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun StatusPanel(state: CaptureWorkspaceState) {
    Surface(shape = RoundedCornerShape(18.dp), color = WorkspacePanel) {
        Column(Modifier.fillMaxWidth().padding(15.dp)) {
            Text(
                state.message,
                color = if (state.recording) WorkspaceAqua else Color.White,
                fontSize = 13.sp,
            )
            state.lastSession?.takeIf { !state.recording }?.let {
                Spacer(Modifier.height(6.dp))
                Text(it.directory.name + " · " + it.audioSource, color = WorkspaceMuted, fontSize = 10.sp)
            }
            state.calibrationProfileStatus?.takeIf { !state.recording }?.let { status ->
                Spacer(Modifier.height(7.dp))
                Text(
                    when (status) {
                        "PASS" -> "设备档案：已通过双次认证"
                        "ACTIVE" -> "设备档案：550.8 Hz已认证，本次质量不足"
                        "CANDIDATE" -> "设备档案：候选已保存，需再次复核"
                        "PROVISIONAL" -> "设备档案：暂定参数，待复核"
                        else -> "设备档案：尚未形成可靠参数"
                    },
                    color = if (status == "PASS") WorkspaceAqua else WorkspaceWarm,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

@Composable
private fun Header() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(36.dp).background(WorkspaceAqua, RoundedCornerShape(11.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text("◉", color = WorkspaceInk, fontSize = 23.sp)
        }
        Spacer(Modifier.width(11.dp))
        Column {
            Text("AcousticLens", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("双通道音频 × IMU 姿态扫描", color = WorkspaceMuted, fontSize = 10.sp)
        }
        Spacer(Modifier.weight(1f))
        Text("LIVE", color = WorkspaceAqua, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun CameraWorkspace(
    state: CaptureWorkspaceState,
    preview: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier.fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(Color(0xFF0C1B22))
    ) {
        preview()
        Canvas(Modifier.fillMaxSize()) {
            drawLine(
                color = Color.White.copy(alpha = 0.45f),
                start = Offset(size.width / 2 - 18.dp.toPx(), size.height / 2),
                end = Offset(size.width / 2 + 18.dp.toPx(), size.height / 2),
                strokeWidth = 1.dp.toPx(),
            )
            drawLine(
                color = Color.White.copy(alpha = 0.45f),
                start = Offset(size.width / 2, size.height / 2 - 18.dp.toPx()),
                end = Offset(size.width / 2, size.height / 2 + 18.dp.toPx()),
                strokeWidth = 1.dp.toPx(),
            )
            drawCircle(
                color = WorkspaceAqua.copy(alpha = if (state.motionTracking) 0.8f else 0.3f),
                radius = 4.dp.toPx(),
                center = center,
                style = Stroke(1.5.dp.toPx()),
            )
            if (state.recording && state.directionDegrees != null) {
                val angle = state.directionDegrees.coerceIn(-75f, 75f)
                val directionX = size.width / 2f + angle / 75f * size.width * 0.46f
                val confidence = state.directionConfidence.coerceIn(0f, 1f)
                val bandWidth = (92.dp.toPx() * (1.2f - confidence * 0.55f))
                drawRect(
                    brush = Brush.horizontalGradient(
                        0f to Color.Transparent,
                        0.5f to WorkspaceWarm.copy(alpha = 0.12f + confidence * 0.28f),
                        1f to Color.Transparent,
                        startX = directionX - bandWidth,
                        endX = directionX + bandWidth,
                    ),
                    topLeft = Offset(directionX - bandWidth, 0f),
                    size = Size(bandWidth * 2f, size.height),
                )
                drawLine(
                    color = WorkspaceWarm.copy(alpha = 0.5f + confidence * 0.5f),
                    start = Offset(directionX, size.height * 0.16f),
                    end = Offset(directionX, size.height * 0.84f),
                    strokeWidth = (1.5f + confidence * 2f).dp.toPx(),
                    cap = StrokeCap.Round,
                )
                drawCircle(
                    color = WorkspaceWarm,
                    radius = 8.dp.toPx(),
                    center = Offset(directionX, size.height / 2f),
                    style = Stroke((1.5f + confidence).dp.toPx()),
                )
            }
            if (state.calibrationActive) {
                val railStart = 28.dp.toPx()
                val railEnd = size.width - 28.dp.toPx()
                val railY = size.height - 68.dp.toPx()
                fun angleX(angle: Float): Float =
                    railStart + ((angle.coerceIn(-50f, 50f) + 50f) / 100f) * (railEnd - railStart)
                drawLine(
                    Color.White.copy(alpha = 0.35f),
                    Offset(railStart, railY),
                    Offset(railEnd, railY),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round,
                )
                listOf(-40f, 0f, 40f).forEach { angle ->
                    drawLine(
                        Color.White.copy(alpha = 0.45f),
                        Offset(angleX(angle), railY - 5.dp.toPx()),
                        Offset(angleX(angle), railY + 5.dp.toPx()),
                        strokeWidth = 1.dp.toPx(),
                    )
                }
                drawCircle(
                    WorkspaceWarm,
                    7.dp.toPx(),
                    Offset(angleX(state.calibrationTargetDegrees), railY),
                    style = Stroke(2.dp.toPx()),
                )
                drawCircle(
                    WorkspaceAqua,
                    5.dp.toPx(),
                    Offset(angleX(state.calibrationRelativeDegrees), railY),
                )
            }
        }
        Surface(
            modifier = Modifier.align(Alignment.TopStart).padding(14.dp),
            color = WorkspaceInk.copy(alpha = 0.76f),
            shape = RoundedCornerShape(12.dp),
        ) {
            Row(
                Modifier.padding(horizontal = 11.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Canvas(Modifier.size(7.dp)) {
                    drawCircle(if (state.motionTracking) WorkspaceAqua else WorkspaceWarm)
                }
                Spacer(Modifier.width(7.dp))
                Text(state.motionStatus, color = Color.White, fontSize = 11.sp)
            }
        }
        if (state.recording) {
            Surface(
                modifier = Modifier.align(Alignment.TopEnd).padding(14.dp),
                color = WorkspaceInk.copy(alpha = 0.82f),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(
                    if (state.directionDegrees != null) {
                        "%+.1f° · %.0f%%".format(
                            state.directionDegrees,
                            state.directionConfidence * 100f,
                        )
                    } else {
                        "等待方向"
                    },
                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp),
                    color = if (state.directionStatus == "TRACKING") WorkspaceAqua else WorkspaceWarm,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        if (state.trajectory.size > 1) {
            TrajectoryOverlay(
                trajectory = state.trajectory,
                modifier = Modifier.align(Alignment.BottomEnd).padding(14.dp).size(112.dp, 72.dp),
            )
        }
        Surface(
            modifier = Modifier.align(Alignment.BottomStart).padding(14.dp),
            color = WorkspaceInk.copy(alpha = 0.78f),
            shape = RoundedCornerShape(14.dp),
        ) {
            Text(
                if (state.calibrationActive) {
                    "相对 %+.1f°  → 目标 %+.0f°".format(
                        state.calibrationRelativeDegrees,
                        state.calibrationTargetDegrees,
                    )
                } else {
                    "相对转角 %+.1f°  俯仰 %+.1f°  横滚 %+.1f°".format(
                        state.relativeHeadingDegrees,
                        state.pitchDegrees,
                        state.rollDegrees,
                    )
                },
                modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp),
                color = WorkspaceAqua,
                fontSize = 10.sp,
            )
        }
    }
}

@Composable
private fun GuidedCalibrationPanel(state: CaptureWorkspaceState) {
    val holdProgress = animateFloatAsState(
        state.calibrationHoldProgress.coerceIn(0f, 1f),
        tween(120),
        label = "calibration hold progress",
    ).value
    Surface(shape = RoundedCornerShape(20.dp), color = WorkspacePanel) {
        Column(Modifier.fillMaxWidth().padding(17.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (state.calibrationActive) {
                        "初始化 ${state.calibrationStage}/${state.calibrationStageCount}"
                    } else if (state.recording) {
                        "宽带方向定位"
                    } else {
                        "实时声源方向"
                    },
                    color = WorkspaceAqua,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    if (state.calibrationActive) {
                        "%+.1f° / %+.0f°".format(
                            state.calibrationRelativeDegrees,
                            state.calibrationTargetDegrees,
                        )
                    } else if (state.recording && state.directionDegrees != null) {
                        "%+.1f°".format(state.directionDegrees)
                    } else {
                        when (state.calibrationProfileStatus) {
                            "PASS" -> "双麦就绪"
                            else -> "等待开始"
                        }
                    },
                    color = if (state.calibrationActive) WorkspaceWarm else WorkspaceMuted,
                    fontSize = 11.sp,
                )
            }
            Spacer(Modifier.height(8.dp))
            if (state.calibrationActive) {
                Text(
                    "旋转平面偏差 %.1f° · %s".format(
                        state.calibrationPlaneDeviationDegrees,
                        if (state.calibrationPostureReady) "稳定" else "请调整",
                    ),
                    color = if (state.calibrationPostureReady) WorkspaceAqua else WorkspaceWarm,
                    fontSize = 10.sp,
                )
                Spacer(Modifier.height(5.dp))
            }
            Text(
                if (state.calibrationActive) state.calibrationInstruction
                else if (state.recording) directionStatusText(state.directionStatus)
                else "直接面对未知持续声源开始定位；无需先把声源放在准星中央。",
                color = Color.White,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            )
            AnimatedVisibility(state.calibrationActive && holdProgress > 0f) {
                Column {
                    Spacer(Modifier.height(11.dp))
                    Canvas(Modifier.fillMaxWidth().height(6.dp)) {
                        drawRoundRect(Color(0xFF29434D), cornerRadius = CornerRadius(size.height / 2))
                        drawRoundRect(
                            WorkspaceWarm,
                            size = Size(size.width * holdProgress, size.height),
                            cornerRadius = CornerRadius(size.height / 2),
                        )
                    }
                }
            }
            if (!state.calibrationActive) {
                Spacer(Modifier.height(8.dp))
                Text(
                    if (state.recording) {
                        "置信度 %.0f%% · 物理时延上限 %.2f samples".format(
                            state.directionConfidence * 100f,
                            state.maxPhysicalLagSamples ?: 19.95f,
                        )
                    } else {
                        "CAMCORDER 真双麦 · 物理基线 14.26 cm · 48 kHz"
                    },
                    color = WorkspaceMuted,
                    fontSize = 10.sp,
                )
            }
        }
    }
}

@Composable
private fun TrajectoryOverlay(
    trajectory: List<Pair<Float, Float>>,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier.background(WorkspaceInk.copy(alpha = 0.72f), RoundedCornerShape(13.dp)).padding(10.dp)) {
        val xs = trajectory.map { it.first }
        val zs = trajectory.map { it.second }
        val minX = xs.minOrNull() ?: 0f
        val maxX = xs.maxOrNull() ?: 0f
        val minZ = zs.minOrNull() ?: 0f
        val maxZ = zs.maxOrNull() ?: 0f
        val rangeX = (maxX - minX).coerceAtLeast(0.03f)
        val rangeZ = (maxZ - minZ).coerceAtLeast(0.03f)
        val path = Path()
        trajectory.forEachIndexed { index, point ->
            val px = (point.first - minX) / rangeX * size.width
            val py = size.height - (point.second - minZ) / rangeZ * size.height
            if (index == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        drawPath(path, WorkspaceAqua, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
    }
}

@Composable
private fun CaptureMetrics(state: CaptureWorkspaceState) {
    val progress = animateFloatAsState(
        (state.scanSpanDegrees / 60f).coerceIn(0f, 1f),
        tween(240),
        label = "aperture progress",
    ).value
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        MetricCard(
            "角覆盖",
            state.scanSpanDegrees.roundToInt().toString() + "°",
            Modifier.weight(1f),
        )
        MetricCard("姿态", state.orientationRows.toString(), Modifier.weight(1f))
        MetricCard("时间", formatWorkspaceTime(state.seconds), Modifier.weight(1f))
    }
    Spacer(Modifier.height(10.dp))
    Surface(shape = RoundedCornerShape(12.dp), color = WorkspacePanel) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row {
                Text("扫描覆盖", color = WorkspaceMuted, fontSize = 10.sp)
                Spacer(Modifier.weight(1f))
                Text(
                    (progress * 100).roundToInt().toString() + "% / 60°",
                    color = WorkspaceAqua,
                    fontSize = 10.sp,
                )
            }
            Spacer(Modifier.height(8.dp))
            Canvas(Modifier.fillMaxWidth().height(5.dp)) {
                drawRoundRect(Color(0xFF29434D), cornerRadius = CornerRadius(size.height / 2))
                drawRoundRect(
                    WorkspaceAqua,
                    size = Size(size.width * progress, size.height),
                    cornerRadius = CornerRadius(size.height / 2),
                )
            }
        }
    }
}

@Composable
private fun MetricCard(label: String, value: String, modifier: Modifier) {
    Surface(modifier, shape = RoundedCornerShape(15.dp), color = WorkspacePanel) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 11.dp)) {
            Text(label, color = WorkspaceMuted, fontSize = 9.sp)
            Spacer(Modifier.height(3.dp))
            Text(value, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun AudioPanel(state: CaptureWorkspaceState) {
    val level = animateFloatAsState(
        if (state.recording) (state.rms * 18f).coerceIn(0.02f, 1f) else 0f,
        tween(160),
        label = "audio level",
    ).value
    val pulse = rememberInfiniteTransition(label = "record pulse").animateFloat(
        0.35f,
        1f,
        infiniteRepeatable(tween(800), RepeatMode.Reverse),
        label = "record pulse alpha",
    ).value
    Surface(shape = RoundedCornerShape(22.dp), color = WorkspacePanel) {
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(8.dp)) {
                    drawCircle(
                        if (state.recording) WorkspaceWarm.copy(alpha = pulse)
                        else WorkspaceMuted
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    if (state.recording) "麦克风正在采集" else "麦克风就绪",
                    color = if (state.recording) WorkspaceWarm else WorkspaceMuted,
                    fontSize = 11.sp,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    if (state.channelCount > 0) {
                        "48 kHz · ${state.channelCount} ch"
                    } else {
                        "48 kHz · 检测通道中"
                    },
                    color = WorkspaceAqua,
                    fontSize = 10.sp,
                )
            }
            Spacer(Modifier.height(13.dp))
            Canvas(Modifier.fillMaxWidth().height(34.dp)) {
                val count = 35
                val gap = size.width / count
                val barWidth = gap * 0.4f
                repeat(count) { index ->
                    val centerWeight = 1f - kotlin.math.abs(index * 2f / (count - 1) - 1f)
                    val barHeight = size.height * (0.1f + level * (0.35f + centerWeight * 0.55f))
                    drawRoundRect(
                        if (state.recording) WorkspaceAqua else Color(0xFF47636E),
                        topLeft = Offset(index * gap, (size.height - barHeight) / 2),
                        size = Size(barWidth, barHeight),
                        cornerRadius = CornerRadius(barWidth / 2),
                    )
                }
            }
            if (state.recording && state.channelCount == 2) {
                Spacer(Modifier.height(10.dp))
                Text(
                    if (state.directionDegrees != null) {
                        "方向 %+.1f° · 置信度 %.0f%% · %s".format(
                            state.directionDegrees,
                            state.directionConfidence * 100f,
                            directionStatusText(state.directionStatus),
                        )
                    } else {
                        directionStatusText(state.directionStatus)
                    },
                    color = if (state.directionStatus == "TRACKING") WorkspaceAqua else WorkspaceWarm,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "GCC 时延 " + String.format("%+.2f", state.lagSamples ?: 0f) +
                        " samples · 峰值比 " +
                        String.format("%.2f", state.delayConfidence ?: 0f) +
                        " · 差异 " +
                        String.format("%.3f", state.channelDifferenceRatio ?: 0f),
                    color = WorkspaceMuted,
                    fontSize = 10.sp,
                )
            } else if (state.recording && state.channelCount == 1) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "当前设备仅返回单声道；本次用于确认硬件限制",
                    color = WorkspaceWarm,
                    fontSize = 10.sp,
                )
            }
        }
    }
}

private fun formatWorkspaceTime(seconds: Double): String {
    val total = seconds.roundToInt()
    return "%02d:%02d".format(total / 60, total % 60)
}

private fun directionStatusText(status: String): String = when (status) {
    "TRACKING" -> "方向已锁定"
    "ACQUIRING" -> "正在累积稳定窗口"
    "WEAK_SIGNAL" -> "声音过弱"
    "LOW_COHERENCE" -> "声场反射较强或相关峰不清晰"
    "CHANNELS_TOO_SIMILAR" -> "双声道差异不足"
    "OUT_OF_PHYSICAL_RANGE" -> "时延超出物理范围"
    "UNSTABLE" -> "方向变化过快"
    else -> "等待可定位的宽带声音"
}
