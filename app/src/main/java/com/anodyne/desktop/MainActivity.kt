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

    private lateinit var workspaceContainer: FrameLayout
    private lateinit var rootLayout: LinearLayout
    private lateinit var tabScroll: HorizontalScrollView
    private lateinit var tabContainer: LinearLayout
    private lateinit var webViewContainer: FrameLayout

    // Spotlight Search elements
    private lateinit var spotlightOverlay: FrameLayout
    private lateinit var spotlightInput: EditText
    private lateinit var spotlightBtn: TextView

    // Floating Virtual Keyboard
    private lateinit var virtualKeyboardPanel: LinearLayout
    private var isShiftEnabled = false
    private val rowViews = mutableListOf<TextView>()

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
    private lateinit var logoText: TextView
    private lateinit var anodyneMenu: TextView
    private lateinit var leftContainer: LinearLayout

    private lateinit var wifiTextView: TextView
    private lateinit var batteryTextView: TextView
    private lateinit var clockTextView: TextView

    // Custom dropdown layouts inside workspaceContainer
    private var activeDropdownView: View? = null
    private var activeSubmenuView: View? = null

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
        leftContainer.addView(logoText)
        topBar.addView(leftContainer)

        // Center Container for Clock/Calendar/Notifications (GNOME style)
        val centerContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
            gravity = Gravity.CENTER
        }

        clockTextView = TextView(this).apply {
            setTextColor(Color.parseColor("#f8fafc"))
            textSize = 8.5f
            gravity = Gravity.CENTER
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

        // Manual Floating Virtual Keyboard Toggle Icon
        val keyboardToggle = TextView(this).apply {
            text = "⌨️"
            setTextColor(Color.parseColor("#94a3b8"))
            textSize = 8.5f
            setPadding(dpToPx(4), 0, dpToPx(2), 0)
            setOnClickListener {
                if (virtualKeyboardPanel.visibility == View.VISIBLE) {
                    hideVirtualKeyboard()
                    presentation?.hideVirtualKeyboard()
                } else {
                    showVirtualKeyboard()
                    presentation?.showVirtualKeyboard()
                }
            }
        }
        rightContainer.addView(keyboardToggle)

        // macOS Spotlight Button Icon
        spotlightBtn = TextView(this).apply {
            text = "🔍"
            setTextColor(Color.parseColor("#94a3b8"))
            textSize = 8.5f
            setPadding(dpToPx(5), 0, dpToPx(5), 0)
            setOnClickListener { toggleSpotlightSearch() }
        }
        rightContainer.addView(spotlightBtn)

        wifiTextView = TextView(this).apply {
            text = "🛜"
            setTextColor(Color.parseColor("#94a3b8"))
            textSize = 8.5f
            setPadding(dpToPx(4), 0, 0, 0)
            setOnClickListener { showWifiDropdown() }
        }
        rightContainer.addView(wifiTextView)

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

        // 5. Spotlight Search overlay
        setupSpotlightSearch()

        // 6. Miniature Draggable Keyboard Overlay
        setupVirtualKeyboard()

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

        val popupView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dpToPx(4), 0, dpToPx(4))
            val borderDrawable = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#12121a"))
                setStroke(1, Color.parseColor("#2a2a3a"))
                cornerRadius = dpToPx(8).toFloat()
            }
            background = borderDrawable
            elevation = dpToPx(8).toFloat()
        }

        val menuItems = listOf(
            MacMenuItem("Accessories  ▶"),
            MacMenuItem("Internet  ▶"),
            MacMenuItem("System Tools  ▶"),
            MacMenuItem("Preferences  ▶"),
            MacMenuItem(isSeparator = true),
            MacMenuItem("Restart Shell") { recreate() },
            MacMenuItem("Shut Down") { finish() }
        )

        for (item in menuItems) {
            if (item.isSeparator) {
                val sep = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        1
                    ).apply {
                        setMargins(0, dpToPx(4), 0, dpToPx(4))
                    }
                    setBackgroundColor(Color.parseColor("#2a2a3a"))
                }
                popupView.addView(sep)
            } else {
                val row = TextView(this).apply {
                    text = item.title
                    setTextColor(Color.parseColor("#e2e8f0"))
                    textSize = 11f
                    setPadding(dpToPx(16), dpToPx(6), dpToPx(24), dpToPx(6))
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
                        if (item.title.contains("▶")) {
                            val subItems = when {
                                item.title.startsWith("Accessories") -> listOf(
                                    MacMenuItem("Spotlight Search") { toggleSpotlightSearch() },
                                    MacMenuItem("Floating Keyboard") { 
                                        showVirtualKeyboard()
                                        presentation?.showVirtualKeyboard()
                                    }
                                )
                                item.title.startsWith("Internet") -> listOf(
                                    MacMenuItem("Web Browser") { openOrSwitchTab("web_" + System.currentTimeMillis(), "https://www.google.com", "Google") }
                                )
                                item.title.startsWith("System Tools") -> listOf(
                                    MacMenuItem("Files (Nautilus)") { openOrSwitchTab("files", "file:///android_asset/files/index.html", "Files") },
                                    MacMenuItem("Settings (GNOME)") { openOrSwitchTab("settings", "file:///android_asset/settings/index.html", "Settings") }
                                )
                                item.title.startsWith("Preferences") -> listOf(
                                    MacMenuItem("System Settings") { openOrSwitchTab("settings", "file:///android_asset/settings/index.html", "Settings") }
                                )
                                else -> emptyList()
                            }
                            
                            val loc = IntArray(2)
                            this.getLocationOnScreen(loc)
                            val mainX = activeDropdownView?.x ?: 0f
                            val mainW = activeDropdownView?.width ?: 0
                            
                            activeSubmenuView?.let { sub ->
                                workspaceContainer.removeView(sub)
                                activeSubmenuView = null
                            }
                            
                            showMacMenu(this, subItems, isSubMenu = true, subMenuX = mainX + mainW + dpToPx(4), subMenuY = loc[1].toFloat())
                        } else {
                            dismissActiveDropdown()
                            item.action?.invoke()
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
            val location = IntArray(2)
            anchorView.getLocationOnScreen(location)
            popupView.x = location[0].toFloat()
            popupView.y = (location[1] + anchorView.height + dpToPx(2)).toFloat()
            activeDropdownView = popupView
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

        val popupWidth = dpToPx(480)
        val popupHeight = dpToPx(280)
        
        val rootDropdown = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(16))
            val borderDrawable = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#f912121a")) // Glassmorphism
                setStroke(2, Color.parseColor("#2a2a3a"))
                cornerRadius = dpToPx(12).toFloat()
            }
            background = borderDrawable
            elevation = dpToPx(16).toFloat()
        }

        // --- Left Column: Notifications ---
        val notificationsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.1f).apply {
                rightMargin = dpToPx(16)
            }
        }

        val notifHeader = TextView(this).apply {
            text = "Notifications"
            setTextColor(Color.parseColor("#94a3b8"))
            textSize = 12f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dpToPx(10))
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
                setPadding(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8))
                val bg = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.parseColor("#1a1a26"))
                    cornerRadius = dpToPx(6).toFloat()
                }
                background = bg
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dpToPx(8)
                }
            }
            
            val tRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            val titleText = TextView(this).apply {
                text = titleStr
                setTextColor(Color.WHITE)
                textSize = 10f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val timeText = TextView(this).apply {
                text = timeStr
                setTextColor(Color.parseColor("#64748b"))
                textSize = 8f
            }
            tRow.addView(titleText)
            tRow.addView(timeText)
            item.addView(tRow)

            val descText = TextView(this).apply {
                text = textStr
                setTextColor(Color.parseColor("#94a3b8"))
                textSize = 9f
                setPadding(0, dpToPx(2), 0, 0)
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
                dpToPx(1),
                ViewGroup.LayoutParams.MATCH_PARENT
            ).apply {
                rightMargin = dpToPx(16)
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
            textSize = 12f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, dpToPx(10))
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
                textSize = 9f
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
                    height = dpToPx(22)
                    columnSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f)
                }
            }
            daysGrid.addView(blank)
        }

        for (day in 1..maxDays) {
            val cell = TextView(this).apply {
                text = day.toString()
                textSize = 9f
                gravity = Gravity.CENTER
                
                layoutParams = android.widget.GridLayout.LayoutParams().apply {
                    width = 0
                    height = dpToPx(20)
                    columnSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f)
                }

                if (day == currentDay) {
                    setTextColor(Color.WHITE)
                    val bg = android.graphics.drawable.GradientDrawable().apply {
                        setColor(Color.parseColor("#3584e4"))
                        cornerRadius = dpToPx(10).toFloat()
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
            rootDropdown.x = (location[0] - dpToPx(180)).toFloat().coerceAtLeast(0f)
            rootDropdown.y = (location[1] + anchorView.height + dpToPx(2)).toFloat()
            activeDropdownView = rootDropdown
        }
    }

    // Dynamic TopBar menu updater according to focused PWA
    private fun refreshTopBarMenus() {
        runOnUiThread {
            leftContainer.removeAllViews()
            leftContainer.addView(logoText)

            val activeTab = getActiveTabItem()
            val tabId = activeTab?.id ?: "home"
            val appConfig = tabMenusMap[tabId]

            val appName: String
            val menuCategories: List<Pair<String, List<String>>>

            if (appConfig != null) {
                appName = appConfig.appName
                menuCategories = appConfig.menus.map { it.categoryName to it.items }
            } else {
                when (tabId) {
                    "home" -> {
                        appName = "Anodyne"
                        menuCategories = listOf(
                            "File" to listOf("New Browser Tab", "Close Active Tab", "Go to Website URL..."),
                            "Edit" to listOf("Undo", "Redo", "Cut", "Copy", "Paste", "Select All"),
                            "View" to listOf("Reload Page", "Force Reload", "Actual Size", "Zoom In", "Zoom Out"),
                            "Window" to listOf("Minimize", "Bring All to Front"),
                            "Help" to listOf("Anodyne Help", "Send Feedback...")
                        )
                    }
                    "settings" -> {
                        appName = "Settings"
                        menuCategories = listOf(
                            "File" to listOf("Close Active Tab"),
                            "Edit" to listOf("Copy", "Paste"),
                            "Help" to listOf("About Settings PWA")
                        )
                    }
                    "files" -> {
                        appName = "Files"
                        menuCategories = listOf(
                            "File" to listOf("New Folder...", "Close Active Tab"),
                            "Edit" to listOf("Cut", "Copy", "Paste", "Delete"),
                            "View" to listOf("Grid View", "List View"),
                            "Help" to listOf("About Files PWA")
                        )
                    }
                    else -> {
                        appName = activeTab?.title ?: "Browser"
                        menuCategories = listOf(
                            "File" to listOf("New Browser Tab", "Close Active Tab"),
                            "Edit" to listOf("Undo", "Redo", "Cut", "Copy", "Paste"),
                            "View" to listOf("Reload Page", "Zoom In", "Zoom Out"),
                            "Help" to listOf("Web Page Help")
                        )
                    }
                }
            }

            anodyneMenu = TextView(this).apply {
                text = "  $appName"
                setTextColor(Color.parseColor("#f8fafc"))
                textSize = 8.5f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setPadding(dpToPx(4), 0, dpToPx(4), 0)
                setOnClickListener { showAppNameDropdown(appName) }
            }
            leftContainer.addView(anodyneMenu)

            for (category in menuCategories) {
                val menuView = TextView(this).apply {
                    text = "  ${category.first}"
                    setTextColor(Color.parseColor("#94a3b8"))
                    textSize = 8.5f
                    setPadding(dpToPx(4), 0, dpToPx(4), 0)
                    setOnClickListener {
                        showCategoryDropdown(this, category.first, category.second)
                    }
                }
                leftContainer.addView(menuView)
            }
        }
    }

    private fun showAppNameDropdown(appName: String) {
        val list = listOf(
            MacMenuItem("About $appName") { showAboutDialog() },
            MacMenuItem(isSeparator = true),
            MacMenuItem("Quit $appName") { getActiveTabItem()?.let { closeTab(it) } }
        )
        showMacMenu(anodyneMenu, list)
    }

    private fun showCategoryDropdown(anchorView: View, categoryName: String, items: List<String>) {
        val menuItems = items.map { itemTitle ->
            MacMenuItem(itemTitle) {
                val activeTab = getActiveTabItem()
                val tabId = activeTab?.id ?: "home"
                
                if (tabMenusMap.containsKey(tabId)) {
                    val webView = activeTab?.webView
                    val js = """
                        window.dispatchEvent(new CustomEvent('appMenuClicked', {
                            detail: { category: '$categoryName', item: '$itemTitle' }
                        }));
                    """.trimIndent()
                    webView?.evaluateJavascript(js, null)
                } else {
                    executeDefaultMenuAction(categoryName, itemTitle)
                }
            }
        }
        showMacMenu(anchorView, menuItems)
    }

    private fun executeDefaultMenuAction(category: String, item: String) {
        val webView = getActiveWebView()
        when (category) {
            "File" -> {
                when (item) {
                    "New Browser Tab" -> openOrSwitchTab("web_" + System.currentTimeMillis(), "file:///android_asset/homepage/index.html", "New Tab")
                    "Close Active Tab" -> getActiveTabItem()?.let { closeTab(it) }
                    "Go to Website URL..." -> showGoToUrlDialog()
                }
            }
            "Edit" -> {
                if (webView == null) return
                when (item) {
                    "Undo" -> webView.evaluateJavascript("document.execCommand('undo')", null)
                    "Redo" -> webView.evaluateJavascript("document.execCommand('redo')", null)
                    "Cut" -> webView.evaluateJavascript("document.execCommand('cut')", null)
                    "Copy" -> webView.evaluateJavascript("document.execCommand('copy')", null)
                    "Paste" -> webView.evaluateJavascript("document.execCommand('paste')", null)
                    "Select All" -> webView.evaluateJavascript("document.execCommand('selectAll')", null)
                }
            }
            "View" -> {
                if (webView == null) return
                when (item) {
                    "Reload Page" -> webView.reload()
                    "Force Reload" -> { webView.clearCache(true); webView.reload() }
                    "Actual Size" -> webView.zoomBy(1.0f)
                    "Zoom In" -> webView.zoomIn()
                    "Zoom Out" -> webView.zoomOut()
                }
            }
            "Window" -> {
                when (item) {
                    "Minimize" -> moveTaskToBack(true)
                    "Bring All to Front" -> openOrSwitchTab("home", "file:///android_asset/homepage/index.html", "Dashboard")
                }
            }
            "Help" -> {
                when (item) {
                    "Anodyne Help" -> openOrSwitchTab("help", "https://github.com/7CGPA-Labs/Anodyne-Desktop-Android", "Anodyne Help")
                    "Send Feedback..." -> openOrSwitchTab("feedback", "https://github.com/7CGPA-Labs/Anodyne-Desktop-Android/issues", "Send Feedback")
                    "About Settings PWA", "About Files PWA", "Web Page Help" -> showAboutDialog()
                }
            }
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
    private fun showMacMenu(anchorView: View, menuItems: List<MacMenuItem>, isSubMenu: Boolean = false, subMenuX: Float = 0f, subMenuY: Float = 0f) {
        if (!isSubMenu) {
            dismissActiveDropdown()
        }

        val popupView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dpToPx(4), 0, dpToPx(4))
            val borderDrawable = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#12121a"))
                setStroke(1, Color.parseColor("#2a2a3a"))
                cornerRadius = dpToPx(8).toFloat()
            }
            background = borderDrawable
            elevation = dpToPx(8).toFloat()
        }

        for (item in menuItems) {
            if (item.isSeparator) {
                val sep = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        1
                    ).apply {
                        setMargins(0, dpToPx(4), 0, dpToPx(4))
                    }
                    setBackgroundColor(Color.parseColor("#2a2a3a"))
                }
                popupView.addView(sep)
            } else {
                val row = TextView(this).apply {
                    text = item.title
                    setTextColor(Color.parseColor("#e2e8f0"))
                    textSize = 12f
                    setPadding(dpToPx(16), dpToPx(6), dpToPx(24), dpToPx(6))
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
            } else {
                val location = IntArray(2)
                anchorView.getLocationOnScreen(location)
                popupView.x = location[0].toFloat()
                popupView.y = (location[1] + anchorView.height + dpToPx(2)).toFloat()
                activeDropdownView = popupView
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

                    val targetWidth = 1600
                    view?.evaluateJavascript(
                        """
                        (function() {
                            var meta = document.querySelector('meta[name=viewport]');
                            if (!meta) {
                                meta = document.createElement('meta');
                                meta.name = 'viewport';
                                document.head.appendChild(meta);
                            }
                            var scale = window.screen.width / $targetWidth;
                            meta.setAttribute('content', 'width=$targetWidth, initial-scale=' + scale + ', minimum-scale=' + scale + ', maximum-scale=' + scale + ', user-scalable=no');
                            
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
                }
            }
        }

        newWebView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            allowUniversalAccessFromFileURLs = true
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

    private fun refreshTabUI() {
        tabContainer.removeAllViews()

        for (i in 0 until tabsList.size) {
            val tab = tabsList[i]
            val isActive = (i == currentTabIndex)

            val tabItem = LinearLayout(this).apply {
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
                }
            }

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
                        if (event.action == MotionEvent.ACTION_HOVER_ENTER) {
                            (v.background as? android.graphics.drawable.GradientDrawable)?.setColor(Color.parseColor("#334155"))
                            setTextColor(Color.WHITE)
                        } else if (event.action == MotionEvent.ACTION_HOVER_EXIT) {
                            (v.background as? android.graphics.drawable.GradientDrawable)?.setColor(Color.TRANSPARENT)
                            setTextColor(Color.parseColor(if (isActive) "#94a3b8" else "#64748b"))
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

        val addShortcut = { iconStr: String, labelStr: String, action: () -> Unit ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dpToPx(12), dpToPx(8), dpToPx(12), dpToPx(8))
                val bg = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.parseColor("#1a1a26"))
                    cornerRadius = dpToPx(8).toFloat()
                }
                background = bg
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    dpToPx(90),
                    dpToPx(65)
                ).apply {
                    leftMargin = dpToPx(8)
                    rightMargin = dpToPx(8)
                }
                setOnClickListener {
                    hideSpotlightSearch()
                    action()
                }
            }
            val iconView = TextView(this).apply {
                text = iconStr
                textSize = 18f
                gravity = Gravity.CENTER
            }
            val labelView = TextView(this).apply {
                text = labelStr
                setTextColor(Color.WHITE)
                textSize = 9f
                setPadding(0, dpToPx(4), 0, 0)
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

    // Custom Draggable Virtual Keyboard Setup
    private fun setupVirtualKeyboard() {
        virtualKeyboardPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                dpToPx(420),
                dpToPx(190)
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
                bottomMargin = dpToPx(20)
            }
            setPadding(dpToPx(10), dpToPx(8), dpToPx(10), dpToPx(8))
            visibility = View.GONE
            elevation = dpToPx(16).toFloat()

            val bg = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#f912121a"))
                setStroke(2, Color.parseColor("#3a3a4e"))
                cornerRadius = dpToPx(12).toFloat()
            }
            background = bg
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(30)
            )
            gravity = Gravity.CENTER_VERTICAL
        }

        val title = TextView(this).apply {
            text = "⌨️ Floating Keyboard"
            setTextColor(Color.parseColor("#94a3b8"))
            textSize = 11f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        header.addView(title)

        val close = TextView(this).apply {
            text = " × "
            setTextColor(Color.parseColor("#ef4444"))
            textSize = 16f
            setPadding(dpToPx(6), 0, dpToPx(6), 0)
            setOnClickListener {
                hideVirtualKeyboard()
                presentation?.hideVirtualKeyboard()
            }
        }
        header.addView(close)

        virtualKeyboardPanel.addView(header)

        var dX = 0f
        var dY = 0f
        header.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dX = virtualKeyboardPanel.x - event.rawX
                    dY = virtualKeyboardPanel.y - event.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    virtualKeyboardPanel.x = event.rawX + dX
                    virtualKeyboardPanel.y = event.rawY + dY
                }
            }
            true
        }

        val row1Keys = listOf("Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P")
        val row2Keys = listOf("A", "S", "D", "F", "G", "H", "J", "K", "L")
        val row3Keys = listOf("Shift", "Z", "X", "C", "V", "B", "N", "M", "Backspace")
        val row4Keys = listOf("?123", "Space", "Enter")

        virtualKeyboardPanel.addView(createKeyRow(row1Keys))
        virtualKeyboardPanel.addView(createKeyRow(row2Keys))
        virtualKeyboardPanel.addView(createKeyRow(row3Keys))
        virtualKeyboardPanel.addView(createKeyRow(row4Keys))

        workspaceContainer.addView(virtualKeyboardPanel)
    }

    private fun createKeyRow(keys: List<String>): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(34)
            ).apply {
                topMargin = dpToPx(4)
            }
        }
        for (key in keys) {
            val keyView = TextView(this).apply {
                text = key
                setTextColor(Color.WHITE)
                textSize = 12f
                gravity = Gravity.CENTER
                val weight = when(key) {
                    "Space" -> 4f
                    "Backspace", "Enter", "Shift", "?123" -> 1.5f
                    else -> 1f
                }
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight).apply {
                    leftMargin = dpToPx(3)
                    rightMargin = dpToPx(3)
                }
                val bg = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.parseColor("#2a2a3e"))
                    cornerRadius = dpToPx(6).toFloat()
                }
                background = bg
                
                if (key.length == 1 && key[0].isLetter()) {
                    rowViews.add(this)
                }

                setOnClickListener {
                    onVirtualKeyClick(key)
                }
            }
            row.addView(keyView)
        }
        return row
    }

    private fun onVirtualKeyClick(key: String) {
        if (key == "Shift") {
            isShiftEnabled = !isShiftEnabled
            for (tv in rowViews) {
                val txt = tv.text.toString()
                tv.text = if (isShiftEnabled) txt.uppercase() else txt.lowercase()
            }
            return
        }

        if (spotlightOverlay.visibility == View.VISIBLE) {
            when (key) {
                "Backspace" -> {
                    val str = spotlightInput.text.toString()
                    if (str.isNotEmpty()) {
                        spotlightInput.setText(str.substring(0, str.length - 1))
                        spotlightInput.setSelection(spotlightInput.text.length)
                    }
                }
                "Space" -> {
                    spotlightInput.append(" ")
                }
                "Enter" -> {
                    val query = spotlightInput.text.toString()
                    if (query.trim().isNotEmpty()) {
                        triggerSpotlightSearch(query)
                        hideSpotlightSearch()
                    }
                }
                else -> {
                    if (key != "?123") {
                        val txt = if (isShiftEnabled) key.uppercase() else key.lowercase()
                        spotlightInput.append(txt)
                    }
                }
            }
        } else {
            val webView = getActiveWebView() ?: return
            val js = when (key) {
                "Backspace" -> {
                    """
                    (function() {
                        var el = document.activeElement;
                        if (el && (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA' || el.contentEditable === 'true')) {
                            var val = el.value || '';
                            if (val.length > 0) {
                                el.value = val.substring(0, val.length - 1);
                                el.dispatchEvent(new Event('input', { bubbles: true }));
                            }
                        }
                    })();
                    """.trimIndent()
                }
                "Space" -> {
                    """
                    (function() {
                        var el = document.activeElement;
                        if (el && (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA' || el.contentEditable === 'true')) {
                            el.value = (el.value || '') + ' ';
                            el.dispatchEvent(new Event('input', { bubbles: true }));
                        }
                    })();
                    """.trimIndent()
                }
                "Enter" -> {
                    """
                    (function() {
                        var el = document.activeElement;
                        if (el) {
                            el.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', code: 'Enter', keyCode: 13, bubbles: true }));
                            el.dispatchEvent(new KeyboardEvent('keypress', { key: 'Enter', code: 'Enter', keyCode: 13, bubbles: true }));
                            el.dispatchEvent(new KeyboardEvent('keyup', { key: 'Enter', code: 'Enter', keyCode: 13, bubbles: true }));
                            if (el.form) {
                                el.form.submit();
                            }
                        }
                    })();
                    """.trimIndent()
                }
                else -> {
                    if (key != "?123") {
                        val txt = if (isShiftEnabled) key.uppercase() else key.lowercase()
                        """
                        (function() {
                            var el = document.activeElement;
                            if (el && (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA' || el.contentEditable === 'true')) {
                                el.value = (el.value || '') + '$txt';
                                el.dispatchEvent(new Event('input', { bubbles: true }));
                            }
                        })();
                        """.trimIndent()
                    } else ""
                }
            }
            if (js.isNotEmpty()) {
                webView.evaluateJavascript(js, null)
            }
        }
    }

    fun showVirtualKeyboard() {
        runOnUiThread {
            virtualKeyboardPanel.visibility = View.VISIBLE
            virtualKeyboardPanel.x = (workspaceContainer.width - virtualKeyboardPanel.width) / 2f
            virtualKeyboardPanel.y = workspaceContainer.height - virtualKeyboardPanel.height - dpToPx(20).toFloat()
            
            cursorView.bringToFront()
            if (isTrackpadMode) {
                touchpadOverlay.bringToFront()
            }
        }
    }

    fun hideVirtualKeyboard() {
        runOnUiThread {
            virtualKeyboardPanel.visibility = View.GONE
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
        val cx = cursorX
        val cy = cursorY
        
        // Dispatch hover events to custom menu overlays to trigger button hover colors dynamically
        if (activeDropdownView != null) {
            val menu = activeDropdownView!!
            val mx = menu.x
            val my = menu.y
            val mw = menu.width
            val mh = menu.height
            if (cx >= mx && cx <= mx + mw && cy >= my && cy <= my + mh) {
                val downTime = SystemClock.uptimeMillis()
                val eventTime = SystemClock.uptimeMillis()
                val hoverEvent = MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_HOVER_MOVE, cx, cy, 0).apply {
                    source = InputDevice.SOURCE_MOUSE
                }
                workspaceContainer.dispatchGenericMotionEvent(hoverEvent)
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
                val hoverEvent = MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_HOVER_MOVE, cx, cy, 0).apply {
                    source = InputDevice.SOURCE_MOUSE
                }
                workspaceContainer.dispatchGenericMotionEvent(hoverEvent)
                hoverEvent.recycle()
                return
            }
        }

        val webView = getActiveWebView() ?: return
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

        // Intercept dropdown outside taps
        if (activeDropdownView != null) {
            val menu = activeDropdownView!!
            val mx = menu.x
            val my = menu.y
            val mw = menu.width
            val mh = menu.height
            
            val inMain = (cx >= mx && cx <= mx + mw && cy >= my && cy <= my + mh)
            
            var inSub = false
            activeSubmenuView?.let { sub ->
                val sx = sub.x
                val sy = sub.y
                val sw = sub.width
                val sh = sub.height
                inSub = (cx >= sx && cx <= sx + sw && cy >= sy && cy <= sy + sh)
            }
            
            if (!inMain && !inSub) {
                dismissActiveDropdown()
                return
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
                    workspaceContainer.dispatchTouchEvent(downEvent)
                    workspaceContainer.dispatchTouchEvent(upEvent)
                    downEvent.recycle()
                    upEvent.recycle()
                }
                return
            }
        }

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
