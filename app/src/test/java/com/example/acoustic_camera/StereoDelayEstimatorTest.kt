package com.example.acoustic_camera

import com.example.acoustic_camera.audio.GccPhatEstimator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class StereoDelayEstimatorTest {
    @Test
    fun findsKnownRightChannelDelay() {
        val delay = 5
        val frames = 1024
        val source = ShortArray(frames) { Random(42 + it).nextInt(-20_000, 20_001).toShort() }
        val interleaved = ShortArray(frames * 2)
        repeat(frames) { frame ->
            interleaved[frame * 2] = source[frame]
            interleaved[frame * 2 + 1] = if (frame >= delay) source[frame - delay] else 0
        }

        val estimate = requireNotNull(
            GccPhatEstimator.estimate(interleaved, interleaved.size, sampleRate = 48_000),
        )

        assertEquals(delay.toFloat(), estimate.lagSamples, 0.25f)
        assertTrue(estimate.peakRatio > 1.2f)
        assertTrue(estimate.differenceRatio > 0.5f)
    }
}
