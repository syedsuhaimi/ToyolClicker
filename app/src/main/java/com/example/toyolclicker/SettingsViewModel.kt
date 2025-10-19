package com.example.toyolclicker

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update

// Data class to hold the entire state of the settings screen
data class SettingsState(
    val isServiceRunning: Boolean = false,
    val serviceTypes: Map<String, Boolean> = mapOf(
        "JustGrab" to false,
        "Plus" to false,
        "6 seats" to false,
        "Premium" to false,
        "Executive" to false
    ),
    val toKlia: Boolean = false,
    val fromKlia: Boolean = false,
    val manualPriceEnabled: Boolean = false,
    val manualPrice: String = "",
    val timeSelection: String = "Random", // "Random" or "Manual"
    val manualHours: Map<Int, Boolean> = (0..23).associateWith { false },
    val refreshInterval: String = "1000"
)

class SettingsViewModel : ViewModel() {

    private val _state = MutableStateFlow(SettingsState())
    val state = _state.asStateFlow()

    init {
        // Whenever the ViewModel's state changes, update the shared singleton state.
        _state.onEach { newState ->
            ToyolClickerState.settings.value = newState
        }.launchIn(viewModelScope)
    }

    fun onServiceTypeChange(type: String, isChecked: Boolean) {
        _state.update { currentState ->
            val updatedTypes = currentState.serviceTypes.toMutableMap()
            updatedTypes[type] = isChecked
            currentState.copy(serviceTypes = updatedTypes)
        }
    }

    fun onToKliaChange(isChecked: Boolean) {
        _state.update { it.copy(toKlia = isChecked) }
    }

    fun onFromKliaChange(isChecked: Boolean) {
        _state.update { it.copy(fromKlia = isChecked) }
    }

    fun onManualPriceEnabledChange(isEnabled: Boolean) {
        _state.update { it.copy(manualPriceEnabled = isEnabled) }
    }

    fun onManualPriceChange(price: String) {
        _state.update { it.copy(manualPrice = price) }
    }

    fun onTimeSelectionChange(selection: String) {
        _state.update { it.copy(timeSelection = selection) }
    }

    fun onManualHourChange(hour: Int, isChecked: Boolean) {
        _state.update { currentState ->
            val updatedHours = currentState.manualHours.toMutableMap()
            updatedHours[hour] = isChecked
            currentState.copy(manualHours = updatedHours)
        }
    }

    fun onRefreshIntervalChange(interval: String) {
        _state.update { it.copy(refreshInterval = interval) }
    }
}
