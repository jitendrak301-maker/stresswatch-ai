package com.stresswatch.ai.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "stress_readings")
data class StressReading(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val score: Int,
    val level: String,
    val faceScore: Float,
    val hrv: Float,
    val edaScore: Float,
    val voiceScore: Float,
    val motionScore: Float,
    val heartRate: Int,
    val sessionId: String
)

@Entity(tableName = "sessions")
data class Session(
    @PrimaryKey val sessionId: String,
    val startTime: Long,
    val endTime: Long = 0L,
    val avgScore: Float = 0f,
    val peakScore: Int = 0,
    val durationSeconds: Long = 0L
)

enum class StressLevel(val label: String, val color: String, val range: IntRange) {
    LOW("Low", "#4ade80", 0..35),
    MODERATE("Moderate", "#facc15", 36..60),
    HIGH("High", "#fb923c", 61..80),
    CRITICAL("Critical", "#ef4444", 81..100);

    companion object {
        fun fromScore(score: Int): StressLevel = when (score) {
            in 0..35  -> LOW
            in 36..60 -> MODERATE
            in 61..80 -> HIGH
            else      -> CRITICAL
        }
    }
}

data class StressState(
    val score: Int = 0,
    val level: StressLevel = StressLevel.LOW,
    val faceScore: Float = 0f,
    val heartRate: Int = 0,
    val hrv: Float = 0f,
    val voiceScore: Float = 0f,
    val motionScore: Float = 0f,
    val confidence: Float = 0f,
    val trend: Trend = Trend.STABLE
)

enum class Trend { RISING, FALLING, STABLE }
