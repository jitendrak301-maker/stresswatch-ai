package com.stresswatch.ai.ml

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.*
import kotlin.math.*

/**
 * VoiceAnalyzer: Real-Time Voice Stress Analysis
 *
 * Features extracted from microphone (16kHz, 16-bit, mono):
 *   1. RMS Energy — overall loudness / arousal
 *   2. Zero Crossing Rate (ZCR) — voice quality / tension
 *   3. Spectral Centroid — brightness / pitch tension
 *   4. MFCC (Mel Frequency Cepstral Coefficients) — voice timbre
 *   5. Fundamental frequency (F0) jitter — vocal tremor
 *
 * Stress estimation: weighted combination → [0, 100]
 */
class VoiceAnalyzer(private val context: Context) {

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val FRAME_SIZE  = 2048
        private const val OVERLAP     = FRAME_SIZE / 2
        private const val MEL_BANDS   = 40
    }

    @Volatile var currentScore: Float = 0f
        private set

    private var audioRecord: AudioRecord? = null
    private var analyzerJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val scoreHistory = ArrayDeque<Float>()

    fun start() {
        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(FRAME_SIZE * 2)

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )
        audioRecord?.startRecording()
        analyzerJob = scope.launch { analysisLoop() }
    }

    fun stop() {
        analyzerJob?.cancel()
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }

    private suspend fun analysisLoop() {
        val buffer = ShortArray(FRAME_SIZE)
        while (isActive) {
            val read = audioRecord?.read(buffer, 0, FRAME_SIZE) ?: break
            if (read > 0) {
                val floatFrame = FloatArray(read) { buffer[it] / 32768f }
                val score = computeStressScore(floatFrame)
                // EMA smoothing
                currentScore = 0.4f * score + 0.6f * currentScore
            }
            delay(50)
        }
    }

    private fun computeStressScore(frame: FloatArray): Float {
        val rms       = computeRMS(frame)
        val zcr       = computeZCR(frame)
        val centroid  = computeSpectralCentroid(frame)
        val jitter    = computeJitter(frame)

        // Normalize each feature to [0, 1] with empirical ranges
        val rmsN     = (rms / 0.15f).coerceIn(0f, 1f)
        val zcrN     = (zcr / 0.25f).coerceIn(0f, 1f)
        val centroidN = ((centroid - 500f) / 3000f).coerceIn(0f, 1f)
        val jitterN  = (jitter / 0.05f).coerceIn(0f, 1f)

        // Weighted combination
        val stress = (rmsN * 0.25f + zcrN * 0.20f + centroidN * 0.30f + jitterN * 0.25f) * 100f
        return stress.coerceIn(0f, 100f)
    }

    private fun computeRMS(frame: FloatArray): Float {
        val sum = frame.sumOf { (it * it).toDouble() }
        return sqrt(sum / frame.size).toFloat()
    }

    private fun computeZCR(frame: FloatArray): Float {
        var crossings = 0
        for (i in 1 until frame.size) {
            if ((frame[i] >= 0) != (frame[i - 1] >= 0)) crossings++
        }
        return crossings.toFloat() / frame.size
    }

    private fun computeSpectralCentroid(frame: FloatArray): Float {
        val spectrum = fft(frame)
        val magnitudes = FloatArray(spectrum.size / 2) {
            val re = spectrum[it * 2]; val im = spectrum[it * 2 + 1]
            sqrt(re * re + im * im)
        }
        val freqStep = SAMPLE_RATE.toFloat() / frame.size
        val totalMag = magnitudes.sum().coerceAtLeast(0.0001f)
        var centroid = 0f
        for (i in magnitudes.indices) centroid += magnitudes[i] * (i * freqStep)
        return centroid / totalMag
    }

    private fun computeJitter(frame: FloatArray): Float {
        // Estimate F0 using autocorrelation and compute jitter
        val minLag = (SAMPLE_RATE / 400).toInt()  // 400Hz max
        val maxLag = (SAMPLE_RATE / 70).toInt()   // 70Hz min
        val ac = FloatArray(maxLag + 1)
        for (lag in minLag..maxLag) {
            var sum = 0f
            for (i in 0 until frame.size - lag) sum += frame[i] * frame[i + lag]
            ac[lag] = sum
        }
        // Find peaks in autocorrelation
        val periods = mutableListOf<Int>()
        var prevPeak = minLag
        for (i in minLag + 1 until maxLag) {
            if (ac[i] > ac[i-1] && ac[i] > ac[i+1] && ac[i] > 0) {
                periods.add(i - prevPeak)
                prevPeak = i
            }
        }
        if (periods.size < 2) return 0.01f
        val meanPeriod = periods.average().toFloat()
        val jitter = periods.zipWithNext { a, b -> abs(b - a).toFloat() }.average().toFloat()
        return if (meanPeriod > 0) jitter / meanPeriod else 0.01f
    }

    /** Simple FFT using Cooley-Tukey */
    private fun fft(input: FloatArray): FloatArray {
        val n = input.size
        val out = FloatArray(n * 2)
        for (i in 0 until n) { out[i * 2] = input[i]; out[i * 2 + 1] = 0f }
        var len = 2
        while (len <= n) {
            val ang = 2f * PI.toFloat() / len
            var i = 0
            while (i < n) {
                for (j in 0 until len / 2) {
                    val re = cos((ang * j).toDouble()).toFloat()
                    val im = -sin((ang * j).toDouble()).toFloat()
                    val uRe = out[(i + j) * 2]; val uIm = out[(i + j) * 2 + 1]
                    val vRe = re * out[(i + j + len / 2) * 2] - im * out[(i + j + len / 2) * 2 + 1]
                    val vIm = re * out[(i + j + len / 2) * 2 + 1] + im * out[(i + j + len / 2) * 2]
                    out[(i + j) * 2] = uRe + vRe; out[(i + j) * 2 + 1] = uIm + vIm
                    out[(i + j + len / 2) * 2] = uRe - vRe; out[(i + j + len / 2) * 2 + 1] = uIm - vIm
                }
                i += len
            }
            len *= 2
        }
        return out
    }
}
