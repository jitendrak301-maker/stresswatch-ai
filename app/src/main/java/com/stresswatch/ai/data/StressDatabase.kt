package com.stresswatch.ai.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface StressDao {
    @Insert
    suspend fun insertReading(reading: StressReading)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: Session)

    @Query("SELECT * FROM stress_readings WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun getReadingsForSession(sessionId: String): Flow<List<StressReading>>

    @Query("SELECT * FROM stress_readings ORDER BY timestamp DESC LIMIT 200")
    fun getRecentReadings(): Flow<List<StressReading>>

    @Query("SELECT * FROM sessions ORDER BY startTime DESC LIMIT 50")
    fun getAllSessions(): Flow<List<Session>>

    @Query("SELECT AVG(score) FROM stress_readings WHERE sessionId = :sessionId")
    suspend fun getAvgScoreForSession(sessionId: String): Float?

    @Query("SELECT MAX(score) FROM stress_readings WHERE sessionId = :sessionId")
    suspend fun getPeakScoreForSession(sessionId: String): Int?

    @Query("DELETE FROM stress_readings WHERE timestamp < :before")
    suspend fun deleteOlderThan(before: Long)

    @Update
    suspend fun updateSession(session: Session)
}

@Database(
    entities = [StressReading::class, Session::class],
    version = 1,
    exportSchema = false
)
abstract class StressDatabase : RoomDatabase() {
    abstract fun stressDao(): StressDao

    companion object {
        @Volatile private var INSTANCE: StressDatabase? = null

        fun getInstance(context: Context): StressDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    StressDatabase::class.java,
                    "stress_db"
                ).build().also { INSTANCE = it }
            }
    }
}
