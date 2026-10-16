package com.example.acoustic_camera.calibration

import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

data class DeviceCalibrationResult(
    val status: String,
    val message: String,
    val frequencyHz: Double? = null,
    val effectiveBaselineCm: Double? = null,
    val cameraToMicrophoneAxisOffsetDeg: Double? = null,
    val medianResidualDeg: Double? = null,
    val contributingBandCount: Int = 0,
)

/** Fits a persistent per-device direction profile from one guided initialization session. */
object DeviceCalibrationSolver {
    private const val SPEED_OF_SOUND_M_S = 343.0
    private val requestedBands = listOf(550, 700, 850, 1000, 1150)

    private data class TimedHeading(val timeNs: Double, val radians: Double)
    private data class FeatureRow(
        val centerFrame: Double,
        val values: Map<String, String>,
    )
    private data class BandFit(
        val frequencyHz: Double,
        val score: Double,
        val residualRmsDeg: Double,
        val baselineCm: Double,
        val directionDeg: Double,
        val phaseOffsetRad: Double,
        val sampleCount: Int,
        val channelBalance: Double,
    )
    private data class PhaseSample(
        val index: Int,
        val phase: Double,
        val crossMagnitude: Double,
        val channelBalance: Double,
    )
    private data class FrequencyCertificationSummary(
        val status: String,
        val message: String,
        val primaryProfile: JSONObject?,
        val frequencyResults: JSONArray,
        val eligibleFrequencyCount: Int,
        val primaryQualityStatus: String?,
    )

    fun solve(sessionDirectory: File, profileDirectory: File): DeviceCalibrationResult {
        val result = runCatching { solveInternal(sessionDirectory, profileDirectory) }
        return result.getOrElse { error ->
            val report = JSONObject().apply {
                put("status", "NOT_READY")
                put("error", error.localizedMessage ?: error.javaClass.simpleName)
            }
            File(sessionDirectory, "calibration_result.json").writeText(report.toString(2))
            DeviceCalibrationResult(
                status = "NOT_READY",
                message = "初始化数据不足：${error.localizedMessage ?: "无法拟合设备参数"}",
            )
        }
    }

