// SPDX-FileCopyrightText: 2026 The Viber <theviber707@proton.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.listentube.service.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Process-wide real-time spectrum analyzer fed with the exact decoded PCM that [MusicPlaybackService]'s
 * ExoPlayer writes to the `AudioTrack`, via a [TeeAudioProcessor] inserted into the audio sink.
 *
 * Tapping the player's own output means no `RECORD_AUDIO` permission is required (unlike
 * `android.media.audiofx.Visualizer`) and the analysis only ever sees this app's audio.
 *
 * Each FFT frame is stamped with its arrival time; [readBands] releases frames only once the
 * `AudioTrack` buffer latency has elapsed, so the visuals line up with what is actually audible.
 */
@OptIn(UnstableApi::class)
object AudioSpectrumAnalyzer : TeeAudioProcessor.AudioBufferSink {

    /** Number of log-spaced frequency bands produced per frame (low → high frequency). */
    const val BAND_COUNT = 24

    private const val FFT_SIZE = 2048
    private const val HOP_SIZE = FFT_SIZE / 2
    private const val MIN_FREQUENCY_HZ = 40.0
    private const val MAX_FREQUENCY_HZ = 16_000.0

    /** Spectral tilt so treble is not drowned out by bass energy (dB per octave around 1 kHz). */
    private const val TILT_DB_PER_OCTAVE = 3.0

    /** Dynamic window, in dB, displayed below the adaptive reference level. */
    private const val DYNAMIC_RANGE_DB = 42.0
    private const val MIN_REFERENCE_DB = -36.0
    private const val REFERENCE_RELEASE_DB_PER_FRAME = 0.06

    /** Extra mixer/HAL latency on top of the `AudioTrack` buffer, minus half the FFT window. */
    private const val EXTRA_OUTPUT_LATENCY_NANOS = 20_000_000L
    private const val DEFAULT_BUFFER_LATENCY_NANOS = 250_000_000L

    /** Frames older than this relative to the playback clock are considered silence/stall. */
    private const val STALE_FRAME_NANOS = 150_000_000L
    private const val MAX_QUEUED_FRAMES = 160

    private class Frame(var timeNanos: Long, val bands: FloatArray)

    private val lock = Any()

    // --- Playback-thread analysis state -------------------------------------------------------
    private var sampleRateHz = 0
    private var channelCount = 0
    private var encoding = C.ENCODING_INVALID

    private val window = FloatArray(FFT_SIZE) { i ->
        (0.5 - 0.5 * cos(2.0 * PI * i / (FFT_SIZE - 1))).toFloat()
    }
    private val ring = FloatArray(FFT_SIZE)
    private var ringWritePos = 0
    private var samplesSinceLastFrame = 0
    private var ringFilled = 0

    private val re = FloatArray(FFT_SIZE)
    private val im = FloatArray(FFT_SIZE)
    private val bandStartBin = IntArray(BAND_COUNT)
    private val bandEndBin = IntArray(BAND_COUNT)
    private val bandTiltDb = FloatArray(BAND_COUNT)
    private val bandDb = FloatArray(BAND_COUNT)
    private var referenceDb = MIN_REFERENCE_DB

    // --- Shared state (guarded by [lock]) -----------------------------------------------------
    private val queue = ArrayDeque<Frame>()
    private var lastShown: Frame? = null
    private var pausedAtNanos = 0L

    @Volatile
    private var bufferLatencyNanos = DEFAULT_BUFFER_LATENCY_NANOS

