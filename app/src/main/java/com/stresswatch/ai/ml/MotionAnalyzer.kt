package com.stresswatch.ai.ml

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

/**
 * MotionAnalyzer: IMU-based Stress Indicator
 *
 * Stress manifests physically as:
 *   - Increased body movement / fidgeting (accelerometer)
 *   - Postural instability (gyroscope)
 *   - Micro-tremors (high-frequency gyro components)
 *
 * Features:
 *   1. Jerk magnitude (derivative of acceleration)
 *   2. Motion entropy (irregularity of movement)
 *   3. Gyroscope RMS (postural instability)
 */
class MotionAnalyzer(context: Context) : SensorEventListener {

    @Volatile var currentScore: Float = 0f
        private set

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroSensor  = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    private val accelHistory = ArrayDeque<FloatArray>()
    private val gyroHistory  = ArrayDeque<FloatArray>()
    private val WINDOW = 50  // 1 second at 50Hz

    private var prevAccel = floatArrayOf(0f, 0f, 9.8f)

    fun start() {
        accelSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        gyroSensor?.let  { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        accelHistory.clear()
        gyroHistory.clear()
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                val a = event.values.clone()
                accelHistory.addWithLimit(a, WINDOW)
                if (accelHistory.size >= WINDOW / 2) computeScore()
            }
            Sensor.TYPE_GYROSCOPE -> {
                gyroHistory.addWithLimit(event.values.clone(), WINDOW)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun computeScore() {
        val jerkScore   = computeJerkMagnitude()
        val gyroScore   = computeGyroRMS()
        val entropyScore = computeMotionEntropy()

        val raw = (jerkScore * 0.45f + gyroScore * 0.35f + entropyScore * 0.20f) * 100f
        currentScore = (0.3f * raw + 0.7f * currentScore).coerceIn(0f, 100f)
    }

    private fun computeJerkMagnitude(): Float {
        if (accelHistory.size < 2) return 0f
        var jerkSum = 0f
        for (i in 1 until accelHistory.size) {
            val da = FloatArray(3) { accelHistory[i][it] - accelHistory[i-1][it] }
            jerkSum += sqrt(da[0]*da[0] + da[1]*da[1] + da[2]*da[2])
        }
        val avgJerk = jerkSum / (accelHistory.size - 1)
        // Normalize: 0.5 m/s³ → high stress, 0.02 m/s³ → calm
        return (avgJerk / 0.5f).coerceIn(0f, 1f)
    }

    private fun computeGyroRMS(): Float {
        if (gyroHistory.isEmpty()) return 0f
        val rms = sqrt(gyroHistory.sumOf { g ->
            (g[0]*g[0] + g[1]*g[1] + g[2]*g[2]).toDouble()
        }.toFloat() / gyroHistory.size)
        return (rms / 2.0f).coerceIn(0f, 1f)  // 2 rad/s = very agitated
    }

    private fun computeMotionEntropy(): Float {
        if (accelHistory.size < 4) return 0f
        val magnitudes = accelHistory.map { a ->
            sqrt(a[0]*a[0] + a[1]*a[1] + a[2]*a[2])
        }
        val mean = magnitudes.average().toFloat()
        val variance = magnitudes.map { (it - mean) * (it - mean) }.average().toFloat()
        return (variance / 25f).coerceIn(0f, 1f)  // 25 m²/s⁴ = very variable
    }

    private fun <T> ArrayDeque<T>.addWithLimit(e: T, limit: Int) {
        if (size >= limit) removeFirst()
        addLast(e)
    }
}