    private fun solveInternal(
        sessionDirectory: File,
        profileDirectory: File,
    ): DeviceCalibrationResult {
        val features = readCsv(File(sessionDirectory, "acoustic_features.csv"))
            .mapNotNull { row ->
                row["center_frame"]?.toDoubleOrNull()?.let { FeatureRow(it, row) }
            }
        val orientations = readCsv(File(sessionDirectory, "orientation.csv"))
        val timestamps = readCsv(File(sessionDirectory, "audio_timestamps.csv"))
        require(features.size >= 8) { "声学特征行数不足" }
        require(orientations.size >= 8) { "姿态数据行数不足" }

        val clock = timestamps.mapNotNull { row ->
            val frame = row["audio_frame_position"]?.toDoubleOrNull()
            val time = row["audio_monotonic_ns"]?.toDoubleOrNull()
            if (frame != null && time != null) frame to time else null
        }
        require(clock.size >= 2) { "缺少有效 AudioTimestamp" }
        val (nsPerFrame, interceptNs) = linearRegression(clock)

        val headingKey = if (orientations.any { !it["camera_heading_deg"].isNullOrBlank() }) {
            "camera_heading_deg"
        } else {
            "yaw_deg"
        }
        val headings = unwrapHeadings(
            orientations.mapNotNull { row ->
                val time = row["callback_monotonic_ns"]?.toDoubleOrNull()
                val degrees = row[headingKey]?.toDoubleOrNull()
                if (time != null && degrees != null) time to degrees else null
            },
        )
        require(headings.size >= 8) { "没有可用的镜头方向数据" }
        val featureTimes = features.map { interceptNs + nsPerFrame * it.centerFrame }
        val overlapIndices = featureTimes.indices.filter {
            featureTimes[it] in headings.first().timeNs..headings.last().timeNs
        }
        require(overlapIndices.size >= 8) { "音频与姿态时间轴没有充分重叠" }
        val alignedHeadings = DoubleArray(features.size) { index ->
            interpolateHeading(featureTimes[index], headings)
        }
        val initialWindowEnd = headings.first().timeNs + 2_000_000_000.0
        val initialHeadingDeg = wrapDegrees(
            radiansToDegrees(
                median(headings.filter { it.timeNs <= initialWindowEnd }.map { it.radians }),
            ),
        )
        val headingSpanDeg = radiansToDegrees(
            headings.maxOf { it.radians } - headings.minOf { it.radians },
        )

        val bandFits = requestedBands.mapNotNull { requested ->
            fitRequestedBand(requested, features, overlapIndices, alignedHeadings)
        }
        val certification = certifyFrequencies(
            bandFits = bandFits,
            initialHeadingDeg = initialHeadingDeg,
            headingSpanDeg = headingSpanDeg,
            sessionName = sessionDirectory.name,
            profileDirectory = profileDirectory,
        )
        val profile = certification.primaryProfile ?: JSONObject().apply {
            put("status", certification.status)
        }

        val report = JSONObject().apply {
            put("format_version", 3)
            put("status", certification.status)
            put("certification_mode", "per_frequency_cross_session")
            put("primary_frequency_hz", FrequencyCertificationPolicy.PRIMARY_FREQUENCY_HZ)
            put("heading_span_deg", headingSpanDeg)
            put("initial_center_heading_deg", initialHeadingDeg)
            put("eligible_frequency_count", certification.eligibleFrequencyCount)
            put("primary_quality_status", certification.primaryQualityStatus ?: "NO_PRIMARY_DATA")
            put("calibration_profile", profile)
            put("frequency_certification", certification.frequencyResults)
            put(
                "band_results",
                JSONArray().apply {
                    bandFits.forEach { fit ->
                        put(JSONObject().apply {
                            put("frequency_hz", fit.frequencyHz)
                            put("score", fit.score)
                            put("residual_rms_deg", fit.residualRmsDeg)
                            put("effective_baseline_cm", fit.baselineCm)
                            put("fit_direction_deg", fit.directionDeg)
                            put(
                                "camera_to_microphone_axis_offset_deg",
                                wrapDegrees(initialHeadingDeg - fit.directionDeg),
                            )
                            put("phase_offset_rad", fit.phaseOffsetRad)
                            put("sample_count", fit.sampleCount)
                            put("channel_balance", fit.channelBalance)
                        })
                    }
                },
            )
        }
        File(sessionDirectory, "calibration_result.json").writeText(report.toString(2))

        val status = certification.status
        val frequency = profile.optDoubleOrNull("frequency_hz")
        val baseline = profile.optDoubleOrNull("effective_baseline_cm")
        val offset = profile.optDoubleOrNull("camera_to_microphone_axis_offset_deg")
        val residual = profile.optDoubleOrNull("median_phase_residual_deg")
        return DeviceCalibrationResult(
            status = status,
            message = certification.message,
            frequencyHz = frequency,
            effectiveBaselineCm = baseline,
            cameraToMicrophoneAxisOffsetDeg = offset,
            medianResidualDeg = residual,
            contributingBandCount = certification.eligibleFrequencyCount,
        )
    }

