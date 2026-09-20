package com.byd.charging.data

import androidx.lifecycle.LiveData
import androidx.room.*

// --- Agregační datové třídy pro grafy ---

data class TypeStats(
    val chargingType: String,
    val totalKwh: Double,
    val totalCost: Double,
    val count: Int
)

data class MonthlyStats(
    val month: String,      // "2024-01"
    val totalKwh: Double,
    val totalCost: Double,
    val sessionCount: Int
)

@Dao
interface ChargingDao {

    @Query("SELECT * FROM charging_sessions ORDER BY date DESC, startTime DESC")
    fun getAllSessions(): LiveData<List<ChargingSession>>

    @Query("SELECT * FROM charging_sessions ORDER BY date DESC, startTime DESC")
    suspend fun getAllSessionsOnce(): List<ChargingSession>

    @Query("""
        SELECT * FROM charging_sessions 
        WHERE date BETWEEN :startDate AND :endDate 
        AND (:type IS NULL OR chargingType = :type)
        ORDER BY date DESC, startTime DESC
    """)
    fun getFilteredSessions(startDate: String, endDate: String, type: String?): LiveData<List<ChargingSession>>

    @Query("""
        SELECT * FROM charging_sessions 
        WHERE date BETWEEN :startDate AND :endDate 
        AND (:type IS NULL OR chargingType = :type)
        ORDER BY date DESC, startTime DESC
    """)
    suspend fun getFilteredSessionsOnce(startDate: String, endDate: String, type: String?): List<ChargingSession>

    @Query("SELECT * FROM charging_sessions WHERE id = :id")
    suspend fun getById(id: Long): ChargingSession?

    @Query("SELECT * FROM charging_sessions WHERE date = :date")
    suspend fun getSessionsByDate(date: String): List<ChargingSession>

    @Query("SELECT * FROM charging_sessions ORDER BY date DESC, startTime DESC LIMIT 1")
    suspend fun getLatestSession(): ChargingSession?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(session: ChargingSession)

    @Update
    suspend fun update(session: ChargingSession)

    @Delete
    suspend fun delete(session: ChargingSession)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(sessions: List<ChargingSession>)

    @Query("DELETE FROM charging_sessions")
    suspend fun deleteAll()

    /**
     * Atomická náhrada všech dat zálohou.
     * @Transaction zajišťuje, že pokud insertAll selže, deleteAll se rollbackuje —
     * uživatel nikdy nepřijde o data kvůli přerušení (crash, OOM).
     */
    @Transaction
    suspend fun replaceAll(sessions: List<ChargingSession>) {
        deleteAll()
        insertAll(sessions)
    }

    // --- Grafy ---

    @Query("""
        SELECT chargingType,
               SUM(chargedKwh)                  AS totalKwh,
               SUM(chargedKwh * pricePerKwh)    AS totalCost,
               COUNT(*)                          AS count
        FROM charging_sessions
        GROUP BY chargingType
    """)
    suspend fun getStatsByType(): List<TypeStats>

    @Query("""
        SELECT substr(date, 1, 7)               AS month,
               SUM(chargedKwh)                  AS totalKwh,
               SUM(chargedKwh * pricePerKwh)    AS totalCost,
               COUNT(*)                          AS sessionCount
        FROM charging_sessions
        GROUP BY month
        ORDER BY month ASC
        LIMIT 12
    """)
    suspend fun getMonthlyStats(): List<MonthlyStats>
}
