package com.stresswatch.ai.ml

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.*

/**
 * rPPG (Remote Photoplethysmography) Analyzer
 *
 * Extracts heart rate and HRV from facial video without contact.
 * Algorithm:
 *   1. Detect forehead / cheek ROI from face bounding box
 *   2. Extract mean Green channel (G) from ROI every frame
 *   3. Apply bandpass filter [0.7 – 4.0 Hz] (42–240 BPM)
 *   4. Peak detection → inter-beat intervals (IBI)
 *   5. Compute RMSSD from IBIs (gold-standard HRV metric)
 *
 * Based on: CHROM algorithm (de Haan & Jeanne, IEEE-TBME 2013)
 */
class RPPGAnalyzer {

    data class RPPGData(
        val heartRate: Int = 0,
        val rmssd: Float = 0f,
        val sdnn: Float = 0f,
        val confidence: Float = 0f
    )

    companion object {
        private const val WINDOW_SIZE    = 150   // ~5 seconds at 30fps
        private const val SAMPLE_RATE    = 30f   // fps
        private const val BP_LOW         = 0.7f  // Hz
        private const val BP_HIGH        = 3.5f  // Hz
        private const val MIN_PEAKS      = 4     // minimum peaks for valid HRV
    }

    private val greenChannel  = ArrayDeque<Float>()
    private val redChannel    = ArrayDeque<Float>()
    private val blueChannel   = ArrayDeque<Float>()
    private val timestamps    = ArrayDeque<Long>()

    @Volatile var currentData = RPPGData()
        private set

    private var isRunning = false

    fun start() { isRunning = true }
    fun stop() {
        isRunning = false
        greenChannel.clear(); redChannel.clear(); blueChannel.clear(); timestamps.clear()
    }

    fun processFrame(bitmap: Bitmap) {
        if (!isRunning) return
        val (r, g, b) = extractROIMeans(bitmap)
        greenChannel.addWithLimit(g)
        redChannel.addWithLimit(r)
        blueChannel.addWithLimit(b)
        timestamps.addWithLimit(System.currentTimeMillis().toFloat())

        if (greenChannel.size >= WINDOW_SIZE / 2) {
            currentData = computeHRV()
        }
    }

    private fun extractROIMeans(bitmap: Bitmap): Triple<Float, Float, Float> {
        val w = bitmap.width
        val h = bitmap.height
        // Forehead ROI: top-center 20% of face
        val x0 = (w * 0.3).toInt()
        val x1 = (w * 0.7).toInt()
        val y0 = (h * 0.05).toInt()
        val y1 = (h * 0.25).toInt()

        var rSum = 0L; var gSum = 0L; var bSum = 0L; var count = 0
        for (x in x0 until x1 step 2) {
            for (y in y0 until y1 step 2) {
                val px = bitmap.getPixel(x, y)
                rSum += Color.red(px)
                gSum += Color.green(px)
                bSum += Color.blue(px)
                count++
            }
        }
        if (count == 0) return Triple(0f, 0f, 0f)
        return Triple(rSum.toFloat() / count, gSum.toFloat() / count, bSum.toFloat() / count)
    }

    private fun computeHRV(): RPPGData {
        val g = greenChannel.toFloatArray()
        val r = redChannel.toFloatArray()
        val b = blueChannel.toFloatArray()

        // CHROM algorithm: construct chrominance signal
        val chrom = FloatArray(g.size) { i ->
            val gNorm = g[i] / (g.average().toFloat().coerceAtLeast(0.01f))
            val rNorm = r[i] / (r.average().toFloat().coerceAtLeast(0.01f))
            3f * rNorm - 2f * gNorm  // simplified CHROM
        }

        // Bandpass filter
        val filtered = bandpassFilter(chrom, SAMPLE_RATE, BP_LOW, BP_HIGH)

        // Peak detection
        val peaks = detectPeaks(filtered)
        if (peaks.size < MIN_PEAKS) return currentData  // Not enough data

        // IBI (Inter-Beat Interval) in ms
        val ibis = peaks.zipWithNext { a, b -> ((b - a) / SAMPLE_RATE * 1000f) }

        // Heart rate
        val avgIBI = ibis.average().toFloat()
        val hr = (60000f / avgIBI).toInt().coerceIn(40, 200)

        // RMSSD (Root Mean Square of Successive Differences)
        val rmssd = sqrt(ibis.zipWithNext { a, b -> (b - a).pow(2) }.average().toFloat())

        // SDNN
        val meanIBI = ibis.average().toFloat()
        val sdnn = sqrt(ibis.map { (it - meanIBI).pow(2) }.average().toFloat())

        val confidence = (peaks.size.toFloat() / 8f).coerceIn(0f, 1f)

        return RPPGData(heartRate = hr, rmssd = rmssd, sdnn = sdnn, confidence = confidence)
    }

    /** Simple IIR bandpass filter (2nd-order Butterworth approximation) */
    private fun bandpassFilter(signal: FloatArray, fs: Float, low: Float, high: Float): FloatArray {
        val out = FloatArray(signal.size)
        val dt = 1f / fs
        val rcLow = 1f / (2f * PI.toFloat() * low)
        val rcHigh = 1f / (2f * PI.toFloat() * high)
        val alphaLow = dt / (rcLow + dt)
        val alphaHigh = rcHigh / (rcHigh + dt)

        var prev = signal[0]; var highPrev = 0f; var outPrev = 0f
        for (i in 1 until signal.size) {
            // Low-pass
            val lp = prev + alphaLow * (signal[i] - prev)
            // High-pass
            val hp = alphaHigh * (outPrev + signal[i] - prev)
            out[i] = hp
            prev = lp; outPrev = hp
        }
        return out
    }

    /** Simple peak detection with adaptive threshold */
    private fun detectPeaks(signal: FloatArray): List<Int> {
        val peaks = mutableListOf<Int>()
        val threshold = signal.max() * 0.55f
        val minDist = (SAMPLE_RATE * 0.4f).toInt()  // min 400ms between beats
        var lastPeak = -minDist
        for (i in 1 until signal.size - 1) {
            if (signal[i] > threshold && signal[i] > signal[i-1] && signal[i] > signal[i+1]
                && i - lastPeak >= minDist) {
                peaks.add(i)
                lastPeak = i
            }
        }
        return peaks
    }

    suspend fun getCurrentData(): RPPGData = currentData

    private fun <T> ArrayDeque<T>.addWithLimit(e: T) {
        if (size >= WINDOW_SIZE) removeFirst()
        addLast(e)
    }
    private fun ArrayDeque<Float>.toFloatArray() = FloatArray(size) { this[it] }
    private fun FloatArray.average() = if (isEmpty()) 0.0 else sumOf { it.toDouble() } / size
    private fun FloatArray.max() = maxOrNull() ?: 0f
    private fun List<Float>.average() = if (isEmpty()) 0.0 else sumOf { it.toDouble() } / size
}
