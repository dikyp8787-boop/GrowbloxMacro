package com.example.growbloxmacro

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.CheckBox
import kotlinx.coroutines.*

class MacroService : AccessibilityService() {

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private var isMacroRunning = false
    private var job: Job? = null

    private val PUNCH_X = 800f
    private val PUNCH_Y = 600f
    private val JOYSTICK_X = 250f
    private val JOYSTICK_Y = 800f

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val inflater = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
        floatingView = inflater.inflate(R.layout.floating_menu, null)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) 
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY 
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 50
        params.y = 150

        windowManager.addView(floatingView, params)

        val btnStart = floatingView.findViewById<Button>(R.id.btnStart)
        val cbAutoPunch = floatingView.findViewById<CheckBox>(R.id.cbAutoPunch)
        val cbAutoCollect = floatingView.findViewById<CheckBox>(R.id.cbAutoCollect)

        btnStart.setOnClickListener {
            if (isMacroRunning) {
                isMacroRunning = false
                btnStart.text = "MULAI MACRO"
                btnStart.setBackgroundColor(android.graphics.Color.parseColor("#4CAF50"))
                job?.cancel()
            } else {
                isMacroRunning = true
                btnStart.text = "BERHENTI"
                btnStart.setBackgroundColor(android.graphics.Color.parseColor("#F44336"))
                startMacro(cbAutoPunch.isChecked, cbAutoCollect.isChecked)
            }
        }
    }

    private fun startMacro(autoPunch: Boolean, autoCollect: Boolean) {
        job = CoroutineScope(Dispatchers.Main).launch {
            while (isActive && isMacroRunning) {
                if (autoPunch) {
                    simulateHold(PUNCH_X, PUNCH_Y, 1000L)
                    delay(1100L)
                }
                if (autoCollect) {
                    simulateSwipe(JOYSTICK_X, JOYSTICK_Y, JOYSTICK_X + 150f, JOYSTICK_Y, 400L) 
                    delay(450L)
                    simulateSwipe(JOYSTICK_X, JOYSTICK_Y, JOYSTICK_X - 150f, JOYSTICK_Y, 400L) 
                    delay(450L)
                }
                delay(100L)
            }
        }
    }

    private fun simulateHold(x: Float, y: Float, duration: Long) {
        val path = Path()
        path.moveTo(x, y)
        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        val builder = GestureDescription.Builder().addStroke(stroke)
        dispatchGesture(builder.build(), null, null)
    }

    private fun simulateSwipe(startX: Float, startY: Float, endX: Float, endY: Float, duration: Long) {
        val path = Path()
        path.moveTo(startX, startY)
        path.lineTo(endX, endY)
        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        val builder = GestureDescription.Builder().addStroke(stroke)
        dispatchGesture(builder.build(), null, null)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    
    override fun onDestroy() {
        super.onDestroy()
        if (this::floatingView.isInitialized) windowManager.removeView(floatingView)
        job?.cancel()
    }
}
