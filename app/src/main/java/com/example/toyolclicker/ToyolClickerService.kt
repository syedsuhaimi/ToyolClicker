package com.example.toyolclicker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.util.regex.Pattern
import kotlin.math.abs

class ToyolClickerService : AccessibilityService() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var searchJob: Job? = null
    private var floatingView: View? = null
    private lateinit var windowManager: WindowManager

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
            val jobPrice = extractPrice(text = nodeText)
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
        val pattern = Pattern.compile("""(\d{1,2}):\d{2} (AM|PM)""")
        val matcher = pattern.matcher(text)
        if (matcher.find()) {
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
        val pattern = Pattern.compile("""RM(\d+\.\d{2})""")
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
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        showFloatingButton()

        ToyolClickerState.isServiceRunning
            .onEach { isRunning ->
                searchJob?.cancel()

                val button = floatingView?.findViewById<Button>(R.id.floating_button)
                if (isRunning) {
                    button?.text = "Stop"
                    button?.backgroundTintList = ColorStateList.valueOf(Color.GREEN)

                    Log.d("ToyolClickerService", "Starting job search loop...")
                    searchJob = serviceScope.launch {
                        while (true) {
                            val interval = ToyolClickerState.settings.value.refreshInterval.toLongOrNull() ?: 1000L
                            rootInActiveWindow?.let { root ->
                                val plannerNode = findNodeByText(root, "Booking Planner")
                                if (plannerNode != null) {
                                    Log.d("ToyolClickerService", "On Booking Planner, performing swipe refresh.")
                                    performSwipe()
                                } else {
                                    Log.d("ToyolClickerService", "Not on Booking Planner, skipping swipe.")
                                }
                            }
                            delay(interval)
                        }
                    }
                } else {
                    button?.text = "Start"
                    button?.backgroundTintList = ColorStateList.valueOf(Color.RED)
                    Log.d("ToyolClickerService", "Stopping job search loop.")
                }
            }
            .launchIn(serviceScope)
    }

    private fun showFloatingButton() {
        floatingView = LayoutInflater.from(this).inflate(R.layout.floating_layout, null)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )

        params.gravity = Gravity.TOP or Gravity.START
        params.x = 0
        params.y = 100

        windowManager.addView(floatingView, params)

        floatingView?.setOnTouchListener(object : View.OnTouchListener {
            private var initialX: Int = 0
            private var initialY: Int = 0
            private var initialTouchX: Float = 0f
            private var initialTouchY: Float = 0f
            private var isADrag: Boolean = false
            private val CLICK_DRAG_TOLERANCE = 10f

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                // It's possible for touch events to be dispatched after the view is detached.
                if (!v.isAttachedToWindow) {
                    return false
                }
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        isADrag = false
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val xDiff = event.rawX - initialTouchX
                        val yDiff = event.rawY - initialTouchY
                        if (!isADrag && (abs(xDiff) > CLICK_DRAG_TOLERANCE || abs(yDiff) > CLICK_DRAG_TOLERANCE)) {
                            isADrag = true
                        }
                        if (isADrag) {
                            params.x = initialX + xDiff.toInt()
                            params.y = initialY + yDiff.toInt()
                            // Safely update layout by checking if the view is still attached.
                            windowManager.updateViewLayout(v, params)
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!isADrag) {
                            ToyolClickerState.toggleServiceStatus()
                        }
                        return true
                    }
                }
                return false
            }
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        // Safely remove the view and nullify the reference to prevent leaks and crashes.
        floatingView?.let {
            if (it.isAttachedToWindow) {
                windowManager.removeView(it)
            }
        }
        floatingView = null
        Log.d("ToyolClickerService", "Service destroyed.")
    }
}
