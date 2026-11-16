package com.example.growbloxmacro

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
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
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import kotlin.random.Random

class MacroService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private lateinit var prefs: SharedPreferences

    // Floating Icon & Mod Menu
    private var iconView: TextView? = null
    private var iconParams: WindowManager.LayoutParams? = null
    private var menuView: LinearLayout? = null
    private var menuParams: WindowManager.LayoutParams? = null
    private var isMenuOpen = false

    // 5 Penanda Kalibrasi (Karakter, Slot Block, Slot Hand, Jalan Kiri, Jalan Maju/Kanan)
    private var isCalibrating = false
    private var markerChar: TextView? = null
    private var markerCharParams: WindowManager.LayoutParams? = null
    private var markerBlock: TextView? = null
    private var markerBlockParams: WindowManager.LayoutParams? = null
    private var markerHand: TextView? = null
    private var markerHandParams: WindowManager.LayoutParams? = null
    private var markerLeft: TextView? = null
    private var markerLeftParams: WindowManager.LayoutParams? = null
    private var markerRight: TextView? = null
    private var markerRightParams: WindowManager.LayoutParams? = null
    private lateinit var btnCalibrateAll: Button

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
    private lateinit var btnFarmModeCycle: Button
    private lateinit var btnStepDirCycle: Button
    private lateinit var btnStartStopFarm: Button
    private var swMove: Switch? = null
    private var swSpam: Switch? = null

    // Farm Mode: 0 = FULL AUTO (Put Block -> Hand -> Break -> Jeda -> Maju), 1 = BREAK ONLY, 2 = PLACE ONLY
    private var farmSubMode = 0
    // Arah Maju setelah Break: 1 = MAJU KANAN [▶], 2 = MAJU KIRI [◀], 0 = DIAM (OFF)
    private var stepAfterBreakDir = 1

    private var isFarmRunning = false
    private var isMoveRunning = false
    private var isSpamRunning = false

    // Grid Tile Selection (5 kolom x 3 baris, index 7 = pusat karakter [ME])
    private val selectedTiles = mutableSetOf<Int>()
    private val gridCells = mutableListOf<TextView>()

    // Antrean Langkah Berurutan
    private data class MacroStep(
        val x: Float,
        val y: Float,
        val holdMs: Long,
        val delayAfterMs: Long
    )
    private val stepQueue = mutableListOf<MacroStep>()
    private var currentStepIdx = 0

    // Koordinat Terkalibrasi (Lanskap)
    private var charX = 0f
    private var charY = 0f
    private var blockSlotX = 0f
    private var blockSlotY = 0f
    private var handSlotX = 0f
    private var handSlotY = 0f
    private var leftBtnX = 0f
    private var leftBtnY = 0f
    private var rightBtnX = 0f
    private var rightBtnY = 0f
    private var tileSizePx = 110

    // Parameter Timing
    private var punchHitsPerBlock = 4      // Jumlah pukulan per blok
    private var punchHoldMs = 150L         // Durasi tahan setiap pukulan
    private var actionDelayMs = 130L       // Jeda antar place / antar pukulan
    private var slotSwitchDelayMs = 200L   // Jeda ganti slot Block <-> Hand
    private var pauseAfterBreakMs = 250L   // Jeda setelah break sebelum mulai maju
    private var stepForwardHoldMs = 220L   // Durasi tahan tombol maju (jarak langkah)
    private var pauseAfterMoveMs = 150L    // Jeda setelah maju sebelum put block lagi
    private var moveHoldMs = 1000L
    private var spamIntervalSec = 5
    private var randomDelayMs = 30
    private var moveDirectionRight = true

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = getSharedPreferences("growblox_calib", Context.MODE_PRIVATE)
        loadSavedCalibration()
        initModMenu()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (iconView == null && Settings.canDrawOverlays(this)) {
            initModMenu()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val dm = resources.displayMetrics
        iconParams?.let { p ->
            p.x = p.x.coerceIn(0, (dm.widthPixels - dp(44)).coerceAtLeast(0))
            p.y = p.y.coerceIn(0, (dm.heightPixels - dp(44)).coerceAtLeast(0))
            runCatching { windowManager?.updateViewLayout(iconView, p) }
        }
        menuParams?.let { p ->
            p.x = p.x.coerceIn(0, (dm.widthPixels - dp(305)).coerceAtLeast(0))
            p.y = p.y.coerceIn(0, (dm.heightPixels - dp(250)).coerceAtLeast(0))
            runCatching { windowManager?.updateViewLayout(menuView, p) }
        }
    }

    override fun onInterrupt() {
        stopAllAutomation()
    }

    private fun loadSavedCalibration() {
        val dm = resources.displayMetrics
        val landW = maxOf(dm.widthPixels, dm.heightPixels).toFloat()
        val landH = minOf(dm.widthPixels, dm.heightPixels).toFloat()

        charX = prefs.getFloat("charX", landW * 0.50f)
        charY = prefs.getFloat("charY", landH * 0.50f)
        blockSlotX = prefs.getFloat("blockSlotX", landW * 0.44f)
        blockSlotY = prefs.getFloat("blockSlotY", landH * 0.90f)
        handSlotX = prefs.getFloat("handSlotX", landW * 0.36f)
        handSlotY = prefs.getFloat("handSlotY", landH * 0.90f)
        leftBtnX = prefs.getFloat("leftBtnX", landW * 0.08f)
        leftBtnY = prefs.getFloat("leftBtnY", landH * 0.82f)
        rightBtnX = prefs.getFloat("rightBtnX", landW * 0.21f)
        rightBtnY = prefs.getFloat("rightBtnY", landH * 0.82f)
        tileSizePx = prefs.getInt("tileSizePx", (landW * 0.055f).toInt().coerceAtLeast(55))
    }

    private fun createBaseLayoutParams(w: Int, h: Int, startX: Int, startY: Int): WindowManager.LayoutParams {
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        return WindowManager.LayoutParams(
            w,
            h,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = startX
            y = startY
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    private fun initModMenu() {
        if (!Settings.canDrawOverlays(this) || iconView != null) return
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        selectedTiles.clear()
        selectedTiles.add(8) // Default 1 kotak di depan kanan karakter

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

        iconParams = createBaseLayoutParams(dp(44), dp(44), dp(14), dp(24))
        makeDraggable(iconView!!, iconParams!!) {
            if (isFarmRunning || isMoveRunning || isSpamRunning) {
                stopAllAutomation()
                isMenuOpen = true
                menuView?.visibility = View.VISIBLE
                Toast.makeText(this, "Macro dihentikan!", Toast.LENGTH_SHORT).show()
            } else {
                isMenuOpen = !isMenuOpen
                menuView?.visibility = if (isMenuOpen) View.VISIBLE else View.GONE
            }
        }
        windowManager?.addView(iconView, iconParams)
    }

    private fun buildFloatingMenu() {
        menuView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = makeBoxBg("#F20D111A", "#00E5FF", 12, 1)
            setPadding(dp(10), dp(8), dp(10), dp(10))
            visibility = View.GONE
        }

        // Header Bar (Draggable)
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), 0, dp(2), dp(6))
        }

        val titleBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        titleBox.addView(TextView(this).apply {
            text = "GROWBLOX MOD (LANDSCAPE)"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#00E5FF"))
        })

        tvHeaderStatus = TextView(this).apply {
            text = "STANDBY • SIAP"
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
        tabSetting = makeTab("KALIBRASI") { switchTab(3) }

        tabBar.addView(tabFarm)
        tabBar.addView(tabMove)
        tabBar.addView(tabSpam)
        tabBar.addView(tabSetting)
        menuView?.addView(tabBar)

        // Scrollable Content Area
        val scrollContainer = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(200)
            ).apply { topMargin = dp(8) }
        }

        val pages = FrameLayout(this)
        pageFarm = createFarmPage()
        pageMove = createMovePage()
        pageSpam = createSpamPage()
        pageSetting = createSettingPage()

        pages.addView(pageFarm)
        pages.addView(pageMove)
        pages.addView(pageSpam)
        pages.addView(pageSetting)
        scrollContainer.addView(pages)
        menuView?.addView(scrollContainer)

        menuParams = createBaseLayoutParams(dp(305), WindowManager.LayoutParams.WRAP_CONTENT, dp(64), dp(14))
        makeDraggable(header, menuParams!!, null)
        windowManager?.addView(menuView, menuParams)
        switchTab(0)
    }

    private fun createFarmPage(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            // 5x3 Tile Grid Selector
            val gridContainer = LinearLayout(this@MacroService).apply {
                orientation = LinearLayout.VERTICAL
                background = makeBoxBg("#080B11", "#1E293B", 8, 1)
                setPadding(dp(5), dp(5), dp(5), dp(5))
            }

            gridCells.clear()
            for (row in 0 until 3) {
                val rowLayout = LinearLayout(this@MacroService).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(28)
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
                                if (selectedTiles.contains(idx)) selectedTiles.remove(idx)
                                else selectedTiles.add(idx)
                                refreshGridColors()
                                if (isFarmRunning) rebuildFarmQueue()
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

            // Tombol Reset Pilihan Grid
            val btnClearGrid = Button(this@MacroService).apply {
                text = "RESET PILIHAN KOTAK GRID"
                textSize = 10f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#F87171"))
                background = makeBoxBg("#1E151B", "#EF4444", 6, 1)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(28)
                ).apply { topMargin = dp(6) }
                setOnClickListener {
                    selectedTiles.clear()
                    refreshGridColors()
                    if (isFarmRunning) stopAllAutomation()
                }
            }
            addView(btnClearGrid)

            // Tombol Pilih Urutan Farm
            btnFarmModeCycle = Button(this@MacroService).apply {
                text = "URUTAN: PUT BLOCK -> BREAK -> JEDA -> MAJU"
                textSize = 10f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#00E5FF"))
                background = makeBoxBg("#131C2E", "#00E5FF", 8, 1)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(34)
                ).apply { topMargin = dp(6) }
                setOnClickListener {
                    farmSubMode = (farmSubMode + 1) % 3
                    text = when (farmSubMode) {
                        0 -> "URUTAN: PUT BLOCK -> BREAK -> JEDA -> MAJU"
                        1 -> "URUTAN: HANYA BREAK -> JEDA -> MAJU"
                        else -> "URUTAN: HANYA PUT BLOCK -> JEDA -> MAJU"
                    }
                    if (isFarmRunning) rebuildFarmQueue()
                }
            }
            addView(btnFarmModeCycle)

            // Tombol Pilih Arah Maju Setelah Break
            btnStepDirCycle = Button(this@MacroService).apply {
                text = "ARAH MAJU SETELAH BREAK: KANAN [▶]"
                textSize = 10f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#FBBF24"))
                background = makeBoxBg("#231C11", "#F59E0B", 8, 1)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(34)
                ).apply { topMargin = dp(6) }
                setOnClickListener {
                    stepAfterBreakDir = (stepAfterBreakDir + 1) % 3
                    text = when (stepAfterBreakDir) {
                        1 -> "ARAH MAJU SETELAH BREAK: KANAN [▶]"
                        2 -> "ARAH MAJU SETELAH BREAK: KIRI [◀]"
                        else -> "ARAH MAJU SETELAH BREAK: DIAM (OFF)"
                    }
                    if (isFarmRunning) rebuildFarmQueue()
                }
            }
            addView(btnStepDirCycle)

            // Tombol Start / Stop Farm
            btnStartStopFarm = Button(this@MacroService).apply {
                text = "▶ MULAI AUTO FARM"
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.WHITE)
                background = makeBoxBg("#15803D", "#22C55E", 8, 1)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(36)
                ).apply { topMargin = dp(6) }
                setOnClickListener {
                    if (isFarmRunning) {
                        stopAllAutomation()
                    } else {
                        startFarmAutomation()
                    }
                }
            }
            addView(btnStartStopFarm)

            addView(makeSlider("Jeda Setelah Break (Sebelum Maju)", pauseAfterBreakMs.toInt(), 50, 1500, "ms") {
                pauseAfterBreakMs = it.toLong()
                if (isFarmRunning) rebuildFarmQueue()
            })

            addView(makeSlider("Durasi Tekan Maju (Jarak Langkah)", stepForwardHoldMs.toInt(), 50, 1500, "ms") {
                stepForwardHoldMs = it.toLong()
                if (isFarmRunning) rebuildFarmQueue()
            })

            addView(makeSlider("Jumlah Pukulan per Blok", punchHitsPerBlock, 1, 12, "x") {
                punchHitsPerBlock = it
                if (isFarmRunning) rebuildFarmQueue()
            })

            addView(makeSlider("Durasi Tahan Punch", punchHoldMs.toInt(), 50, 800, "ms") {
                punchHoldMs = it.toLong()
                if (isFarmRunning) rebuildFarmQueue()
            })

            addView(makeSlider("Jeda Ganti Slot (Block <-> Hand)", slotSwitchDelayMs.toInt(), 80, 600, "ms") {
                slotSwitchDelayMs = it.toLong()
                if (isFarmRunning) rebuildFarmQueue()
            })

            addView(makeSlider("Jeda Antar Aksi", actionDelayMs.toInt(), 50, 600, "ms") {
                actionDelayMs = it.toLong()
                if (isFarmRunning) rebuildFarmQueue()
            })
        }
    }

    private fun createMovePage(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE

            addView(makeHint("Auto Patroli menyentuh posisi tombol jalan Kiri [◀] & Kanan [▶] yang sudah dikalibrasi."))

            val row = makeToggleRow("Auto Walk Left / Right", isMoveRunning) { checked ->
                if (checked) {
                    stopAllAutomation()
                    isMoveRunning = true
                    swMove?.isChecked = true
                    isMenuOpen = false
                    menuView?.visibility = View.GONE
                    updateRunningUI("AUTO MOVE")
                    runMoveLoop()
                } else if (isMoveRunning) {
                    stopAllAutomation()
                }
            }
            swMove = row.getChildAt(1) as Switch
            addView(row)

            addView(makeSlider("Durasi Jalan per Arah", moveHoldMs.toInt(), 300, 3500, "ms") {
                moveHoldMs = it.toLong()
            })
        }
    }

    private fun createSpamPage(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE

            addView(makeHint("Menyalin teks dari aplikasi utama dan mengirim otomatis ke chat."))

            val row = makeToggleRow("Auto Spam Chat", isSpamRunning) { checked ->
                if (checked) {
                    stopAllAutomation()
                    isSpamRunning = true
                    swSpam?.isChecked = true
                    isMenuOpen = false
                    menuView?.visibility = View.GONE
                    updateRunningUI("AUTO SPAM")
                    runSpamLoop()
                } else if (isSpamRunning) {
                    stopAllAutomation()
                }
            }
            swSpam = row.getChildAt(1) as Switch
            addView(row)

            addView(makeSlider("Interval Spam", spamIntervalSec, 3, 30, "dtk") {
                spamIntervalSec = it
            })
        }
    }

    private fun createSettingPage(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE

            addView(makeHint("1. Tekan tombol di bawah.\n2. Geser [⊕] ke karakter, [BLK] ke slot block, [HND] ke slot tinju, serta [◀] & [▶] ke tombol jalan.\n3. Tekan SIMPAN."))

            btnCalibrateAll = Button(this@MacroService).apply {
                text = "ATUR POSISI [⊕ | BLK | HND | ◀ | ▶]"
                textSize = 11f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#0D111A"))
                background = makeBoxBg("#00E5FF", "#00E5FF", 8, 1)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(38)
                ).apply { topMargin = dp(8) }
                setOnClickListener {
                    if (!isCalibrating) showCalibrationMarkers()
                    else saveAndHideCalibrationMarkers()
                }
            }
            addView(btnCalibrateAll)

            addView(makeSlider("Ukuran Kotak Blok (Zoom)", tileSizePx, 35, 220, "px") {
                tileSizePx = it
                prefs.edit().putInt("tileSizePx", tileSizePx).apply()
                if (isFarmRunning) rebuildFarmQueue()
            })

            addView(makeSlider("Random Delay (Anti-Ban)", randomDelayMs, 0, 200, "ms") {
                randomDelayMs = it
            })
        }
    }

    // --- SISTEM KALIBRASI 5 TITIK AKURAT BERBASIS getLocationOnScreen() ---
    private fun showCalibrationMarkers() {
        if (isCalibrating) return
        stopAllAutomation()
        isCalibrating = true

        btnCalibrateAll.text = "✔ SIMPAN SEMUA POSISI KALIBRASI"
        btnCalibrateAll.background = makeBoxBg("#22C55E", "#22C55E", 8, 1)

        val size = dp(40)
        val half = size / 2

        markerChar = makeMarkerBadge("⊕", "#00E5FF", "#990E7490")
        markerCharParams = createBaseLayoutParams(size, size, (charX - half).toInt(), (charY - half).toInt())
        makeDraggable(markerChar!!, markerCharParams!!, null)
        windowManager?.addView(markerChar, markerCharParams)

        markerBlock = makeMarkerBadge("BLK", "#F59E0B", "#9992400E")
        markerBlockParams = createBaseLayoutParams(size, size, (blockSlotX - half).toInt(), (blockSlotY - half).toInt())
        makeDraggable(markerBlock!!, markerBlockParams!!, null)
        windowManager?.addView(markerBlock, markerBlockParams)

        markerHand = makeMarkerBadge("HND", "#22C55E", "#99166534")
        markerHandParams = createBaseLayoutParams(size, size, (handSlotX - half).toInt(), (handSlotY - half).toInt())
        makeDraggable(markerHand!!, markerHandParams!!, null)
        windowManager?.addView(markerHand, markerHandParams)

        markerLeft = makeMarkerBadge("◀", "#A855F7", "#99581C87")
        markerLeftParams = createBaseLayoutParams(size, size, (leftBtnX - half).toInt(), (leftBtnY - half).toInt())
        makeDraggable(markerLeft!!, markerLeftParams!!, null)
        windowManager?.addView(markerLeft, markerLeftParams)

        markerRight = makeMarkerBadge("▶", "#EC4899", "#99831843")
        markerRightParams = createBaseLayoutParams(size, size, (rightBtnX - half).toInt(), (rightBtnY - half).toInt())
        makeDraggable(markerRight!!, markerRightParams!!, null)
        windowManager?.addView(markerRight, markerRightParams)

        Toast.makeText(this, "Geser ⊕, BLK, HND, ◀, dan ▶ ke posisinya lalu tekan SIMPAN!", Toast.LENGTH_LONG).show()
    }

    private fun saveAndHideCalibrationMarkers() {
        if (!isCalibrating) return

        markerChar?.let { v ->
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            charX = loc[0] + v.width / 2f
            charY = loc[1] + v.height / 2f
        }
        markerBlock?.let { v ->
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            blockSlotX = loc[0] + v.width / 2f
            blockSlotY = loc[1] + v.height / 2f
        }
        markerHand?.let { v ->
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            handSlotX = loc[0] + v.width / 2f
            handSlotY = loc[1] + v.height / 2f
        }
        markerLeft?.let { v ->
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            leftBtnX = loc[0] + v.width / 2f
            leftBtnY = loc[1] + v.height / 2f
        }
        markerRight?.let { v ->
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            rightBtnX = loc[0] + v.width / 2f
            rightBtnY = loc[1] + v.height / 2f
        }

        prefs.edit()
            .putFloat("charX", charX)
            .putFloat("charY", charY)
            .putFloat("blockSlotX", blockSlotX)
            .putFloat("blockSlotY", blockSlotY)
            .putFloat("handSlotX", handSlotX)
            .putFloat("handSlotY", handSlotY)
            .putFloat("leftBtnX", leftBtnX)
            .putFloat("leftBtnY", leftBtnY)
            .putFloat("rightBtnX", rightBtnX)
            .putFloat("rightBtnY", rightBtnY)
            .apply()

        removeCalibrationMarkers()
        Toast.makeText(this, "Semua posisi kalibrasi berhasil disimpan!", Toast.LENGTH_SHORT).show()
    }

    private fun removeCalibrationMarkers() {
        isCalibrating = false
        runCatching { windowManager?.removeView(markerChar) }
        runCatching { windowManager?.removeView(markerBlock) }
        runCatching { windowManager?.removeView(markerHand) }
        runCatching { windowManager?.removeView(markerLeft) }
        runCatching { windowManager?.removeView(markerRight) }
        markerChar = null
        markerBlock = null
        markerHand = null
        markerLeft = null
        markerRight = null

        if (::btnCalibrateAll.isInitialized) {
            btnCalibrateAll.text = "ATUR POSISI [⊕ | BLK | HND | ◀ | ▶]"
            btnCalibrateAll.background = makeBoxBg("#00E5FF", "#00E5FF", 8, 1)
        }
    }

    private fun makeMarkerBadge(label: String, strokeHex: String, fillHex: String): TextView {
        return TextView(this).apply {
            text = label
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor(fillHex))
                setStroke(dp(2), Color.parseColor(strokeHex))
            }
        }
    }

    // --- MESIN EKSEKUSI: PUT BLOCK -> BREAK -> JEDA -> MAJU ---
    private fun startFarmAutomation() {
        if (selectedTiles.isEmpty()) {
            Toast.makeText(this, "Pilih minimal 1 kotak blok di grid!", Toast.LENGTH_SHORT).show()
            return
        }
        if (isCalibrating) saveAndHideCalibrationMarkers()
        stopAllAutomation()

        isMenuOpen = false
        menuView?.visibility = View.GONE

        isFarmRunning = true
        btnStartStopFarm.text = "⏹ HENTIKAN AUTO FARM"
        btnStartStopFarm.background = makeBoxBg("#991B1B", "#EF4444", 8, 1)
        updateRunningUI("AUTO FARM")

        rebuildFarmQueue()
        currentStepIdx = 0
        executeFarmQueueStep()
    }

    private fun rebuildFarmQueue() {
        stepQueue.clear()
        val tiles = selectedTiles.toList()
        if (tiles.isEmpty()) return

        when (farmSubMode) {
            0 -> {
                // 1. Pilih Slot Block di Hotbar
                stepQueue.add(MacroStep(blockSlotX, blockSlotY, 55L, slotSwitchDelayMs))

                // 2. Taruh (Put) Block di semua kotak grid yang dipilih
                for (tileIdx in tiles) {
                    val (tx, ty) = getTileScreenCoord(tileIdx)
                    stepQueue.add(MacroStep(tx, ty, 55L, actionDelayMs))
                }

                // 3. Ganti ke Slot Hand / Fist di Hotbar
                stepQueue.add(MacroStep(handSlotX, handSlotY, 55L, slotSwitchDelayMs))

                // 4. Break (Punch) semua kotak grid sebanyak punchHitsPerBlock
                for (hit in 0 until punchHitsPerBlock) {
                    for (tIdx in tiles.indices) {
                        val (tx, ty) = getTileScreenCoord(tiles[tIdx])
                        val isLastPunch = (hit == punchHitsPerBlock - 1) && (tIdx == tiles.size - 1)
                        // Jika ini pukulan terakhir dan mode Maju aktif, beri jeda pauseAfterBreakMs
                        val delayAfter = if (isLastPunch && stepAfterBreakDir != 0) {
                            pauseAfterBreakMs
                        } else {
                            actionDelayMs
                        }
                        stepQueue.add(MacroStep(tx, ty, punchHoldMs, delayAfter))
                    }
                }

                // 5. Setelah jeda break selesai, tekan tombol Maju (Kanan atau Kiri)
                appendStepForwardCommand()
            }

            1 -> {
                // Mode Hanya Break -> Jeda -> Maju
                for (hit in 0 until punchHitsPerBlock) {
                    for (tIdx in tiles.indices) {
                        val (tx, ty) = getTileScreenCoord(tiles[tIdx])
                        val isLastPunch = (hit == punchHitsPerBlock - 1) && (tIdx == tiles.size - 1)
                        val delayAfter = if (isLastPunch && stepAfterBreakDir != 0) {
                            pauseAfterBreakMs
                        } else {
                            actionDelayMs
                        }
                        stepQueue.add(MacroStep(tx, ty, punchHoldMs, delayAfter))
                    }
                }
                appendStepForwardCommand()
            }

            2 -> {
                // Mode Hanya Put Block -> Jeda -> Maju
                for (tIdx in tiles.indices) {
                    val (tx, ty) = getTileScreenCoord(tiles[tIdx])
                    val isLastPlace = (tIdx == tiles.size - 1)
                    val delayAfter = if (isLastPlace && stepAfterBreakDir != 0) {
                        pauseAfterBreakMs
                    } else {
                        actionDelayMs
                    }
                    stepQueue.add(MacroStep(tx, ty, 55L, delayAfter))
                }
                appendStepForwardCommand()
            }
        }
    }

    private fun appendStepForwardCommand() {
        when (stepAfterBreakDir) {
            1 -> {
                // Maju ke Kanan [▶]
                stepQueue.add(MacroStep(rightBtnX, rightBtnY, stepForwardHoldMs, pauseAfterMoveMs))
            }
            2 -> {
                // Maju ke Kiri [◀]
                stepQueue.add(MacroStep(leftBtnX, leftBtnY, stepForwardHoldMs, pauseAfterMoveMs))
            }
        }
    }

    private fun getTileScreenCoord(tileIdx: Int): Pair<Float, Float> {
        val colOffset = (tileIdx % 5) - 2 // -2, -1, 0, +1, +2
        val rowOffset = (tileIdx / 5) - 1 // -1, 0, +1
        val tx = charX + (colOffset * tileSizePx)
        val ty = charY + (rowOffset * tileSizePx)
        return Pair(tx, ty)
    }

    private fun executeFarmQueueStep() {
        if (!isFarmRunning || stepQueue.isEmpty()) return

        val step = stepQueue[currentStepIdx % stepQueue.size]
        currentStepIdx = (currentStepIdx + 1) % stepQueue.size

        val jitter = if (randomDelayMs > 0) Random.nextLong(0, randomDelayMs.toLong() + 1) else 0L

        dispatchTapOrHold(step.x, step.y, step.holdMs) {
            if (isFarmRunning) {
                handler.postDelayed({ executeFarmQueueStep() }, step.delayAfterMs + jitter)
            }
        }
    }

    private fun runMoveLoop() {
        if (!isMoveRunning) return
        val jitter = if (randomDelayMs > 0) Random.nextLong(0, randomDelayMs.toLong() + 1) else 0L

        val tapX = if (moveDirectionRight) rightBtnX else leftBtnX
        val tapY = if (moveDirectionRight) rightBtnY else leftBtnY
        moveDirectionRight = !moveDirectionRight

        dispatchTapOrHold(tapX, tapY, moveHoldMs) {
            if (isMoveRunning) {
                handler.postDelayed({ runMoveLoop() }, 150L + jitter)
            }
        }
    }

    private fun runSpamLoop() {
        if (!isSpamRunning) return
        val dm = resources.displayMetrics
        val jitter = if (randomDelayMs > 0) Random.nextLong(0, randomDelayMs.toLong() + 1) else 0L

        val appPrefs = getSharedPreferences("growblox_prefs", Context.MODE_PRIVATE)
        val msg = appPrefs.getString("spam_text", "Sell Cheap WL / Auto Farm Ready!") ?: ""
        val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cb.setPrimaryClip(ClipData.newPlainText("GrowbloxSpam", msg))

        dispatchTapOrHold(dm.widthPixels * 0.12f, dm.heightPixels * 0.12f, 50L) {
            handler.postDelayed({
                dispatchTapOrHold(dm.widthPixels * 0.50f, dm.heightPixels * 0.50f, 50L) {
                    handler.postDelayed({
                        dispatchTapOrHold(dm.widthPixels * 0.88f, dm.heightPixels * 0.50f, 50L) {
                            if (isSpamRunning) {
                                handler.postDelayed({ runSpamLoop() }, (spamIntervalSec * 1000L) + jitter)
                            }
                        }
                    }, 350L)
                }
            }, 350L)
        }
    }

    private fun updateRunningUI(modeLabel: String) {
        tvHeaderStatus.text = "RUNNING • $modeLabel"
        tvHeaderStatus.setTextColor(Color.parseColor("#22C55E"))
        iconView?.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#E6064E3B"))
            setStroke(dp(2), Color.parseColor("#22C55E"))
        }
    }

    private fun stopAllAutomation() {
        isFarmRunning = false
        isMoveRunning = false
        isSpamRunning = false
        handler.removeCallbacksAndMessages(null)

        swMove?.isChecked = false
        swSpam?.isChecked = false

        if (::btnStartStopFarm.isInitialized) {
            btnStartStopFarm.text = "▶ MULAI AUTO FARM"
            btnStartStopFarm.background = makeBoxBg("#15803D", "#22C55E", 8, 1)
        }

        tvHeaderStatus.text = "STANDBY • SIAP"
        tvHeaderStatus.setTextColor(Color.parseColor("#64748B"))
        iconView?.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#E60B0E14"))
            setStroke(dp(2), Color.parseColor("#00E5FF"))
        }
    }

    private fun dispatchTapOrHold(x: Float, y: Float, durationMs: Long, onDone: () -> Unit) {
        val dm = resources.displayMetrics
        val safeX = x.coerceIn(5f, (dm.widthPixels - 5).toFloat())
        val safeY = y.coerceIn(5f, (dm.heightPixels - 5).toFloat())

        val path = Path().apply { moveTo(safeX, safeY) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs.coerceAtLeast(35L))
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        val sent = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) = onDone()
            override fun onCancelled(gestureDescription: GestureDescription?) = onDone()
        }, null)

        if (!sent) onDone()
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
            setPadding(0, dp(5), 0, dp(5))
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
        stopAllAutomation()
        removeCalibrationMarkers()
        runCatching { windowManager?.removeView(iconView) }
        runCatching { windowManager?.removeView(menuView) }
        iconView = null
        menuView = null
        super.onDestroy()
    }
}
