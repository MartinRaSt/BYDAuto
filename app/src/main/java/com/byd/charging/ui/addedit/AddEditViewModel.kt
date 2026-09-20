package com.byd.charging.ui.addedit

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.byd.charging.data.ChargingDatabase
import com.byd.charging.data.ChargingRepository
import com.byd.charging.data.ChargingSession
import com.byd.charging.util.DateUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AddEditViewModel(application: Application) : AndroidViewModel(application) {

    private companion object {
        const val TAG = "AddEditViewModel"
    }

    private val repository = ChargingRepository(
        ChargingDatabase.getDatabase(application).chargingDao()
    )

    private val _currentSession = MutableLiveData<ChargingSession?>()
    val currentSession: LiveData<ChargingSession?> = _currentSession

    private val _lastSession = MutableLiveData<ChargingSession?>()
    val lastSession: LiveData<ChargingSession?> = _lastSession

    fun loadSession(id: Long) {
        viewModelScope.launch {
            try {
                val session = withContext(Dispatchers.IO) { repository.getById(id) }
                _currentSession.value = session
                if (session == null) {
                    Log.w(TAG, "Session with id=$id not found in database")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load session id=$id", e)
                _currentSession.value = null
            }
        }
    }

    private val _allSessions = MutableLiveData<List<ChargingSession>>()

    /** Vsechny zaznamy - potrebne pro odvozene hodnoty (spotreba, dojezd). */
    val allSessions: LiveData<List<ChargingSession>> = _allSessions

    fun loadAllSessions() {
        viewModelScope.launch {
            try {
                _allSessions.value = withContext(Dispatchers.IO) { repository.getAllSessionsOnce() }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load all sessions", e)
            }
        }
    }

    fun loadLastSession() {
        viewModelScope.launch {
            try {
                val session = withContext(Dispatchers.IO) { repository.getLatestSession() }
                _lastSession.value = session
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load last session", e)
            }
        }
    }

    /**
     * Kontrola duplicity. Vrací nalezenou podobnou relaci nebo null.
     */
    suspend fun findDuplicate(session: ChargingSession): ChargingSession? = withContext(Dispatchers.IO) {
        val sameDay = repository.getSessionsByDate(session.date)
        sameDay.find { existing ->
            if (existing.id == session.id) return@find false // Ignorovat sebe sama při editaci
            
            // Podobnost 1: Stejný stav elektroměru
            if (existing.startMeterKwh == session.startMeterKwh && session.startMeterKwh > 0) return@find true
            
            // Podobnost 2: Časový překryv nebo velmi blízký čas (např. 15 minut)
            // absMinutesDiff, ne calculateDuration: ta pri zapornem rozdilu pricita
            // 24 hodin, takze o par minut DRIVEJSI zaznam by duplicitou nikdy nebyl
            val diff = DateUtil.absMinutesDiff(existing.startTime, session.startTime)
            if (diff < 15) return@find true
            
            false
        }
    }

    fun saveSession(session: ChargingSession) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (session.id == 0L) repository.insert(session)
                    else repository.update(session)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save session", e)
            }
        }
    }
}
