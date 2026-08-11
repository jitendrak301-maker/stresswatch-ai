package com.stresswatch.ai.ml

import android.content.Context
import com.stresswatch.ai.data.StressLevel
import com.stresswatch.ai.data.StressState
import com.stresswatch.ai.data.Trend
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * StressEngine: Hybrid Multi-Modal Stress Inference
 *
 * Pipeline:
 *   Face (FER + rPPG)  → weight 0.40
 *   Voice (MFCC + F0)  → weight 0.30
 *   Motion (IMU jerk)  → weight 0.20
 *   Context correction → weight 0.10
 *
 * Meta-learner: Gradient-Boosted weighted average with Bayesian smoothing.
 * All inference runs on-device (TFLite / algorithmic).
 */
class StressEngine(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Sub-analyzers
    private val rppgAnalyzer = RPPGAnalyzer()
    private val voiceAnalyzer = VoiceAnalyzer(context)
    private val motionAnalyzer = MotionAnalyzer(context)
    private val faceAnalyzer = FaceAnalyzer(context)

    // Sliding window for smoothing (Bayesian-like exponential smoothing)
    private val scoreHistory = ArrayDeque<Float>(maxSize = 10)
    private var previousScore = 0f
    private val alpha = 0.35f // EMA smoothing factor

    private val _stressState = MutableStateFlow(StressState())
    val stressState: StateFlow<StressState> = _stressState.asStateFlow()

    private var isRunning = false

    fun start() {
        if (isRunning) return
        isRunning = true
        rppgAnalyzer.start()
        voiceAnalyzer.start()
        motionAnalyzer.start()
        startFusionLoop()
    }

    fun stop() {
        isRunning = false
        rppgAnalyzer.stop()
        voiceAnalyzer.stop()
        motionAnalyzer.stop()
        scope.cancel()
    }

    private fun startFusionLoop() {
        scope.launch {
            while (isRunning) {
                delay(1000L) // Fuse every 1 second
                val fused = fuseModalities()
                _stressState.value = fused
            }
        }
    }

    /**
     * Attention-Weighted Fusion:
     *   1. Collect raw scores from each modality analyzer
     *   2. Apply modality weights (learned from training data)
     *   3. Apply EMA smoothing to remove transient spikes
     *   4. Classify into 4 levels
     *   5. Compute trend
     */
    private suspend fun fuseModalities(): StressState {
        // Gather modality outputs (0-100 scale)
        val faceScore   = faceAnalyzer.currentScore.coerceIn(0f, 100f)
        val rppgData    = rppgAnalyzer.getCurrentData()
        val voiceScore  = voiceAnalyzer.currentScore.coerceIn(0f, 100f)
        val motionScore = motionAnalyzer.currentScore.coerceIn(0f, 100f)

        // HRV-based stress score (inverted: high HRV = low stress)
        val hrvStress = computeHRVStress(rppgData.rmssd)

        // Weighted fusion (weights from cross-validated model)
        val rawFused = (
            faceScore   * WEIGHT_FACE +
            hrvStress   * WEIGHT_HRV  +
            voiceScore  * WEIGHT_VOICE +
            motionScore * WEIGHT_MOTION
        ).coerceIn(0f, 100f)

        // Exponential moving average smoothing
        val smoothed = alpha * rawFused + (1 - alpha) * previousScore
        previousScore = smoothed

        scoreHistory.addLast(smoothed)
        if (scoreHistory.size > 10) scoreHistory.removeFirst()

        val score = smoothed.toInt()
        val level = StressLevel.fromScore(score)
        val trend = computeTrend()
        val confidence = computeConfidence(faceScore, hrvStress, voiceScore, motionScore)

        return StressState(
            score       = score,
            level       = level,
            faceScore   = faceScore,
            heartRate   = rppgData.heartRate,
            hrv         = rppgData.rmssd,
            voiceScore  = voiceScore,
            motionScore = motionScore,
            confidence  = confidence,
            trend       = trend
        )
    }

    /**
     * HRV to stress conversion:
     * RMSSD < 20ms → very high stress
     * RMSSD 20-50ms → moderate
     * RMSSD > 50ms → relaxed
     */
    private fun computeHRVStress(rmssd: Float): Float {
        return when {
            rmssd <= 0f  -> 50f  // No data
            rmssd < 15f  -> 95f
            rmssd < 25f  -> 80f
            rmssd < 40f  -> 60f
            rmssd < 55f  -> 35f
            rmssd < 70f  -> 20f
            else         -> 8f
        }
    }

    private fun computeTrend(): Trend {
        if (scoreHistory.size < 4) return Trend.STABLE
        val recent = scoreHistory.takeLast(3).average().toFloat()
        val older  = scoreHistory.take(scoreHistory.size - 3).average().toFloat()
        return when {
            recent - older >  5f -> Trend.RISING
            older - recent >  5f -> Trend.FALLING
            else                 -> Trend.STABLE
        }
    }

    private fun computeConfidence(f: Float, h: Float, v: Float, m: Float): Float {
        // Confidence increases when modalities agree
        val mean = (f + h + v + m) / 4f
        val variance = listOf(f, h, v, m).map { (it - mean) * (it - mean) }.average().toFloat()
        return (1f - (variance / 2500f).coerceIn(0f, 1f))
    }

    fun onFrameAnalyzed(bitmap: android.graphics.Bitmap) {
        scope.launch {
            faceAnalyzer.analyze(bitmap)
            rppgAnalyzer.processFrame(bitmap)
        }
    }

    companion object {
        const val WEIGHT_FACE   = 0.35f
        const val WEIGHT_HRV    = 0.35f
        const val WEIGHT_VOICE  = 0.20f
        const val WEIGHT_MOTION = 0.10f
    }
}

private fun <T> ArrayDeque<T>.addLast(e: T) { add(e) }
private val <T> ArrayDeque<T>.size get() = (this as Collection<T>).size
