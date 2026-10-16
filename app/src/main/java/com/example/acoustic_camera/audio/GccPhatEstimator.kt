package com.example.acoustic_camera.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class BandPhaseEstimate(
    val frequencyHz: Float,
    val phaseRadians: Float,
    val crossMagnitude: Double,
    val channelMagnitudeRatio: Double,
)

data class GccPhatEstimate(
    val lagSamples: Float,
    val peakRatio: Float,
    val differenceRatio: Float,
    val bandPhases: List<BandPhaseEstimate>,
)

/** Broadband GCC-PHAT delay estimate for interleaved stereo PCM. */
object GccPhatEstimator {
    fun estimate(
        interleaved: ShortArray,
        sampleCount: Int,
        sampleRate: Int,
        maxLag: Int = 24,
        lowFrequencyHz: Int = 500,
        highFrequencyHz: Int = 8_000,
        phaseFrequenciesHz: IntArray = intArrayOf(550, 700, 850, 1_000, 1_150),
    ): GccPhatEstimate? {
        val frameCount = sampleCount / 2
        if (frameCount < 256) return null
        val fftSize = nextPowerOfTwo(frameCount)
        val leftReal = DoubleArray(fftSize)
        val leftImag = DoubleArray(fftSize)
        val rightReal = DoubleArray(fftSize)
        val rightImag = DoubleArray(fftSize)

        var leftMean = 0.0
        var rightMean = 0.0
        for (frame in 0 until frameCount) {
            leftMean += interleaved[frame * 2]
            rightMean += interleaved[frame * 2 + 1]
        }
        leftMean /= frameCount
        rightMean /= frameCount

        var signalEnergy = 0.0
        var differenceEnergy = 0.0
        for (frame in 0 until frameCount) {
            val left = interleaved[frame * 2] - leftMean
            val right = interleaved[frame * 2 + 1] - rightMean
            val window = 0.5 - 0.5 * cos(2.0 * PI * frame / (frameCount - 1).coerceAtLeast(1))
            leftReal[frame] = left * window
            rightReal[frame] = right * window
            signalEnergy += left * left + right * right
            val difference = left - right
            differenceEnergy += difference * difference
        }
        if (signalEnergy < 1.0) return null
        val differenceRatio = sqrt(2.0 * differenceEnergy / signalEnergy).toFloat()

        fft(leftReal, leftImag, inverse = false)
        fft(rightReal, rightImag, inverse = false)
        val bandPhases = phaseFrequenciesHz.map { targetFrequency ->
            val bin = (targetFrequency.toDouble() * fftSize / sampleRate)
                .roundToInt()
                .coerceIn(1, fftSize / 2)
            val real = rightReal[bin] * leftReal[bin] + rightImag[bin] * leftImag[bin]
            val imaginary = rightImag[bin] * leftReal[bin] - rightReal[bin] * leftImag[bin]
            val leftMagnitude = hypot(leftReal[bin], leftImag[bin])
            val rightMagnitude = hypot(rightReal[bin], rightImag[bin])
            BandPhaseEstimate(
                frequencyHz = (bin.toDouble() * sampleRate / fftSize).toFloat(),
                phaseRadians = kotlin.math.atan2(imaginary, real).toFloat(),
                crossMagnitude = hypot(real, imaginary),
                channelMagnitudeRatio = minOf(leftMagnitude, rightMagnitude) /
                    maxOf(leftMagnitude, rightMagnitude).coerceAtLeast(1e-12),
            )
        }
        val crossReal = DoubleArray(fftSize)
        val crossImag = DoubleArray(fftSize)
        for (bin in 0 until fftSize) {
            val foldedBin = minOf(bin, fftSize - bin)
            val frequency = foldedBin.toDouble() * sampleRate / fftSize
            if (frequency < lowFrequencyHz || frequency > highFrequencyHz) continue
            val real = leftReal[bin] * rightReal[bin] + leftImag[bin] * rightImag[bin]
            // R * conj(L): positive lag means the right channel arrives later.
            val imaginary = rightImag[bin] * leftReal[bin] - rightReal[bin] * leftImag[bin]
            val magnitude = hypot(real, imaginary)
            if (magnitude > 1e-12) {
                crossReal[bin] = real / magnitude
                crossImag[bin] = imaginary / magnitude
            }
        }
        fft(crossReal, crossImag, inverse = true)

        val values = FloatArray(maxLag * 2 + 1)
        var peakOffset = 0
        var peakValue = -1f
        for (lag in -maxLag..maxLag) {
            val index = if (lag >= 0) lag else fftSize + lag
            val value = abs(crossReal[index]).toFloat()
            values[lag + maxLag] = value
            if (value > peakValue) {
                peakValue = value
                peakOffset = lag + maxLag
            }
        }
        if (peakValue <= 0f) return null
        val integerLag = peakOffset - maxLag
        var fractionalOffset = 0f
        if (peakOffset in 1 until values.lastIndex) {
            val before = values[peakOffset - 1]
            val center = values[peakOffset]
            val after = values[peakOffset + 1]
            val denominator = before - 2f * center + after
            if (abs(denominator) > 1e-9f) {
                fractionalOffset = (0.5f * (before - after) / denominator).coerceIn(-0.5f, 0.5f)
            }
        }
        var secondPeak = 0f
        values.forEachIndexed { index, value ->
            if (abs(index - peakOffset) > 2) secondPeak = maxOf(secondPeak, value)
        }
        val peakRatio = peakValue / secondPeak.coerceAtLeast(1e-9f)
        return GccPhatEstimate(
            lagSamples = integerLag + fractionalOffset,
            peakRatio = peakRatio,
            differenceRatio = differenceRatio,
            bandPhases = bandPhases,
        )
    }