    private fun fitRequestedBand(
        requested: Int,
        features: List<FeatureRow>,
        overlapIndices: List<Int>,
        headings: DoubleArray,
    ): BandFit? {
        val frequencyKey = "phase_${requested}_frequency_hz"
        val phaseKey = "phase_${requested}_rad"
        val magnitudeKey = "cross_$requested"
        val balanceKey = "balance_$requested"
        val candidates = overlapIndices.mapNotNull { index ->
            val phase = features[index].values[phaseKey]?.toDoubleOrNull()
            val magnitude = features[index].values[magnitudeKey]?.toDoubleOrNull()
            val balance = features[index].values[balanceKey]?.toDoubleOrNull() ?: 1.0
            if (phase != null && magnitude != null) {
                PhaseSample(index, phase, magnitude, balance)
            } else {
                null
            }
        }
        if (candidates.size < 8) return null
        val threshold = percentile(candidates.map { it.crossMagnitude }, 0.20)
        val selected = candidates.filter { it.crossMagnitude >= threshold }
        if (selected.size < 8) return null
        val phases = selected.map { it.phase }
        val selectedHeadings = selected.map { headings[it.index] }
        val channelBalance = median(selected.map { it.channelBalance })
        val frequency = median(
            features.mapNotNull { it.values[frequencyKey]?.toDoubleOrNull() },
        )
        if (!frequency.isFinite()) return null
        return fitBand(selectedHeadings, phases, frequency).copy(channelBalance = channelBalance)
    }

    private fun fitBand(
        headings: List<Double>,
        phases: List<Double>,
        frequencyHz: Double,
    ): BandFit {
        var best = searchGrid(headings, phases, frequencyHz, 0.0, 0.20, 0.0025, -180.0, 180.0, 3.0)
        best = searchGrid(
            headings,
            phases,
            frequencyHz,
            (best.baselineCm / 100.0 - 0.006).coerceAtLeast(0.0),
            (best.baselineCm / 100.0 + 0.006).coerceAtMost(0.20),
            0.0005,
            best.directionDeg - 6.0,
            best.directionDeg + 6.0,
            0.5,
        )
        return best
    }

    private fun searchGrid(
        headings: List<Double>,
        phases: List<Double>,
        frequencyHz: Double,
        baselineStart: Double,
        baselineEnd: Double,
        baselineStep: Double,
        directionStartDeg: Double,
        directionEndDeg: Double,
        directionStepDeg: Double,
    ): BandFit {
        var bestScore = -1.0
        var bestBaseline = 0.0
        var bestDirection = 0.0
        var bestOffset = 0.0
        var baseline = baselineStart
        while (baseline <= baselineEnd + 1e-9) {
            val amplitude = 2.0 * PI * frequencyHz * baseline / SPEED_OF_SOUND_M_S
            var directionDeg = directionStartDeg
            while (directionDeg <= directionEndDeg + 1e-9) {
                val direction = degreesToRadians(directionDeg)
                var real = 0.0
                var imaginary = 0.0
                phases.indices.forEach { index ->
                    val residual = phases[index] - amplitude * cos(headings[index] - direction)
                    real += cos(residual)
                    imaginary += sin(residual)
                }
                real /= phases.size
                imaginary /= phases.size
                val score = hypot(real, imaginary)
                if (score > bestScore) {
                    bestScore = score
                    bestBaseline = baseline
                    bestDirection = direction
                    bestOffset = atan2(imaginary, real)
                }
                directionDeg += directionStepDeg
            }
            baseline += baselineStep
        }
        val amplitude = 2.0 * PI * frequencyHz * bestBaseline / SPEED_OF_SOUND_M_S
        val squaredResidual = phases.indices.sumOf { index ->
            val raw = phases[index] - bestOffset - amplitude * cos(headings[index] - bestDirection)
            val wrapped = atan2(sin(raw), cos(raw))
            wrapped * wrapped
        }
        return BandFit(
            frequencyHz = frequencyHz,
            score = bestScore,
            residualRmsDeg = radiansToDegrees(sqrt(squaredResidual / phases.size)),
            baselineCm = bestBaseline * 100.0,
            directionDeg = wrapDegrees(radiansToDegrees(bestDirection)),
            phaseOffsetRad = bestOffset,
            sampleCount = phases.size,
            channelBalance = 1.0,
        )
    }

