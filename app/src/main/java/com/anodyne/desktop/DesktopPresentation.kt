package com.anodyne.desktop

import android.app.Presentation
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
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
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DesktopPresentation(
    outerContext: Context,
    display: Display
) : Presentation(outerContext, display) {

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

    private val tabsList = mutableListOf<TabItem>()
    private var currentTabIndex = -1

    private lateinit var workspaceContainer: FrameLayout
    private lateinit var rootLayout: LinearLayout
    private lateinit var tabScroll: HorizontalScrollView
    private lateinit var tabContainer: LinearLayout
    private lateinit var webViewContainer: FrameLayout

    // Presentation Virtual Cursor
    private lateinit var cursorView: ImageView
    private var cursorX = 0f
    private var cursorY = 0f

    // Top Bar UI elements
    private lateinit var topBar: LinearLayout
    private lateinit var logoText: TextView
    private lateinit var anodyneMenu: TextView
    private lateinit var fileMenu: TextView
    private lateinit var editMenu: TextView
    private lateinit var viewMenu: TextView
    private lateinit var windowMenu: TextView
    private lateinit var helpMenu: TextView

    private lateinit var wifiTextView: TextView
    private lateinit var batteryTextView: TextView
    private lateinit var clockTextView: TextView

    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockRunnable = object : Runnable {
        override fun run() {
            updateClockAndStatus()
            clockHandler.postDelayed(this, 10000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "Creating secondary display Presentation on display: ${display.name}")

        workspaceContainer = FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        rootLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#050508"))
        }

        // 1. Top Bar
        topBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(30)
            )
            setBackgroundColor(Color.parseColor("#0c0c14"))
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(14), 0, dpToPx(14), 0)
        }

        logoText = TextView(context).apply {
            text = "⬡"
            setTextColor(Color.parseColor("#f8fafc"))
            textSize = 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setOnClickListener { showLogoDropdown() }
        }
        topBar.addView(logoText)

        val createMenuText = { title: String, onClick: () -> Unit ->
            TextView(context).apply {
                text = "  $title"
                setTextColor(Color.parseColor("#94a3b8"))
                textSize = 11f
                setPadding(dpToPx(6), 0, dpToPx(6), 0)
                setOnClickListener { onClick() }
            }
        }

        anodyneMenu = TextView(context).apply {
            text = "  Anodyne"
            setTextColor(Color.parseColor("#f8fafc"))
            textSize = 11f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(dpToPx(6), 0, dpToPx(6), 0)
            setOnClickListener { showAnodyneDropdown() }
        }
        topBar.addView(anodyneMenu)

        fileMenu = createMenuText("File") { showFileDropdown() }
        topBar.addView(fileMenu)

        editMenu = createMenuText("Edit") { showEditDropdown() }
        topBar.addView(editMenu)

        viewMenu = createMenuText("View") { showViewDropdown() }
        topBar.addView(viewMenu)

        windowMenu = createMenuText("Window") { showWindowDropdown() }
        topBar.addView(windowMenu)

        helpMenu = createMenuText("Help") { showHelpDropdown() }
        topBar.addView(helpMenu)

        val spacer = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
        }
        topBar.addView(spacer)

        wifiTextView = TextView(context).apply {
            text = "Wi-Fi"
            setTextColor(Color.parseColor("#94a3b8"))
            textSize = 11f
            setOnClickListener { showWifiDropdown() }
        }
        topBar.addView(wifiTextView)

        batteryTextView = TextView(context).apply {
            text = "100%"
            setTextColor(Color.parseColor("#94a3b8"))
            textSize = 11f
            setPadding(dpToPx(8), 0, dpToPx(8), 0)
            setOnClickListener { showBatteryDropdown() }
        }
        topBar.addView(batteryTextView)

        clockTextView = TextView(context).apply {
            setTextColor(Color.parseColor("#f8fafc"))
            textSize = 11f
            setPadding(dpToPx(12), 0, 0, 0)
            setOnClickListener { showClockDropdown() }
        }
        topBar.addView(clockTextView)

        rootLayout.addView(topBar)

        rootLayout.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
            setBackgroundColor(Color.parseColor("#1a1a24"))
        })

        // 2. Tab Bar
        tabScroll = HorizontalScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(40)
            )
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(Color.parseColor("#0c0c14"))
        }

        tabContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        tabScroll.addView(tabContainer)
        rootLayout.addView(tabScroll)

        rootLayout.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
            setBackgroundColor(Color.parseColor("#1a1a24"))
        })

        // 3. WebView Container
        webViewContainer = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        rootLayout.addView(webViewContainer)

        workspaceContainer.addView(rootLayout)

        // Custom drawn mouse pointer on external screen
        cursorView = ImageView(context).apply {
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
            visibility = View.VISIBLE
        }
        workspaceContainer.addView(cursorView)

        setContentView(workspaceContainer)

        // Default to Home tab
        openOrSwitchTab("home", "file:///android_asset/homepage/index.html", "Dashboard")

        // Start clock timer
        clockHandler.post(clockRunnable)
    }

    // macOS Dropdown UI Helper
    private fun showMacMenu(anchorView: View, menuItems: List<MacMenuItem>) {
        val popupView = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dpToPx(4), 0, dpToPx(4))
            val borderDrawable = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#12121a"))
                setStroke(1, Color.parseColor("#2a2a3a"))
                cornerRadius = dpToPx(8).toFloat()
            }
            background = borderDrawable
        }

        val popupWindow = android.widget.PopupWindow(
            popupView,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            elevation = dpToPx(8).toFloat()
            isOutsideTouchable = true
            isFocusable = true
        }

        for (item in menuItems) {
            if (item.isSeparator) {
                val sep = View(context).apply {
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
                val row = TextView(context).apply {
                    text = item.title
                    setTextColor(Color.parseColor("#e2e8f0"))
                    textSize = 12f
                    setPadding(dpToPx(16), dpToPx(6), dpToPx(24), dpToPx(6))
                    gravity = Gravity.CENTER_VERTICAL
                    val hoverBg = android.graphics.drawable.StateListDrawable().apply {
                        addState(intArrayOf(android.R.attr.state_pressed), android.graphics.drawable.ColorDrawable(Color.parseColor("#3584e4")))
                        addState(intArrayOf(android.R.attr.state_focused), android.graphics.drawable.ColorDrawable(Color.parseColor("#3584e4")))
                        addState(intArrayOf(), android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
                    }
                    background = hoverBg
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        popupWindow.dismiss()
                        item.action?.invoke()
                    }
                }
                popupView.addView(row)
            }
        }

        popupView.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        popupWindow.showAsDropDown(anchorView, 0, dpToPx(2))
    }

    private fun showLogoDropdown() {
        showMacMenu(logoText, listOf(
            MacMenuItem("About Anodyne Desktop") { showAboutDialog() },
            MacMenuItem("System Preferences...") { openOrSwitchTab("settings", "file:///android_asset/settings/index.html", "Settings") },
            MacMenuItem(isSeparator = true),
            MacMenuItem("Lock Screen") { showToast("Desktop Locked") },
            MacMenuItem("Shut Down...") { dismiss() }
        ))
    }

    private fun showAnodyneDropdown() {
        showMacMenu(anodyneMenu, listOf(
            MacMenuItem("Quit Anodyne Presentation") { dismiss() }
        ))
    }

    private fun showFileDropdown() {
        showMacMenu(fileMenu, listOf(
            MacMenuItem("New Browser Tab") { openOrSwitchTab("web_" + System.currentTimeMillis(), "file:///android_asset/homepage/index.html", "New Tab") },
            MacMenuItem("Close Active Tab") { getActiveTabItem()?.let { closeTab(it) } },
            MacMenuItem(isSeparator = true),
            MacMenuItem("Go to Website URL...") { showGoToUrlDialog() }
        ))
    }

    private fun showEditDropdown() {
        showMacMenu(editMenu, listOf(
            MacMenuItem("Undo") { getActiveWebView()?.evaluateJavascript("document.execCommand('undo')", null) },
            MacMenuItem("Redo") { getActiveWebView()?.evaluateJavascript("document.execCommand('redo')", null) },
            MacMenuItem(isSeparator = true),
            MacMenuItem("Cut") { getActiveWebView()?.evaluateJavascript("document.execCommand('cut')", null) },
            MacMenuItem("Copy") { getActiveWebView()?.evaluateJavascript("document.execCommand('copy')", null) },
            MacMenuItem("Paste") { getActiveWebView()?.evaluateJavascript("document.execCommand('paste')", null) },
            MacMenuItem("Select All") { getActiveWebView()?.evaluateJavascript("document.execCommand('selectAll')", null) }
        ))
    }

    private fun showViewDropdown() {
        showMacMenu(viewMenu, listOf(
            MacMenuItem("Reload Page") { getActiveWebView()?.reload() },
            MacMenuItem("Force Reload") { getActiveWebView()?.apply { clearCache(true); reload() } },
            MacMenuItem(isSeparator = true),
            MacMenuItem("Actual Size") { getActiveWebView()?.zoomBy(1.0f) },
            MacMenuItem("Zoom In") { getActiveWebView()?.zoomIn() },
            MacMenuItem("Zoom Out") { getActiveWebView()?.zoomOut() }
        ))
    }

    private fun showWindowDropdown() {
        showMacMenu(windowMenu, listOf(
            MacMenuItem("Bring All to Front") { openOrSwitchTab("home", "file:///android_asset/homepage/index.html", "Dashboard") }
        ))
    }

    private fun showHelpDropdown() {
        showMacMenu(helpMenu, listOf(
            MacMenuItem("Anodyne Help") { openOrSwitchTab("help", "https://github.com/7CGPA-Labs/Anodyne-Desktop-Android", "Anodyne Help") },
            MacMenuItem("Send Feedback...") { openOrSwitchTab("feedback", "https://github.com/7CGPA-Labs/Anodyne-Desktop-Android/issues", "Send Feedback") }
        ))
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
            MacMenuItem("Current Charge: " + batteryTextView.text),
            MacMenuItem(isSeparator = true),
            MacMenuItem("Battery Health: Good")
        ))
    }

    private fun showClockDropdown() {
        showMacMenu(clockTextView, listOf(
            MacMenuItem("Date: " + SimpleDateFormat("EEEE, d MMMM yyyy", Locale.US).format(Date())),
            MacMenuItem("Timezone: " + java.util.TimeZone.getDefault().displayName)
        ))
    }

    private fun getWifiSSID(): String {
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
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

    private fun getBatteryPowerSource(): String {
        try {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return "Battery"
            val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
            if (plugged == BatteryManager.BATTERY_PLUGGED_AC || plugged == BatteryManager.BATTERY_PLUGGED_USB || plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS) {
                return "Power Adapter"
            }
        } catch (e: Exception) {}
        return "Battery"
    }

    private fun showAboutDialog() {
        AlertDialog.Builder(context)
            .setTitle("About Anodyne Desktop")
            .setMessage("Anodyne Desktop Virtual Container\nVersion 2.0 (Build 2026.08.01)\n\nCreated to preserve look-and-feel virtualization.\nAuthor: Gagan\n© 2026 7CGPA-Labs. All rights reserved.")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showGoToUrlDialog() {
        val input = EditText(context).apply {
            hint = "https://example.com"
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
            setPadding(dpToPx(16), dpToPx(8), dpToPx(16), dpToPx(8))
        }
        AlertDialog.Builder(context)
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
        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    fun movePresentationCursor(dx: Float, dy: Float) {
        clockHandler.post {
            val maxW = workspaceContainer.width.toFloat()
            val maxH = workspaceContainer.height.toFloat()

            cursorX = (cursorX + dx).coerceIn(0f, maxW)
            cursorY = (cursorY + dy).coerceIn(0f, maxH)

            cursorView.x = cursorX
            cursorView.y = cursorY

            dispatchHoverAtCursor()
        }
    }

    fun performPresentationClick(isRightClick: Boolean) {
        clockHandler.post {
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
    }

    fun scrollPresentation(dy: Float) {
        clockHandler.post {
            val webView = getActiveWebView() ?: return@post
            webView.scrollBy(0, (-dy).toInt())
        }
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

    private fun updateClockAndStatus() {
        val sdf = SimpleDateFormat("EEE MMM d  H:mm", Locale.US)
        clockTextView.text = sdf.format(Date())

        try {
            val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val pct = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            batteryTextView.text = "$pct%"

            wifiTextView.text = getWifiSSID()
        } catch (e: Exception) {
            Log.e(TAG, "Error updating status metrics", e)
        }
    }

    private fun createTabWebView(url: String): WebView {
        val newWebView = WebView(context).apply {
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

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    Log.d(TAG, "Casting WebView page finished loading: $url")

                    val metrics = context.resources.displayMetrics
                    val width = metrics.widthPixels

                    val targetWidth = if (width >= 1920) 1920 else 1280
                    val uiScale = when {
                        width >= 3840 -> 2.0
                        width >= 2560 -> 1.5
                        width >= 1920 -> 1.0
                        else -> 0.85
                    }

                    view?.evaluateJavascript(
                        """
                        (function() {
                            var meta = document.querySelector('meta[name=viewport]');
                            if (!meta) {
                                meta = document.createElement('meta');
                                meta.name = 'viewport';
                                document.head.appendChild(meta);
                            }
                            meta.setAttribute('content', 'width=$targetWidth, initial-scale=1.0, minimum-scale=1.0, maximum-scale=1.0, user-scalable=no');
                            
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

        val sysContext = AndroidSysContext(context,
            launchTabCallback = { appId, targetUrl, title ->
                openOrSwitchTab(appId, targetUrl, title)
            },
            evaluateJs = { js ->
                newWebView.post {
                    newWebView.evaluateJavascript(js, null)
                }
            }
        )
        newWebView.addJavascriptInterface(sysContext, "sysContext")
        newWebView.loadUrl(url)

        return newWebView
    }

    fun openOrSwitchTab(id: String, url: String, title: String) {
        webViewContainer.post {
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

            val tabItem = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    dpToPx(140),
                    LinearLayout.LayoutParams.MATCH_PARENT
                ).apply {
                    topMargin = dpToPx(6)
                    rightMargin = dpToPx(2)
                }
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dpToPx(12), 0, dpToPx(8), 0)
                
                val tabDrawable = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.parseColor(if (isActive) "#1e1e2e" else "#0c0c14"))
                    val r = dpToPx(8).toFloat()
                    cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
                }
                background = tabDrawable
                
                setOnClickListener {
                    switchTab(i)
                }
            }

            val titleText = TextView(context).apply {
                text = tab.title
                setTextColor(Color.parseColor(if (isActive) "#f8fafc" else "#94a3b8"))
                textSize = 11f
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
                val closeBtn = TextView(context).apply {
                    text = " × "
                    setTextColor(Color.parseColor(if (isActive) "#94a3b8" else "#64748b"))
                    textSize = 14f
                    gravity = Gravity.CENTER
                    setPadding(dpToPx(4), dpToPx(2), dpToPx(4), dpToPx(2))
                    val btnBg = android.graphics.drawable.GradientDrawable().apply {
                        setColor(Color.TRANSPARENT)
                        cornerRadius = dpToPx(8).toFloat()
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

        val plusBtn = TextView(context).apply {
            text = " + "
            setTextColor(Color.parseColor("#94a3b8"))
            textSize = 16f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                dpToPx(26),
                dpToPx(26)
            ).apply {
                gravity = Gravity.CENTER_VERTICAL
                leftMargin = dpToPx(6)
                topMargin = dpToPx(6)
            }
            val bg = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#1a1a24"))
                cornerRadius = dpToPx(13).toFloat()
            }
            background = bg
            
            setOnHoverListener { v, event ->
                if (event.action == MotionEvent.ACTION_HOVER_ENTER) {
                    (v.background as? android.graphics.drawable.GradientDrawable)?.setColor(Color.parseColor("#334155"))
                    setTextColor(Color.WHITE)
                } else if (event.action == MotionEvent.ACTION_HOVER_EXIT) {
                    (v.background as? android.graphics.drawable.GradientDrawable)?.setColor(Color.parseColor("#1a1a24"))
                    setTextColor(Color.parseColor("#94a3b8"))
                }
                false
            }
            
            setOnClickListener {
                openOrSwitchTab("web_" + System.currentTimeMillis(), "file:///android_asset/homepage/index.html", "New Tab")
            }
        }
        tabContainer.addView(plusBtn)
    }

    private fun dpToPx(dp: Int): Int {
        val density = context.resources.displayMetrics.density
        return (dp * density).toInt()
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

    override fun onStop() {
        Log.i(TAG, "Stopping presentation")
        clockHandler.removeCallbacks(clockRunnable)
        for (tab in tabsList) {
            tab.webView.destroy()
        }
        super.onStop()
    }

    companion object {
        private const val TAG = "DesktopPresentation"
    }
}
