package com.example.growbloxmacro

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.*
import kotlin.math.abs
import kotlin.random.Random

class MacroService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null

    // Floating Icon & Mod Menu
    private var iconView: TextView? = null
    private var iconParams: WindowManager.LayoutParams? = null
    private var menuView: LinearLayout? = null
    private var menuParams: WindowManager.LayoutParams? = null
    private var isMenuOpen = false

    // Temporary Calibration Marker (Hanya muncul saat tombol Kalibrasi ditekan)
    private var calibView: TextView? = null
    private var calibParams: WindowManager.LayoutParams? = null

    // UI References
    private lateinit var tabFarm: TextView
    private lateinit var tabMove: TextView
    private lateinit var tabSpam: TextView
    private lateinit var tabSetting: TextView
    private lateinit var pageFarm: LinearLayout
    private lateinit var pageMove: LinearLayout
    private lateinit var pageSpam: LinearLayout
    private lateinit var pageSetting: LinearLayout
    private lateinit var tvHeaderStatus: TextView

    // PowerKuy-style Feature Toggles
    private var autoBreakEnabled = false
    private var autoPlaceEnabled = false
    private var autoMoveEnabled = false
    private var autoSpamEnabled = false

    // Grid Tile Selection (5 kolom x 3 baris, index 7 = pusat karakter [0,0])
    private val selectedTiles = mutableSetOf<Int>()
    private val gridCells = mutableListOf<TextView>()
    private var currentGridCursor = 0

    // Koordinat Karakter & Ukuran Tile (disesuaikan dengan zoom game)
    private var centerX = 0f
    private var centerY = 0f
    private var tileSizePx = 110

    // Timing & Parameters
    private var breakHoldMs = 180L
    private var placeDelayMs = 120L
    private var moveHoldMs = 1000L
    private var spamIntervalSec = 5
    private var randomDelayMs = 35
    private var moveDirectionRight = true

    private var isLoopActive = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        val dm = resources.displayMetrics
        centerX = dm.widthPixels / 2f
        centerY = dm.heightPixels / 2f
        tileSizePx = (dm.widthPixels * 0.065f).toInt().coerceAtLeast(60)
        initModMenu()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (iconView == null && Settings.canDrawOverlays(this)) {
            initModMenu()
        }
    }

    override fun onInterrupt() {
        disableAllFeatures()
    }

    private fun getOverlayType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
    }

    private fun initModMenu() {
        if (!Settings.canDrawOverlays(this) || iconView != null) return
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        // Default tile aktif: 1 kotak di kiri karakter (index 6) & 1 kotak di kanan karakter (index 8)
        selectedTiles.add(6)
        selectedTiles.add(8)

        buildFloatingMenu()
        buildFloatingIcon()
    }

    private fun buildFloatingIcon() {
        iconView = TextView(this).apply {
            text = "PK"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#00E5FF"))
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#E60B0E14"))
                setStroke(dp(2), Color.parseColor("#00E5FF"))
            }
        }

        iconParams = WindowManager.LayoutParams(
            dp(44),
            dp(44),
            getOverlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(14)
            y = dp(110)
        }

        makeDraggable(iconView!!, iconParams!!) {
            isMenuOpen = !isMenuOpen
            menuView?.visibility = if (isMenuOpen) View.VISIBLE else View.GONE
        }
        windowManager?.addView(iconView, iconParams)
    }

    private fun buildFloatingMenu() {
        menuView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = makeBoxBg("#F20D111A", "#00E5FF", 12, 1)
            setPadding(dp(12), dp(10), dp(12), dp(12))
            visibility = View.GONE
        }

        // Header Bar
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), 0, dp(2), dp(8))
        }

        val titleBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        titleBox.addView(TextView(this).apply {
            text = "GROWBLOX INTERNAL UI"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#00E5FF"))
        })

        tvHeaderStatus = TextView(this).apply {
            text = "IDLE • ZERO-TARGET MODE"
            textSize = 10f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#64748B"))
        }
        titleBox.addView(tvHeaderStatus)
        header.addView(titleBox)

        val btnHide = TextView(this).apply {
            text = "✕"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = makeBoxBg("#1E293B", "#334155", 6, 1)
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(26))
            setOnClickListener {
                isMenuOpen = false
                menuView?.visibility = View.GONE
            }
        }
        header.addView(btnHide)
        menuView?.addView(header)

        // Navigation Tabs
        val tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = makeBoxBg("#07090E", "#1E293B", 8, 1)
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }

        tabFarm = makeTab("FARM") { switchTab(0) }
        tabMove = makeTab("MOVE") { switchTab(1) }
        tabSpam = makeTab("SPAM") { switchTab(2) }
        tabSetting = makeTab("SETTING") { switchTab(3) }

        tabBar.addView(tabFarm)
        tabBar.addView(tabMove)
        tabBar.addView(tabSpam)
        tabBar.addView(tabSetting)
        menuView?.addView(tabBar)

        // Pages Container
        val pages = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        }

        pageFarm = createFarmPage()
        pageMove = createMovePage()
        pageSpam = createSpamPage()
        pageSetting = createSettingPage()

        pages.addView(pageFarm)
        pages.addView(pageMove)
        pages.addView(pageSpam)
        pages.addView(pageSetting)
        menuView?.addView(pages)

        menuParams = WindowManager.LayoutParams(
            dp(285),
            WindowManager.LayoutParams.WRAP_CONTENT,
            getOverlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(64)
            y = dp(95)
        }

        makeDraggable(header, menuParams!!, null)
        windowManager?.addView(menuView, menuParams)
        switchTab(0)
    }

    private fun createFarmPage(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            addView(makeHint("Pilih kotak blok di sekitar karakter [ME]:"))

            // 5x3 Tile Grid Selector (Mirip PowerKuy Tile Selector)
            val gridContainer = LinearLayout(this@MacroService).apply {
                orientation = LinearLayout.VERTICAL
                background = makeBoxBg("#080B11", "#1E293B", 8, 1)
                setPadding(dp(6), dp(6), dp(6), dp(6))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(6) }
            }

            gridCells.clear()
            for (row in 0 until 3) {
                val rowLayout = LinearLayout(this@MacroService).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(32)
                    ).apply { if (row > 0) topMargin = dp(4) }
                }

                for (col in 0 until 5) {
                    val idx = row * 5 + col
                    val isCenter = (idx == 7)
                    val cell = TextView(this@MacroService).apply {
                        text = if (isCenter) "ME" else ""
                        textSize = 10f
                        setTypeface(null, Typeface.BOLD)
                        setTextColor(if (isCenter) Color.parseColor("#0D111A") else Color.WHITE)
                        gravity = Gravity.CENTER
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                            if (col > 0) leftMargin = dp(4)
                        }
                        if (!isCenter) {
                            setOnClickListener {
                                if (selectedTiles.contains(idx)) {
                                    selectedTiles.remove(idx)
                                } else {
                                    selectedTiles.add(idx)
                                }
                                refreshGridColors()
                            }
                        }
                    }
                    gridCells.add(cell)
                    rowLayout.addView(cell)
                }
                gridContainer.addView(rowLayout)
            }
            refreshGridColors()
            addView(gridContainer)

            // Toggle Switches (Langsung aktif saat digeser seperti Mod Menu)
            addView(makeToggleRow("Auto Break (Tahan Hancurkan)", autoBreakEnabled) { checked ->
                autoBreakEnabled = checked
                if (checked) autoPlaceEnabled = false
                evaluateLoopState()
            })

            addView(makeToggleRow("Auto Place (Tap Pasang Blok)", autoPlaceEnabled) { checked ->
                autoPlaceEnabled = checked
                if (checked) autoBreakEnabled = false
                evaluateLoopState()
            })

            addView(makeSlider("Durasi Break / Hold", breakHoldMs.toInt(), 50, 1200, "ms") {
                breakHoldMs = it.toLong()
            })

            addView(makeSlider("Delay Antar Blok", placeDelayMs.toInt(), 40, 1000, "ms") {
                placeDelayMs = it.toLong()
            })
        }
    }

    private fun createMovePage(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE

            addView(makeHint("Auto Patroli menyentuh sisi kiri & kanan layar secara otomatis tanpa ikon target."))

            addView(makeToggleRow("Auto Walk Left / Right", autoMoveEnabled) { checked ->
                autoMoveEnabled = checked
                evaluateLoopState()
            })

            addView(makeSlider("Durasi Jalan per Arah", moveHoldMs.toInt(), 300, 3500, "ms") {
                moveHoldMs = it.toLong()
            })
        }
    }

    private fun createSpamPage(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE

            addView(makeHint("Menyalin teks dari aplikasi utama dan mengirim otomatis ke kolom chat."))

            addView(makeToggleRow("Auto Spam Chat", autoSpamEnabled) { checked ->
                autoSpamEnabled = checked
                evaluateLoopState()
            })

            addView(makeSlider("Interval Spam", spamIntervalSec, 3, 30, "dtk") {
                spamIntervalSec = it
            })
        }
    }

    private fun createSettingPage(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE

            addView(makeSlider("Ukuran Tile (Sesuaikan Zoom)", tileSizePx, 40, 220, "px") {
                tileSizePx = it
            })

            addView(makeSlider("Random Delay (Anti-Ban)", randomDelayMs, 0, 250, "ms") {
                randomDelayMs = it
            })

            val btnCalibrate = Button(this@MacroService).apply {
                text = "KALIBRASI TITIK KARAKTER (3 DETIK)"
                textSize = 11f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#00E5FF"))
                background = makeBoxBg("#152033", "#00E5FF", 8, 1)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(36)
                ).apply { topMargin = dp(10) }
                setOnClickListener { showTemporaryCenterCalibration() }
            }
            addView(btnCalibrate)
        }
    }

    private fun showTemporaryCenterCalibration() {
        if (calibView != null) return
        calibView = TextView(this).apply {
            text = "⊕"
            textSize = 22f
            setTextColor(Color.parseColor("#00E5FF"))
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#6600E5FF"))
                setStroke(dp(2), Color.WHITE)
            }
        }

        calibParams = WindowManager.LayoutParams(
            dp(42),
            dp(42),
            getOverlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (centerX - dp(21)).toInt()
            y = (centerY - dp(21)).toInt()
        }

        makeDraggable(calibView!!, calibParams!!) {
            saveCalibrationAndHide()
        }
        windowManager?.addView(calibView, calibParams)
        Toast.makeText(this, "Geser tanda ⊕ tepat ke badan karakter, lalu ketuk tanda ⊕ untuk simpan!", Toast.LENGTH_LONG).show()
    }

    private fun saveCalibrationAndHide() {
        calibParams?.let { p ->
            centerX = p.x + dp(21).toFloat()
            centerY = p.y + dp(21).toFloat()
        }
        runCatching { windowManager?.removeView(calibView) }
        calibView = null
        calibParams = null
        Toast.makeText(this, "Posisi karakter tersimpan!", Toast.LENGTH_SHORT).show()
    }

    private fun refreshGridColors() {
        gridCells.forEachIndexed { idx, cell ->
            if (idx == 7) {
                cell.background = makeBoxBg("#00E5FF", "#00E5FF", 5, 1)
            } else if (selectedTiles.contains(idx)) {
                cell.background = makeBoxBg("#15803D", "#22C55E", 5, 1)
            } else {
                cell.background = makeBoxBg("#151B26", "#273244", 5, 1)
            }
        }
    }

    private fun evaluateLoopState() {
        val anyActive = autoBreakEnabled || autoPlaceEnabled || autoMoveEnabled || autoSpamEnabled
        if (anyActive) {
            tvHeaderStatus.text = "ACTIVE • RUNNING"
            tvHeaderStatus.setTextColor(Color.parseColor("#22C55E"))
            iconView?.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#E6064E3B"))
                setStroke(dp(2), Color.parseColor("#22C55E"))
            }
            if (!isLoopActive) {
                isLoopActive = true
                currentGridCursor = 0
                runAutomationCycle()
            }
        } else {
            disableAllFeatures()
        }
    }

    private fun disableAllFeatures() {
        isLoopActive = false
        autoBreakEnabled = false
        autoPlaceEnabled = false
        autoMoveEnabled = false
        autoSpamEnabled = false
        handler.removeCallbacksAndMessages(null)

        tvHeaderStatus.text = "IDLE • ZERO-TARGET MODE"
        tvHeaderStatus.setTextColor(Color.parseColor("#64748B"))
        iconView?.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#E60B0E14"))
            setStroke(dp(2), Color.parseColor("#00E5FF"))
        }
    }

    private fun runAutomationCycle() {
        if (!isLoopActive) return
        val jitter = if (randomDelayMs > 0) Random.nextLong(0, randomDelayMs.toLong() + 1) else 0L

        when {
            autoBreakEnabled || autoPlaceEnabled -> {
                if (selectedTiles.isEmpty()) {
                    handler.postDelayed({ runAutomationCycle() }, 300L)
                    return
                }
                val tileList = selectedTiles.toList()
                val tileIdx = tileList[currentGridCursor % tileList.size]
                currentGridCursor++

                val colOffset = (tileIdx % 5) - 2 // -2, -1, 0, +1, +2
                val rowOffset = (tileIdx / 5) - 1 // -1, 0, +1
                val targetX = centerX + (colOffset * tileSizePx)
                val targetY = centerY + (rowOffset * tileSizePx)

                val holdTime = if (autoBreakEnabled) breakHoldMs else 45L
                dispatchTapOrHold(targetX, targetY, holdTime) {
                    if (isLoopActive) {
                        handler.postDelayed({ runAutomationCycle() }, placeDelayMs + jitter)
                    }
                }
            }

            autoMoveEnabled -> {
                val dm = resources.displayMetrics
                val tapX = if (moveDirectionRight) dm.widthPixels * 0.22f else dm.widthPixels * 0.08f
                val tapY = dm.heightPixels * 0.82f
                moveDirectionRight = !moveDirectionRight

                dispatchTapOrHold(tapX, tapY, moveHoldMs) {
                    if (isLoopActive) {
                        handler.postDelayed({ runAutomationCycle() }, 150L + jitter)
                    }
                }
            }

            autoSpamEnabled -> {
                copySpamText()
                val dm = resources.displayMetrics
                // Urutan klik standar UI chat: Ikon Chat -> Kolom Input -> Tombol Kirim
                dispatchTapOrHold(dm.widthPixels * 0.12f, dm.heightPixels * 0.12f, 50L) {
                    handler.postDelayed({
                        dispatchTapOrHold(dm.widthPixels * 0.50f, dm.heightPixels * 0.50f, 50L) {
                            handler.postDelayed({
                                dispatchTapOrHold(dm.widthPixels * 0.88f, dm.heightPixels * 0.50f, 50L) {
                                    if (isLoopActive) {
                                        handler.postDelayed({ runAutomationCycle() }, (spamIntervalSec * 1000L) + jitter)
                                    }
                                }
                            }, 350L)
                        }
                    }, 350L)
                }
            }
        }
    }

    private fun copySpamText() {
        val prefs = getSharedPreferences("growblox_prefs", Context.MODE_PRIVATE)
        val msg = prefs.getString("spam_text", "Sell Cheap WL / Auto Farm Ready!") ?: return
        val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cb.setPrimaryClip(ClipData.newPlainText("GrowbloxSpam", msg))
    }

    private fun dispatchTapOrHold(x: Float, y: Float, durationMs: Long, onDone: () -> Unit) {
        val dm = resources.displayMetrics
        val safeX = x.coerceIn(10f, dm.widthPixels - 10f)
        val safeY = y.coerceIn(10f, dm.heightPixels - 10f)

        val path = Path().apply { moveTo(safeX, safeY) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs.coerceAtLeast(30L))
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        val sent = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) = onDone()
            override fun onCancelled(gestureDescription: GestureDescription?) = onDone()
        }, null)

        if (!sent) onDone()
    }

    private fun switchTab(index: Int) {
        val tabs = listOf(tabFarm, tabMove, tabSpam, tabSetting)
        val pages = listOf(pageFarm, pageMove, pageSpam, pageSetting)

        tabs.forEachIndexed { i, tv ->
            val selected = (i == index)
            tv.setTextColor(if (selected) Color.parseColor("#0D111A") else Color.parseColor("#94A3B8"))
            tv.background = if (selected) makeBoxBg("#00E5FF", "#00E5FF", 6, 1) else null
        }
        pages.forEachIndexed { i, view ->
            view.visibility = if (i == index) View.VISIBLE else View.GONE
        }
    }

    private fun makeTab(title: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = title
            textSize = 10f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(6))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { onClick() }
        }
    }

    private fun makeToggleRow(label: String, initial: Boolean, onToggle: (Boolean) -> Unit): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = makeBoxBg("#131824", "#222C3F", 8, 1)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }

            val tv = TextView(this@MacroService).apply {
                text = label
                textSize = 11f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            addView(tv)

            val sw = Switch(this@MacroService).apply {
                isChecked = initial
                setOnCheckedChangeListener { _, isChecked -> onToggle(isChecked) }
            }
            addView(sw)
        }
    }

    private fun makeSlider(
        title: String,
        initial: Int,
        min: Int,
        max: Int,
        unit: String,
        onChange: (Int) -> Unit
    ): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }

            val tv = TextView(this@MacroService).apply {
                text = "$title: $initial $unit"
                textSize = 11f
                setTextColor(Color.parseColor("#CBD5E1"))
            }
            addView(tv)

            val sb = SeekBar(this@MacroService).apply {
                this.max = max - min
                progress = (initial - min).coerceAtLeast(0)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                        val v = min + p
                        tv.text = "$title: $v $unit"
                        onChange(v)
                    }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
            }
            addView(sb)
        }
    }

    private fun makeHint(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 11f
            setTextColor(Color.parseColor("#94A3B8"))
        }
    }

    private fun makeBoxBg(fill: String, stroke: String, radiusDp: Int, strokeDp: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor(fill))
            cornerRadius = dp(radiusDp).toFloat()
            setStroke(dp(strokeDp), Color.parseColor(stroke))
        }
    }

    private fun makeDraggable(view: View, params: WindowManager.LayoutParams, onClick: (() -> Unit)?) {
        var initX = 0
        var initY = 0
        var touchX = 0f
        var touchY = 0f

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initX = params.x
                    initY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initX + (event.rawX - touchX).toInt()
                    params.y = initY + (event.rawY - touchY).toInt()
                    runCatching { windowManager?.updateViewLayout(view, params) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (abs(event.rawX - touchX) < 12 && abs(event.rawY - touchY) < 12) {
                        onClick?.invoke()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        disableAllFeatures()
        runCatching { windowManager?.removeView(iconView) }
        runCatching { windowManager?.removeView(menuView) }
        runCatching { windowManager?.removeView(calibView) }
        iconView = null
        menuView = null
        calibView = null
        super.onDestroy()
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