    private fun certifyFrequencies(
        bandFits: List<BandFit>,
        initialHeadingDeg: Double,
        headingSpanDeg: Double,
        sessionName: String,
        profileDirectory: File,
    ): FrequencyCertificationSummary {
        profileDirectory.mkdirs()
        val pendingFile = File(profileDirectory, "pending_frequency_profiles.json")
        val activeFile = File(profileDirectory, "active_frequency_profiles.json")
        val pendingRoot = loadFrequencyRoot(pendingFile)
        val activeRoot = loadFrequencyRoot(activeFile)
        val pendingBands = pendingRoot.getJSONObject("bands")
        val activeBands = activeRoot.getJSONObject("bands")
        val frequencyResults = JSONArray()
        var eligibleCount = 0
        var currentPrimary: JSONObject? = null
        var verifiedPrimary: JSONObject? = null
        var primaryQualityStatus: String? = null

        bandFits.forEach { fit ->
            val candidate = FrequencyCandidate(
                frequencyHz = fit.frequencyHz,
                axisOffsetDeg = wrapDegrees(initialHeadingDeg - fit.directionDeg),
                effectiveBaselineCm = fit.baselineCm,
                residualRmsDeg = fit.residualRmsDeg,
                score = fit.score,
                sessionName = sessionName,
                channelBalance = fit.channelBalance,
            )
            val key = FrequencyCertificationPolicy.key(fit.frequencyHz)
            val eligible = FrequencyCertificationPolicy.isEligible(candidate, headingSpanDeg)
            val qualityStatus = if (eligible) "ELIGIBLE" else rejectionReason(candidate, headingSpanDeg)
            if (isPrimaryFrequency(fit.frequencyHz)) primaryQualityStatus = qualityStatus
            val detail = candidateToJson(candidate).apply {
                put("eligible", eligible)
                put("quality_status", qualityStatus)
            }
            if (eligible) {
                eligibleCount += 1
                val previous = pendingBands.optJSONObject(key)?.let(::candidateFromJson)
                val comparison = previous?.let { FrequencyCertificationPolicy.compare(it, candidate) }
                if (comparison?.consistent == true) {
                    val prior = requireNotNull(previous)
                    val merged = FrequencyCertificationPolicy.merge(prior, candidate)
                    val verified = candidateToJson(merged).apply {
                        put("status", "PASS")
                        put("certification_runs", 2)
                        put("verified_against_session", prior.sessionName)
                        put("axis_difference_deg", comparison.axisDifferenceDeg)
                        put("baseline_relative_difference", comparison.baselineRelativeDifference)
                    }
                    pendingBands.put(key, verified)
                    activeBands.put(key, verified)
                    detail.put("status", "PASS")
                    detail.put("axis_difference_deg", comparison.axisDifferenceDeg)
                    detail.put("baseline_relative_difference", comparison.baselineRelativeDifference)
                    if (isPrimaryFrequency(fit.frequencyHz)) verifiedPrimary = verified
                } else {
                    val pending = candidateToJson(candidate).apply {
                        put("status", "CANDIDATE")
                        put("certification_runs", 1)
                        if (comparison != null) {
                            put("previous_candidate_axis_difference_deg", comparison.axisDifferenceDeg)
                            put("previous_candidate_baseline_relative_difference", comparison.baselineRelativeDifference)
                        }
                    }
                    pendingBands.put(key, pending)
                    detail.put("status", "CANDIDATE")
                    if (comparison != null) {
                        detail.put("previous_candidate_axis_difference_deg", comparison.axisDifferenceDeg)
                        detail.put("previous_candidate_baseline_relative_difference", comparison.baselineRelativeDifference)
                    }
                    if (isPrimaryFrequency(fit.frequencyHz)) currentPrimary = pending
                }
            } else {
                detail.put("status", "NOT_READY")
            }
            frequencyResults.put(detail)
        }

        if (eligibleCount > 0) pendingFile.writeText(pendingRoot.toString(2))
        if (verifiedPrimary != null || activeBands.length() > 0) {
            activeFile.writeText(activeRoot.toString(2))
        }
        val existingActivePrimary =
            activeBands.optJSONObject(FrequencyCertificationPolicy.key(FrequencyCertificationPolicy.PRIMARY_FREQUENCY_HZ))
        val status = when {
            verifiedPrimary != null -> "PASS"
            currentPrimary != null -> "CANDIDATE"
            existingActivePrimary != null -> "ACTIVE"
            else -> "NOT_READY"
        }
        val primaryProfile = verifiedPrimary ?: currentPrimary ?: existingActivePrimary
        val message = when (status) {
            "PASS" -> "550.8 Hz双次认证通过 · 频率档案已激活"
            "CANDIDATE" -> "550.8 Hz候选已保存 · 请换一个方向再次初始化"
            "ACTIVE" -> "本次${qualityMessage(primaryQualityStatus)} · 已认证档案仍然有效"
            else -> "550.8 Hz${qualityMessage(primaryQualityStatus)} · 按单频近场流程重测"
        }
        return FrequencyCertificationSummary(
            status = status,
            message = message,
            primaryProfile = primaryProfile,
            frequencyResults = frequencyResults,
            eligibleFrequencyCount = eligibleCount,
            primaryQualityStatus = primaryQualityStatus,
        )
    }

