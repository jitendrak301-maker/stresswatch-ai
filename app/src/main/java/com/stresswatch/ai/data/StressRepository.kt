package com.stresswatch.ai.data

import android.content.Context
import kotlinx.coroutines.flow.Flow
import java.util.UUID

class StressRepository(context: Context) {

    private val dao = StressDatabase.getInstance(context).stressDao()
    val currentSessionId: String = UUID.randomUUID().toString()

    init {
        // Session is inserted when monitoring starts
    }

    suspend fun saveReading(state: StressState) {
        val reading = StressReading(
            score = state.score,
            level = state.level.label,
            faceScore = state.faceScore,
            hrv = state.hrv,
            edaScore = 0f,
            voiceScore = state.voiceScore,
            motionScore = state.motionScore,
            heartRate = state.heartRate,
            sessionId = currentSessionId
        )
        dao.insertReading(reading)
    }

    suspend fun startSession() {
        dao.insertSession(
            Session(
                sessionId = currentSessionId,
                startTime = System.currentTimeMillis()
            )
        )
    }

    suspend fun endSession() {
        val avg = dao.getAvgScoreForSession(currentSessionId) ?: 0f
        val peak = dao.getPeakScoreForSession(currentSessionId) ?: 0
        val session = dao.getAllSessions()
        // Update session record with final stats
        dao.updateSession(
            Session(
                sessionId = currentSessionId,
                startTime = System.currentTimeMillis(),
                endTime = System.currentTimeMillis(),
                avgScore = avg,
                peakScore = peak
            )
        )
    }

    fun getRecentReadings(): Flow<List<StressReading>> = dao.getRecentReadings()
    fun getAllSessions(): Flow<List<Session>> = dao.getAllSessions()
    fun getReadingsForSession(sessionId: String): Flow<List<StressReading>> =
        dao.getReadingsForSession(sessionId)

    suspend fun cleanOldData() {
        val sevenDaysAgo = System.currentTimeMillis() - (7L * 24 * 60 * 60 * 1000)
        dao.deleteOlderThan(sevenDaysAgo)
    }
}
