package com.example.acoustic_camera.ui

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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.acoustic_camera.audio.AudioSession
import kotlin.math.roundToInt

private val Ink = Color(0xFF07131B)
private val Panel = Color(0xFF10232D)
private val PanelLight = Color(0xFF17313D)
private val Aqua = Color(0xFF74E4CF)
private val Warm = Color(0xFFFFBB85)
private val Muted = Color(0xFF94ABB5)

data class RecorderUiState(
    val recording: Boolean = false,
    val stopping: Boolean = false,
    val seconds: Double = 0.0,
    val rms: Float = 0f,
    val message: String = "准备开始一次新的采集",
    val lastSession: AudioSession? = null,
)

@Composable
fun AcousticLensScreen(
    state: RecorderUiState,
    onRecord: () -> Unit,
    onStop: () -> Unit,
    onExport: () -> Unit,
) {
    Scaffold(
        containerColor = Ink,
        bottomBar = {
            Surface(color = Ink, shadowElevation = 12.dp) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Button(
                        onClick = if (state.recording) onStop else onRecord,
                        enabled = !state.stopping,
                        modifier = Modifier.fillMaxWidth().height(58.dp),
                        shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (state.recording) Warm else Aqua,
                            contentColor = Ink,
                        ),
                    ) {
                        Text(
                            text = when {
                                state.stopping -> "正在保存…"
                                state.recording -> "停止并保存录音"
                                else -> "开始录音"
                            },
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    if (!state.recording && state.lastSession != null) {
                        OutlinedButton(
                            onClick = onExport,
                            modifier = Modifier.fillMaxWidth().height(50.dp),
                            shape = RoundedCornerShape(16.dp),
                        ) {
                            Text("导出上一次采集 · ZIP", color = Aqua)
                        }
                    }
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding)
                .background(Brush.verticalGradient(listOf(Color(0xFF0B202B), Ink, Ink)))
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
        ) {
            Spacer(Modifier.height(24.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(36.dp).background(Aqua, RoundedCornerShape(11.dp)),
                    contentAlignment = Alignment.Center,
                ) { Text("◉", color = Ink, fontSize = 23.sp) }
                Spacer(Modifier.width(12.dp))
                Text("AcousticLens", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text("M1  /  采集", color = Aqua, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(38.dp))
            Text("听见位置，\n看见声音。", color = Color.White, fontSize = 38.sp, lineHeight = 45.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(14.dp))
            Text(
                "从可靠的原始录音开始。每一次采集都保留声音与时间信息，为后续空间成像打好基础。",
                color = Muted, fontSize = 14.sp, lineHeight = 22.sp,
            )
            Spacer(Modifier.height(30.dp))
            CapturePanel(state)
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                InfoPill("48 kHz", Modifier.weight(1f))
                InfoPill("单声道", Modifier.weight(1f))
                InfoPill("PCM · WAV", Modifier.weight(1f))
            }
            Spacer(Modifier.height(30.dp))
            Text("采集状态", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            Surface(shape = RoundedCornerShape(20.dp), color = Panel) {
                Column(Modifier.fillMaxWidth().padding(18.dp)) {
                    Text(
                        text = state.message,
                        color = if (state.recording) Aqua else Color.White,
                        fontSize = 14.sp,
                    )
                    if (state.lastSession != null && !state.recording) {
                        Spacer(Modifier.height(9.dp))
                        Text(
                            state.lastSession.directory.name + " · " +
                                state.lastSession.sampleRate / 1000 + " kHz · " +
                                state.lastSession.audioSource,
                            color = Muted, fontSize = 12.sp,
                        )
                    }
                }
            }
            Spacer(Modifier.height(22.dp))
            Text("即将加入", color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            Text("相机画面  ·  扫描轨迹  ·  声源热图", color = Color(0xFF66818D), fontSize = 13.sp)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun CapturePanel(state: RecorderUiState) {
    val animatedLevel = animateFloatAsState(
        targetValue = if (state.recording) (state.rms * 18f).coerceIn(0.02f, 1f) else 0f,
        animationSpec = tween(180),
        label = "microphone level",
    ).value
    val pulse = rememberInfiniteTransition(label = "recording pulse").animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "pulse alpha",
    ).value
    Surface(shape = RoundedCornerShape(28.dp), color = PanelLight) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(10.dp)) {
                    drawCircle(if (state.recording) Warm.copy(alpha = pulse) else Muted)
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    if (state.recording) "正在采集声音" else "等待录音",
                    color = if (state.recording) Warm else Muted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(26.dp))
            Text(
                formatTime(state.seconds),
                color = Color.White,
                fontSize = 48.sp,
                fontWeight = FontWeight.Light,
                letterSpacing = 2.sp,
            )
            Spacer(Modifier.height(25.dp))
            Canvas(Modifier.fillMaxWidth().height(70.dp)) {
                val count = 29
                val gap = size.width / count
                val width = gap * 0.42f
                for (index in 0 until count) {
                    val position = index.toFloat() / (count - 1)
                    val taper = 0.35f + 0.65f * (1f - kotlin.math.abs(position * 2f - 1f))
                    val height = size.height * (0.08f + animatedLevel * taper * 0.82f)
                    val color = if (state.recording && animatedLevel > 0.08f) Aqua else Color(0xFF466774)
                    drawRoundRect(
                        color = color,
                        topLeft = Offset(index * gap, (size.height - height) / 2),
                        size = Size(width, height),
                        cornerRadius = CornerRadius(width / 2),
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
            Text(
                if (state.recording) "实时电平来自麦克风数据" else "录音后可导出原始 WAV 与时间戳",
                color = Muted, fontSize = 12.sp, textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun InfoPill(label: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(13.dp), color = Panel) {
        Text(
            label,
            modifier = Modifier.padding(vertical = 11.dp),
            color = Aqua,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
        )
    }
}

private fun formatTime(seconds: Double): String {
    val whole = seconds.roundToInt()
    return "%02d:%02d".format(whole / 60, whole % 60)
}
