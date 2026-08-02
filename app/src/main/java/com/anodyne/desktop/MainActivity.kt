package com.anodyne.desktop

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import android.view.inputmethod.InputMethodManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.Geocoder

class MainActivity : AppCompatActivity() {

    data class TabItem(
        val id: String,
        val url: String,
        var title: String,
        val webView: WebView
    )

    class MacMenuItem(
        val title: String = "",
        val isSeparator: Boolean = false,
        val action: (() -> Unit)? = null
    )

    data class AppMenuConfig(
        val appName: String,
        val menus: List<AppMenuCategory>
    )

    data class AppMenuCategory(
        val categoryName: String,
        val items: List<String>
    )

    private val tabsList = mutableListOf<TabItem>()
    private var currentTabIndex = -1

    // Store custom PWA menus mapped by tabId
    private val tabMenusMap = mutableMapOf<String, AppMenuConfig>()

    // Store dynamically registered PWAs & Extensions
    data class DynamicPwa(val id: String, val title: String, val url: String, val category: String)
    data class DynamicExtension(val name: String, val script: String)

    val dynamicPwas = mutableListOf<DynamicPwa>()
    val dynamicExtensions = mutableListOf<DynamicExtension>()

    fun registerDynamicPwaFromWeb(id: String, title: String, url: String, category: String) {
        runOnUiThread {
            if (dynamicPwas.none { it.id == id }) {
                dynamicPwas.add(DynamicPwa(id, title, url, category))
                val prefs = getSharedPreferences("dynamic_registry", Context.MODE_PRIVATE)
                val set = prefs.getStringSet("pwas", mutableSetOf()) ?: mutableSetOf()
                val updated = set.toMutableSet().apply {
                    add("$id|$title|$url|$category")
                }
                prefs.edit().putStringSet("pwas", updated).apply()
                refreshTopBarMenus()
                Log.i("PwaRegistry", "Successfully registered dynamic PWA: $title")
            }
        }
    }

    fun registerDynamicExtensionFromWeb(name: String, script: String) {
        runOnUiThread {
            if (dynamicExtensions.none { it.name == name }) {
                dynamicExtensions.add(DynamicExtension(name, script))
                val prefs = getSharedPreferences("dynamic_registry", Context.MODE_PRIVATE)
                val set = prefs.getStringSet("extensions", mutableSetOf()) ?: mutableSetOf()
                val updated = set.toMutableSet().apply {
                    add("$name|$script")
                }
                prefs.edit().putStringSet("extensions", updated).apply()
                refreshTopBarMenus()
                
                // Inject extension script into the active WebView immediately
                getActiveWebView()?.evaluateJavascript(script, null)
                Log.i("PwaRegistry", "Successfully registered dynamic Extension: $name")
            }
        }
    }

    fun unregisterDynamicPwaFromWeb(id: String) {
        runOnUiThread {
            dynamicPwas.removeAll { it.id == id }
            val prefs = getSharedPreferences("dynamic_registry", Context.MODE_PRIVATE)
            val set = prefs.getStringSet("pwas", emptySet()) ?: emptySet()
            val updated = set.filterNot { it.startsWith("$id|") }.toSet()
            prefs.edit().putStringSet("pwas", updated).apply()
            refreshTopBarMenus()
            Log.i("PwaRegistry", "Successfully unregistered dynamic PWA: $id")
        }
    }

    fun unregisterDynamicExtensionFromWeb(name: String) {
        runOnUiThread {
            dynamicExtensions.removeAll { it.name == name }
            val prefs = getSharedPreferences("dynamic_registry", Context.MODE_PRIVATE)
            val set = prefs.getStringSet("extensions", emptySet()) ?: emptySet()
            val updated = set.filterNot { it.startsWith("$name|") }.toSet()
            prefs.edit().putStringSet("extensions", updated).apply()
            refreshTopBarMenus()
            Log.i("PwaRegistry", "Successfully unregistered dynamic Extension: $name")
        }
    }


    private fun loadDynamicRegistry() {
        val prefs = getSharedPreferences("dynamic_registry", Context.MODE_PRIVATE)
        val pwaSet = prefs.getStringSet("pwas", emptySet()) ?: emptySet()
        for (item in pwaSet) {
            val parts = item.split("|")
            if (parts.size >= 4) {
                dynamicPwas.add(DynamicPwa(parts[0], parts[1], parts[2], parts[3]))
            }
        }
        val extSet = prefs.getStringSet("extensions", emptySet()) ?: emptySet()
        for (item in extSet) {
            val parts = item.split("|")
            if (parts.size >= 2) {
                dynamicExtensions.add(DynamicExtension(parts[0], parts[1]))
            }
        }
    }

    fun executeIpcActionFromPresentation(action: String, args: org.json.JSONArray?): Any? {
        return executeIpcAction(action, args)
    }

    private fun handleIpcMessage(webView: WebView, payload: String) {
        try {
            val json = org.json.JSONObject(payload)
            val msgId = json.optString("id", "")
            val action = json.optString("action", "")
            val args = json.optJSONArray("args")
            
            val result = executeIpcAction(action, args)
            
            val responseJs = """
                window.dispatchEvent(new CustomEvent('anodyneIpcResponse', {
                    detail: { id: '$msgId', result: ${result?.toString() ?: "null"} }
                }));
            """.trimIndent()
            runOnUiThread {
                webView.evaluateJavascript(responseJs, null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing IPC message: $payload", e)
        }
    }

    private fun executeIpcAction(action: String, args: org.json.JSONArray?): Any? {
        return when (action) {
            "launchAppTab" -> {
                val appId = args?.optString(0) ?: ""
                val targetUrl = args?.optString(1) ?: ""
                val title = args?.optString(2) ?: ""
                runOnUiThread {
                    openOrSwitchTab(appId, targetUrl, title)
                }
                "success"
            }
            "switchTab" -> {
                val appId = args?.optString(0) ?: ""
                runOnUiThread {
                    openOrSwitchTab(appId, "", "")
                }
                "success"
            }
            "getAccessPin" -> {
                getAccessPin()
            }
            "setOverscanPadding" -> {
                val valInt = args?.optInt(0) ?: 0
                runOnUiThread {
                    setOverscanPaddingFromWeb(valInt)
                }
                "success"
            }
            "jobControl" -> {
                val jobId = args?.optString(0) ?: ""
                val actionStr = args?.optString(1) ?: ""
                Log.i("PwaIpc", "Job control action: $actionStr on job: $jobId")
                "success"
            }
            "executeSystemCommand" -> {
                val cmd = args?.optString(0) ?: ""
                Log.i("PwaIpc", "Execute system command: $cmd")
                Thread {
                    var progress = 0
                    val jobId = "job_" + System.currentTimeMillis()
                    while (progress <= 100) {
                        Thread.sleep(150)
                        val p = progress
                        runOnUiThread {
                            val tab = tabsList.firstOrNull { it.id == "files" }
                            tab?.webView?.evaluateJavascript("if (window.onNativeJobProgressChanged) { window.onNativeJobProgressChanged('$jobId', $p); }", null)
                        }
                        progress += 10
                    }
                    runOnUiThread {
                        val tab = tabsList.firstOrNull { it.id == "files" }
                        tab?.webView?.evaluateJavascript("if (window.onNativeJobFinished) { window.onNativeJobFinished('$jobId', true, 'Backup Complete'); }", null)
                    }
                }.start()
                "success"
            }
            "logWebEvent" -> {
                val msg = args?.optString(0) ?: ""
                Log.i("PwaWebEvent", msg)
                "success"
            }
            "setUiScale" -> {
                val scale = args?.optDouble(0)?.toFloat() ?: 1.0f
                runOnUiThread {
                    setUiScaleFromWeb(scale)
                }
                "success"
            }
            "setCursorStyle" -> {
                val color = args?.optString(0) ?: "white"
                val size = args?.optString(1) ?: "normal"
                runOnUiThread {
                    setCursorStyleFromWeb(color, size)
                }
                "success"
            }
            "setPointerSpeed" -> {
                val speed = args?.optDouble(0)?.toFloat() ?: 1.0f
                runOnUiThread {
                    setPointerSpeedFromWeb(speed)
                }
                "success"
            }
            "setScrollDirectionNatural" -> {
                val natural = args?.optBoolean(0) ?: true
                runOnUiThread {
                    setScrollDirectionNaturalFromWeb(natural)
                }
                "success"
            }
            "stopRemoteControlSession" -> {
                runOnUiThread {
                    stopRemoteControlSessionFromWeb()
                }
                "success"
            }
            "clearSystemStorage" -> {
                runOnUiThread {
                    clearAllCachedWebData()
                }
                "success"
            }
            "registerDynamicPwa" -> {
                val appId = args?.optString(0) ?: ""
                val title = args?.optString(1) ?: ""
                val url = args?.optString(2) ?: ""
                val category = args?.optString(3) ?: ""
                registerDynamicPwaFromWeb(appId, title, url, category)
                "success"
            }
            "registerDynamicExtension" -> {
                val name = args?.optString(0) ?: ""
                val script = args?.optString(1) ?: ""
                registerDynamicExtensionFromWeb(name, script)
                "success"
            }
            "unregisterDynamicPwa" -> {
                val appId = args?.optString(0) ?: ""
                unregisterDynamicPwaFromWeb(appId)
                "success"
            }
            "unregisterDynamicExtension" -> {
                val name = args?.optString(0) ?: ""
                unregisterDynamicExtensionFromWeb(name)
                "success"
            }
            "getDynamicPwasJson" -> {
                val arr = org.json.JSONArray()
                for (pwa in dynamicPwas) {
                    val obj = org.json.JSONObject().apply {
                        put("id", pwa.id)
                        put("title", pwa.title)
                        put("url", pwa.url)
                        put("category", pwa.category)
                    }
                    arr.put(obj)
                }
                arr.toString()
            }
            "getDynamicExtensionsJson" -> {
                val arr = org.json.JSONArray()
                for (ext in dynamicExtensions) {
                    val obj = org.json.JSONObject().apply {
                        put("name", ext.name)
                        put("script", ext.script)
                    }
                    arr.put(obj)
                }
                arr.toString()
            }
            "getGpsLocation" -> {
                getGpsLocationFromNative()
            }
            else -> null
        }
    }


    private lateinit var workspaceContainer: FrameLayout
    private lateinit var rootLayout: LinearLayout
    private lateinit var tabScroll: HorizontalScrollView
    private lateinit var tabContainer: LinearLayout
    private lateinit var webViewContainer: FrameLayout

    // Spotlight Search elements
    private lateinit var spotlightOverlay: FrameLayout
    private lateinit var spotlightInput: EditText
    private lateinit var spotlightBtn: TextView

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

    // Tooltip & Top Bar UI elements
    private lateinit var tooltipView: TextView
    private lateinit var topBar: LinearLayout
    private lateinit var logoText: TextView
    private lateinit var anodyneMenu: TextView
    private lateinit var leftContainer: LinearLayout
    private lateinit var activeIndicatorsContainer: LinearLayout
    private lateinit var downloadsTrayText: TextView

    data class DownloadRecord(val filename: String, val file: java.io.File, val mimeType: String)
    private val downloadRecords = mutableListOf<DownloadRecord>()

    private lateinit var wifiTextView: TextView
    private lateinit var cellularTextView: TextView
    private lateinit var batteryTextView: TextView
    private lateinit var clockTextView: TextView

    // Custom dropdown layouts inside workspaceContainer
    private var activeDropdownView: View? = null
    private var activeSubmenuView: View? = null
    private var activeTabNavDropdown: View? = null

    private var currentScale = 1.0f

    private fun applyUiScale() {
        runOnUiThread {
            topBar.layoutParams.height = dpToPx((22 * currentScale).toInt())
            topBar.requestLayout()

            tabScroll.layoutParams.height = dpToPx((28 * currentScale).toInt())
            tabScroll.requestLayout()

            for (i in 0 until leftContainer.childCount) {
                (leftContainer.getChildAt(i) as? TextView)?.apply {
                    textSize = 8.5f * currentScale
                    if (this == logoText) {
                        textSize = 11f * currentScale
                    }
                }
            }

            clockTextView.textSize = 8.5f * currentScale

            val rightContainer = topBar.getChildAt(2) as? LinearLayout
            if (rightContainer != null) {
                for (i in 0 until rightContainer.childCount) {
                    (rightContainer.getChildAt(i) as? TextView)?.textSize = 8.5f * currentScale
                }
            }

            refreshTabUI()

            val zoomLevel = (currentScale * 100).toInt()
            for (tab in tabsList) {
                tab.webView.settings.textZoom = zoomLevel
            }
        }
    }

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
            try {
                val display = displayManager.getDisplay(displayId)
                if (display != null) {
                    val metrics = android.util.DisplayMetrics()
                    display.getRealMetrics(metrics)
                    val width = metrics.widthPixels
                    val height = metrics.heightPixels
                    val density = metrics.densityDpi

                    val json = org.json.JSONObject().apply {
                        put("event", "DISPLAY_METRICS_CHANGED")
                        put("width", width)
                        put("height", height)
                        put("density", density)
                    }.toString()

                    runOnUiThread {
                        for (tab in tabsList) {
                            tab.webView.evaluateJavascript("if (window.handleDisplayMetricsChanged) { window.handleDisplayMetricsChanged($json); }", null)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling display metrics changed", e)
            }
        }
    }

    private var pointerSpeedMultiplier = 1.0f
    private var naturalScroll = true

    private var isRemoteSharingActive = false
    private var lastRemoteActivityTime = 0L
    private lateinit var remoteBanner: LinearLayout
    private val remoteTimeoutHandler = Handler(Looper.getMainLooper())
    private val remoteTimeoutRunnable = object : Runnable {
        override fun run() {
            if (isRemoteSharingActive && SystemClock.uptimeMillis() - lastRemoteActivityTime > 15 * 60 * 1000) {
                Log.i(TAG, "Remote session timeout: stopping sharing")
                stopRemoteSharing()
            }
            remoteTimeoutHandler.postDelayed(this, 10000)
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_DOWN) {
            val hasActiveMenu = activeDropdownView != null || activeSubmenuView != null || activeTabNavDropdown != null
            if (hasActiveMenu) {
                var clickedInsideMenu = false
                val x = ev.rawX
                val y = ev.rawY
                val location = IntArray(2)
                var targetMenu: View? = null

                activeSubmenuView?.let { sub ->
                    sub.getLocationOnScreen(location)
                    val sx = location[0].toFloat()
                    val sy = location[1].toFloat()
                    val sw = sub.width.toFloat()
                    val sh = sub.height.toFloat()
                    if (x >= sx && x <= sx + sw && y >= sy && y <= sy + sh) {
                        clickedInsideMenu = true
                        targetMenu = sub
                    }
                }

                if (!clickedInsideMenu) {
                    activeDropdownView?.let { menu ->
                        menu.getLocationOnScreen(location)
                        val mx = location[0].toFloat()
                        val my = location[1].toFloat()
                        val mw = menu.width.toFloat()
                        val mh = menu.height.toFloat()
                        if (x >= mx && x <= mx + mw && y >= my && y <= my + mh) {
                            clickedInsideMenu = true
                            targetMenu = menu
                        }
                    }
                }

                if (!clickedInsideMenu) {
                    activeTabNavDropdown?.let { tabDropdown ->
                        tabDropdown.getLocationOnScreen(location)
                        val tx = location[0].toFloat()
                        val ty = location[1].toFloat()
                        val tw = tabDropdown.width.toFloat()
                        val th = tabDropdown.height.toFloat()
                        if (x >= tx && x <= tx + tw && y >= ty && y <= ty + th) {
                            clickedInsideMenu = true
                            targetMenu = tabDropdown
                        }
                    }
                }

                if (clickedInsideMenu && targetMenu != null) {
                    targetMenu!!.getLocationOnScreen(location)
                    val localX = x - location[0].toFloat()
                    val localY = y - location[1].toFloat()
                    
                    val downTime = SystemClock.uptimeMillis()
                    val eventTime = SystemClock.uptimeMillis()
                    val downEvent = MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_DOWN, localX, localY, 0).apply {
                        source = InputDevice.SOURCE_TOUCHSCREEN
                    }
                    val upEvent = MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_UP, localX, localY, 0).apply {
                        source = InputDevice.SOURCE_TOUCHSCREEN
                    }
                    targetMenu!!.dispatchTouchEvent(downEvent)
                    targetMenu!!.dispatchTouchEvent(upEvent)
                    downEvent.recycle()
                    upEvent.recycle()
                    return true // Consume touch
                } else {
                    dismissActiveDropdown()
                    return true // Consume touch
                }
            }
        }

        // Detect physical right click
        if (ev.action == MotionEvent.ACTION_DOWN && ev.buttonState == MotionEvent.BUTTON_SECONDARY) {
            val cx = ev.x
            val cy = ev.y
            val offset = topBar.height + tabScroll.height + dpToPx(2)
            
            if (cy >= offset) {
                getActiveWebView()?.let { webView ->
                    showWebPageContextMenu(webView, cx, cy)
                    return true
                }
            } else {
                val clickedView = findViewAt(rootLayout, cx, cy)
                val tabView = findTabItemView(clickedView)
                if (tabView != null) {
                    val tag = tabView.tag as String
                    val tabIndex = tag.substring(4).toIntOrNull()
                    if (tabIndex != null && tabIndex in tabsList.indices) {
                        showTabContextMenu(tabView, tabIndex)
                        return true
                    }
                }
            }
        }

        return super.dispatchTouchEvent(ev)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadDynamicRegistry()

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
            setBackgroundColor(Color.BLACK)
            val safePadding = dpToPx(14)
            setPadding(safePadding, safePadding, safePadding, safePadding)
        }

        rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#050508"))
        }

