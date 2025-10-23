package com.example.toyolclicker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
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

    private var initialX: Int = 0
    private var initialY: Int = 0
    private var initialTouchX: Float = 0f
    private var initialTouchY: Float = 0f
    private var isADrag: Boolean = false
    private val CLICK_DRAG_TOLERANCE = 10f

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
        // Keutamaan 1: Semak jika job sudah berjaya sepenuhnya
        findNodeByText(rootNode, "Booking is confirmed!")?.let {
            Log.d("ToyolClickerService", "'Booking is confirmed!' found. Stopping service and closing pop-up.")
            playNotificationSound()
            ToyolClickerState.toggleServiceStatus()
            findNodeByText(rootNode, "Close")?.let { closeButton -> performClick(closeButton) }
            return
        }

        // == PERUBAHAN DI SINI: Tambah dan aktifkan carian untuk "Confirm" ==
        // Keutamaan 2: Tindakan pengesahan terakhir
        findNodeByText(rootNode, "Confirm")?.let {
            Log.d("ToyolClickerService", "'Confirm' button found. Clicking it.")
            performClick(it)
            return // Berhenti selepas klik Confirm, kerana ini adalah tindakan terakhir dalam aliran.
        }
        // =================================================================

        // Keutamaan 3: Tindakan menerima job
        findNodeByText(rootNode, "Accept")?.let {
            Log.d("ToyolClickerService", "'Accept' button found. Clicking it.")
            performClick(it)
            // Selepas klik Accept, skrin akan berubah untuk memaparkan 'Confirm'.
            // Fungsi ini akan dicetuskan semula oleh onAccessibilityEvent untuk skrin baru itu.
            return
        }

        // Keutamaan 4: Kendalikan mesej ralat atau status
        findNodeByText(rootNode, "Slots are fully reserved")?.let {
            Log.d("ToyolClickerService", "'Slots are fully reserved' found. Going back.")
            performGlobalAction(GLOBAL_ACTION_BACK)
            return
        }
        findNodeByText(rootNode, "Request timed out")?.let {
            Log.d("ToyolClickerService", "'Request timed out' found. Going back.")
            performGlobalAction(GLOBAL_ACTION_BACK)
            return
        }

        // Keutamaan 5: Jika tiada pop-up atau tindakan di atas, cari job baru di skrin utama
        val plannerNode = findNodeByText(rootNode, "Booking Planner")
        if (plannerNode == null) return // Bukan di skrin utama, abaikan

        val jobNodes = rootNode.findAccessibilityNodeInfosByViewId("com.grab.passenger:id/container")
        if (jobNodes.isEmpty()) return

        val settings = ToyolClickerState.settings.value
        for (node in jobNodes) {
            val nodeText = getAllTextFromNode(node).joinToString("\n")
            if (isJobMatch(nodeText, settings)) {
                Log.w("ToyolClickerService", "MATCH FOUND! Clicking job: $nodeText")
                performClick(node)
                return // Berhenti selepas klik job pertama yang sepadan
            }
        }
    }

    // ... (Tiada perubahan pada fungsi-fungsi lain: isJobMatch, performClick, performSwipe, dll.)

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
        return if (criteriaSelected) toKliaMatch || fromKliaMatch || priceMatch else true
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
        val regex = "(\\d{1,2}):\\d{2} (AM|PM)".toRegex()
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
        val regex = "RM(\\d+\\.\\d{2})".toRegex()
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
                            val interval = ToyolClickerState.settings.value.refreshInterval.toLongOrNull() ?: 1000L
                            val currentRoot = rootInActiveWindow
                            if (currentRoot != null) {
                                try {
                                    val plannerNode = findNodeByText(currentRoot, "Booking Planner")
                                    if (plannerNode != null) {
                                        Log.d("ToyolClickerService", "On Booking Planner, performing swipe refresh.")
                                        performSwipe()
                                    } else {
                                        Log.d("ToyolClickerService", "Not on Booking Planner, skipping swipe.")
                                    }
                                } catch (e: IllegalStateException) {
                                    Log.e("ToyolClickerService", "Error during swipe loop, node might be stale: ${e.message}")
                                }
                            } else {
                                Log.w("ToyolClickerService", "Root window is null, cannot perform swipe.")
                            }
                            delay(interval)
                        }
                    }
                } else {
                    button?.text = "Start"
                    button?.backgroundTintList = ColorStateList.valueOf(Color.GREEN)
                    Log.d("ToyolClickerService", "Stopping job search loop.")
                }
            }
            .launchIn(mainScope)
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

        val button = floatingView?.findViewById<Button>(R.id.floating_button)

        button?.setOnClickListener {
            ToyolClickerState.toggleServiceStatus()
        }

        button?.setOnTouchListener { v, event ->
            if (floatingView?.isAttachedToWindow != true) {
                return@setOnTouchListener false
            }

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isADrag = false
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
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
                        windowManager.updateViewLayout(floatingView, params)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (!isADrag) {
                        v.performClick()
                    }
                }
            }
            true
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        floatingView?.let {
            if (it.isAttachedToWindow) windowManager.removeView(it)
        }
        mainScope.cancel()
        searchJob?.cancel()
    }
}