    private fun loadFrequencyRoot(file: File): JSONObject = runCatching {
        JSONObject(file.readText()).takeIf { it.optInt("format_version") == 3 }
    }.getOrNull() ?: JSONObject().apply {
        put("format_version", 3)
        put("device_model", Build.MANUFACTURER + " " + Build.MODEL)
        put("required_device_orientation", "landscape_charging_port_right")
        put("certification_mode", "per_frequency_cross_session")
        put("bands", JSONObject())
    }

    private fun candidateToJson(candidate: FrequencyCandidate): JSONObject = JSONObject().apply {
        put("frequency_hz", candidate.frequencyHz)
        put("camera_to_microphone_axis_offset_deg", candidate.axisOffsetDeg)
        put("effective_baseline_cm", candidate.effectiveBaselineCm)
        put("median_phase_residual_deg", candidate.residualRmsDeg)
        put("score", candidate.score)
        put("channel_balance", candidate.channelBalance)
        put("created_from_session", candidate.sessionName)
    }

    private fun candidateFromJson(source: JSONObject): FrequencyCandidate = FrequencyCandidate(
        frequencyHz = source.getDouble("frequency_hz"),
        axisOffsetDeg = source.getDouble("camera_to_microphone_axis_offset_deg"),
        effectiveBaselineCm = source.getDouble("effective_baseline_cm"),
        residualRmsDeg = source.getDouble("median_phase_residual_deg"),
        score = source.getDouble("score"),
        sessionName = source.getString("created_from_session"),
        channelBalance = source.optDouble("channel_balance", 1.0),
    )

    private fun rejectionReason(candidate: FrequencyCandidate, headingSpanDeg: Double): String = when {
        headingSpanDeg < FrequencyCertificationPolicy.MIN_HEADING_SPAN_DEG -> "INSUFFICIENT_SPAN"
        candidate.channelBalance < FrequencyCertificationPolicy.MIN_CHANNEL_BALANCE -> "CHANNEL_IMBALANCE"
        candidate.score < FrequencyCertificationPolicy.MIN_SCORE -> "LOW_SCORE"
        candidate.residualRmsDeg > FrequencyCertificationPolicy.MAX_RESIDUAL_DEG -> "HIGH_RESIDUAL"
        candidate.effectiveBaselineCm !in FrequencyCertificationPolicy.MIN_BASELINE_CM..
            FrequencyCertificationPolicy.MAX_BASELINE_CM -> "BASELINE_OUT_OF_RANGE"
        else -> "NOT_ELIGIBLE"
    }

    private fun qualityMessage(status: String?): String = when (status) {
        "CHANNEL_IMBALANCE" -> "左右通道能量失衡（疑似反射/遮挡）"
        "LOW_SCORE" -> "相位随角度变化不稳定"
        "HIGH_RESIDUAL" -> "拟合残差过大"
        "BASELINE_OUT_OF_RANGE" -> "有效麦克风间距异常"
        "INSUFFICIENT_SPAN" -> "旋转角度不足"
        "NO_PRIMARY_DATA", null -> "没有取得目标频率数据"
        else -> "未达到质量门槛"
    }