    private fun nextPowerOfTwo(value: Int): Int {
        var result = 1
        while (result < value) result = result shl 1
        return result
    }

    private fun fft(real: DoubleArray, imaginary: DoubleArray, inverse: Boolean) {
        val size = real.size
        var reversed = 0
        for (index in 1 until size) {
            var bit = size shr 1
            while (reversed and bit != 0) {
                reversed = reversed xor bit
                bit = bit shr 1
            }
            reversed = reversed xor bit
            if (index < reversed) {
                val realTemp = real[index]
                real[index] = real[reversed]
                real[reversed] = realTemp
                val imaginaryTemp = imaginary[index]
                imaginary[index] = imaginary[reversed]
                imaginary[reversed] = imaginaryTemp
            }
        }

        var length = 2
        while (length <= size) {
            val angle = 2.0 * PI / length * if (inverse) 1.0 else -1.0
            val stepReal = cos(angle)
            val stepImaginary = kotlin.math.sin(angle)
            var start = 0
            while (start < size) {
                var twiddleReal = 1.0
                var twiddleImaginary = 0.0
                for (offset in 0 until length / 2) {
                    val even = start + offset
                    val odd = even + length / 2
                    val oddReal = real[odd] * twiddleReal - imaginary[odd] * twiddleImaginary
                    val oddImaginary = real[odd] * twiddleImaginary + imaginary[odd] * twiddleReal
                    real[odd] = real[even] - oddReal
                    imaginary[odd] = imaginary[even] - oddImaginary
                    real[even] += oddReal
                    imaginary[even] += oddImaginary
                    val nextReal = twiddleReal * stepReal - twiddleImaginary * stepImaginary
                    twiddleImaginary = twiddleReal * stepImaginary + twiddleImaginary * stepReal
                    twiddleReal = nextReal
                }
                start += length
            }
            length = length shl 1
        }
        if (inverse) {
            for (index in 0 until size) {
                real[index] /= size
                imaginary[index] /= size
            }
        }
    }
}
