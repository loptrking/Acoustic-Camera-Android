package com.example.acoustic_camera.calibration

import kotlin.math.abs

enum class CalibrationPhase {
    IDLE,
    HOLD_CENTER,
    ROTATE_LEFT,
    HOLD_LEFT,
    ROTATE_RIGHT,
    HOLD_RIGHT,
    RETURN_CENTER,
    HOLD_FINAL,
    COMPLETE,
}

data class CalibrationGuide(
    val phase: CalibrationPhase = CalibrationPhase.IDLE,
    val stage: Int = 0,
    val stageCount: Int = 7,
    val instruction: String = "准备开始设备初始化",
    val targetDegrees: Float = 0f,
    val relativeDegrees: Float = 0f,
    val holdProgress: Float = 0f,
    val planeDeviationDegrees: Float = 0f,
    val postureReady: Boolean = true,
    val complete: Boolean = false,
)

class CalibrationGuideController {
    private var phase = CalibrationPhase.IDLE
    private var holdStartedNs: Long? = null
    private var referencePitchDegrees = 0f
    private var referenceRollDegrees = 0f

    fun start(referencePitchDegrees: Float = 0f, referenceRollDegrees: Float = 0f): CalibrationGuide {
        phase = CalibrationPhase.HOLD_CENTER
        holdStartedNs = null
        this.referencePitchDegrees = referencePitchDegrees
        this.referenceRollDegrees = referenceRollDegrees
        return state(0f, 0f)
    }

    fun cancel(): CalibrationGuide {
        phase = CalibrationPhase.IDLE
        holdStartedNs = null
        return state(0f, 0f)
    }

    fun update(
        relativeDegrees: Float,
        angularSpeedRadS: Float,
        timestampNs: Long,
        pitchDegrees: Float = referencePitchDegrees,
        rollDegrees: Float = referenceRollDegrees,
    ): CalibrationGuide {
        val planeDeviation = maxOf(
            abs(pitchDegrees - referencePitchDegrees),
            circularDistanceDegrees(rollDegrees, referenceRollDegrees),
        )
        if (planeDeviation > MAX_PLANE_DEVIATION_DEGREES) {
            holdStartedNs = null
            return state(relativeDegrees, 0f).copy(
                instruction = "旋转平面偏离 %.1f° · 调整俯仰/侧倾后继续".format(planeDeviation),
                planeDeviationDegrees = planeDeviation,
                postureReady = false,
            )
        }
        when (phase) {
            CalibrationPhase.HOLD_CENTER -> {
                if (holdSatisfied(abs(relativeDegrees) <= CENTER_TOLERANCE, angularSpeedRadS, timestampNs, 2.0f)) {
                    enter(CalibrationPhase.ROTATE_LEFT)
                }
            }
            CalibrationPhase.ROTATE_LEFT -> {
                if (relativeDegrees <= -TARGET_ANGLE + TARGET_TOLERANCE) {
                    enter(CalibrationPhase.HOLD_LEFT)
                }
            }
            CalibrationPhase.HOLD_LEFT -> {
                if (holdSatisfied(abs(relativeDegrees + TARGET_ANGLE) <= HOLD_TOLERANCE, angularSpeedRadS, timestampNs, 1.5f)) {
                    enter(CalibrationPhase.ROTATE_RIGHT)
                }
            }
            CalibrationPhase.ROTATE_RIGHT -> {
                if (relativeDegrees >= TARGET_ANGLE - TARGET_TOLERANCE) {
                    enter(CalibrationPhase.HOLD_RIGHT)
                }
            }
            CalibrationPhase.HOLD_RIGHT -> {
                if (holdSatisfied(abs(relativeDegrees - TARGET_ANGLE) <= HOLD_TOLERANCE, angularSpeedRadS, timestampNs, 1.5f)) {
                    enter(CalibrationPhase.RETURN_CENTER)
                }
            }
            CalibrationPhase.RETURN_CENTER -> {
                if (abs(relativeDegrees) <= CENTER_TOLERANCE) {
                    enter(CalibrationPhase.HOLD_FINAL)
                }
            }
            CalibrationPhase.HOLD_FINAL -> {
                if (holdSatisfied(abs(relativeDegrees) <= CENTER_TOLERANCE, angularSpeedRadS, timestampNs, 2.0f)) {
                    enter(CalibrationPhase.COMPLETE)
                }
            }
            else -> Unit
        }
        return state(relativeDegrees, holdProgress(timestampNs)).copy(
            planeDeviationDegrees = planeDeviation,
            postureReady = true,
        )
    }

