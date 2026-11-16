package com.example.growbloxmacro

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

class MacroService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Logika makro atau aksi otomatis bisa ditaruh di sini
    }

    override fun onInterrupt() {
    }
}
