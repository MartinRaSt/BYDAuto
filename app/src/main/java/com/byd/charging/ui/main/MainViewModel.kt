package com.byd.charging.ui.main

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.liveData
import androidx.lifecycle.switchMap
import androidx.lifecycle.viewModelScope
import com.byd.charging.data.ChargingDatabase
import com.byd.charging.data.ChargingRepository
import com.byd.charging.data.ChargingSession
import com.byd.charging.data.MonthlyStats
import com.byd.charging.data.TypeStats
import com.byd.charging.util.DateUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private companion object {
        const val TAG = "MainViewModel"
    }

    private val repository = ChargingRepository(
        ChargingDatabase.getDatabase(application).chargingDao()
    )

    val allSessions: LiveData<List<ChargingSession>> = repository.allSessions

    // Filtry pro grafy (v budoucnu možno použít i pro seznam)
    private val _startDate = MutableLiveData<String>("2000-01-01")
    private val _endDate = MutableLiveData<String>("2099-12-31")
    private val _filterType = MutableLiveData<String?>(null) // null = vše

    val filterState: LiveData<Triple<String, String, String?>> = MediatorLiveData<Triple<String, String, String?>>().apply {
        addSource(_startDate) { value = Triple(it, _endDate.value ?: "2099-12-31", _filterType.value) }
        addSource(_endDate) { value = Triple(_startDate.value ?: "2000-01-01", it, _filterType.value) }
        addSource(_filterType) { value = Triple(_startDate.value ?: "2000-01-01", _endDate.value ?: "2099-12-31", it) }
    }

    // Filtrovaná data pro grafy i seznam (nyní jako LiveData přímo z DB)
    val filteredSessions: LiveData<List<ChargingSession>> = filterState.switchMap { (start, end, type) ->
        repository.getFilteredSessions(start, end, type)
    }

    // Statistiky počítané z filtrovaných dat
    val typeStats: LiveData<List<TypeStats>> = filteredSessions.switchMap { sessions ->
        liveData(Dispatchers.Default) {
            val stats = sessions.groupBy { it.chargingType }.map { (type, list) ->
                TypeStats(
                    chargingType = type,
                    totalKwh = list.sumOf { it.chargedKwh }, // Zde se u benzínu ukládají litry
                    totalCost = list.sumOf { it.totalCost },
                    count = list.size
                )
            }
            emit(stats)
        }
    }

    val monthlyStats: LiveData<List<MonthlyStats>> = filteredSessions.switchMap { sessions ->
        liveData(Dispatchers.Default) {
            val stats = sessions.groupBy { it.date.substring(0, 7) }
                .map { (month, list) ->
                    MonthlyStats(
                        month = month,
                        totalKwh = list.sumOf { it.chargedKwh },
                        totalCost = list.sumOf { it.totalCost },
                        sessionCount = list.size
                    )
                }
                .sortedBy { it.month }
                .takeLast(12)
            emit(stats)
        }
    }

    fun setDateRange(start: String, end: String) {
        _startDate.value = start
        _endDate.value = end
    }

    fun setFilterType(type: String?) {
        _filterType.value = type
    }

    fun delete(session: ChargingSession) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { repository.delete(session) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete session id=${session.id}", e)
            }
        }
    }

    suspend fun getAllSessionsOnce(): List<ChargingSession> =
        withContext(Dispatchers.IO) { repository.getAllSessionsOnce() }

    suspend fun getFilteredSessionsOnce(start: String, end: String, type: String?): List<ChargingSession> =
        withContext(Dispatchers.IO) { repository.getFilteredSessionsOnce(start, end, type) }

    fun importSessions(
        sessions: List<ChargingSession>,
        replaceAll: Boolean,
        onComplete: (count: Int) -> Unit
    ) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (replaceAll) repository.replaceAll(sessions)
                    else repository.insertAll(sessions)
                }
                onComplete(sessions.size)
            } catch (e: Exception) {
                Log.e(TAG, "Import failed (replaceAll=$replaceAll, count=${sessions.size})", e)
                onComplete(-1)
            }
        }
    }
}
