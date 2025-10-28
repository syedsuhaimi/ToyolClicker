package com.example.toyolclicker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.media.RingtoneManager
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlin.math.abs

class ToyolClickerService : AccessibilityService() {

    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var searchJob: Job? = null
    private var floatingView: View? = null
    private lateinit var windowManager: WindowManager
    private var longPressJob: Job? = null
    private var longPressHandled = false

    // (c) Short-term memory for the job to be verified
    private var pendingJobToVerify: String? = null

    // (d) Timeout mechanism variables
    private var backToPlannerJob: Job? = null
    private var forceRefreshOnNextPlannerView = false

    private var initialX: Int = 0
    private var initialY: Int = 0
    private var initialTouchX: Float = 0f
    private var initialTouchY: Float = 0f
    private var isADrag: Boolean = false
    private val CLICK_DRAG_TOLERANCE = 10f

    private val showButtonReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.example.toyolclicker.SHOW_BUTTON") {
                Log.d("ToyolClickerService", "Received broadcast to show button.")
                showFloatingButton()
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!ToyolClickerState.isServiceRunning.value) return

        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            mainScope.launch {
                rootInActiveWindow?.let { root ->
                    try {
                        findAndProcessJobs(root)
                    } catch (e: IllegalStateException) {
                        Log.e("ToyolClickerService", "Error processing node on event: ${e.message}")
                    }
                }
            }
        }
    }

    private fun findAndProcessJobs(rootNode: AccessibilityNodeInfo) {
        // Priority 1: Check for successful booking
        findNodeByText(rootNode, "Booking is confirmed!")?.let {
            Log.d("ToyolClickerService", "'Booking is confirmed!' found. Stopping service and closing pop-up.")
            pendingJobToVerify = null // Clear memory
            playNotificationSound()
            ToyolClickerState.toggleServiceStatus()
            findNodeByText(rootNode, "Close")?.let { closeButton -> performClick(closeButton) }
            return
        }

        // Priority 2: Final confirmation action
        findNodeByText(rootNode, "Confirm")?.let {
            Log.d("ToyolClickerService", "'Confirm' button found. Clicking it.")
            performClick(it)
            return
        }

        // (c) New Priority 3: Verification on the Accept screen
        val acceptButton = findNodeByText(rootNode, "Accept")
        if (acceptButton != null && pendingJobToVerify != null) {
            Log.d("ToyolClickerService", "On Accept screen, verifying job details...")
            val currentPageText = getAllTextFromNode(rootNode).joinToString("\n")

            if (isJobMatch(currentPageText, ToyolClickerState.settings.value)) {
                Log.d("ToyolClickerService", "Verification SUCCESS. Clicking Accept.")
                performClick(acceptButton)
            } else {
                Log.w("ToyolClickerService", "Verification FAILED. Job no longer matches. Going back.")
                recoverToPlanner(isTimeout = false) // Go back without forcing refresh
            }
            pendingJobToVerify = null // Clear memory after action
            return
        }

        // Priority 4: Handle error or status messages
        findNodeByText(rootNode, "Slots are fully reserved")?.let {
            Log.d("ToyolClickerService", "'Slots are fully reserved' found. Going back.")
            pendingJobToVerify = null // Clear memory on error
            recoverToPlanner(isTimeout = false)
            return
        }
        findNodeByText(rootNode, "Request timed out")?.let {
            Log.d("ToyolClickerService", "'Request timed out' found. Going back.")
            pendingJobToVerify = null // Clear memory on error
            recoverToPlanner(isTimeout = false)
            return
        }

        // Priority 5: If no pop-ups, search for new jobs on the main screen
        val plannerNode = findNodeByText(rootNode, "Booking Planner")
        if (plannerNode == null) return

        pendingJobToVerify = null

        val jobNodes = rootNode.findAccessibilityNodeInfosByViewId("com.grabtaxi.driver2:id/unified_item_layout")
        if (jobNodes.isEmpty()) return

        val settings = ToyolClickerState.settings.value
        for (node in jobNodes) {
            val nodeText = getAllTextFromNode(node).joinToString("\n")
            if (isJobMatch(nodeText, settings)) {
                Log.w("ToyolClickerService", "MATCH FOUND! Storing job text and clicking: $nodeText")
                pendingJobToVerify = nodeText // Store in memory
                performClick(node)
                return // Stop after clicking the first matched job
            }
        }
    }

    private fun isJobMatch(nodeText: String, settings: SettingsState): Boolean {
        // This is the V2 logic, which is more advanced and what we want to keep.
        val serviceType = settings.serviceTypes.keys.firstOrNull { nodeText.contains(it, ignoreCase = true) }
            ?: return false

        if (settings.serviceTypes[serviceType] != true) {
            return false
        }

        if (settings.timeSelection == "Manual") {
            val jobTime = extractTime(nodeText)
            val selectedHours = settings.manualHours.filter { it.value }.keys
            if (selectedHours.isNotEmpty() && (jobTime == null || jobTime !in selectedHours)) {
                return false
            }
        }

        val target = settings.jobTargets[serviceType]

        if (target == null || !target.isEnabled) {
            return true
        }

        if (target.distanceFilterEnabled && !target.fromKlia) {
            val maxDistance = target.maxPickupDistance.toDoubleOrNull()
            if (maxDistance != null) {
                val jobDistance = extractDistance(nodeText)
                if (jobDistance == null || jobDistance > maxDistance) {
                    return false
                }
            }
        }

        // Merged logic: Use V2's target object with V1-fixed's specific string check
        val toKliaMatch = target.toKlia && nodeText.contains("(To KLIA)", ignoreCase = true)
        val fromKliaMatch = target.fromKlia && nodeText.contains("(From KLIA)", ignoreCase = true)

        val priceMatch = if (target.manualPriceEnabled) {
            val jobPrice = extractPrice(nodeText)
            val targetPrice = target.manualPrice.toDoubleOrNull()
            jobPrice != null && targetPrice != null && jobPrice >= targetPrice
        } else {
            false
        }

        val criteriaSelected = target.toKlia || target.fromKlia || target.manualPriceEnabled
        return if (criteriaSelected) toKliaMatch || fromKliaMatch || priceMatch else true
    }
    
    private fun recoverToPlanner(isTimeout: Boolean) {
        Log.w("ToyolClickerService", "Recovery needed! isTimeout: $isTimeout. Attempting to go back to planner.")
        if(isTimeout) {
            forceRefreshOnNextPlannerView = true
        }
        pendingJobToVerify = null // Clear any pending job

        val rootNode = rootInActiveWindow ?: return

        // Hierarchical recovery strategy
        findNodeByText(rootNode, "Cancel")?.let {
            Log.d("ToyolClickerService", "Recovery: Found 'Cancel', clicking it.")
            performClick(it)
            return
        }
        
        rootNode.findAccessibilityNodeInfosByViewId("com.grabtaxi.driver2:id/jobs_toolbar_left_icon").firstOrNull()?.let {
             if(it.contentDescription?.contains("Back", ignoreCase = true) == true) {
                Log.d("ToyolClickerService", "Recovery: Found back button by ID and description, clicking it.")
                performClick(it)
                return
             }
        }

        Log.d("ToyolClickerService", "Recovery: No specific button found, using global back action.")
        performGlobalAction(GLOBAL_ACTION_BACK)
    }

    private fun performClick(node: AccessibilityNodeInfo) {
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
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

    private fun playNotificationSound() {
        try {
            val notification = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val r = RingtoneManager.getRingtone(applicationContext, notification)
            r.play()
        } catch (e: Exception) {
            Log.e("ToyolClickerService", "Error playing notification sound", e)
        }
    }

    private fun extractTime(text: String): Int? {
        val regex = "(\\d{1,2}):\\d{2}\\s?(AM|PM)".toRegex(RegexOption.IGNORE_CASE)
        val matchResult = regex.find(text)
        if (matchResult != null) {
            val (hourString, amPm) = matchResult.destructured
            var hour = hourString.toIntOrNull() ?: return null
            if (amPm.equals("PM", ignoreCase = true) && hour < 12) {
                hour += 12
            }
            if (amPm.equals("AM", ignoreCase = true) && hour == 12) {
                hour = 0
            }
            return hour
        }
        return null
    }

    private fun extractPrice(text: String): Double? {
        val regex = "RM(\\d+\\.?\\d*)".toRegex()
        val matchResult = regex.find(text)
        return matchResult?.destructured?.component1()?.toDoubleOrNull()
    }

    private fun extractDistance(text: String): Double? {
        val regex = "(\\d+\\.?\\d*)\\s?Km from you".toRegex(RegexOption.IGNORE_CASE)
        val matchResult = regex.find(text)
        return matchResult?.destructured?.component1()?.toDoubleOrNull()
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

        val filter = IntentFilter("com.example.toyolclicker.SHOW_BUTTON")
        registerReceiver(showButtonReceiver, filter, Context.RECEIVER_NOT_EXPORTED)

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        showFloatingButton()

        ToyolClickerState.isServiceRunning
            .onEach { isRunning ->
                searchJob?.cancel()
                val button = floatingView?.findViewById<Button>(R.id.floating_button)
                if (isRunning) {
                    button?.text = "Stop"
                    button?.backgroundTintList = ColorStateList.valueOf(Color.RED)
                    Log.d("ToyolClickerService", "Starting job search loop...")

                    searchJob = mainScope.launch {
                        while (true) {
                            val currentRoot = rootInActiveWindow
                            if (currentRoot == null) {
                                delay(5000L) // Wait if screen is not available
                                continue
                            }

                            val plannerNode = findNodeByText(currentRoot, "Booking Planner")
                            if (plannerNode != null) {
                                backToPlannerJob?.cancel() // We are on the right screen, cancel timeout
                                if (forceRefreshOnNextPlannerView) {
                                    Log.i("ToyolClickerService", "Forcing refresh after recovery.")
                                    performSwipe()
                                    forceRefreshOnNextPlannerView = false
                                    delay(2000L) // Wait a bit after forced refresh
                                    continue // Restart loop to get fresh nodes
                                }
                                
                                Log.d("ToyolClickerService", "On Booking Planner, performing swipe refresh.")
                                performSwipe()
                                val baseInterval = ToyolClickerState.settings.value.refreshInterval.toLongOrNull() ?: 1000L
                                val jitter = (baseInterval * 0.2).toLong()
                                val delayDuration = if (jitter > 0) baseInterval - jitter + (0..jitter * 2).random() else baseInterval
                                Log.d("ToyolClickerService", "Active mode. Next refresh in: ${delayDuration}ms")
                                delay(delayDuration)

                            } else {
                                // Not on planner screen, start or continue the timeout
                                if (backToPlannerJob == null || backToPlannerJob?.isCompleted == true) {
                                    backToPlannerJob = mainScope.launch {
                                        Log.w("ToyolClickerService", "Not on planner screen. Starting 5s timeout to recover.")
                                        delay(5000L)
                                        recoverToPlanner(isTimeout = true)
                                    }
                                }
                                delay(2000L) // Check screen state every 2s while in timeout mode
                            }
                        }
                    }
                } else {
                    button?.text = "Start"
                    button?.backgroundTintList = ColorStateList.valueOf(Color.GREEN)
                    Log.d("ToyolClickerService", "Stopping job search loop.")
                    backToPlannerJob?.cancel()
                }
            }
            .launchIn(mainScope)
    }

    private fun showFloatingButton() {
        if (floatingView?.isAttachedToWindow == true) return // Don't show if already shown
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

        val button = floatingView?.findViewById<Button>(R.id.floating_button)

        button?.setOnTouchListener { v, event ->
            if (floatingView?.isAttachedToWindow != true) {
                return@setOnTouchListener false
            }

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isADrag = false
                    longPressHandled = false
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY

                    longPressJob?.cancel()
                    longPressJob = mainScope.launch {
                        delay(1000L) // 1-second delay for long press
                        longPressHandled = true
                        Log.d("ToyolClickerService", "Long press detected. Hiding button.")
                        Toast.makeText(this@ToyolClickerService, "Floating button hidden.", Toast.LENGTH_SHORT).show()
                        floatingView?.let {
                            if (it.isAttachedToWindow) {
                                windowManager.removeView(it)
                            }
                        }
                        floatingView = null // Prevent reuse
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    val xDiff = event.rawX - initialTouchX
                    val yDiff = event.rawY - initialTouchY
                    if (abs(xDiff) > CLICK_DRAG_TOLERANCE || abs(yDiff) > CLICK_DRAG_TOLERANCE) {
                        if (!isADrag) {
                            isADrag = true
                            longPressJob?.cancel() // It's a drag, cancel long press
                        }
                        params.x = initialX + xDiff.toInt()
                        params.y = initialY + yDiff.toInt()
                        windowManager.updateViewLayout(floatingView, params)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    longPressJob?.cancel() // Always cancel the timer on ACTION_UP
                    if (!isADrag && !longPressHandled) {
                        // It's a short click
                        Log.d("ToyolClickerService", "Short click detected.")
                        ToyolClickerState.toggleServiceStatus()
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    longPressJob?.cancel()
                }
            }
            true
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(showButtonReceiver)
        floatingView?.let {
            if (it.isAttachedToWindow) windowManager.removeView(it)
        }
        mainScope.cancel()
        searchJob?.cancel()
        longPressJob?.cancel()
        backToPlannerJob?.cancel()
    }
}
