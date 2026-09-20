package com.byd.charging.data

import androidx.lifecycle.LiveData

class ChargingRepository(private val dao: ChargingDao) {

    val allSessions: LiveData<List<ChargingSession>> = dao.getAllSessions()

    suspend fun insert(session: ChargingSession) = dao.insert(session)
    suspend fun update(session: ChargingSession) = dao.update(session)
    suspend fun delete(session: ChargingSession) = dao.delete(session)
    suspend fun getById(id: Long): ChargingSession? = dao.getById(id)
    suspend fun getSessionsByDate(date: String): List<ChargingSession> = dao.getSessionsByDate(date)
    suspend fun getLatestSession(): ChargingSession? = dao.getLatestSession()
    suspend fun getAllSessionsOnce(): List<ChargingSession> = dao.getAllSessionsOnce()

    fun getFilteredSessions(startDate: String, endDate: String, type: String?): LiveData<List<ChargingSession>> =
        dao.getFilteredSessions(startDate, endDate, type)

    suspend fun getFilteredSessionsOnce(startDate: String, endDate: String, type: String?): List<ChargingSession> =
        dao.getFilteredSessionsOnce(startDate, endDate, type)

    // Import / záloha
    suspend fun insertAll(sessions: List<ChargingSession>) = dao.insertAll(sessions)

    /**
     * Atomicky nahradí všechna data zálohou v jediné transakci.
     * Data se nikdy neztratí — pokud insertAll selže, deleteAll se rollbackuje.
     */
    suspend fun replaceAll(sessions: List<ChargingSession>) = dao.replaceAll(sessions)

    // Grafy
    suspend fun getStatsByType(): List<TypeStats> = dao.getStatsByType()
    suspend fun getMonthlyStats(): List<MonthlyStats> = dao.getMonthlyStats()
}