    private fun isPrimaryFrequency(frequencyHz: Double): Boolean =
        abs(frequencyHz - FrequencyCertificationPolicy.PRIMARY_FREQUENCY_HZ) < 1.0

    private fun readCsv(file: File): List<Map<String, String>> {
        require(file.exists()) { "${file.name} 不存在" }
        val lines = file.readLines().filter { it.isNotBlank() }
        require(lines.size >= 2) { "${file.name} 没有数据" }
        val headers = lines.first().split(',')
        return lines.drop(1).map { line ->
            val values = line.split(',', ignoreCase = false, limit = headers.size)
            headers.indices.associate { index -> headers[index] to values.getOrElse(index) { "" } }
        }
    }

    private fun unwrapHeadings(values: List<Pair<Double, Double>>): List<TimedHeading> {
        val sorted = values.sortedBy { it.first }
        var previous: Double? = null
        var unwrapped = 0.0
        return sorted.map { (time, degrees) ->
            val raw = degreesToRadians(degrees)
            val prior = previous
            if (prior == null) {
                unwrapped = raw
            } else {
                var delta = raw - prior
                while (delta > PI) delta -= 2.0 * PI
                while (delta < -PI) delta += 2.0 * PI
                unwrapped += delta
            }
            previous = raw
            TimedHeading(time, unwrapped)
        }
    }

    private fun interpolateHeading(timeNs: Double, values: List<TimedHeading>): Double {
        var low = 0
        var high = values.lastIndex
        while (low + 1 < high) {
            val middle = (low + high) / 2
            if (values[middle].timeNs <= timeNs) low = middle else high = middle
        }
        val before = values[low]
        val after = values[high]
        if (after.timeNs == before.timeNs) return before.radians
        val fraction = ((timeNs - before.timeNs) / (after.timeNs - before.timeNs)).coerceIn(0.0, 1.0)
        return before.radians + fraction * (after.radians - before.radians)
    }

    private fun linearRegression(points: List<Pair<Double, Double>>): Pair<Double, Double> {
        val meanX = points.map { it.first }.average()
        val meanY = points.map { it.second }.average()
        val denominator = points.sumOf { (it.first - meanX) * (it.first - meanX) }
        require(denominator > 0.0) { "AudioTimestamp 帧位置无变化" }
        val slope = points.sumOf { (it.first - meanX) * (it.second - meanY) } / denominator
        return slope to (meanY - slope * meanX)
    }

    private fun circularMeanDegrees(values: List<Double>, weights: List<Double>): Double {
        val real = values.indices.sumOf { cos(degreesToRadians(values[it])) * weights[it] }
        val imaginary = values.indices.sumOf { sin(degreesToRadians(values[it])) * weights[it] }
        return wrapDegrees(radiansToDegrees(atan2(imaginary, real)))
    }

    private fun circularDistance(first: Double, second: Double): Double = abs(wrapDegrees(first - second))

    private fun percentile(values: List<Double>, fraction: Double): Double {
        val sorted = values.sorted()
        if (sorted.isEmpty()) return Double.NaN
        val index = ((sorted.lastIndex) * fraction).toInt().coerceIn(0, sorted.lastIndex)
        return sorted[index]
    }

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        if (sorted.isEmpty()) return Double.NaN
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2.0 else sorted[middle]
    }

    private fun JSONObject.optDoubleOrNull(name: String): Double? =
        if (has(name) && !isNull(name)) optDouble(name).takeIf { it.isFinite() } else null

    private fun degreesToRadians(value: Double): Double = value * PI / 180.0
    private fun radiansToDegrees(value: Double): Double = value * 180.0 / PI
    private fun wrapDegrees(value: Double): Double = ((value + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
}
