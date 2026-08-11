package com.stresswatch.ai.ui.dashboard

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.stresswatch.ai.data.StressRepository
import com.stresswatch.ai.data.StressState
import com.stresswatch.ai.ml.StressEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DashboardViewModel(application: Application) : AndroidViewModel(application) {

    val stressEngine = StressEngine(application)
    private val repository = StressRepository(application)

    private val _isMonitoring = MutableStateFlow(false)
    val isMonitoring: StateFlow<Boolean> = _isMonitoring.asStateFlow()

    val stressState: StateFlow<StressState> = stressEngine.stressState

    fun startMonitoring() {
        if (_isMonitoring.value) return
        _isMonitoring.value = true
        stressEngine.start()
        viewModelScope.launch {
            repository.startSession()
            stressEngine.stressState.collect { state ->
                if (_isMonitoring.value) {
                    repository.saveReading(state)
                }
            }
        }
    }

    fun stopMonitoring() {
        _isMonitoring.value = false
        stressEngine.stop()
        viewModelScope.launch { repository.endSession() }
    }

    override fun onCleared() {
        super.onCleared()
        stressEngine.stop()
    }
}
