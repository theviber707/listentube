// SPDX-FileCopyrightText: 2026 The Viber <theviber707@proton.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.listentube.service.playback

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.sin

class AudioSpectrumAnalyzerTest {

    private val sampleRate = 48_000

    private fun feedSine(frequencyHz: Double, seconds: Double) {
        AudioSpectrumAnalyzer.flush(sampleRate, 2, C.ENCODING_PCM_16BIT)
        val frames = (sampleRate * seconds).toInt()
        val buffer = ByteBuffer.allocateDirect(frames * 2 * 2).order(ByteOrder.nativeOrder())
        for (n in 0 until frames) {
            val sample = (sin(2.0 * PI * frequencyHz * n / sampleRate) * 0.5 * Short.MAX_VALUE).toInt().toShort()
            buffer.putShort(sample)
            buffer.putShort(sample)
        }
        buffer.flip()
        // Mirror TeeAudioProcessor, which passes a big-endian read-only view.
        AudioSpectrumAnalyzer.handleBuffer(buffer.asReadOnlyBuffer())
    }

    private fun expectedBand(frequencyHz: Double): Int =
        (AudioSpectrumAnalyzer.BAND_COUNT * ln(frequencyHz / 40.0) / ln(16_000.0 / 40.0)).toInt()

    private fun readAfterLatency(): FloatArray? {
        val out = FloatArray(AudioSpectrumAnalyzer.BAND_COUNT)
        val readAt = System.nanoTime() + 300_000_000L
        return if (AudioSpectrumAnalyzer.readBands(readAt, out)) out else null
    }

    @Test
    fun sineWave_peaksInMatchingBand() {
        for (frequency in listOf(100.0, 1_000.0, 8_000.0)) {
            feedSine(frequency, seconds = 0.5)
            val bands = readAfterLatency()!!
            val peakBand = bands.indices.maxBy { bands[it] }
            assertEquals("peak band for $frequency Hz", expectedBand(frequency).toDouble(), peakBand.toDouble(), 1.0)
            // Adaptive gain carries over between tones, so only require a strong reading.
            assertTrue("level for $frequency Hz: ${bands.toList()}", bands[peakBand] > 0.7f)
        }
    }

    @Test
    fun framesAreHeldBackUntilAudible() {
        feedSine(440.0, seconds = 0.2)
        val out = FloatArray(AudioSpectrumAnalyzer.BAND_COUNT)
        assertFalse(AudioSpectrumAnalyzer.readBands(System.nanoTime(), out))
        assertTrue(readAfterLatency() != null)
    }

    @Test
    fun pausedPlayback_reportsNoAudio() {
        feedSine(440.0, seconds = 0.2)
        AudioSpectrumAnalyzer.onPlaybackActiveChanged(false)
        assertTrue(readAfterLatency() == null)
        AudioSpectrumAnalyzer.onPlaybackActiveChanged(true)
        val bands = readAfterLatency()!!
        assertEquals(expectedBand(440.0), bands.indices.maxBy { bands[it] })
    }
}
