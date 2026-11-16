package com.example.growbloxmacro

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.TextView
import kotlin.math.abs

class MacroService : AccessibilityService() {

    // Kecepatan klik dalam milidetik (300L = 0,3 detik per klik. Ubah sesuai kebutuhan)
    private val clickIntervalMs = 300L

    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = false

    private var windowManager: WindowManager? = null
    private var controlButton: Button? = null
    private var targetView: TextView? = null
    private var targetParams: WindowManager.LayoutParams? = null

    private val clickRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            performClickAtTarget()
            handler.postDelayed(this, clickIntervalMs)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        setupFloatingControls()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (controlButton == null && Settings.canDrawOverlays(this)) {
            setupFloatingControls()
        }
    }

    override fun onInterrupt() {
        stopMacro()
    }

    private fun setupFloatingControls() {
        if (!Settings.canDrawOverlays(this) || controlButton != null) return

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        // 1. Buat Ikon Target (+) yang bisa digeser
        targetView = TextView(this).apply {
            text = "+"
            textSize = 26f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#AAFF0000"))
        }

        targetParams = WindowManager.LayoutParams(
            110,
            110,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 400
            y = 700
        }

        makeDraggable(targetView!!, targetParams!!, null)
        windowManager?.addView(targetView, targetParams)

        // 2. Buat Tombol Kontrol Mulai / Stop
        controlButton = Button(this).apply {
            text = "▶ MULAI"
            textSize = 14f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#CC1B5E20"))
        }

        val controlParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 80
            y = 300
        }

        makeDraggable(controlButton!!, controlParams) {
            if (isRunning) stopMacro() else startMacro()
        }
        windowManager?.addView(controlButton, controlParams)
    }

    private fun startMacro() {
        isRunning = true
        controlButton?.text = "⏹ STOP"
        controlButton?.setBackgroundColor(Color.parseColor("#CCB71C1C"))

        // Buat target tembus sentuhan saat klik otomatis berjalan
        targetParams?.let { params ->
            params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            windowManager?.updateViewLayout(targetView, params)
        }

        handler.post(clickRunnable)
    }

    private fun stopMacro() {
        isRunning = false
        handler.removeCallbacks(clickRunnable)
        controlButton?.text = "▶ MULAI"
        controlButton?.setBackgroundColor(Color.parseColor("#CC1B5E20"))

        // Kembalikan agar target bisa digeser lagi
        targetParams?.let { params ->
            params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            windowManager?.updateViewLayout(targetView, params)
        }
    }

    private fun performClickAtTarget() {
        val view = targetView ?: return
        val location = IntArray(2)
        view.getLocationOnScreen(location)

        val clickX = location[0] + view.width / 2f
        val clickY = location[1] + view.height / 2f

        val path = Path().apply {
            moveTo(clickX, clickY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 50L))
            .build()

        dispatchGesture(gesture, null, null)
    }

    private fun makeDraggable(
        view: View,
        params: WindowManager.LayoutParams,
        onClick: (() -> Unit)?
    ) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager?.updateViewLayout(view, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val movedX = abs(event.rawX - initialTouchX)
                    val movedY = abs(event.rawY - initialTouchY)
                    if (movedX < 10 && movedY < 10) {
                        onClick?.invoke()
                    }
                    true
                }
                else -> false
            }
        }
    }

    override fun onDestroy() {
        stopMacro()
        runCatching { windowManager?.removeView(controlButton) }
        runCatching { windowManager?.removeView(targetView) }
        controlButton = null
        targetView = null
        super.onDestroy()
    }
}
