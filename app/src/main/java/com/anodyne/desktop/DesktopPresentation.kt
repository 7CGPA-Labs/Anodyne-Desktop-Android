package com.anodyne.desktop

import android.app.Presentation
import android.content.Context
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
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
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

        val logoText = TextView(context).apply {
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
            val menu = TextView(context).apply {
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

        val spacer = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
        }
        topBar.addView(spacer)

        wifiTextView = TextView(context).apply {
            text = "Wi-Fi"
            setTextColor(Color.parseColor("#94a3b8"))
            textSize = 11f
        }
        topBar.addView(wifiTextView)

        batteryTextView = TextView(context).apply {
            text = "100%"
            setTextColor(Color.parseColor("#94a3b8"))
            textSize = 11f
            setPadding(dpToPx(8), 0, dpToPx(8), 0)
        }
        topBar.addView(batteryTextView)

        clockTextView = TextView(context).apply {
            setTextColor(Color.parseColor("#f8fafc"))
            textSize = 11f
            setPadding(dpToPx(12), 0, 0, 0)
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

            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
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

            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dpToPx(10), 0, dpToPx(6), 0)
            }

            val titleText = TextView(context).apply {
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
                val closeBtn = TextView(context).apply {
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

            val indicator = View(context).apply {
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

    private fun dpToPx(dp: Int): Int {
        val density = context.resources.displayMetrics.density
        return (dp * density).toInt()
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
