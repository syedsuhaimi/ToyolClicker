package com.example.toyolclicker

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * A singleton object to hold the shared state between the UI and the AccessibilityService.
 */
object ToyolClickerState {
    val isServiceRunning = MutableStateFlow(false)
    val settings = MutableStateFlow(SettingsState())
}