    /**
     * Wraps the default `AudioTrack` buffer sizing so the analyzer knows how far ahead of the
     * speaker the tapped PCM is.
     */
    val bufferSizeProvider = DefaultAudioSink.AudioTrackBufferSizeProvider {
            minBufferSizeInBytes, encoding, outputMode, pcmFrameSize, sampleRate, bitrate, maxSpeed ->
        val bytes = DefaultAudioSink.AudioTrackBufferSizeProvider.DEFAULT.getBufferSizeInBytes(
            minBufferSizeInBytes, encoding, outputMode, pcmFrameSize, sampleRate, bitrate, maxSpeed,
        )
        if (pcmFrameSize > 0 && sampleRate > 0) {
            bufferLatencyNanos = bytes.toLong() * 1_000_000_000L / (pcmFrameSize.toLong() * sampleRate)
        }
        bytes
    }

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        if (sampleRateHz != this.sampleRateHz) configureBands(sampleRateHz)
        this.sampleRateHz = sampleRateHz
        this.channelCount = channelCount
        this.encoding = encoding
        ring.fill(0f)
        ringWritePos = 0
        ringFilled = 0
        samplesSinceLastFrame = 0
        synchronized(lock) {
            queue.clear()
            lastShown = null
        }
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        val channels = channelCount
        if (channels <= 0 || sampleRateHz <= 0) return
        // TeeAudioProcessor hands over a read-only view, which resets the byte order to big-endian.
        val data = buffer.duplicate().order(ByteOrder.nativeOrder())
        val bytesPerSample = when (encoding) {
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_FLOAT, C.ENCODING_PCM_32BIT -> 4
            else -> return
        }
        val frameBytes = bytesPerSample * channels
        while (data.remaining() >= frameBytes) {
            var sum = 0f
            repeat(channels) {
                sum += when (encoding) {
                    C.ENCODING_PCM_16BIT -> data.short / 32768f
                    C.ENCODING_PCM_FLOAT -> data.float
                    else -> data.int / 2147483648f
                }
            }
            ring[ringWritePos] = sum / channels
            ringWritePos = (ringWritePos + 1) % FFT_SIZE
            if (ringFilled < FFT_SIZE) ringFilled++
            if (++samplesSinceLastFrame >= HOP_SIZE && ringFilled == FFT_SIZE) {
                samplesSinceLastFrame = 0
                analyzeFrame()
            }
        }
    }

    /** Called from the player's main-thread listener so queued frames survive pause/resume in sync. */
    fun onPlaybackActiveChanged(isPlaying: Boolean) {
        val now = System.nanoTime()
        synchronized(lock) {
            if (!isPlaying) {
                pausedAtNanos = now
            } else if (pausedAtNanos != 0L) {
                // Audio still sitting in the AudioTrack buffer resumes where it left off.
                val shift = now - pausedAtNanos
                queue.forEach { it.timeNanos += shift }
                lastShown?.let { it.timeNanos += shift }
                pausedAtNanos = 0L
            }
        }
    }

    /**
     * Copies the band levels (0..1, low → high frequency) that are audible at [nowNanos]
     * (a [System.nanoTime] timestamp) into [out]. Returns `false` when nothing is playing.
     */
    fun readBands(nowNanos: Long, out: FloatArray): Boolean {
        val playbackClock = nowNanos - bufferLatencyNanos - EXTRA_OUTPUT_LATENCY_NANOS
        synchronized(lock) {
            if (pausedAtNanos != 0L) return false
            while (queue.isNotEmpty() && queue.first().timeNanos <= playbackClock) {
                lastShown = queue.removeFirst()
            }
            val frame = lastShown ?: return false
            if (playbackClock - frame.timeNanos > STALE_FRAME_NANOS) return false
            frame.bands.copyInto(out, endIndex = min(out.size, BAND_COUNT))
            return true
        }
    }

    private fun configureBands(sampleRate: Int) {
        if (sampleRate <= 0) return
        val binHz = sampleRate.toDouble() / FFT_SIZE
        val maxBin = FFT_SIZE / 2 - 1
        val maxFrequency = min(MAX_FREQUENCY_HZ, sampleRate / 2.0 - binHz)
        val ratio = maxFrequency / MIN_FREQUENCY_HZ
        var previousEnd = 1
        for (band in 0 until BAND_COUNT) {
            val lowHz = MIN_FREQUENCY_HZ * ratio.pow(band.toDouble() / BAND_COUNT)
            val highHz = MIN_FREQUENCY_HZ * ratio.pow((band + 1).toDouble() / BAND_COUNT)
            val start = max(previousEnd, (lowHz / binHz).roundToInt()).coerceAtMost(maxBin)
            val end = max(start + 1, (highHz / binHz).roundToInt()).coerceAtMost(maxBin + 1)
            bandStartBin[band] = start
            bandEndBin[band] = end
            previousEnd = end
            val centerHz = sqrt(lowHz * highHz)
            bandTiltDb[band] = (TILT_DB_PER_OCTAVE * ln(centerHz / 1000.0) / ln(2.0)).toFloat()
        }
        referenceDb = MIN_REFERENCE_DB
    }

    private fun analyzeFrame() {
        // Oldest sample first: the ring write position is the oldest entry.
        for (i in 0 until FFT_SIZE) {
            re[i] = ring[(ringWritePos + i) % FFT_SIZE] * window[i]
            im[i] = 0f
        }
        fft(re, im)

        // Hann window coherent gain is 0.5, so a full-scale sine peaks at |X| = N / 4.
        val amplitudeScale = 4f / FFT_SIZE
        var frameMaxDb = Double.NEGATIVE_INFINITY
        for (band in 0 until BAND_COUNT) {
            var peak = 0f
            for (bin in bandStartBin[band] until bandEndBin[band]) {
                val power = re[bin] * re[bin] + im[bin] * im[bin]
                if (power > peak) peak = power
            }
            val amplitude = sqrt(peak) * amplitudeScale
            val db = if (amplitude > 1e-7f) 20.0 * log10(amplitude.toDouble()) + bandTiltDb[band] else -140.0
            bandDb[band] = db.toFloat()
            if (db > frameMaxDb) frameMaxDb = db
        }

        // Adaptive reference: instant attack, slow release, so quiet and loud masters both fill the range.
        referenceDb = max(MIN_REFERENCE_DB, max(frameMaxDb, referenceDb - REFERENCE_RELEASE_DB_PER_FRAME))
        val floorDb = referenceDb - DYNAMIC_RANGE_DB

        val levels = FloatArray(BAND_COUNT) { band ->
            ((bandDb[band] - floorDb) / DYNAMIC_RANGE_DB).toFloat().coerceIn(0f, 1f)
        }

        val frame = Frame(System.nanoTime(), levels)
        synchronized(lock) {
            queue.addLast(frame)
            while (queue.size > MAX_QUEUED_FRAMES) queue.removeFirst()
        }
    }

    /** In-place iterative radix-2 Cooley–Tukey FFT. */
    private fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var length = 2
        while (length <= n) {
            val angle = -2.0 * PI / length
            val wRe = cos(angle).toFloat()
            val wIm = sin(angle).toFloat()
            var start = 0
            while (start < n) {
                var curRe = 1f
                var curIm = 0f
                for (k in 0 until length / 2) {
                    val a = start + k
                    val b = a + length / 2
                    val tRe = re[b] * curRe - im[b] * curIm
                    val tIm = re[b] * curIm + im[b] * curRe
                    re[b] = re[a] - tRe
                    im[b] = im[a] - tIm
                    re[a] += tRe
                    im[a] += tIm
                    val nextRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = nextRe
                }
                start += length
            }
            length = length shl 1
        }
    }
}
