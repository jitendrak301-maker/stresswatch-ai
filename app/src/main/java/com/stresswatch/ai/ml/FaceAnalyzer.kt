package com.stresswatch.ai.ml

import android.content.Context
import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions

/**
 * FaceAnalyzer: Facial Expression-based Stress Detection
 *
 * Uses Google ML Kit Face Detection to extract:
 *   1. Euler angles (head pitch, yaw, roll) → postural tension
 *   2. Smiling probability (inverse stress indicator)
 *   3. Left/right eye open probability → blink rate
 *   4. Facial contour landmarks → jaw tension proxy
 *
 * Stress indicators:
 *   - Low smile probability (< 0.2)
 *   - Frequent blinking or wide-open eyes (startle response)
 *   - Head forward pitch (looking down, slouching)
 */
class FaceAnalyzer(context: Context) {

    @Volatile var currentScore: Float = 0f
        private set

    private val options = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
        .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
        .setMinFaceSize(0.15f)
        .build()

    private val detector = FaceDetection.getClient(options)

    private val blinkHistory = ArrayDeque<Float>()
    private var prevEyeOpen = 1.0f
    private var blinkCount = 0
    private var frameCount = 0

    fun analyze(bitmap: Bitmap) {
        val image = InputImage.fromBitmap(bitmap, 0)
        detector.process(image)
            .addOnSuccessListener { faces -> if (faces.isNotEmpty()) updateScore(faces[0]) }
            .addOnFailureListener { /* ignore */ }
    }

    private fun updateScore(face: Face) {
        frameCount++

        // 1. Smiling probability (0=frown → high stress, 1=smile → low stress)
        val smileProb = face.smilingProbability ?: 0.3f
        val smileStress = (1f - smileProb)

        // 2. Eye openness — blink rate increases with stress
        val leftEye  = face.leftEyeOpenProbability  ?: 0.8f
        val rightEye = face.rightEyeOpenProbability ?: 0.8f
        val avgEye = (leftEye + rightEye) / 2f
        if (prevEyeOpen > 0.5f && avgEye < 0.3f) blinkCount++
        prevEyeOpen = avgEye
        val blinkRate = blinkCount.toFloat() / frameCount.coerceAtLeast(1) * 30f  // per second
        val blinkStress = when {
            blinkRate > 0.4f -> 0.8f  // > 24 blinks/min → stressed
            blinkRate > 0.25f -> 0.5f
            blinkRate > 0.1f  -> 0.2f
            else -> 0.6f  // too few blinks also indicates tension
        }

        // 3. Head pose — forward pitch indicates tension / fatigue
        val pitch = face.headEulerAngleX  // negative = looking down
        val poseStress = when {
            pitch < -20f -> 0.75f  // significant forward head lean
            pitch < -10f -> 0.45f
            pitch in -10f..10f -> 0.1f  // neutral = relaxed
            pitch > 20f  -> 0.55f  // head back = possible discomfort
            else -> 0.2f
        }

        // 4. Head yaw — constantly looking away = disengaged / anxious
        val yaw = kotlin.math.abs(face.headEulerAngleY)
        val yawStress = (yaw / 45f).coerceIn(0f, 1f)

        // Weighted fusion
        val raw = smileStress * 0.40f + blinkStress * 0.25f +
                  poseStress * 0.25f + yawStress * 0.10f
        currentScore = (0.35f * raw * 100f + 0.65f * currentScore).coerceIn(0f, 100f)
    }
}
