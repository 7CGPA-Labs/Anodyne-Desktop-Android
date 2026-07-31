package com.anodyne.desktop

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.hardware.display.DisplayManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    data class TabItem(
        val id: String,
        val url: String,
        var title: String,
        val webView: WebView
    )

    private val tabsList = mutableListOf<TabItem>()
    private var currentTabIndex = -1

    private lateinit var workspaceContainer: FrameLayout
    private lateinit var rootLayout: LinearLayout
    private lateinit var tabScroll: HorizontalScrollView
    private lateinit var tabContainer: LinearLayout
    private lateinit var webViewContainer: FrameLayout

    // Cursor & Touchpad UI elements (Covering the entire screen)
    private lateinit var touchpadOverlay: TouchpadLayout
    private lateinit var cursorView: ImageView
    private var cursorX = 0f
    private var cursorY = 0f
    private var isTrackpadMode = false

    // Casting Touchpad Overlay
    private lateinit var castingTrackpad: TouchpadLayout
    private var isCasting = false

    // Access PIN for remote desktop
    private val accessPin = (100000..999999).random().toString()
    private var remoteServer: DesktopRemoteServer? = null

    // Top Bar UI elements
    private lateinit var topBar: LinearLayout
    private lateinit var wifiTextView: TextView
    private lateinit var batteryTextView: TextView
    private lateinit var clockTextView: TextView

    private lateinit var displayManager: DisplayManager
    private var presentation: DesktopPresentation? = null

    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockRunnable = object : Runnable {
        override fun run() {
            updateClockAndStatus()
            clockHandler.postDelayed(this, 10000)
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            Log.d(TAG, "Display added: $displayId")
            updatePresentation()
        }

        override fun onDisplayRemoved(displayId: Int) {
            Log.d(TAG, "Display removed: $displayId")
            updatePresentation()
        }

        override fun onDisplayChanged(displayId: Int) {
            Log.d(TAG, "Display changed: $displayId")
            updatePresentation()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        supportActionBar?.hide()

        window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )

        workspaceContainer = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#050508"))
        }

        // 1. Unified QML TopBar Reproduction
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(30)
            )
            setBackgroundColor(Color.parseColor("#0c0c14"))
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(14), 0, dpToPx(14), 0)
        }

        val logoText = TextView(this).apply {
            text = "⬡"
            setTextColor(Color.parseColor("#f8fafc"))
            textSize = 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setOnClickListener {
                openOrSwitchTab("home", "file:///android_asset/homepage/index.html", "Dashboard")
            }
        }
        topBar.addView(logoText)

        val addMenu = { title: String, isActive: Boolean ->
            val menu = TextView(this).apply {
                text = "  $title"
                setTextColor(Color.parseColor(if (isActive) "#f8fafc" else "#94a3b8"))
                textSize = 11f
                if (isActive) typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            topBar.addView(menu)
        }
        addMenu("Anodyne", true)
        addMenu("File", false)
        addMenu("Edit", false)

        val spacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
        }
        topBar.addView(spacer)

        // Remote Access PIN Label
        val pinLabel = TextView(this).apply {
            text = "PIN: $accessPin"
            setTextColor(Color.parseColor("#a855f7"))
            textSize = 11f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, 0, dpToPx(12), 0)
        }
        topBar.addView(pinLabel)

        // Mouse Mode Toggle
        val modeToggle = TextView(this).apply {
            text = "📱 Touch"
            setTextColor(Color.parseColor("#f8fafc"))
            textSize = 11f
            setPadding(dpToPx(8), dpToPx(2), dpToPx(8), dpToPx(2))
            setBackgroundColor(Color.parseColor("#1a1a24"))
            setOnClickListener {
                if (isTrackpadMode) {
                    text = "📱 Touch"
                    isTrackpadMode = false
                    touchpadOverlay.visibility = View.GONE
                    cursorView.visibility = View.GONE
                } else {
                    text = "🖱️ Trackpad"
                    isTrackpadMode = true
                    touchpadOverlay.visibility = View.VISIBLE
                    cursorView.visibility = View.VISIBLE
                    cursorX = workspaceContainer.width / 2f
                    cursorY = workspaceContainer.height / 2f
                    updateCursorViewPosition()
                }
            }
        }
        topBar.addView(modeToggle)

        wifiTextView = TextView(this).apply {
            text = "Wi-Fi"
            setTextColor(Color.parseColor("#94a3b8"))
            textSize = 11f
            setPadding(dpToPx(12), 0, 0, 0)
        }
        topBar.addView(wifiTextView)

        batteryTextView = TextView(this).apply {
            text = "100%"
            setTextColor(Color.parseColor("#94a3b8"))
            textSize = 11f
            setPadding(dpToPx(8), 0, dpToPx(8), 0)
        }
        topBar.addView(batteryTextView)

        clockTextView = TextView(this).apply {
            setTextColor(Color.parseColor("#f8fafc"))
            textSize = 11f
        }
        topBar.addView(clockTextView)

        rootLayout.addView(topBar)

        rootLayout.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
            setBackgroundColor(Color.parseColor("#1a1a24"))
        })

        // 2. Tab Bar
        tabScroll = HorizontalScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(40)
            )
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(Color.parseColor("#0c0c14"))
        }

        tabContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        tabScroll.addView(tabContainer)
        rootLayout.addView(tabScroll)

        rootLayout.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
            setBackgroundColor(Color.parseColor("#1a1a24"))
        })

        // 3. Web viewport frame layout
        webViewContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        rootLayout.addView(webViewContainer)
        workspaceContainer.addView(rootLayout)

        // 4. Touchpad overlay covering the whole screen
        touchpadOverlay = TouchpadLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            visibility = View.GONE
        }
        workspaceContainer.addView(touchpadOverlay)

        // Programmatically drawn mouse arrow cursor overlay
        cursorView = ImageView(this).apply {
            val bmp = Bitmap.createBitmap(16, 24, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val paint = Paint().apply {
                color = Color.WHITE
                style = Paint.Style.FILL
                isAntiAlias = true
            }
            val strokePaint = Paint().apply {
                color = Color.BLACK
                style = Paint.Style.STROKE
                strokeWidth = 2f
                isAntiAlias = true
            }
            val path = Path().apply {
                moveTo(0f, 0f)
                lineTo(16f, 16f)
                lineTo(9f, 16f)
                lineTo(14f, 24f)
                lineTo(11f, 24f)
                lineTo(6f, 16f)
                lineTo(0f, 20f)
                close()
            }
            canvas.drawPath(path, paint)
            canvas.drawPath(path, strokePaint)

            setImageBitmap(bmp)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            visibility = View.GONE
        }
        workspaceContainer.addView(cursorView)

        setContentView(workspaceContainer)

        // Initialize default Home tab
        openOrSwitchTab("home", "file:///android_asset/homepage/index.html", "Dashboard")

        // Start clock status timer
        clockHandler.post(clockRunnable)

        // Launch HTTP remote desktop server on port 8080
        remoteServer = DesktopRemoteServer(
            port = 8080,
            getPin = { accessPin },
            getActiveWebView = { getActiveWebView() },
            handleRemoteInput = { type, rx, ry ->
                runOnUiThread {
                    val webView = getActiveWebView() ?: return@runOnUiThread
                    val cx = rx * webView.width
                    val cy = ry * webView.height
                    
                    if (type == "left_click" || type == "right_click") {
                        val isRight = (type == "right_click")
                        if (isRight) {
                            webView.evaluateJavascript(
                                "var el = document.elementFromPoint($cx, $cy); if (el) { el.dispatchEvent(new MouseEvent('contextmenu', { bubbles: true, cancelable: true, view: window, button: 2, clientX: $cx, clientY: $cy })); }",
                                null
                            )
                        } else {
                            val downTime = SystemClock.uptimeMillis()
                            val eventTime = SystemClock.uptimeMillis()
                            val downEvent = MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_DOWN, cx, cy, 0).apply {
                                source = InputDevice.SOURCE_MOUSE
                            }
                            val upEvent = MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_UP, cx, cy, 0).apply {
                                source = InputDevice.SOURCE_MOUSE
                            }
                            webView.dispatchTouchEvent(downEvent)
                            webView.dispatchTouchEvent(upEvent)
                            downEvent.recycle()
                            upEvent.recycle()
                        }
                    } else if (type == "move") {
                        val downTime = SystemClock.uptimeMillis()
                        val eventTime = SystemClock.uptimeMillis()
                        val hoverEvent = MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_HOVER_MOVE, cx, cy, 0).apply {
                            source = InputDevice.SOURCE_MOUSE
                        }
                        webView.dispatchGenericMotionEvent(hoverEvent)
                        hoverEvent.recycle()
                    }
                }
            }
        ).apply {
            start()
        }

        // Register display listener for casting monitors
        displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        displayManager.registerDisplayListener(displayListener, null)
        updatePresentation()
    }

    private fun enterCastingTouchpad() {
        if (isCasting) return
        isCasting = true

        rootLayout.visibility = View.GONE

        castingTrackpad = TouchpadLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        val infoLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER
            }
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val touchpadText = TextView(this).apply {
            text = "Anodyne Casting Controller\n[ AMOLED Power Saving Mode ]"
            setTextColor(Color.parseColor("#475569"))
            textSize = 14f
            gravity = Gravity.CENTER
        }
        infoLayout.addView(touchpadText)

        val pinText = TextView(this).apply {
            text = "Access PIN: $accessPin"
            setTextColor(Color.parseColor("#a855f7"))
            textSize = 18f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, dpToPx(16), 0, 0)
        }
        infoLayout.addView(pinText)

        castingTrackpad.addView(infoLayout)
        workspaceContainer.addView(castingTrackpad)
    }

    private fun exitCastingTouchpad() {
        if (!isCasting) return
        isCasting = false
        workspaceContainer.removeView(castingTrackpad)
        rootLayout.visibility = View.VISIBLE
    }

    private fun updateClockAndStatus() {
        val sdf = SimpleDateFormat("EEE MMM d  H:mm", Locale.US)
        clockTextView.text = sdf.format(Date())

        try {
            val batteryManager = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val pct = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            batteryTextView.text = "$pct%"

            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val info = wifiManager.connectionInfo
            if (info != null && info.ssid != null && info.ssid != "<unknown ssid>" && info.ssid.isNotEmpty()) {
                val ssid = info.ssid
                wifiTextView.text = if (ssid.startsWith("\"") && ssid.endsWith("\"")) ssid.substring(1, ssid.length - 1) else ssid
            } else {
                wifiTextView.text = "Wi-Fi"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating status metrics", e)
        }
    }

    private fun createTabWebView(url: String): WebView {
        val newWebView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            visibility = View.GONE
            setLayerType(View.LAYER_TYPE_HARDWARE, null)

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    Log.d(TAG, "Page finished loading: $url")

                    val targetWidth = 1280
                    view?.evaluateJavascript(
                        """
                        (function() {
                            var meta = document.querySelector('meta[name=viewport]');
                            if (!meta) {
                                meta = document.createElement('meta');
                                meta.name = 'viewport';
                                document.head.appendChild(meta);
                            }
                            meta.setAttribute('content', 'width=$targetWidth, initial-scale=' + (window.screen.width / $targetWidth) + ', minimum-scale=0.1, maximum-scale=2.0');
                            
                            var style = document.getElementById('zoom-style');
                            if (!style) {
                                style = document.createElement('style');
                                style.id = 'zoom-style';
                                style.innerHTML = 'html { zoom: var(--ui-scale, 1.0); }';
                                document.head.appendChild(style);
                            }
                            document.documentElement.style.setProperty('--ui-scale', '0.75');
                        })();
                        """.trimIndent(),
                        null
                    )
                }
            }
        }

        newWebView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = false
            displayZoomControls = false
            cacheMode = WebSettings.LOAD_DEFAULT
        }

        val sysContext = AndroidSysContext(this,
            launchTabCallback = { appId, targetUrl, title ->
                openOrSwitchTab(appId, targetUrl, title)
            },
            evaluateJs = { js ->
                runOnUiThread {
                    newWebView.evaluateJavascript(js, null)
                }
            }
        )
        newWebView.addJavascriptInterface(sysContext, "sysContext")
        newWebView.loadUrl(url)

        return newWebView
    }

    fun openOrSwitchTab(id: String, url: String, title: String) {
        runOnUiThread {
            val existingIndex = tabsList.indexOfFirst { it.id == id }
            if (existingIndex != -1) {
                switchTab(existingIndex)
            } else {
                val newWebView = createTabWebView(url)
                webViewContainer.addView(newWebView)

                val newTab = TabItem(id, url, title, newWebView)
                tabsList.add(newTab)

                switchTab(tabsList.size - 1)
            }
        }
    }

    private fun switchTab(index: Int) {
        if (index !in tabsList.indices) return

        if (currentTabIndex in tabsList.indices) {
            tabsList[currentTabIndex].webView.visibility = View.GONE
        }

        currentTabIndex = index

        val activeTab = tabsList[currentTabIndex]
        activeTab.webView.visibility = View.VISIBLE

        refreshTabUI()
    }

    private fun closeTab(tab: TabItem) {
        if (tab.id == "home") return

        val index = tabsList.indexOf(tab)
        if (index == -1) return

        webViewContainer.removeView(tab.webView)
        tab.webView.destroy()
        tabsList.removeAt(index)

        if (currentTabIndex >= tabsList.size) {
            currentTabIndex = tabsList.size - 1
        } else if (currentTabIndex > index) {
            currentTabIndex--
        }

        switchTab(currentTabIndex)
    }

    private fun refreshTabUI() {
        tabContainer.removeAllViews()

        for (i in 0 until tabsList.size) {
            val tab = tabsList[i]
            val isActive = (i == currentTabIndex)

            val tabItem = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    dpToPx(130),
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                setBackgroundColor(Color.parseColor(if (isActive) "#161622" else "#08080f"))
                setOnClickListener {
                    switchTab(i)
                }
            }

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dpToPx(10), 0, dpToPx(6), 0)
            }

            val titleText = TextView(this).apply {
                text = tab.title
                setTextColor(Color.parseColor(if (isActive) "#f8fafc" else "#94a3b8"))
                textSize = 12f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
                )
            }
            row.addView(titleText)

            if (tab.id != "home") {
                val closeBtn = TextView(this).apply {
                    text = " × "
                    setTextColor(Color.parseColor("#64748b"))
                    textSize = 15f
                    gravity = Gravity.CENTER
                    setPadding(dpToPx(4), dpToPx(2), dpToPx(4), dpToPx(2))
                    setOnClickListener {
                        closeTab(tab)
                    }
                }
                row.addView(closeBtn)
            }

            tabItem.addView(row)

            val indicator = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dpToPx(2)
                )
                setBackgroundColor(Color.parseColor(if (isActive) "#a855f7" else "#00000000"))
            }
            tabItem.addView(indicator)

            tabContainer.addView(tabItem)
        }
    }

    private fun updatePresentation() {
        val displays = displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
        if (displays.isNotEmpty()) {
            val targetDisplay = displays[0]
            if (presentation == null || presentation?.display != targetDisplay) {
                presentation?.dismiss()

                Log.i(TAG, "Showing presentation on display: ${targetDisplay.name}")
                presentation = DesktopPresentation(this, targetDisplay).apply {
                    setOnDismissListener {
                        if (presentation == this) {
                            presentation = null
                            exitCastingTouchpad()
                        }
                    }
                    try {
                        show()
                        enterCastingTouchpad()
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to show presentation dialog", e)
                        presentation = null
                    }
                }
            }
        } else {
            if (presentation != null) {
                Log.i(TAG, "Dismissing presentation")
                presentation?.dismiss()
                presentation = null
                exitCastingTouchpad()
            }
        }
    }

    private fun moveVirtualCursor(dx: Float, dy: Float) {
        val maxW = if (workspaceContainer.width > 0) workspaceContainer.width.toFloat() else resources.displayMetrics.widthPixels.toFloat()
        val maxH = if (workspaceContainer.height > 0) workspaceContainer.height.toFloat() else resources.displayMetrics.heightPixels.toFloat()

        cursorX = (cursorX + dx).coerceIn(0f, maxW)
        cursorY = (cursorY + dy).coerceIn(0f, maxH)

        updateCursorViewPosition()
        dispatchHoverAtCursor()
    }

    private fun updateCursorViewPosition() {
        cursorView.x = cursorX
        cursorView.y = cursorY
    }

    private fun dispatchHoverAtCursor() {
        val webView = getActiveWebView() ?: return
        val cx = cursorX
        val cy = cursorY
        val offset = topBar.height + tabScroll.height + dpToPx(2)

        if (cy >= offset) {
            val relativeY = cy - offset
            val downTime = SystemClock.uptimeMillis()
            val eventTime = SystemClock.uptimeMillis()
            val hoverEvent = MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_HOVER_MOVE, cx, relativeY, 0).apply {
                source = InputDevice.SOURCE_MOUSE
            }
            webView.dispatchGenericMotionEvent(hoverEvent)
            hoverEvent.recycle()
        }
    }

    private fun performClickAtCursor(isRightClick: Boolean) {
        val webView = getActiveWebView()
        val cx = cursorX
        val cy = cursorY
        val offset = topBar.height + tabScroll.height + dpToPx(2)

        if (webView != null && cy >= offset) {
            val relativeY = cy - offset
            if (isRightClick) {
                webView.evaluateJavascript(
                    "var el = document.elementFromPoint($cx, $relativeY); if (el) { el.dispatchEvent(new MouseEvent('contextmenu', { bubbles: true, cancelable: true, view: window, button: 2, clientX: $cx, clientY: $relativeY })); }",
                    null
                )
            } else {
                val downTime = SystemClock.uptimeMillis()
                val eventTime = SystemClock.uptimeMillis()
                val downEvent = MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_DOWN, cx, relativeY, 0).apply {
                    source = InputDevice.SOURCE_MOUSE
                }
                val upEvent = MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_UP, cx, relativeY, 0).apply {
                    source = InputDevice.SOURCE_MOUSE
                }
                webView.dispatchTouchEvent(downEvent)
                webView.dispatchTouchEvent(upEvent)
                downEvent.recycle()
                upEvent.recycle()
            }
        } else {
            if (!isRightClick) {
                val downTime = SystemClock.uptimeMillis()
                val eventTime = SystemClock.uptimeMillis()
                val downEvent = MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_DOWN, cx, cy, 0).apply {
                    source = InputDevice.SOURCE_MOUSE
                }
                val upEvent = MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_UP, cx, cy, 0).apply {
                    source = InputDevice.SOURCE_MOUSE
                }
                rootLayout.dispatchTouchEvent(downEvent)
                rootLayout.dispatchTouchEvent(upEvent)
                downEvent.recycle()
                upEvent.recycle()
            }
        }
    }

    private fun scrollActiveWebView(dy: Float) {
        val webView = getActiveWebView() ?: return
        webView.scrollBy(0, (-dy).toInt())
    }

    private fun getActiveWebView(): WebView? {
        if (currentTabIndex in tabsList.indices) {
            return tabsList[currentTabIndex].webView
        }
        return null
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
        }
    }

    private fun dpToPx(dp: Int): Int {
        val density = resources.displayMetrics.density
        return (dp * density).toInt()
    }

    override fun onDestroy() {
        clockHandler.removeCallbacks(clockRunnable)
        remoteServer?.stop()
        displayManager.unregisterDisplayListener(displayListener)
        presentation?.dismiss()
        presentation = null
        for (tab in tabsList) {
            tab.webView.destroy()
        }
        super.onDestroy()
    }

    inner class TouchpadLayout(context: Context) : FrameLayout(context) {
        private var lastX = 0f
        private var lastY = 0f
        private var activePointerId = -1
        private val gestureDetector = android.view.GestureDetector(context, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (isCasting && presentation != null) {
                    presentation?.performPresentationClick(false)
                } else {
                    performClickAtCursor(false)
                }
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (isCasting && presentation != null) {
                    presentation?.performPresentationClick(true)
                } else {
                    performClickAtCursor(true)
                }
            }
        })

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            return true
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            gestureDetector.onTouchEvent(event)

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.x
                    lastY = event.y
                    activePointerId = event.getPointerId(0)
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (event.pointerCount == 2) {
                        lastY = event.getY(1)
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount == 1) {
                        val pointerIndex = event.findPointerIndex(activePointerId)
                        if (pointerIndex != -1) {
                            val dx = event.getX(pointerIndex) - lastX
                            val dy = event.getY(pointerIndex) - lastY
                            lastX = event.getX(pointerIndex)
                            lastY = event.getY(pointerIndex)

                            if (isCasting && presentation != null) {
                                presentation?.movePresentationCursor(dx, dy)
                            } else {
                                moveVirtualCursor(dx, dy)
                            }
                        }
                    } else if (event.pointerCount == 2) {
                        val currentY = event.getY(1)
                        val dy = currentY - lastY
                        lastY = currentY
                        if (isCasting && presentation != null) {
                            presentation?.scrollPresentation(dy)
                        } else {
                            scrollActiveWebView(dy)
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    activePointerId = -1
                }
            }
            return true
        }
    }

    companion object {
        private const val TAG = "AnodyneMainActivity"
    }
}
