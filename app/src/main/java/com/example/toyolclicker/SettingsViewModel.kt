package com.example.toyolclicker

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update

// V2.1 & V2.2: JobTarget now includes distance filter settings
data class JobTarget(
    val isEnabled: Boolean = false,
    val toKlia: Boolean = false,
    val fromKlia: Boolean = false,
    val manualPriceEnabled: Boolean = false,
    val manualPrice: String = "",
    val distanceFilterEnabled: Boolean = false, // Moved from global
    val maxPickupDistance: String = "10"      // Moved from global
)

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

    // V2.1: Personalized targets per service type
    val jobTargets: Map<String, JobTarget> = mapOf(
        "JustGrab" to JobTarget(),
        "Plus" to JobTarget(),
        "6 seats" to JobTarget(),
        "Premium" to JobTarget(),
        "Executive" to JobTarget()
    ),

    // Other global settings
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

    // --- V2 Event Handlers ---

    fun onServiceTypeChange(type: String, isChecked: Boolean) {
        _state.update { currentState ->
            val updatedTypes = currentState.serviceTypes.toMutableMap()
            updatedTypes[type] = isChecked
            currentState.copy(serviceTypes = updatedTypes)
        }
    }

    // V2.1 & V2.2: Powerful handler for personalized job targets
    fun onJobTargetChange(serviceType: String, update: (JobTarget) -> JobTarget) {
        _state.update { currentState ->
            val updatedTargets = currentState.jobTargets.toMutableMap()
            val currentTarget = updatedTargets[serviceType] ?: JobTarget()
            updatedTargets[serviceType] = update(currentTarget)
            currentState.copy(jobTargets = updatedTargets)
        }
    }

    // Global settings handlers
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
