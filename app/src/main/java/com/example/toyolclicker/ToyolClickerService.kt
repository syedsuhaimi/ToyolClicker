package com.example.toyolclicker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.util.regex.Pattern

class ToyolClickerService : AccessibilityService() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var searchJob: Job? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!ToyolClickerState.isServiceRunning.value) return

        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            rootInActiveWindow?.let {
                findAndProcessJobs(it)
                // DO NOT recycle the root node. The system manages its lifecycle.
            }
        }
    }

    private fun findAndProcessJobs(rootNode: AccessibilityNodeInfo) {
        // Check for specific pop-ups first and act on them
        findNodeByText(rootNode, "Accept")?.let { 
            Log.d("ToyolClickerService", "'Accept' button found. Clicking it.")
            performClick(it)
            return
        }
        findNodeByText(rootNode, "Confirm")?.let { 
            Log.d("ToyolClickerService", "'Confirm' button found. Clicking it.")
            performClick(it)
            return
        }
        findNodeByText(rootNode, "Slots are fully reserved")?.let { 
            Log.d("ToyolClickerService", "'Slots are fully reserved' found. Going back.")
            performGlobalAction(GLOBAL_ACTION_BACK)
            return
        }

        // If no pop-ups, proceed with job search on the main planner screen
        val plannerNode = findNodeByText(rootNode, "Booking Planner")
        if (plannerNode == null) return // Not on the correct screen

        val jobNodes = rootNode.findAccessibilityNodeInfosByViewId("com.grab.passenger:id/container")
        if (jobNodes.isEmpty()) return

        val settings = ToyolClickerState.settings.value

        for (node in jobNodes) {
            val nodeText = getAllTextFromNode(node).joinToString("\n")

            if (isJobMatch(nodeText, settings)) {
                Log.w("ToyolClickerService", "MATCH FOUND! Clicking job: $nodeText")
                performClick(node)
                return // Stop searching after finding and clicking a match
            }
        }
    }

    private fun isJobMatch(nodeText: String, settings: SettingsState): Boolean {
        val selectedServiceTypes = settings.serviceTypes.filter { it.value }.keys
        if (selectedServiceTypes.isNotEmpty() && selectedServiceTypes.none { nodeText.contains(it, ignoreCase = true) }) {
            return false
        }

        if (settings.timeSelection == "Manual") {
            val jobTime = extractTime(nodeText)
            val selectedHours = settings.manualHours.filter { it.value }.keys
            if (selectedHours.isNotEmpty() && (jobTime == null || jobTime !in selectedHours)) {
                return false
            }
        }

        val toKliaMatch = settings.toKlia && nodeText.contains("KLIA", ignoreCase = true)
        val fromKliaMatch = settings.fromKlia && nodeText.contains("KLIA", ignoreCase = true)

        val priceMatch = if (settings.manualPriceEnabled) {
            val jobPrice = extractPrice(nodeText)
            val targetPrice = settings.manualPrice.toDoubleOrNull()
            jobPrice != null && targetPrice != null && jobPrice >= targetPrice
        } else {
            false
        }

        val criteriaSelected = settings.toKlia || settings.fromKlia || settings.manualPriceEnabled
        // If job type criteria are selected, at least one must match.
        // If no job type criteria are selected, the check passes (as service type/time already passed).
        return if(criteriaSelected) toKliaMatch || fromKliaMatch || priceMatch else true
    }

    private fun performClick(node: AccessibilityNodeInfo) {
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        // DO NOT RECYCLE the node passed in as an argument.
    }

    private fun performSwipe() {
        val displayMetrics = resources.displayMetrics
        val width = displayMetrics.widthPixels
        val height = displayMetrics.heightPixels

        val path = Path()
        path.moveTo(width / 2f, height / 4f)
        path.lineTo(width / 2f, height * 3 / 4f)

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 200))
            .build()

        dispatchGesture(gesture, null, null)
        Log.d("ToyolClickerService", "Performed swipe refresh.")
    }

    private fun extractTime(text: String): Int? {
        // Corrected Regex with proper escaping for Kotlin String
        val pattern = Pattern.compile("(\\d{1,2}):\\d{2} (AM|PM)")
        val matcher = pattern.matcher(text)
        if (matcher.find()) {
            // Group 1: hour, Group 2: AM/PM
            var hour = matcher.group(1)?.toIntOrNull() ?: return null
            val amPm = matcher.group(2)
            if (amPm.equals("PM", ignoreCase = true) && hour < 12) {
                hour += 12
            }
            if (amPm.equals("AM", ignoreCase = true) && hour == 12) {
                hour = 0 // Midnight case
            }
            return hour
        }
        return null
    }

    private fun extractPrice(text: String): Double? {
        // Corrected Regex with proper escaping for Kotlin String
        val pattern = Pattern.compile("RM(\\d+\\.\\d{2})")
        val matcher = pattern.matcher(text)
        return if (matcher.find()) {
            matcher.group(1)?.toDoubleOrNull()
        } else {
            null
        }
    }

    private fun getAllTextFromNode(node: AccessibilityNodeInfo?): List<String> {
        if (node == null) return emptyList()
        val texts = mutableListOf<String>()
        if (node.text != null) {
            texts.add(node.text.toString())
        }
        for (i in 0 until node.childCount) {
            texts.addAll(getAllTextFromNode(node.getChild(i)))
        }
        return texts
    }

    private fun findNodeByText(rootNode: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val nodes = rootNode.findAccessibilityNodeInfosByText(text)
        return nodes.firstOrNull()
    }

    override fun onInterrupt() {
        Log.d("ToyolClickerService", "Service interrupted.")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d("ToyolClickerService", "Service connected.")

        ToyolClickerState.isServiceRunning
            .onEach { isRunning ->
                searchJob?.cancel()
                if (isRunning) {
                    Log.d("ToyolClickerService", "Starting job search loop...")
                    searchJob = serviceScope.launch {
                        while (true) {
                            val interval = ToyolClickerState.settings.value.refreshInterval.toLongOrNull() ?: 1000L
                            performSwipe()
                            delay(interval)
                        }
                    }
                } else {
                    Log.d("ToyolClickerService", "Stopping job search loop.")
                }
            }
            .launchIn(serviceScope)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        Log.d("ToyolClickerService", "Service destroyed.")
    }
}