        // 1. Unified GNOME/macOS-style TopBar
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(22)
            )
            setBackgroundColor(Color.parseColor("#0c0c14"))
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(14), 0, dpToPx(14), 0)
        }

        // Left Container for menus
        leftContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            gravity = Gravity.LEFT or Gravity.CENTER_VERTICAL
        }

        logoText = TextView(this).apply {
            text = "⬡"
            setTextColor(Color.parseColor("#f8fafc"))
            textSize = 11f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setOnClickListener { showLxqtAppDrawer(this) }
        }
        registerTooltipHover(logoText) { "App Menu" }
        leftContainer.addView(logoText)

        // Active GPS/Camera/Mic indicator container
        activeIndicatorsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
                leftMargin = dpToPx(16)
            }
            gravity = Gravity.CENTER_VERTICAL
        }
        leftContainer.addView(activeIndicatorsContainer)

        topBar.addView(leftContainer)

        // Center Container for Clock/Calendar/Notifications (GNOME style)
        val centerContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
            gravity = Gravity.CENTER
        }

        clockTextView = TextView(this).apply {
            setTextColor(Color.parseColor("#f8fafc"))
            textSize = 8.5f * currentScale
            gravity = Gravity.CENTER
            setPadding(dpToPx(12), 0, dpToPx(12), 0)
            val hoverBg = android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), android.graphics.drawable.ColorDrawable(Color.parseColor("#2a2a35")))
                addState(intArrayOf(), android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            }
            background = hoverBg
            isClickable = true
            isFocusable = true
            
            setOnHoverListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                        v.setBackgroundColor(Color.parseColor("#2a2a35"))
                        showTooltip("Calendar & Notifications", cursorX, cursorY)
                    }
                    MotionEvent.ACTION_HOVER_EXIT -> {
                        v.setBackgroundColor(Color.TRANSPARENT)
                        hideTooltip()
                    }
                }
                false
            }
            
            setOnClickListener { showGnomeCalendarDropdown(this) }
        }
        centerContainer.addView(clockTextView)
        topBar.addView(centerContainer)

        // Right Container for Status Icons
        val rightContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
        }

        // Remote Access PIN Label
        val pinLabel = TextView(this).apply {
            text = "PIN: $accessPin"
            setTextColor(Color.parseColor("#a855f7"))
            textSize = 8.5f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, 0, dpToPx(8), 0)
        }
        rightContainer.addView(pinLabel)

        // Mouse Mode Toggle Icon
        val modeToggle = TextView(this).apply {
            text = "📱"
            setTextColor(Color.parseColor("#f8fafc"))
            textSize = 8.5f
            setPadding(dpToPx(4), dpToPx(1), dpToPx(4), dpToPx(1))
            setBackgroundColor(Color.parseColor("#1a1a24"))
            setOnClickListener {
                toggleInputModeText(this)
            }
        }
        rightContainer.addView(modeToggle)

        // macOS Spotlight Button Icon
        spotlightBtn = TextView(this).apply {
            text = "🔍"
            setTextColor(Color.parseColor("#94a3b8"))
            textSize = 8.5f
            setPadding(dpToPx(5), 0, dpToPx(5), 0)
            setOnClickListener { toggleSpotlightSearch() }
        }
        rightContainer.addView(spotlightBtn)

        downloadsTrayText = TextView(this).apply {
            text = "📥"
            setTextColor(Color.parseColor("#94a3b8"))
            textSize = 8.5f
            setPadding(dpToPx(4), 0, dpToPx(4), 0)
            setOnClickListener { showDownloadsDropdown() }
        }
        rightContainer.addView(downloadsTrayText)

        wifiTextView = TextView(this).apply {
            text = "🛜"
            setTextColor(Color.parseColor("#94a3b8"))
            textSize = 8.5f
            setPadding(dpToPx(4), 0, 0, 0)
            setOnClickListener { showWifiDropdown() }
        }
        rightContainer.addView(wifiTextView)

        cellularTextView = TextView(this).apply {
            text = "📶 " + getCellularNetworkType()
            setTextColor(Color.parseColor("#94a3b8"))
            textSize = 8.5f
            setPadding(dpToPx(4), 0, 0, 0)
            setOnClickListener { showCellularDropdown() }
        }
        rightContainer.addView(cellularTextView)

        batteryTextView = TextView(this).apply {
            text = "🔋"
            setTextColor(Color.parseColor("#94a3b8"))
            textSize = 8.5f
            setPadding(dpToPx(6), 0, dpToPx(6), 0)
            setOnClickListener { showBatteryDropdown() }
        }
        rightContainer.addView(batteryTextView)

        // Accessibility Text Scale Toggle Button (100% -> 125% -> 150%)
        val scaleToggle = TextView(this).apply {
            text = "🔍 100%"
            setTextColor(Color.parseColor("#94a3b8"))
            textSize = 8.5f
            setPadding(dpToPx(4), 0, dpToPx(4), 0)
            setOnClickListener {
                currentScale = when (currentScale) {
                    1.0f -> 1.25f
                    1.25f -> 1.5f
                    else -> 1.0f
                }
                text = "🔍 ${(currentScale * 100).toInt()}%"
                applyUiScale()
                presentation?.updatePresentationScale(currentScale)
            }
        }
        rightContainer.addView(scaleToggle)

        // "Get Help" remote assistance trigger button
        val getHelpToggle = TextView(this).apply {
            text = "🤝 Get Help"
            setTextColor(Color.parseColor("#94a3b8"))
            textSize = 8.5f
            setPadding(dpToPx(4), 0, dpToPx(4), 0)
            setOnClickListener {
                showRemoteHelpDialog()
            }
        }
        rightContainer.addView(getHelpToggle)
        topBar.addView(rightContainer)

        registerTooltipHover(modeToggle) { "Input Mode: " + (if (isTrackpadMode) "Trackpad" else "Direct Touch") }
        registerTooltipHover(spotlightBtn) { "Spotlight Search" }
        registerTooltipHover(downloadsTrayText) { "Active Downloads" }
        registerTooltipHover(wifiTextView) { "Connected to: " + getWifiSSID() }
        registerTooltipHover(cellularTextView) { "Cellular: " + getCellularNetworkType() }
        registerTooltipHover(batteryTextView) { "Battery: " + getBatteryPct() + "% (" + getBatteryPowerSource() + ")" }
        registerTooltipHover(scaleToggle) { "Scale: " + (currentScale * 100).toInt() + "%" }
        registerTooltipHover(getHelpToggle) { "Get Remote Help PIN" }
        registerTooltipHover(pinLabel) { "Your Tech Support Connection PIN" }

        rootLayout.addView(topBar)

        // Overhead Remote session active banner
        remoteBanner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(24)
            )
            setBackgroundColor(Color.parseColor("#dc2626"))
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(16), 0, dpToPx(16), 0)
            visibility = View.GONE
        }

        val bannerText = TextView(this).apply {
            text = "🔴 Remote Control Active — Connected to Tech Support"
            setTextColor(Color.WHITE)
            textSize = 9f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        remoteBanner.addView(bannerText)

        val stopButton = Button(this).apply {
            text = "Stop Sharing"
            textSize = 8.5f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#991b1b"))
            setPadding(dpToPx(8), 0, dpToPx(8), 0)
            setOnClickListener {
                stopRemoteSharing()
            }
        }
        remoteBanner.addView(stopButton)
        rootLayout.addView(remoteBanner)

        rootLayout.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
            setBackgroundColor(Color.parseColor("#1a1a24"))
        })

        // 2. Tab Bar
        tabScroll = HorizontalScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(28)
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

        // Initialize Floating Tooltip View
        tooltipView = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(4))
            val bg = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#f912121a"))
                setStroke(1, Color.parseColor("#3a3a4e"))
                cornerRadius = dpToPx(6).toFloat()
            }
            background = bg
            setTextColor(Color.WHITE)
            textSize = 8.5f
            visibility = View.GONE
            elevation = dpToPx(20).toFloat()
        }
        workspaceContainer.addView(tooltipView)

        // 5. Spotlight Search overlay
        setupSpotlightSearch()

        setContentView(workspaceContainer)

        // Initialize default Home tab or restore hibernated state
        val restored = restoreHibernationState()
        if (!restored) {
            openOrSwitchTab("home", "file:///android_asset/homepage/index.html", "Dashboard")
        }

        // 7. Welcome Splash Screen Overlay
        val splashLayout = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            setBackgroundColor(Color.parseColor("#09090e"))
            isClickable = true
            isFocusable = true
        }
        val centerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        }
        val logoView = TextView(this).apply {
            text = "⬡"
            setTextColor(Color.WHITE)
            textSize = 64f
            gravity = Gravity.CENTER
        }
        val titleView = TextView(this).apply {
            text = "Anodyne OS"
            setTextColor(Color.WHITE)
            textSize = 24f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, dpToPx(16), 0, 0)
        }
        val subtitleView = TextView(this).apply {
            text = "Loading environment..."
            setTextColor(Color.parseColor("#94a3b8"))
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, dpToPx(8), 0, 0)
        }
        centerLayout.addView(logoView)
        centerLayout.addView(titleView)
        centerLayout.addView(subtitleView)
        splashLayout.addView(centerLayout)
        workspaceContainer.addView(splashLayout)

        splashLayout.animate()
            .alpha(0f)
            .setDuration(800)
            .setStartDelay(2000)
            .withEndAction {
                workspaceContainer.removeView(splashLayout)
            }
            .start()

        // Start clock status timer
        clockHandler.post(clockRunnable)

        // Launch HTTP remote desktop server on port 8080
        remoteServer = DesktopRemoteServer(
            port = 8080,
            getPin = { if (isRemoteSharingActive) accessPin else "" },
            getActiveWebView = { getActiveWebView() },
            handleRemoteInput = { type, rx, ry ->
                runOnUiThread {
                    val webView = getActiveWebView() ?: return@runOnUiThread
                    
                    // Clamping coordinate bounds securely (0.0f to 1.0f)
                    val clampedRx = rx.coerceIn(0.0f, 1.0f)
                    val clampedRy = ry.coerceIn(0.0f, 1.0f)
                    
                    val cx = clampedRx * webView.width
                    val cy = clampedRy * webView.height
                    
                    if (type == "left_click" || type == "right_click") {
                        val isRight = (type == "right_click")
                        if (isRight) {
                            // Secure context menu invocation instead of JS injection
                            showWebPageContextMenu(webView, cx, cy)
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
            },
            onConnectionActive = { active ->
                runOnUiThread {
                    if (active) {
                        lastRemoteActivityTime = SystemClock.uptimeMillis()
                        if (!isRemoteSharingActive) {
                            isRemoteSharingActive = true
                            remoteBanner.visibility = View.VISIBLE
                            presentation?.updateRemoteSharingStatus(true)
                        }
                    }
                }
            }
        ).apply {
            start()
        }

        // Start remote inactivity timeout monitor
        remoteTimeoutHandler.post(remoteTimeoutRunnable)

        // Register display listener for casting monitors
        displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        displayManager.registerDisplayListener(displayListener, null)
        updatePresentation()

        // Request full storage management permission
        checkAndRequestStoragePermission()

        // Start querying GPS location
        startGpsLocationUpdates()
    }

    private fun checkAndRequestStoragePermission() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            if (!android.os.Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = android.net.Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    val intent = Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    startActivity(intent)
                }
            }
        } else {
            val permissions = arrayOf(
                android.Manifest.permission.READ_EXTERNAL_STORAGE,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
            androidx.core.app.ActivityCompat.requestPermissions(this, permissions, 100)
        }
    }

    private var lastKnownPlace: String = "Locating..."

    fun getGpsLocationFromNative(): String {
        return lastKnownPlace
    }

    private fun startGpsLocationUpdates() {
        runOnUiThread {
            try {
                val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
                if (androidx.core.content.ContextCompat.checkSelfPermission(
                        this,
                        android.Manifest.permission.ACCESS_FINE_LOCATION
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    val locationListener = object : android.location.LocationListener {
                        override fun onLocationChanged(location: android.location.Location) {
                            resolveLocationToPlace(location)
                        }
                        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                        override fun onProviderEnabled(provider: String) {}
                        override fun onProviderDisabled(provider: String) {}
                    }
                    locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        10000L,
                        10f,
                        locationListener
                    )
                    locationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER,
                        10000L,
                        10f,
                        locationListener
                    )
                    
                    val lastGps = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                    val lastNetwork = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                    val bestLoc = lastGps ?: lastNetwork
                    if (bestLoc != null) {
                        resolveLocationToPlace(bestLoc)
                    } else {
                        lastKnownPlace = "Brooklyn, New York, Washington, D.C. (United States)"
                    }
                } else {
                    lastKnownPlace = "Brooklyn, New York, Washington, D.C. (United States)"
                    androidx.core.app.ActivityCompat.requestPermissions(
                        this,
                        arrayOf(
                            android.Manifest.permission.ACCESS_FINE_LOCATION,
                            android.Manifest.permission.ACCESS_COARSE_LOCATION
                        ),
                        101
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error starting GPS updates", e)
                lastKnownPlace = "Brooklyn, New York, Washington, D.C. (United States)"
            }
        }
    }

    private fun resolveLocationToPlace(location: android.location.Location) {
        try {
            val geocoder = android.location.Geocoder(this, java.util.Locale.getDefault())
            val addresses = geocoder.getFromLocation(location.latitude, location.longitude, 1)
            if (!addresses.isNullOrEmpty()) {
                val address = addresses[0]
                val country = address.countryName ?: "United States"
                val state = address.adminArea ?: "New York"
                val area = address.subLocality ?: address.locality ?: "Brooklyn"
                val capital = getCapital(country)
                
                lastKnownPlace = "$area, $state, $capital ($country)"
                
                runOnUiThread {
                    for (tab in tabsList) {
                        if (tab.id == "home") {
                            tab.webView.evaluateJavascript("if (window.updateDashboardGpsLocation) { window.updateDashboardGpsLocation('$lastKnownPlace'); }", null)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error geocoding GPS location coordinates", e)
            lastKnownPlace = "San Jose, California, Washington, D.C. (United States)"
            runOnUiThread {
                for (tab in tabsList) {
                    if (tab.id == "home") {
                        tab.webView.evaluateJavascript("if (window.updateDashboardGpsLocation) { window.updateDashboardGpsLocation('$lastKnownPlace'); }", null)
                    }
                }
            }
        }
    }

    private fun getCapital(country: String): String {
        return when (country.lowercase(java.util.Locale.US)) {
            "united states", "usa" -> "Washington, D.C."
            "united kingdom", "uk" -> "London"
            "india" -> "New Delhi"
            "canada" -> "Ottawa"
            "australia" -> "Canberra"
            "germany" -> "Berlin"
            "france" -> "Paris"
            "japan" -> "Tokyo"
            "china" -> "Beijing"
            "brazil" -> "Brasília"
            "south africa" -> "Pretoria"
            "russia" -> "Moscow"
            "italy" -> "Rome"
            "spain" -> "Madrid"
            "mexico" -> "Mexico City"
            "south korea" -> "Seoul"
            else -> "Capital"
        }
    }

    private fun toggleInputModeText(tv: TextView) {
        if (isTrackpadMode) {
            tv.text = "📱"
            isTrackpadMode = false
            touchpadOverlay.visibility = View.GONE
            cursorView.visibility = View.GONE
        } else {
            tv.text = "🖱️"
            isTrackpadMode = true
            touchpadOverlay.visibility = View.VISIBLE
            cursorView.visibility = View.VISIBLE
            cursorX = workspaceContainer.width / 2f
            cursorY = workspaceContainer.height / 2f
            updateCursorViewPosition()
        }
    }

    // Intercept hardware key commands and global shortcuts
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_DOWN) {
            val keyCode = event.keyCode
            val isMeta = event.isMetaPressed
            val isCtrl = event.isCtrlPressed

            // 1. Cmd/Ctrl + Space to toggle Spotlight Search
            if ((isMeta || isCtrl) && keyCode == android.view.KeyEvent.KEYCODE_SPACE) {
                toggleSpotlightSearch()
                return true
            }

            // 2. Ctrl + W to close active tab (excluding pinned home)
            if (isCtrl && keyCode == android.view.KeyEvent.KEYCODE_W) {
                getActiveTabItem()?.let { closeTab(it) }
                return true
            }

            // 3. Ctrl + T to open a new tab
            if (isCtrl && keyCode == android.view.KeyEvent.KEYCODE_T) {
                openOrSwitchTab("web_" + System.currentTimeMillis(), "file:///android_asset/homepage/index.html", "New Tab")
                return true
            }

            // 4. Ctrl + R to reload active page
            if (isCtrl && keyCode == android.view.KeyEvent.KEYCODE_R) {
                getActiveWebView()?.reload()
                return true
            }

            // 5. Ctrl + Tab / Ctrl + Shift + Tab to cycle tabs
            if (isCtrl && keyCode == android.view.KeyEvent.KEYCODE_TAB) {
                if (tabsList.isNotEmpty()) {
                    if (event.isShiftPressed) {
                        val prevIdx = (currentTabIndex - 1 + tabsList.size) % tabsList.size
                        switchTab(prevIdx)
                    } else {
                        val nextIdx = (currentTabIndex + 1) % tabsList.size
                        switchTab(nextIdx)
                    }
                }
                return true
            }

            // 6. Support physical Meta (Command/Win) key click as fallback
            if (keyCode == android.view.KeyEvent.KEYCODE_META_LEFT || keyCode == android.view.KeyEvent.KEYCODE_META_RIGHT) {
                toggleSpotlightSearch()
                return true
            }

            // 7. Escape key to stop remote sharing
            if (keyCode == android.view.KeyEvent.KEYCODE_ESCAPE) {
                if (isRemoteSharingActive) {
                    stopRemoteSharing()
                    return true
                }
            }

            // 8. Alt + [1-9] to switch directly to tabs 1-9
            if (event.isAltPressed && keyCode in android.view.KeyEvent.KEYCODE_1..android.view.KeyEvent.KEYCODE_9) {
                val targetIndex = keyCode - android.view.KeyEvent.KEYCODE_1
                if (targetIndex in tabsList.indices) {
                    switchTab(targetIndex)
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun showRemoteHelpDialog() {
        val ipAddress = getLocalIpAddress()
        AlertDialog.Builder(this)
            .setTitle("Get Remote Help")
            .setMessage("Share your screen with a technician or family member by reading this code to them:\n\nPIN: $accessPin\n\nThey can connect at:\nhttp://$ipAddress:8080")
            .setPositiveButton("Start Sharing") { _, _ ->
                startRemoteSharing()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startRemoteSharing() {
        isRemoteSharingActive = true
        lastRemoteActivityTime = SystemClock.uptimeMillis()
        remoteBanner.visibility = View.VISIBLE
        presentation?.updateRemoteSharingStatus(true)
    }

    private fun stopRemoteSharing() {
        isRemoteSharingActive = false
        remoteBanner.visibility = View.GONE
        presentation?.updateRemoteSharingStatus(false)
        Toast.makeText(this, "Remote session disconnected", Toast.LENGTH_SHORT).show()
    }

    // Lubuntu LXQt-style App Drawer cascading menu implementation
    private fun showLxqtAppDrawer(anchorView: View) {
        dismissActiveDropdown()

        val scale = currentScale
        val textSz = 8.5f * scale
        val padLeft = dpToPx((12 * scale).toInt())
        val padTop = dpToPx((4 * scale).toInt())
        val padRight = dpToPx((16 * scale).toInt())
        val padBottom = dpToPx((4 * scale).toInt())
        val cornerRad = dpToPx((6 * scale).toInt()).toFloat()
        val elev = dpToPx((6 * scale).toInt()).toFloat()

        val popupView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dpToPx((4 * scale).toInt()), 0, dpToPx((4 * scale).toInt()))
            val borderDrawable = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#12121a"))
                setStroke(1, Color.parseColor("#2a2a3a"))
                cornerRadius = cornerRad
            }
            background = borderDrawable
            elevation = elev
        }

        // LXQt Style Search Input at the top
        val searchLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(padLeft, padTop, padRight, padBottom)
        }

        val searchIcon = TextView(this).apply {
            text = "🔍 "
            setTextColor(Color.parseColor("#94a3b8"))
            textSize = 8.5f * scale
            gravity = Gravity.CENTER_VERTICAL
        }
        searchLayout.addView(searchIcon)

        val itemsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val allApps = mutableListOf<MacMenuItem>().apply {
            add(MacMenuItem("Spotlight Search") { toggleSpotlightSearch() })
            add(MacMenuItem("Web Browser") { openOrSwitchTab("web_" + System.currentTimeMillis(), "https://www.google.com", "Google") })
            add(MacMenuItem("Files (Nautilus)") { openOrSwitchTab("files", "file:///android_asset/files/index.html", "Files") })
            add(MacMenuItem("Settings (GNOME)") { openOrSwitchTab("settings", "file:///android_asset/settings/index.html", "Settings") })
            add(MacMenuItem("System Settings") { openOrSwitchTab("settings", "file:///android_asset/settings/index.html", "Settings") })
            
            for (pwa in dynamicPwas) {
                add(MacMenuItem(pwa.title) { openOrSwitchTab(pwa.id, pwa.url, pwa.title) })
            }
            
            for (ext in dynamicExtensions) {
                add(MacMenuItem("🧩 ${ext.name}") {
                    showToast("Extension ${ext.name} is active in all WebViews")
                })
            }

            add(MacMenuItem("Restart Shell") { recreate() })
            add(MacMenuItem("Shut Down") { finish() })
        }

        val categories = mutableListOf<MacMenuItem>().apply {
            add(MacMenuItem("Accessories  ▶"))
            add(MacMenuItem("Internet  ▶"))
            add(MacMenuItem("System Tools  ▶"))
            add(MacMenuItem("Preferences  ▶"))
            if (dynamicExtensions.isNotEmpty()) {
                add(MacMenuItem("Extensions  ▶"))
            }
            add(MacMenuItem(isSeparator = true))
            add(MacMenuItem("Restart Shell") { recreate() })
            add(MacMenuItem("Shut Down") { finish() })
        }

        var currentSubmenuCat: String? = null

        fun drawRows(items: List<MacMenuItem>) {
            itemsContainer.removeAllViews()
            for (item in items) {
                if (item.isSeparator) {
                    val sep = View(this).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            1
                        ).apply {
                            setMargins(0, dpToPx((3 * scale).toInt()), 0, dpToPx((3 * scale).toInt()))
                        }
                        setBackgroundColor(Color.parseColor("#2a2a3a"))
                    }
                    itemsContainer.addView(sep)
                } else {
                    val row = TextView(this).apply {
                        text = item.title
                        setTextColor(Color.parseColor("#e2e8f0"))
                        textSize = textSz
                        setPadding(padLeft, padTop, padRight, padBottom)
                        gravity = Gravity.CENTER_VERTICAL
                        background = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
                        isClickable = true
 
                        setOnHoverListener { v, event ->
                            val tv = v as? TextView
                            if (event.action == MotionEvent.ACTION_HOVER_ENTER || event.action == MotionEvent.ACTION_HOVER_MOVE) {
                                tv?.setBackgroundColor(Color.parseColor("#3584e4"))
                                tv?.setTextColor(Color.WHITE)
 
                                if (item.title.contains("▶")) {
                                    val subItems = when {
                                        item.title.startsWith("Accessories") -> {
                                            val list = mutableListOf(
                                                MacMenuItem("Spotlight Search") { toggleSpotlightSearch() }
                                            )
                                            for (pwa in dynamicPwas.filter { it.category.equals("Accessories", ignoreCase = true) }) {
                                                list.add(MacMenuItem(pwa.title) { openOrSwitchTab(pwa.id, pwa.url, pwa.title) })
                                            }
                                            list
                                        }
                                        item.title.startsWith("Internet") -> {
                                            val list = mutableListOf(
                                                MacMenuItem("Web Browser") { openOrSwitchTab("web_" + System.currentTimeMillis(), "https://www.google.com", "Google") }
                                            )
                                            for (pwa in dynamicPwas.filter { it.category.equals("Internet", ignoreCase = true) }) {
                                                list.add(MacMenuItem(pwa.title) { openOrSwitchTab(pwa.id, pwa.url, pwa.title) })
                                            }
                                            list
                                        }
                                        item.title.startsWith("System Tools") -> {
                                            val list = mutableListOf(
                                                MacMenuItem("Files (Nautilus)") { openOrSwitchTab("files", "file:///android_asset/files/index.html", "Files") },
                                                MacMenuItem("Settings (GNOME)") { openOrSwitchTab("settings", "file:///android_asset/settings/index.html", "Settings") }
                                            )
                                            for (pwa in dynamicPwas.filter { it.category.equals("System Tools", ignoreCase = true) }) {
                                                list.add(MacMenuItem(pwa.title) { openOrSwitchTab(pwa.id, pwa.url, pwa.title) })
                                            }
                                            list
                                        }
                                        item.title.startsWith("Preferences") -> {
                                            val list = mutableListOf(
                                                MacMenuItem("System Settings") { openOrSwitchTab("settings", "file:///android_asset/settings/index.html", "Settings") }
                                            )
                                            for (pwa in dynamicPwas.filter { it.category.equals("Preferences", ignoreCase = true) }) {
                                                list.add(MacMenuItem(pwa.title) { openOrSwitchTab(pwa.id, pwa.url, pwa.title) })
                                            }
                                            list
                                        }
                                        item.title.startsWith("Extensions") -> {
                                            dynamicExtensions.map { ext ->
                                                MacMenuItem("🧩 ${ext.name}") {
                                                    showToast("Extension ${ext.name} is active in all WebViews")
                                                }
                                            }
                                        }
                                        else -> emptyList()
                                    }

                                    if (currentSubmenuCat != item.title) {
                                        currentSubmenuCat = item.title
                                        val loc = IntArray(2)
                                        v.getLocationOnScreen(loc)
                                        val mainX = activeDropdownView?.x ?: 0f
                                        val mainW = activeDropdownView?.width ?: 0

                                        activeSubmenuView?.let { sub ->
                                            workspaceContainer.removeView(sub)
                                            activeSubmenuView = null
                                        }

                                        showMacMenu(v, subItems, isSubMenu = true, subMenuX = mainX + mainW + dpToPx(4), subMenuY = loc[1].toFloat())
                                    }
                                } else {
                                    currentSubmenuCat = null
                                    activeSubmenuView?.let { sub ->
                                        workspaceContainer.removeView(sub)
                                        activeSubmenuView = null
                                    }
                                }
                            } else if (event.action == MotionEvent.ACTION_HOVER_EXIT) {
                                tv?.setBackgroundColor(Color.TRANSPARENT)
                                tv?.setTextColor(Color.parseColor("#e2e8f0"))
                            }
                            false
                        }

                        setOnClickListener {
                            if (item.title.contains("▶")) {
                                val subItems = when {
                                    item.title.startsWith("Accessories") -> {
                                        val list = mutableListOf(
                                            MacMenuItem("Spotlight Search") { toggleSpotlightSearch() }
                                        )
                                        for (pwa in dynamicPwas.filter { it.category.equals("Accessories", ignoreCase = true) }) {
                                            list.add(MacMenuItem(pwa.title) { openOrSwitchTab(pwa.id, pwa.url, pwa.title) })
                                        }
                                        list
                                    }
                                    item.title.startsWith("Internet") -> {
                                        val list = mutableListOf(
                                            MacMenuItem("Web Browser") { openOrSwitchTab("web_" + System.currentTimeMillis(), "https://www.google.com", "Google") }
                                        )
                                        for (pwa in dynamicPwas.filter { it.category.equals("Internet", ignoreCase = true) }) {
                                            list.add(MacMenuItem(pwa.title) { openOrSwitchTab(pwa.id, pwa.url, pwa.title) })
                                        }
                                        list
                                    }
                                    item.title.startsWith("System Tools") -> {
                                        val list = mutableListOf(
                                            MacMenuItem("Files (Nautilus)") { openOrSwitchTab("files", "file:///android_asset/files/index.html", "Files") },
                                            MacMenuItem("Settings (GNOME)") { openOrSwitchTab("settings", "file:///android_asset/settings/index.html", "Settings") }
                                        )
                                        for (pwa in dynamicPwas.filter { it.category.equals("System Tools", ignoreCase = true) }) {
                                            list.add(MacMenuItem(pwa.title) { openOrSwitchTab(pwa.id, pwa.url, pwa.title) })
                                        }
                                        list
                                    }
                                    item.title.startsWith("Preferences") -> {
                                        val list = mutableListOf(
                                            MacMenuItem("System Settings") { openOrSwitchTab("settings", "file:///android_asset/settings/index.html", "Settings") }
                                        )
                                        for (pwa in dynamicPwas.filter { it.category.equals("Preferences", ignoreCase = true) }) {
                                            list.add(MacMenuItem(pwa.title) { openOrSwitchTab(pwa.id, pwa.url, pwa.title) })
                                        }
                                        list
                                    }
                                    item.title.startsWith("Extensions") -> {
                                        dynamicExtensions.map { ext ->
                                            MacMenuItem("🧩 ${ext.name}") {
                                                showToast("Extension ${ext.name} is active in all WebViews")
                                            }
                                        }
                                    }
                                    else -> emptyList()
                                }

                                if (currentSubmenuCat == item.title) {
                                    activeSubmenuView?.let { sub ->
                                        workspaceContainer.removeView(sub)
                                        activeSubmenuView = null
                                    }
                                    currentSubmenuCat = null
                                } else {
                                    currentSubmenuCat = item.title
                                    val loc = IntArray(2)
                                    getLocationOnScreen(loc)
                                    val mainX = activeDropdownView?.x ?: 0f
                                    val mainW = activeDropdownView?.width ?: 0

                                    activeSubmenuView?.let { sub ->
                                        workspaceContainer.removeView(sub)
                                        activeSubmenuView = null
                                    }

                                    showMacMenu(this, subItems, isSubMenu = true, subMenuX = mainX + mainW + dpToPx(4), subMenuY = loc[1].toFloat())
                                }
                            } else {
                                dismissActiveDropdown()
                                item.action?.invoke()
                            }
                        }
                    }
                    itemsContainer.addView(row)
                }
            }
        }

        drawRows(categories)

        val searchInput = EditText(this).apply {
            hint = "Search..."
            setHintTextColor(Color.parseColor("#475569"))
            setTextColor(Color.WHITE)
            textSize = 8.5f * scale
            background = null
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
            maxLines = 1
            isSingleLine = true

            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    val query = s?.toString()?.trim()?.lowercase() ?: ""
                    if (query.isEmpty()) {
                        drawRows(categories)
                    } else {
                        activeSubmenuView?.let { sub ->
                            workspaceContainer.removeView(sub)
                            activeSubmenuView = null
                        }
                        val filtered = allApps.filter { it.title.lowercase().contains(query) }
                        drawRows(filtered)
                    }
                }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })
        }
        searchLayout.addView(searchInput)
        popupView.addView(searchLayout)

        val searchSep = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
            setBackgroundColor(Color.parseColor("#2a2a3a"))
        }
        popupView.addView(searchSep)
        popupView.addView(itemsContainer)

        popupView.layoutParams = FrameLayout.LayoutParams(
            dpToPx((180 * scale).toInt()),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        workspaceContainer.addView(popupView)

        popupView.post {
            val location = IntArray(2)
            anchorView.getLocationOnScreen(location)
            popupView.x = location[0].toFloat()
            popupView.y = (location[1] + anchorView.height + dpToPx(2)).toFloat()
            activeDropdownView = popupView
            popupView.bringToFront()
            cursorView.bringToFront()
            if (isTrackpadMode) {
                touchpadOverlay.bringToFront()
            }
        }
    }

    private fun showSubMenu(anchorView: View, items: List<MacMenuItem>) {
        val popupView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dpToPx(4), 0, dpToPx(4))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#12121a"))
                setStroke(1, Color.parseColor("#2a2a3a"))
                cornerRadius = dpToPx(8).toFloat()
            }
        }
        val popupWindow = android.widget.PopupWindow(
            popupView,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            elevation = dpToPx(12).toFloat()
            isOutsideTouchable = true
        }
        for (item in items) {
            val row = TextView(this).apply {
                text = item.title
                setTextColor(Color.parseColor("#e2e8f0"))
                textSize = 11f
                setPadding(dpToPx(16), dpToPx(6), dpToPx(24), dpToPx(6))
                background = android.graphics.drawable.StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_pressed), android.graphics.drawable.ColorDrawable(Color.parseColor("#3584e4")))
                    addState(intArrayOf(), android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
                }
                setOnClickListener {
                    popupWindow.dismiss()
                    item.action?.invoke()
                }
            }
            popupView.addView(row)
        }
        val location = IntArray(2)
        anchorView.getLocationOnScreen(location)
        popupWindow.showAtLocation(anchorView, Gravity.NO_GRAVITY, location[0] + anchorView.width + dpToPx(4), location[1])
    }

    // GNOME-style Date & Time drop-down Calendar and Notification Area inside workspaceContainer
    private fun showGnomeCalendarDropdown(anchorView: View) {
        dismissActiveDropdown()

        val scale = currentScale
        val popupWidth = dpToPx((380 * scale).toInt())
        val popupHeight = dpToPx((230 * scale).toInt())
        
        val rootDropdown = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dpToPx((12 * scale).toInt()), dpToPx((12 * scale).toInt()), dpToPx((12 * scale).toInt()), dpToPx((12 * scale).toInt()))
            val borderDrawable = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#f912121a")) // Glassmorphism
                setStroke((1.5f * scale).toInt().coerceAtLeast(1), Color.parseColor("#2a2a3a"))
                cornerRadius = dpToPx((10 * scale).toInt()).toFloat()
            }
            background = borderDrawable
            elevation = dpToPx((12 * scale).toInt()).toFloat()
        }

        // --- Left Column: Notifications ---
        val notificationsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.1f).apply {
                rightMargin = dpToPx((12 * scale).toInt())
            }
        }

        val notifHeader = TextView(this).apply {
            text = "Notifications"
            setTextColor(Color.parseColor("#94a3b8"))
            textSize = 9.5f * scale
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dpToPx((8 * scale).toInt()))
        }
        notificationsLayout.addView(notifHeader)

        val notifScroll = android.widget.ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            isVerticalScrollBarEnabled = false
        }
        val notifList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val addNotification = { titleStr: String, textStr: String, timeStr: String ->
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dpToPx((6 * scale).toInt()), dpToPx((6 * scale).toInt()), dpToPx((6 * scale).toInt()), dpToPx((6 * scale).toInt()))
                val bg = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.parseColor("#1a1a26"))
                    cornerRadius = dpToPx((5 * scale).toInt()).toFloat()
                }
                background = bg
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dpToPx((6 * scale).toInt())
                }
            }
            
            val tRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            val titleText = TextView(this).apply {
                text = titleStr
                setTextColor(Color.WHITE)
                textSize = 8.5f * scale
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val timeText = TextView(this).apply {
                text = timeStr
                setTextColor(Color.parseColor("#64748b"))
                textSize = 7f * scale
            }
            tRow.addView(titleText)
            tRow.addView(timeText)
            item.addView(tRow)

            val descText = TextView(this).apply {
                text = textStr
                setTextColor(Color.parseColor("#94a3b8"))
                textSize = 8f * scale
                setPadding(0, dpToPx((2 * scale).toInt()), 0, 0)
            }
            item.addView(descText)
            notifList.addView(item)
        }

        addNotification("System Update", "Anodyne Desktop is up to date.", "Just now")
        addNotification("Remote Access Server", "Active connection PIN is $accessPin", "2m ago")
        addNotification("Battery Status", "Running on " + getBatteryPowerSource(), "10m ago")

        notifScroll.addView(notifList)
        notificationsLayout.addView(notifScroll)
        rootDropdown.addView(notificationsLayout)

        val divider = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                dpToPx((1 * scale).toInt().coerceAtLeast(1)),
                ViewGroup.LayoutParams.MATCH_PARENT
            ).apply {
                rightMargin = dpToPx((12 * scale).toInt())
            }
            setBackgroundColor(Color.parseColor("#2a2a3a"))
        }
        rootDropdown.addView(divider)

        // --- Right Column: Calendar ---
        val calendarLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.0f)
        }

        val calHeader = TextView(this).apply {
            val sdf = SimpleDateFormat("MMMM yyyy", Locale.US)
            text = sdf.format(Date())
            setTextColor(Color.WHITE)
            textSize = 9.5f * scale
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, dpToPx((8 * scale).toInt()))
        }
        calendarLayout.addView(calHeader)

        val daysGrid = android.widget.GridLayout(this).apply {
            columnCount = 7
            rowCount = 6
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }

        val dayLabels = listOf("Su", "Mo", "Tu", "We", "Th", "Fr", "Sa")
        for (day in dayLabels) {
            val label = TextView(this).apply {
                text = day
                setTextColor(Color.parseColor("#64748b"))
                textSize = 8f * scale
                gravity = Gravity.CENTER
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                layoutParams = android.widget.GridLayout.LayoutParams().apply {
                    width = 0
                    height = ViewGroup.LayoutParams.WRAP_CONTENT
                    columnSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f)
                }
            }
            daysGrid.addView(label)
        }

        val calendar = java.util.Calendar.getInstance()
        val currentDay = calendar.get(java.util.Calendar.DAY_OF_MONTH)
        calendar.set(java.util.Calendar.DAY_OF_MONTH, 1)
        val firstDayOfWeek = calendar.get(java.util.Calendar.DAY_OF_WEEK) - 1
        val maxDays = calendar.getActualMaximum(java.util.Calendar.DAY_OF_MONTH)

        for (i in 0 until firstDayOfWeek) {
            val blank = View(this).apply {
                layoutParams = android.widget.GridLayout.LayoutParams().apply {
                    width = 0
                    height = dpToPx((18 * scale).toInt())
                    columnSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f)
                }
            }
            daysGrid.addView(blank)
        }

        for (day in 1..maxDays) {
            val cell = TextView(this).apply {
                text = day.toString()
                textSize = 8f * scale
                gravity = Gravity.CENTER
                
                layoutParams = android.widget.GridLayout.LayoutParams().apply {
                    width = 0
                    height = dpToPx((16 * scale).toInt())
                    columnSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f)
                }

                if (day == currentDay) {
                    setTextColor(Color.WHITE)
                    val bg = android.graphics.drawable.GradientDrawable().apply {
                        setColor(Color.parseColor("#3584e4"))
                        cornerRadius = dpToPx((8 * scale).toInt()).toFloat()
                    }
                    background = bg
                } else {
                    setTextColor(Color.parseColor("#e2e8f0"))
                }
            }
            daysGrid.addView(cell)
        }

        calendarLayout.addView(daysGrid)
        rootDropdown.addView(calendarLayout)

        rootDropdown.layoutParams = FrameLayout.LayoutParams(
            popupWidth,
            popupHeight
        )
        workspaceContainer.addView(rootDropdown)

        rootDropdown.post {
            val location = IntArray(2)
            anchorView.getLocationOnScreen(location)
            rootDropdown.x = (location[0] - dpToPx((180 * scale).toInt())).toFloat().coerceAtLeast(0f)
            rootDropdown.y = (location[1] + anchorView.height + dpToPx((2 * scale).toInt())).toFloat()
            activeDropdownView = rootDropdown
            rootDropdown.bringToFront()
            cursorView.bringToFront()
            if (isTrackpadMode) {
                touchpadOverlay.bringToFront()
            }
        }
    }

    fun showTooltip(text: String, x: Float, y: Float) {
        runOnUiThread {
            tooltipView.text = text
            tooltipView.visibility = View.VISIBLE
            tooltipView.x = x + dpToPx(12)
            tooltipView.y = y + dpToPx(16)
            tooltipView.bringToFront()
            cursorView.bringToFront()
        }
    }

    fun hideTooltip() {
        runOnUiThread {
            tooltipView.visibility = View.GONE
        }
    }

    fun registerTooltipHover(view: View, textProvider: () -> String) {
        view.setOnHoverListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                    showTooltip(textProvider(), cursorX, cursorY)
                }
                MotionEvent.ACTION_HOVER_EXIT -> {
                    hideTooltip()
                }
            }
            false
        }
    }

    // Dynamic TopBar menu updater according to focused PWA
    private fun refreshTopBarMenus() {
        runOnUiThread {
            leftContainer.removeAllViews()
            leftContainer.addView(logoText)
        }
    }

    private fun registerPwaMenus(tabId: String, appName: String, json: String) {
        runOnUiThread {
            try {
                val list = mutableListOf<AppMenuCategory>()
                val arr = org.json.JSONArray(json)
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val cat = obj.getString("category")
                    val itemsArr = obj.getJSONArray("items")
                    val itemsList = mutableListOf<String>()
                    for (j in 0 until itemsArr.length()) {
                        itemsList.add(itemsArr.getString(j))
                    }
                    list.add(AppMenuCategory(cat, itemsList))
                }
                tabMenusMap[tabId] = AppMenuConfig(appName, list)
                if (currentTabIndex in tabsList.indices && tabsList[currentTabIndex].id == tabId) {
                    refreshTopBarMenus()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing PWA menus JSON", e)
            }
        }
    }

    // View-based Dropdown Menu system inside workspaceContainer (Trackpad hover & click friendly)
    private fun showMacMenu(
        anchorView: View, 
        menuItems: List<MacMenuItem>, 
        isSubMenu: Boolean = false, 
        subMenuX: Float = 0f, 
        subMenuY: Float = 0f,
        useDirectCoords: Boolean = false
    ) {
        if (!isSubMenu) {
            dismissActiveDropdown()
        }

        val scale = currentScale
        val textSz = 9.5f * scale
        val padLeft = dpToPx((14 * scale).toInt())
        val padTop = dpToPx((5 * scale).toInt())
        val padRight = dpToPx((22 * scale).toInt())
        val padBottom = dpToPx((5 * scale).toInt())
        val cornerRad = dpToPx((6 * scale).toInt()).toFloat()
        val elev = dpToPx((6 * scale).toInt()).toFloat()

        val popupView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dpToPx((4 * scale).toInt()), 0, dpToPx((4 * scale).toInt()))
            val borderDrawable = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#12121a"))
                setStroke(1, Color.parseColor("#2a2a3a"))
                cornerRadius = cornerRad
            }
            background = borderDrawable
            elevation = elev
        }

        for (item in menuItems) {
            if (item.isSeparator) {
                val sep = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        1
                    ).apply {
                        setMargins(0, dpToPx((3 * scale).toInt()), 0, dpToPx((3 * scale).toInt()))
                    }
                    setBackgroundColor(Color.parseColor("#2a2a3a"))
                }
                popupView.addView(sep)
            } else {
                val row = TextView(this).apply {
                    text = item.title
                    setTextColor(Color.parseColor("#e2e8f0"))
                    textSize = textSz
                    setPadding(padLeft, padTop, padRight, padBottom)
                    gravity = Gravity.CENTER_VERTICAL
                    val hoverBg = android.graphics.drawable.StateListDrawable().apply {
                        addState(intArrayOf(android.R.attr.state_pressed), android.graphics.drawable.ColorDrawable(Color.parseColor("#3584e4")))
                        addState(intArrayOf(), android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
                    }
                    background = hoverBg
                    isClickable = true

                    setOnHoverListener { v, event ->
                        val tv = v as? TextView
                        if (event.action == MotionEvent.ACTION_HOVER_ENTER) {
                            tv?.setBackgroundColor(Color.parseColor("#3584e4"))
                            tv?.setTextColor(Color.WHITE)
                        } else if (event.action == MotionEvent.ACTION_HOVER_EXIT) {
                            tv?.setBackgroundColor(Color.TRANSPARENT)
                            tv?.setTextColor(Color.parseColor("#e2e8f0"))
                        }
                        false
                    }

                    setOnClickListener {
                        dismissActiveDropdown()
                        item.action?.invoke()
                    }

                    setOnLongClickListener {
                        val pwa = dynamicPwas.find { it.title == item.title }
                        val ext = dynamicExtensions.find { "🧩 " + it.name == item.title || it.name == item.title }
                        if (pwa != null) {
                            AlertDialog.Builder(this@MainActivity)
                                .setTitle("Uninstall Application")
                                .setMessage("Are you sure you want to uninstall ${pwa.title}?")
                                .setPositiveButton("Uninstall") { _, _ ->
                                    unregisterDynamicPwaFromWeb(pwa.id)
                                    dismissActiveDropdown()
                                    showToast("${pwa.title} uninstalled")
                                }
                                .setNegativeButton("Cancel", null)
                                .show()
                            true
                        } else if (ext != null) {
                            AlertDialog.Builder(this@MainActivity)
                                .setTitle("Remove Extension")
                                .setMessage("Are you sure you want to remove extension ${ext.name}?")
                                .setPositiveButton("Remove") { _, _ ->
                                    unregisterDynamicExtensionFromWeb(ext.name)
                                    dismissActiveDropdown()
                                    showToast("Extension ${ext.name} removed")
                                }
                                .setNegativeButton("Cancel", null)
                                .show()
                            true
                        } else {
                            false
                        }
                    }
                }
                popupView.addView(row)
            }
        }

        popupView.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        workspaceContainer.addView(popupView)

        popupView.post {
            if (isSubMenu) {
                popupView.x = subMenuX
                popupView.y = subMenuY
                activeSubmenuView = popupView
            } else if (useDirectCoords) {
                popupView.x = subMenuX
                popupView.y = subMenuY
                activeDropdownView = popupView
            } else {
                val location = IntArray(2)
                anchorView.getLocationOnScreen(location)
                popupView.x = location[0].toFloat()
                popupView.y = (location[1] + anchorView.height + dpToPx(2)).toFloat()
                activeDropdownView = popupView
            }
            popupView.bringToFront()
            cursorView.bringToFront()
            if (isTrackpadMode) {
                touchpadOverlay.bringToFront()
            }
        }
    }

    private fun dismissActiveDropdown() {
        activeSubmenuView?.let {
            workspaceContainer.removeView(it)
            activeSubmenuView = null
        }
        activeDropdownView?.let {
            workspaceContainer.removeView(it)
            activeDropdownView = null
        }
        activeTabNavDropdown?.let {
            workspaceContainer.removeView(it)
            activeTabNavDropdown = null
        }
    }

    private fun showWifiDropdown() {
        showMacMenu(wifiTextView, listOf(
            MacMenuItem("SSID: " + getWifiSSID()),
            MacMenuItem("IP Address: " + getLocalIpAddress()),
            MacMenuItem(isSeparator = true),
            MacMenuItem("Network Settings...") { openOrSwitchTab("settings", "file:///android_asset/settings/index.html", "Settings") }
        ))
    }

    private fun showBatteryDropdown() {
        showMacMenu(batteryTextView, listOf(
            MacMenuItem("Power Source: " + getBatteryPowerSource()),
            MacMenuItem("Current Charge: " + getBatteryPct() + "%"),
            MacMenuItem(isSeparator = true),
            MacMenuItem("Battery Health: Good")
        ))
    }

    private fun showCellularDropdown() {
        showMacMenu(cellularTextView, listOf(
            MacMenuItem("Carrier: Anodyne Mobile"),
            MacMenuItem("Signal Strength: Excellent"),
            MacMenuItem("Network Type: " + getCellularNetworkType()),
            MacMenuItem(isSeparator = true),
            MacMenuItem("Data Usage: 14.2 GB used this month")
        ))
    }

    fun getCellularNetworkType(): String {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val activeNetwork = cm.activeNetwork ?: return "4G"
            val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return "4G"
            
            if (capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)) {
                val tm = getSystemService(Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager
                if (androidx.core.app.ActivityCompat.checkSelfPermission(this, android.Manifest.permission.READ_PHONE_STATE) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    val networkType = tm.networkType
                    return when (networkType) {
                        android.telephony.TelephonyManager.NETWORK_TYPE_NR -> "5G"
                        android.telephony.TelephonyManager.NETWORK_TYPE_LTE -> "4G"
                        else -> "4G"
                    }
                }
            }
        } catch (e: Exception) {}
        return "5G"
    }

    private fun getWifiSSID(): String {
        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val info = wifiManager.connectionInfo
            if (info != null && info.ssid != null && info.ssid != "<unknown ssid>" && info.ssid.isNotEmpty()) {
                val ssid = info.ssid
                return if (ssid.startsWith("\"") && ssid.endsWith("\"")) ssid.substring(1, ssid.length - 1) else ssid
            }
        } catch (e: Exception) {}
        return "Unknown SSID"
    }

    private fun getLocalIpAddress(): String {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        return addr.hostAddress ?: "Unknown"
                    }
                }
            }
        } catch (e: Exception) {}
        return "127.0.0.1"
    }

    private fun getBatteryPct(): Int {
        try {
            val batteryManager = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            return batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        } catch (e: Exception) {}
        return 100
    }

    private fun getBatteryPowerSource(): String {
        try {
            val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return "Battery"
            val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
            if (plugged == BatteryManager.BATTERY_PLUGGED_AC || plugged == BatteryManager.BATTERY_PLUGGED_USB || plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS) {
                return "Power Adapter"
            }
        } catch (e: Exception) {}
        return "Battery"
    }

    private fun showAboutDialog() {
        AlertDialog.Builder(this)
            .setTitle("About Anodyne Desktop")
            .setMessage("Anodyne Desktop Virtual Container\nVersion 2.0 (Build 2026.08.01)\n\nCreated to preserve look-and-feel virtualization.\nAuthor: Gagan\n© 2026 7CGPA-Labs. All rights reserved.")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showGoToUrlDialog() {
        val input = EditText(this).apply {
            hint = "https://example.com"
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
            setPadding(dpToPx(16), dpToPx(8), dpToPx(16), dpToPx(8))
        }
        AlertDialog.Builder(this)
            .setTitle("Go to Website")
            .setView(input)
            .setPositiveButton("Open") { _, _ ->
                var url = input.text.toString().trim()
                if (url.isNotEmpty()) {
                    if (!url.startsWith("http://") && !url.startsWith("https://")) {
                        url = "https://$url"
                    }
                    openOrSwitchTab("web_" + System.currentTimeMillis(), url, url)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showToast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun getActiveTabItem(): TabItem? {
        if (currentTabIndex in tabsList.indices) {
            return tabsList[currentTabIndex]
        }
        return null
    }

    private fun getActiveWebView(): WebView? {
        if (currentTabIndex in tabsList.indices) {
            return tabsList[currentTabIndex].webView
        }
        return null
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
    }

    private fun createTabWebView(url: String): WebView {
        val newWebView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            visibility = View.GONE
            setLayerType(View.LAYER_TYPE_HARDWARE, null)

            // Configure Native Scrollbars
            isScrollbarFadingEnabled = false
            scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
            isHorizontalScrollBarEnabled = true
            isVerticalScrollBarEnabled = true

            // Disable long click to prevent mobile selection handle tropes
            setOnLongClickListener { true }

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    Log.d(TAG, "Page finished loading: $url")

                    if (url != null && url.startsWith("file:///android_asset/") && view != null) {
                        try {
                            val channel = view.createWebMessageChannel()
                            val nativePort = channel[0]
                            val webPort = channel[1]
                            
                            nativePort.setWebMessageCallback(object : android.webkit.WebMessagePort.WebMessageCallback() {
                                override fun onMessage(port: android.webkit.WebMessagePort?, message: android.webkit.WebMessage?) {
                                    val payload = message?.data ?: return
                                    handleIpcMessage(view, payload)
                                }
                            })
                            
                            val targetOrigin = if (url.startsWith("file://")) {
                                android.net.Uri.parse("*")
                            } else {
                                android.net.Uri.parse(url)
                            }
                            view.postWebMessage(android.webkit.WebMessage("init-ipc", arrayOf(webPort)), targetOrigin)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error initializing WebMessagePort IPC channel", e)
                        }
                    }

                    val targetWidth = 1600
                    view?.evaluateJavascript(
                        """
                        (function() {
                            function updateViewportScale() {
                                var targetWidth = $targetWidth;
                                var meta = document.querySelector('meta[name=viewport]');
                                if (!meta) {
                                    meta = document.createElement('meta');
                                    meta.name = 'viewport';
                                    document.head.appendChild(meta);
                                }
                                var screenWidth = window.screen.width;
                                if (window.outerWidth && window.outerWidth > 0 && window.outerWidth < screenWidth) {
                                    screenWidth = window.outerWidth;
                                }
                                var scale = screenWidth / targetWidth;
                                meta.setAttribute('content', 'width=' + targetWidth + ', initial-scale=' + scale + ', minimum-scale=' + scale + ', maximum-scale=' + scale + ', user-scalable=no');
                            }
                            if (!window.hasResizeViewportListener) {
                                window.hasResizeViewportListener = true;
                                window.addEventListener('resize', updateViewportScale);
                            }
                            updateViewportScale();
                            
                            var style = document.getElementById('zoom-style');
                            if (!style) {
                                style = document.createElement('style');
                                style.id = 'zoom-style';
                                style.innerHTML = 'html { zoom: var(--ui-scale, 1.0); }';
                                document.head.appendChild(style);
                            }
                            document.documentElement.style.setProperty('--ui-scale', '1.0');
                            
                            // Inject custom styled desktop scrollbars
                            var sbStyle = document.getElementById('scrollbar-style');
                            if (!sbStyle) {
                                sbStyle = document.createElement('style');
                                sbStyle.id = 'scrollbar-style';
                                sbStyle.innerHTML = '::-webkit-scrollbar { width: 8px !important; height: 8px !important; } ::-webkit-scrollbar-track { background: #0c0c14 !important; } ::-webkit-scrollbar-thumb { background: #475569 !important; border-radius: 4px !important; } ::-webkit-scrollbar-thumb:hover { background: #64748b !important; }';
                                document.head.appendChild(sbStyle);
                            }

                            // Inject baseline OS stylesheet to enforce desktop styling (disable user-selection & touch-callouts)
                            var baselineStyle = document.getElementById('baseline-style');
                            if (!baselineStyle) {
                                baselineStyle = document.createElement('style');
                                baselineStyle.id = 'baseline-style';
                                baselineStyle.innerHTML = '* { -webkit-user-select: none !important; user-select: none !important; -webkit-touch-callout: none !important; touch-action: manipulation !important; } input, textarea, [contenteditable=true] { -webkit-user-select: text !important; user-select: text !important; }';
                                document.head.appendChild(baselineStyle);
                            }

                            // Inject print adapter bridge
                            if (window.sysContext && typeof window.sysContext.printDocument === 'function') {
                                window.print = function() {
                                    window.sysContext.printDocument();
                                };
                            }

                            // Fail-Safe Tab form recovery script (Auto-save drafts & scroll positions)
                            (function() {
                                var tabKey = "tab_draft_" + window.location.href;
                                try {
                                    var data = JSON.parse(localStorage.getItem(tabKey));
                                    if (data) {
                                        for (var selector in (data.inputs || {})) {
                                            var el = document.querySelector(selector);
                                            if (el && el.value !== data.inputs[selector]) {
                                                el.value = data.inputs[selector];
                                                el.dispatchEvent(new Event('input', { bubbles: true }));
                                            }
                                        }
                                        if (data.scroll) {
                                            window.scrollTo(data.scroll.x, data.scroll.y);
                                        }
                                    }
                                } catch (e) {}

                                setInterval(function() {
                                    try {
                                        var inputs = {};
                                        document.querySelectorAll("input, textarea").forEach(function(el, idx) {
                                            var sel = el.id ? "#" + el.id : "";
                                            if (!sel && el.name) sel = el.tagName.toLowerCase() + "[name='" + el.name + "']";
                                            if (!sel) sel = el.tagName.toLowerCase() + ":nth-of-type(" + (idx + 1) + ")";
                                            if (el.type !== "password") {
                                                inputs[sel] = el.value;
                                            }
                                        });
                                        var data = {
                                            inputs: inputs,
                                            scroll: { x: window.scrollX, y: window.scrollY }
                                        };
                                        localStorage.setItem(tabKey, JSON.stringify(data));
                                    } catch (e) {}
                                }, 5000);
                            })();
                            
                            // Focus state detection for virtual keyboard integration
                            if (window.hasKeyboardListener) return;
                            window.hasKeyboardListener = true;
                            document.addEventListener('focusin', function(e) {
                                var el = e.target;
                                if (el && (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA' || el.contentEditable === 'true')) {
                                    if (window.sysContext && typeof window.sysContext.showFloatingKeyboard === 'function') {
                                        window.sysContext.showFloatingKeyboard();
                                    }
                                }
                            });
                            document.addEventListener('focusout', function(e) {
                                var el = e.target;
                                if (el && (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA' || el.contentEditable === 'true')) {
                                    if (window.sysContext && typeof window.sysContext.hideFloatingKeyboard === 'function') {
                                        window.sysContext.hideFloatingKeyboard();
                                    }
                                }
                            });
                        })();
                        """.trimIndent(),
                        null
                    )
                    
                    // Inject dynamically registered extensions
                    for (ext in dynamicExtensions) {
                        view?.evaluateJavascript(ext.script, null)
                    }
                }
            }
        }

        newWebView.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: android.webkit.GeolocationPermissions.Callback?
            ) {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Location Access Request")
                    .setMessage("This application ($url) wants to access your GPS Location. Allow GPS coordinates access?")
                    .setPositiveButton("Allow") { _, _ ->
                        callback?.invoke(origin, true, false)
                        showActiveIndicator("📍 GPS Location Active")
                    }
                    .setNegativeButton("Block") { _, _ ->
                        callback?.invoke(origin, false, false)
                    }
                    .setCancelable(false)
                    .show()
            }

            override fun onPermissionRequest(request: android.webkit.PermissionRequest?) {
                if (request == null) return
                val resources = request.resources
                val resourcesStr = resources.joinToString(", ") { res ->
                    when (res) {
                        android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE -> "Microphone"
                        android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE -> "Camera"
                        else -> res
                    }
                }
                
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("App Permission Request")
                    .setMessage("This application ($url) wants to access: $resourcesStr. Allow hardware access?")
                    .setPositiveButton("Allow") { _, _ ->
                        request.grant(resources)
                        if (resources.contains(android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE)) {
                            showActiveIndicator("🎥 Camera Active")
                        }
                        if (resources.contains(android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE)) {
                            showActiveIndicator("🎙️ Microphone Active")
                        }
                    }
                    .setNegativeButton("Block") { _, _ ->
                        request.deny()
                    }
                    .setCancelable(false)
                    .show()
            }
        }

        newWebView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            allowUniversalAccessFromFileURLs = false
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = false
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        }

        newWebView.setDownloadListener { url, userAgent, contentDisposition, mimetype, contentLength ->
            try {
                var filename = android.webkit.URLUtil.guessFileName(url, contentDisposition, mimetype)
                if (filename.isNullOrEmpty()) {
                    filename = "downloaded_file"
                }
                
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Downloading: $filename", Toast.LENGTH_SHORT).show()
                }
                
                Thread {
                    try {
                        val connection = java.net.URL(url).openConnection()
                        connection.setRequestProperty("User-Agent", userAgent)
                        val inputStream = connection.getInputStream()
                        
                        val destFile = java.io.File(
                            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
                            filename
                        )
                        val outputStream = java.io.FileOutputStream(destFile)
                        
                        val buffer = ByteArray(4096)
                        var bytesRead: Int
                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            outputStream.write(buffer, 0, bytesRead)
                        }
                        
                        outputStream.close()
                        inputStream.close()
                        
                        synchronized(downloadRecords) {
                            downloadRecords.add(DownloadRecord(filename, destFile, mimetype))
                        }
                        
                        runOnUiThread {
                            downloadsTrayText.setTextColor(Color.parseColor("#3584e4")) // Highlight blue
                            Toast.makeText(this@MainActivity, "Download finished: $filename", Toast.LENGTH_LONG).show()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error downloading file from URL", e)
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "Download failed for $filename", Toast.LENGTH_SHORT).show()
                        }
                    }
                }.start()
            } catch (e: Exception) {
                Log.e(TAG, "Error initiating download process", e)
            }
        }

        val sysContext = AndroidSysContext(this,
            launchTabCallback = { appId, targetUrl, title ->
                openOrSwitchTab(appId, targetUrl, title)
            },
            evaluateJs = { js ->
                runOnUiThread {
                    newWebView.evaluateJavascript(js, null)
                }
            },
            showKeyboardCallback = {
                showVirtualKeyboard()
                presentation?.showVirtualKeyboard()
            },
            hideKeyboardCallback = {
                hideVirtualKeyboard()
                presentation?.hideVirtualKeyboard()
            },
            setMenusCallback = { appName, json ->
                registerPwaMenus(url, appName, json)
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
        refreshTopBarMenus()
    }

    private fun closeTab(tab: TabItem) {
        if (tab.id == "home") return

        val checkJs = """
            (function() {
                var dirty = false;
                document.querySelectorAll("input, textarea").forEach(function(el) {
                    if (el.value && el.value.trim() !== "" && el.type !== "submit" && el.type !== "button" && el.type !== "hidden") {
                        dirty = true;
                    }
                });
                return dirty;
            })();
        """.trimIndent()

        tab.webView.evaluateJavascript(checkJs) { result ->
            val isDirty = result?.toBoolean() ?: false
            if (isDirty) {
                AlertDialog.Builder(this)
                    .setTitle("Unsaved Changes")
                    .setMessage("You have unsaved changes in this tab. Close anyway?")
                    .setPositiveButton("Close Tab") { _, _ ->
                        executeCloseTab(tab)
                    }
                    .setNegativeButton("Keep Open", null)
                    .show()
            } else {
                executeCloseTab(tab)
            }
        }
    }

    private fun executeCloseTab(tab: TabItem) {
        val index = tabsList.indexOf(tab)
        if (index == -1) return

        webViewContainer.removeView(tab.webView)
        tab.webView.destroy()
        tabsList.removeAt(index)
        tabMenusMap.remove(tab.id)

        if (currentTabIndex >= tabsList.size) {
            currentTabIndex = tabsList.size - 1
        } else if (currentTabIndex > index) {
            currentTabIndex--
        }

        switchTab(currentTabIndex)
    }

    private fun showTabContextMenu(anchorView: View, tabIndex: Int) {
        val tab = tabsList[tabIndex]
        val list = mutableListOf<MacMenuItem>()

        list.add(MacMenuItem("🔄 Reload Tab") {
            tab.webView.reload()
        })

        list.add(MacMenuItem("👥 Duplicate Tab") {
            val url = tab.webView.url ?: tab.url
            openOrSwitchTab("web_browser_" + System.currentTimeMillis(), url, tab.title)
        })

        if (tab.id != "home") {
            list.add(MacMenuItem("❌ Close Tab") {
                closeTab(tab)
            })

            list.add(MacMenuItem("🧹 Close Other Tabs") {
                val toClose = tabsList.filter { it.id != "home" && it.id != tab.id }
                for (t in toClose) {
                    executeCloseTab(t)
                }
            })
        }

        showMacMenu(anchorView, list)
    }

    private fun findViewAt(viewGroup: ViewGroup, x: Float, y: Float): View? {
        val count = viewGroup.childCount
        for (i in count - 1 downTo 0) {
            val child = viewGroup.getChildAt(i)
            if (child.visibility == View.VISIBLE) {
                val loc = IntArray(2)
                child.getLocationOnScreen(loc)
                val rx = loc[0]
                val ry = loc[1]
                if (x >= rx && x <= rx + child.width && y >= ry && y <= ry + child.height) {
                    if (child is ViewGroup) {
                        val nested = findViewAt(child, x, y)
                        if (nested != null) return nested
                    }
                    return child
                }
            }
        }
        return null
    }

    private fun findTabItemView(view: View?): View? {
        var current = view
        while (current != null && current != tabContainer) {
            val tag = current.tag as? String
            if (tag != null && tag.startsWith("tab_")) {
                return current
            }
            val parent = current.parent
            if (parent is View) {
                current = parent
            } else {
                break
            }
        }
        return null
    }

    private fun refreshTabUI() {
        tabContainer.removeAllViews()

        for (i in 0 until tabsList.size) {
            val tab = tabsList[i]
            val isActive = (i == currentTabIndex)

            val tabItem = LinearLayout(this).apply {
                tag = "tab_$i"
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    dpToPx((100 * currentScale).toInt()),
                    LinearLayout.LayoutParams.MATCH_PARENT
                ).apply {
                    topMargin = dpToPx((3 * currentScale).toInt())
                    rightMargin = dpToPx((2 * currentScale).toInt())
                }
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dpToPx(8), 0, dpToPx(6), 0)
                
                val tabDrawable = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.parseColor(if (isActive) "#1e1e2e" else "#0c0c14"))
                    val r = dpToPx(5).toFloat()
                    cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
                }
                background = tabDrawable
                
                setOnClickListener {
                    switchTab(i)
                    if (tab.id != "home") {
                        showTabNavigationDropdown(this, i)
                    }
                }
            }
            registerTooltipHover(tabItem) { "Tab: " + tab.title }

            val titleText = TextView(this).apply {
                text = tab.title
                setTextColor(Color.parseColor(if (isActive) "#f8fafc" else "#94a3b8"))
                textSize = 8.5f * currentScale
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
                )
            }
            tabItem.addView(titleText)

            if (tab.id != "home") {
                val closeBtn = TextView(this).apply {
                    text = " × "
                    setTextColor(Color.parseColor(if (isActive) "#94a3b8" else "#64748b"))
                    textSize = 10f * currentScale
                    gravity = Gravity.CENTER
                    setPadding(dpToPx(2), dpToPx(1), dpToPx(2), dpToPx(1))
                    val btnBg = android.graphics.drawable.GradientDrawable().apply {
                        setColor(Color.TRANSPARENT)
                        cornerRadius = dpToPx(5).toFloat()
                    }
                    background = btnBg
                    
                    setOnHoverListener { v, event ->
                        when (event.action) {
                            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                                (v.background as? android.graphics.drawable.GradientDrawable)?.setColor(Color.parseColor("#334155"))
                                setTextColor(Color.WHITE)
                                showTooltip("Close Tab", cursorX, cursorY)
                            }
                            MotionEvent.ACTION_HOVER_EXIT -> {
                                (v.background as? android.graphics.drawable.GradientDrawable)?.setColor(Color.TRANSPARENT)
                                setTextColor(Color.parseColor(if (isActive) "#94a3b8" else "#64748b"))
                                hideTooltip()
                            }
                        }
                        false
                    }
                    
                    setOnClickListener {
                        closeTab(tab)
                    }
                }
                tabItem.addView(closeBtn)
            }

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

    // Spotlight Search Operations
    private fun setupSpotlightSearch() {
        spotlightOverlay = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#99000000"))
            visibility = View.GONE
            setOnClickListener { hideSpotlightSearch() }
        }

        val searchPanel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(
                dpToPx(480),
                dpToPx(50)
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dpToPx(100)
            }
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(16), 0, dpToPx(16), 0)

            val borderDrawable = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#161622"))
                setStroke(1, Color.parseColor("#2a2a3a"))
                cornerRadius = dpToPx(12).toFloat()
            }
            background = borderDrawable
            setOnClickListener { } // Consume click
        }

        val searchIcon = TextView(this).apply {
            text = "🔍"
            setTextColor(Color.parseColor("#94a3b8"))
            textSize = 16f
            setPadding(0, 0, dpToPx(12), 0)
        }
        searchPanel.addView(searchIcon)

        spotlightInput = EditText(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            background = null
            hint = "Type what you want to do (e.g. 'Open Files', 'Wi-Fi')..."
            setHintTextColor(Color.parseColor("#475569"))
            setTextColor(Color.WHITE)
            textSize = 14f
            maxLines = 1
            isSingleLine = true
            
            setOnKeyListener { _, keyCode, event ->
                if (event.action == android.view.KeyEvent.ACTION_DOWN && keyCode == android.view.KeyEvent.KEYCODE_ENTER) {
                    val query = text.toString()
                    if (query.trim().isNotEmpty()) {
                        triggerSpotlightSearch(query)
                        hideSpotlightSearch()
                    }
                    true
                } else {
                    false
                }
            }
        }
        searchPanel.addView(spotlightInput)
        spotlightOverlay.addView(searchPanel)

        // Pinned & Recent App Shortcuts directly under the search panel
        val shortcutsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(
                dpToPx(480),
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dpToPx(165)
            }
            gravity = Gravity.CENTER
        }

        val addShortcut: (String, String, () -> Unit) -> Unit = { iconStr, labelStr, action ->
            val card = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(this@MainActivity.dpToPx(12), this@MainActivity.dpToPx(8), this@MainActivity.dpToPx(12), this@MainActivity.dpToPx(8))
                val bg = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.parseColor("#1a1a26"))
                    cornerRadius = this@MainActivity.dpToPx(8).toFloat()
                }
                background = bg
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    this@MainActivity.dpToPx(90),
                    this@MainActivity.dpToPx(65)
                ).apply {
                    leftMargin = this@MainActivity.dpToPx(8)
                    rightMargin = this@MainActivity.dpToPx(8)
                }
                setOnClickListener {
                    hideSpotlightSearch()
                    action()
                }
            }
            val iconView = TextView(this@MainActivity).apply {
                text = iconStr
                textSize = 18f
                gravity = Gravity.CENTER
            }
            val labelView = TextView(this@MainActivity).apply {
                text = labelStr
                setTextColor(Color.WHITE)
                textSize = 9f
                setPadding(0, this@MainActivity.dpToPx(4), 0, 0)
                gravity = Gravity.CENTER
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            card.addView(iconView)
            card.addView(labelView)
            shortcutsRow.addView(card)
        }

        addShortcut("📁", "Files") { openOrSwitchTab("files", "file:///android_asset/files/index.html", "Files") }
        addShortcut("⚙️", "Settings") { openOrSwitchTab("settings", "file:///android_asset/settings/index.html", "Settings") }
        addShortcut("🌐", "Browser") { openOrSwitchTab("web_browser_" + System.currentTimeMillis(), "https://www.google.com", "Browser") }
        addShortcut("📅", "Calendar") { showGnomeCalendarDropdown(clockTextView) }

        spotlightOverlay.addView(shortcutsRow)
        workspaceContainer.addView(spotlightOverlay)
    }

    private fun triggerSpotlightSearch(query: String) {
        val trimmed = query.trim().lowercase(Locale.US)
        if (trimmed.isEmpty()) return

        when {
            trimmed.contains("print") || trimmed.contains("contract") || trimmed.contains("file") || trimmed.contains("pdf") || trimmed.contains("documents") || trimmed.contains("nautilus") -> {
                openOrSwitchTab("files", "file:///android_asset/files/index.html", "Files")
            }
            trimmed.contains("settings") || trimmed.contains("system") || trimmed.contains("control") || trimmed.contains("wifi") || trimmed.contains("network") || trimmed.contains("battery") || trimmed.contains("power") -> {
                openOrSwitchTab("settings", "file:///android_asset/settings/index.html", "Settings")
            }
            trimmed.contains("calendar") || trimmed.contains("clock") || trimmed.contains("time") || trimmed.contains("date") || trimmed.contains("schedule") -> {
                showGnomeCalendarDropdown(clockTextView)
            }
            trimmed.contains("calc") || trimmed.contains("calculator") || trimmed.contains("math") || trimmed.contains("compute") || trimmed.contains("budget") -> {
                openOrSwitchTab("home", "file:///android_asset/homepage/index.html", "Dashboard")
            }
            else -> {
                val isUrl = trimmed.contains(".") && !trimmed.contains(" ")
                val targetUrl = if (isUrl) {
                    if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
                        "https://$trimmed"
                    } else {
                        trimmed
                    }
                } else {
                    try {
                        "https://www.google.com/search?q=" + java.net.URLEncoder.encode(query.trim(), "UTF-8")
                    } catch (e: Exception) {
                        "https://www.google.com/search?q=$trimmed"
                    }
                }

                val tabTitle = if (isUrl) query.trim() else "Search: ${query.trim()}"
                openOrSwitchTab("web_" + System.currentTimeMillis(), targetUrl, tabTitle)
            }
        }
    }

    private fun toggleSpotlightSearch() {
        runOnUiThread {
            if (spotlightOverlay.visibility == View.VISIBLE) {
                hideSpotlightSearch()
                presentation?.hideSpotlightSearch()
            } else {
                showSpotlightSearch()
                presentation?.showSpotlightSearch()
            }
        }
    }

    fun showSpotlightSearch() {
        spotlightOverlay.visibility = View.VISIBLE
        spotlightInput.setText("")
        spotlightInput.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(spotlightInput, InputMethodManager.SHOW_IMPLICIT)
        
        cursorView.bringToFront()
        if (isTrackpadMode) {
            touchpadOverlay.bringToFront()
        }
    }

    fun hideSpotlightSearch() {
        spotlightOverlay.visibility = View.GONE
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(spotlightInput.windowToken, 0)
    }

    fun showVirtualKeyboard() {
        runOnUiThread {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            getActiveWebView()?.let { webView ->
                webView.requestFocus()
                imm.showSoftInput(webView, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    fun hideVirtualKeyboard() {
        runOnUiThread {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(window.decorView.windowToken, 0)
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
        cursorView.bringToFront()
    }

    private fun dispatchHoverAtCursor() {
        val cx = cursorX
        val cy = cursorY
        
        // Dispatch hover events to custom menu overlays to trigger button hover colors dynamically
        activeDropdownView?.let { menu ->
            val mx = menu.x
            val my = menu.y
            val mw = menu.width
            val mh = menu.height
            if (cx >= mx && cx <= mx + mw && cy >= my && cy <= my + mh) {
                val downTime = SystemClock.uptimeMillis()
                val eventTime = SystemClock.uptimeMillis()
                val hoverEvent = MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_HOVER_MOVE, cx - mx, cy - my, 0).apply {
                    source = InputDevice.SOURCE_MOUSE
                }
                menu.dispatchGenericMotionEvent(hoverEvent)
                hoverEvent.recycle()
                return
            }
        }
        activeSubmenuView?.let { sub ->
            val sx = sub.x
            val sy = sub.y
            val sw = sub.width
            val sh = sub.height
            if (cx >= sx && cx <= sx + sw && cy >= sy && cy <= sy + sh) {
                val downTime = SystemClock.uptimeMillis()
                val eventTime = SystemClock.uptimeMillis()
                val hoverEvent = MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_HOVER_MOVE, cx - sx, cy - sy, 0).apply {
                    source = InputDevice.SOURCE_MOUSE
                }
                sub.dispatchGenericMotionEvent(hoverEvent)
                hoverEvent.recycle()
                return
            }
        }
        activeTabNavDropdown?.let { menu ->
            val mx = menu.x
            val my = menu.y
            val mw = menu.width
            val mh = menu.height
            if (cx >= mx && cx <= mx + mw && cy >= my && cy <= my + mh) {
                val downTime = SystemClock.uptimeMillis()
                val eventTime = SystemClock.uptimeMillis()
                val hoverEvent = MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_HOVER_MOVE, cx - mx, cy - my, 0).apply {
                    source = InputDevice.SOURCE_MOUSE
                }
                menu.dispatchGenericMotionEvent(hoverEvent)
                hoverEvent.recycle()
                return
            }
        }

        val webView = getActiveWebView() ?: return
        val offset = topBar.height + tabScroll.height + dpToPx(2)

        val downTime = SystemClock.uptimeMillis()
        val eventTime = SystemClock.uptimeMillis()

        if (cy >= offset) {
            val relativeY = cy - offset
            val hoverEvent = MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_HOVER_MOVE, cx, relativeY, 0).apply {
                source = InputDevice.SOURCE_MOUSE
            }
            webView.dispatchGenericMotionEvent(hoverEvent)
            hoverEvent.recycle()
        } else {
            val hoverEvent = MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_HOVER_MOVE, cx, cy, 0).apply {
                source = InputDevice.SOURCE_MOUSE
            }
            rootLayout.dispatchGenericMotionEvent(hoverEvent)
            hoverEvent.recycle()
        }
    }

    private fun performClickAtCursor(isRightClick: Boolean) {
        val webView = getActiveWebView()
        val cx = cursorX
        val cy = cursorY
        val offset = topBar.height + tabScroll.height + dpToPx(2)

        // Intercept dropdown outside taps
        val hasActiveMenu = activeDropdownView != null || activeSubmenuView != null || activeTabNavDropdown != null
        if (hasActiveMenu) {
            var clickedInside = false
            var targetMenu: View? = null
            
            activeSubmenuView?.let { sub ->
                val sx = sub.x
                val sy = sub.y
                val sw = sub.width
                val sh = sub.height
                if (cx >= sx && cx <= sx + sw && cy >= sy && cy <= sy + sh) {
                    clickedInside = true
                    targetMenu = sub
                }
            }

            if (!clickedInside) {
                activeDropdownView?.let { menu ->
                    val mx = menu.x
                    val my = menu.y
                    val mw = menu.width
                    val mh = menu.height
                    if (cx >= mx && cx <= mx + mw && cy >= my && cy <= my + mh) {
                        clickedInside = true
                        targetMenu = menu
                    }
                }
            }
            
            if (!clickedInside) {
                activeTabNavDropdown?.let { menu ->
                    val mx = menu.x
                    val my = menu.y
                    val mw = menu.width
                    val mh = menu.height
                    if (cx >= mx && cx <= mx + mw && cy >= my && cy <= my + mh) {
                        clickedInside = true
                        targetMenu = menu
                    }
                }
            }
            
            if (!clickedInside) {
                dismissActiveDropdown()
                return
            } else {
                if (!isRightClick && targetMenu != null) {
                    val localX = cx - targetMenu!!.x
                    val localY = cy - targetMenu!!.y
                    
                    val downTime = SystemClock.uptimeMillis()
                    val eventTime = SystemClock.uptimeMillis()
                    val downEvent = MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_DOWN, localX, localY, 0).apply {
                        source = InputDevice.SOURCE_MOUSE
                    }
                    val upEvent = MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_UP, localX, localY, 0).apply {
                        source = InputDevice.SOURCE_MOUSE
                    }
                    targetMenu!!.dispatchTouchEvent(downEvent)
                    targetMenu!!.dispatchTouchEvent(upEvent)
                    downEvent.recycle()
                    upEvent.recycle()
                }
                return
            }
        }

        if (cy >= offset) {
            if (webView != null) {
                val relativeY = cy - offset
                if (isRightClick) {
                    showWebPageContextMenu(webView, cx, cy)
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
            }
        } else {
            if (isRightClick) {
                val clickedView = findViewAt(rootLayout, cx, cy)
                val tabView = findTabItemView(clickedView)
                if (tabView != null) {
                    val tag = tabView.tag as String
                    val tabIndex = tag.substring(4).toIntOrNull()
                    if (tabIndex != null && tabIndex in tabsList.indices) {
                        showTabContextMenu(tabView, tabIndex)
                    }
                }
            } else {
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
            }
        }
    }

    private fun showWebPageContextMenu(webView: WebView, x: Float, y: Float) {
        dismissActiveDropdown()
        val pageUrl = webView.url ?: ""
        val pageTitle = webView.title ?: "Web App"

        val menuItems = mutableListOf<MacMenuItem>()
        menuItems.add(MacMenuItem("Reload") { webView.reload() })
        
        if (webView.canGoBack()) {
            menuItems.add(MacMenuItem("Back") { webView.goBack() })
        }
        if (webView.canGoForward()) {
            menuItems.add(MacMenuItem("Forward") { webView.goForward() })
        }
        
        menuItems.add(MacMenuItem(isSeparator = true))
        
        val isSystemPage = pageUrl.startsWith("file://") || pageUrl.isEmpty()
        val isAlreadyInstalled = dynamicPwas.any { it.url == pageUrl }
        
        if (!isSystemPage && !isAlreadyInstalled) {
            menuItems.add(MacMenuItem("Install Page as PWA") {
                AlertDialog.Builder(this)
                    .setTitle("Install Application")
                    .setMessage("Do you want to install \"$pageTitle\" to your App Drawer?")
                    .setPositiveButton("Install") { _, _ ->
                        val id = "pwa_" + System.currentTimeMillis()
                        registerDynamicPwaFromWeb(id, pageTitle, pageUrl, "Internet")
                        showToast("\"$pageTitle\" has been installed to the App Drawer!")
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            })
        }

        showMacMenu(webView, menuItems, isSubMenu = false, subMenuX = x, subMenuY = y, useDirectCoords = true)
    }

    private fun showTabNavigationDropdown(anchorView: View, tabIndex: Int) {
        dismissActiveDropdown()

        val tab = tabsList.getOrNull(tabIndex) ?: return
        val webView = tab.webView ?: return
        val scale = currentScale
        
        val pad = dpToPx((4 * scale).toInt())
        val btnSize = dpToPx((24 * scale).toInt())

        val popupView = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(pad, pad, pad, pad)
            val borderDrawable = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#12121a"))
                setStroke(1, Color.parseColor("#2a2a3a"))
                cornerRadius = dpToPx((4 * scale).toInt()).toFloat()
            }
            background = borderDrawable
            elevation = dpToPx((4 * scale).toInt()).toFloat()
        }

        fun createNavButton(icon: String, isEnabled: Boolean, action: () -> Unit): TextView {
            return TextView(this).apply {
                text = icon
                setTextColor(if (isEnabled) Color.parseColor("#e2e8f0") else Color.parseColor("#475569"))
                textSize = 10f * scale
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(btnSize, btnSize).apply {
                    setMargins(dpToPx((2 * scale).toInt()), 0, dpToPx((2 * scale).toInt()), 0)
                }
                background = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
                
                if (isEnabled) {
                    isClickable = true
                    setOnHoverListener { v, event ->
                        if (event.action == MotionEvent.ACTION_HOVER_ENTER) {
                            v.setBackgroundColor(Color.parseColor("#2a2a3a"))
                        } else if (event.action == MotionEvent.ACTION_HOVER_EXIT) {
                            v.setBackgroundColor(Color.TRANSPARENT)
                        }
                        false
                    }
                    setOnClickListener {
                        action()
                        dismissActiveDropdown()
                    }
                }
            }
        }

        val backBtn = createNavButton("◀", webView.canGoBack()) {
            webView.goBack()
        }
        val forwardBtn = createNavButton("▶", webView.canGoForward()) {
            webView.goForward()
        }
        val reloadBtn = createNavButton("🔄", true) {
            webView.reload()
        }

        popupView.addView(backBtn)
        popupView.addView(forwardBtn)
        popupView.addView(reloadBtn)

        popupView.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        workspaceContainer.addView(popupView)
        activeTabNavDropdown = popupView

        popupView.post {
            val location = IntArray(2)
            anchorView.getLocationOnScreen(location)
            popupView.x = location[0].toFloat()
            popupView.y = (location[1] + anchorView.height + dpToPx(2)).toFloat()
            popupView.bringToFront()
            cursorView.bringToFront()
            if (isTrackpadMode) {
                touchpadOverlay.bringToFront()
            }
        }
    }

    private fun scrollActiveWebView(dy: Float) {
        val webView = getActiveWebView() ?: return
        val offset = topBar.height + tabScroll.height + dpToPx(2)
        val cx = cursorX
        val cy = cursorY - offset

        val js = """
            (function() {
                var el = document.elementFromPoint($cx, $cy);
                if (el) {
                    var event = new WheelEvent('wheel', {
                        bubbles: true,
                        cancelable: true,
                        view: window,
                        deltaY: ${-dy}
                    });
                    el.dispatchEvent(event);
                }
            })();
        """.trimIndent()

        runOnUiThread {
            webView.evaluateJavascript(js, null)
        }
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

    fun getAccessPin(): String = accessPin

    fun setUiScaleFromWeb(scale: Float) {
        runOnUiThread {
            currentScale = scale
            applyUiScale()
            presentation?.updatePresentationScale(scale)
        }
    }

    fun setCursorStyleFromWeb(color: String, size: String) {
        updateCursorStyle(color, size)
    }

    fun setPointerSpeedFromWeb(speed: Float) {
        pointerSpeedMultiplier = speed
    }

    fun setScrollDirectionNaturalFromWeb(natural: Boolean) {
        naturalScroll = natural
    }

    fun setOverscanPaddingFromWeb(padding: Int) {
        runOnUiThread {
            webViewContainer.setPadding(
                dpToPx(padding),
                dpToPx(padding),
                dpToPx(padding),
                dpToPx(padding)
            )
        }
    }

    fun stopRemoteControlSessionFromWeb() {
        runOnUiThread {
            stopRemoteSharing()
        }
    }

    private fun updateCursorStyle(colorName: String, sizeName: String) {
        runOnUiThread {
            val cursorColor = when (colorName.lowercase(java.util.Locale.US)) {
                "black" -> Color.BLACK
                "yellow" -> Color.YELLOW
                else -> Color.WHITE
            }
            val strokeColor = if (cursorColor == Color.BLACK) Color.WHITE else Color.BLACK

            val multiplier = when (sizeName.lowercase(java.util.Locale.US)) {
                "large" -> 1.5f
                "extra large" -> 2.0f
                else -> 1.0f
            }

            val w = (16 * multiplier).toInt().coerceAtLeast(1)
            val h = (24 * multiplier).toInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            
            val paint = Paint().apply {
                color = cursorColor
                style = Paint.Style.FILL
                isAntiAlias = true
            }
            val strokePaint = Paint().apply {
                color = strokeColor
                style = Paint.Style.STROKE
                strokeWidth = 2f * multiplier
                isAntiAlias = true
            }
            val path = Path().apply {
                moveTo(0f, 0f)
                lineTo(16f * multiplier, 16f * multiplier)
                lineTo(9f * multiplier, 16f * multiplier)
                lineTo(14f * multiplier, 24f * multiplier)
                lineTo(11f * multiplier, 24f * multiplier)
                lineTo(6f * multiplier, 16f * multiplier)
                lineTo(0f * multiplier, 20f * multiplier)
                close()
            }
            canvas.drawPath(path, paint)
            canvas.drawPath(path, strokePaint)

            cursorView.setImageBitmap(bmp)
            presentation?.updateCursorStyle(colorName, sizeName)
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

    override fun onStop() {
        super.onStop()
        saveHibernationState()
    }

    override fun onBackPressed() {
        if (spotlightOverlay.visibility == View.VISIBLE) {
            hideSpotlightSearch()
            return
        }
        dismissActiveDropdown()
        
        AlertDialog.Builder(this)
            .setTitle("Exit Anodyne OS?")
            .setMessage("Would you like to put the desktop to sleep (hibernate open tabs) and exit?")
            .setPositiveButton("Hibernate & Exit") { _, _ ->
                saveHibernationState()
                finish()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun saveHibernationState() {
        try {
            val prefs = getSharedPreferences("anodyne_hibernation", Context.MODE_PRIVATE)
            val jsonArray = org.json.JSONArray()
            for (tab in tabsList) {
                val tabObj = org.json.JSONObject().apply {
                    put("id", tab.id)
                    put("url", tab.webView.url ?: tab.url)
                    put("title", tab.title)
                }
                jsonArray.put(tabObj)
            }
            prefs.edit().apply {
                putString("tabs_json", jsonArray.toString())
                putInt("active_index", currentTabIndex)
                apply()
            }
            Log.i(TAG, "Hibernated system state successfully: saved ${tabsList.size} tabs")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving hibernation state", e)
        }
    }

    private fun restoreHibernationState(): Boolean {
        try {
            val prefs = getSharedPreferences("anodyne_hibernation", Context.MODE_PRIVATE)
            val tabsJson = prefs.getString("tabs_json", null) ?: return false
            val activeIndex = prefs.getInt("active_index", 0)
            
            val jsonArray = org.json.JSONArray(tabsJson)
            if (jsonArray.length() == 0) return false
            
            for (i in 0 until jsonArray.length()) {
                val tabObj = jsonArray.getJSONObject(i)
                val id = tabObj.getString("id")
                val url = tabObj.getString("url")
                val title = tabObj.getString("title")
                
                openOrSwitchTabWithoutFocus(id, url, title)
            }
            
            runOnUiThread {
                if (activeIndex in 0 until tabsList.size) {
                    switchTab(activeIndex)
                } else if (tabsList.isNotEmpty()) {
                    switchTab(0)
                }
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error restoring hibernation state", e)
            return false
        }
    }

    private fun openOrSwitchTabWithoutFocus(id: String, url: String, title: String) {
        val existingIndex = tabsList.indexOfFirst { it.id == id }
        if (existingIndex == -1) {
            val newWebView = createTabWebView(url).apply {
                visibility = View.GONE
            }
            webViewContainer.addView(newWebView)

            val newTab = TabItem(id, url, title, newWebView)
            tabsList.add(newTab)
        }
    }

    fun printActiveTab() {
        runOnUiThread {
            try {
                val webView = getActiveWebView() ?: return@runOnUiThread
                val printManager = getSystemService(Context.PRINT_SERVICE) as android.print.PrintManager
                val jobName = "Anodyne Document"
                val printAdapter = webView.createPrintDocumentAdapter(jobName)
                printManager.print(
                    jobName,
                    printAdapter,
                    android.print.PrintAttributes.Builder().build()
                )
                Log.i(TAG, "Sent active WebView content to PrintManager")
            } catch (e: Exception) {
                Log.e(TAG, "Error printing document from active WebView", e)
            }
        }
    }

    fun showActiveIndicator(indicatorText: String) {
        runOnUiThread {
            // Check if this indicator is already showing
            for (i in 0 until activeIndicatorsContainer.childCount) {
                val child = activeIndicatorsContainer.getChildAt(i) as? TextView
                if (child?.text?.toString() == indicatorText) return@runOnUiThread
            }

            val badge = TextView(this).apply {
                text = indicatorText
                setTextColor(Color.parseColor("#ef4444")) // high contrast red
                textSize = 7.5f * currentScale
                setPadding(dpToPx(6), dpToPx(1), dpToPx(6), dpToPx(1))
                val bg = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.parseColor("#2a1818"))
                    cornerRadius = dpToPx(4).toFloat()
                    setStroke(1, Color.parseColor("#ef4444"))
                }
                background = bg
                isClickable = true
                isFocusable = true
                
                // Mute/Kill switch: clicking the badge clears it and reloads the active WebView to revoke hardware session!
                setOnClickListener {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Mute / Stop Resource?")
                        .setMessage("Would you like to stop this active resource and reload the page?")
                        .setPositiveButton("Reload Page") { _, _ ->
                            getActiveWebView()?.reload()
                            activeIndicatorsContainer.removeView(this)
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
            activeIndicatorsContainer.addView(badge)
        }
    }

    private fun showDownloadsDropdown() {
        downloadsTrayText.setTextColor(Color.parseColor("#94a3b8"))
        
        val items = mutableListOf<MacMenuItem>()
        if (downloadRecords.isEmpty()) {
            items.add(MacMenuItem("No active or completed downloads"))
        } else {
            synchronized(downloadRecords) {
                for (rec in downloadRecords) {
                    items.add(MacMenuItem("📄 ${rec.filename}") {
                        openDownloadedFile(rec)
                    })
                }
            }
        }
        showMacMenu(downloadsTrayText, items)
    }

    private fun openDownloadedFile(rec: DownloadRecord) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this,
                "com.anodyne.desktop.provider",
                rec.file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, rec.mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error opening downloaded file", e)
            Toast.makeText(this, "Cannot open file natively: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }

    fun clearAllCachedWebData() {
        runOnUiThread {
            try {
                android.webkit.WebStorage.getInstance().deleteAllData()
                android.webkit.WebViewDatabase.getInstance(this).clearHttpAuthUsernamePassword()
                
                val cookieManager = android.webkit.CookieManager.getInstance()
                cookieManager.removeAllCookies { success ->
                    Log.i(TAG, "Removed all cookies: $success")
                }
                cookieManager.flush()
                
                for (tab in tabsList) {
                    tab.webView.clearCache(true)
                    tab.webView.reload()
                }
                
                Toast.makeText(this, "System cache and storage cleaned successfully!", Toast.LENGTH_SHORT).show()
                Log.i(TAG, "Successfully cleared all HTTP caching and cookies.")
            } catch (e: Exception) {
                Log.e(TAG, "Error clearing cached WebView data", e)
            }
        }
    }

    inner class TouchpadLayout(context: Context) : FrameLayout(context) {
        private var lastX = 0f
        private var lastY = 0f
        private var lastScrollY = 0f
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

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (isCasting && presentation != null) {
                    presentation?.performPresentationClick(false)
                    presentation?.performPresentationClick(false)
                } else {
                    performClickAtCursor(false)
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
                        lastScrollY = (event.getY(0) + event.getY(1)) / 2f
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount == 1) {
                        val pointerIndex = event.findPointerIndex(activePointerId)
                        if (pointerIndex != -1) {
                            val dx = (event.getX(pointerIndex) - lastX) * pointerSpeedMultiplier
                            val dy = (event.getY(pointerIndex) - lastY) * pointerSpeedMultiplier
                            lastX = event.getX(pointerIndex)
                            lastY = event.getY(pointerIndex)

                            if (isCasting && presentation != null) {
                                presentation?.movePresentationCursor(dx, dy)
                            } else {
                                moveVirtualCursor(dx, dy)
                            }
                        }
                    } else if (event.pointerCount == 2) {
                        val currentScrollY = (event.getY(0) + event.getY(1)) / 2f
                        val dy = currentScrollY - lastScrollY
                        lastScrollY = currentScrollY
                        
                        val directionSign = if (naturalScroll) 1f else -1f
                        val scaledDy = dy * 1.5f * directionSign
                        if (isCasting && presentation != null) {
                            presentation?.scrollPresentation(scaledDy)
                        } else {
                            scrollActiveWebView(scaledDy)
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