    private fun holdSatisfied(
        inTarget: Boolean,
        angularSpeedRadS: Float,
        timestampNs: Long,
        requiredSeconds: Float,
    ): Boolean {
        if (!inTarget || angularSpeedRadS > MAX_HOLD_ANGULAR_SPEED_RAD_S) {
            holdStartedNs = null
            return false
        }
        val started = holdStartedNs ?: timestampNs.also { holdStartedNs = it }
        return timestampNs - started >= (requiredSeconds * 1_000_000_000L).toLong()
    }

    private fun holdProgress(timestampNs: Long): Float {
        val requiredSeconds = when (phase) {
            CalibrationPhase.HOLD_CENTER, CalibrationPhase.HOLD_FINAL -> 2.0f
            CalibrationPhase.HOLD_LEFT, CalibrationPhase.HOLD_RIGHT -> 1.5f
            else -> return 0f
        }
        val started = holdStartedNs ?: return 0f
        return ((timestampNs - started) / (requiredSeconds * 1_000_000_000.0)).toFloat()
            .coerceIn(0f, 1f)
    }

    private fun enter(next: CalibrationPhase) {
        phase = next
        holdStartedNs = null
    }

    private fun state(relativeDegrees: Float, holdProgress: Float): CalibrationGuide =
        when (phase) {
            CalibrationPhase.IDLE -> CalibrationGuide(relativeDegrees = relativeDegrees)
            CalibrationPhase.HOLD_CENTER -> CalibrationGuide(
                phase, 1, instruction = "保持声源在准星中央，手机静止", targetDegrees = 0f,
                relativeDegrees = relativeDegrees, holdProgress = holdProgress,
            )
            CalibrationPhase.ROTATE_LEFT -> CalibrationGuide(
                phase, 2, instruction = "缓慢向左旋转到 −40°", targetDegrees = -TARGET_ANGLE,
                relativeDegrees = relativeDegrees,
            )
            CalibrationPhase.HOLD_LEFT -> CalibrationGuide(
                phase, 3, instruction = "到达左侧目标，保持不动", targetDegrees = -TARGET_ANGLE,
                relativeDegrees = relativeDegrees, holdProgress = holdProgress,
            )
            CalibrationPhase.ROTATE_RIGHT -> CalibrationGuide(
                phase, 4, instruction = "缓慢向右旋转到 +40°", targetDegrees = TARGET_ANGLE,
                relativeDegrees = relativeDegrees,
            )
            CalibrationPhase.HOLD_RIGHT -> CalibrationGuide(
                phase, 5, instruction = "到达右侧目标，保持不动", targetDegrees = TARGET_ANGLE,
                relativeDegrees = relativeDegrees, holdProgress = holdProgress,
            )
            CalibrationPhase.RETURN_CENTER -> CalibrationGuide(
                phase, 6, instruction = "缓慢返回中央 0°", targetDegrees = 0f,
                relativeDegrees = relativeDegrees,
            )
            CalibrationPhase.HOLD_FINAL -> CalibrationGuide(
                phase, 7, instruction = "回到中央，保持不动即自动完成", targetDegrees = 0f,
                relativeDegrees = relativeDegrees, holdProgress = holdProgress,
            )
            CalibrationPhase.COMPLETE -> CalibrationGuide(
                phase, 7, instruction = "采集完成，正在计算设备参数", targetDegrees = 0f,
                relativeDegrees = relativeDegrees, holdProgress = 1f, complete = true,
            )
        }

    private fun circularDistanceDegrees(first: Float, second: Float): Float {
        val wrapped = ((first - second + 180f) % 360f + 360f) % 360f - 180f
        return abs(wrapped)
    }

    companion object {
        private const val TARGET_ANGLE = 40f
        private const val TARGET_TOLERANCE = 4f
        private const val HOLD_TOLERANCE = 7f
        private const val CENTER_TOLERANCE = 6f
        private const val MAX_HOLD_ANGULAR_SPEED_RAD_S = 0.30f
        private const val MAX_PLANE_DEVIATION_DEGREES = 3.0f
    }
}
