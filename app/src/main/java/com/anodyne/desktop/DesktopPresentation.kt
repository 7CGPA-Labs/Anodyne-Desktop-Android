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

    // Presentation Virtual Cursor
    private lateinit var cursorView: ImageView
    private var cursorX = 0f
    private var cursorY = 0f

    // Tooltip & Top Bar UI elements
    private lateinit var tooltipView: TextView
    private lateinit var topBar: LinearLayout
    private lateinit var logoText: TextView
    private lateinit var anodyneMenu: TextView
    private lateinit var leftContainer: LinearLayout

    private lateinit var wifiTextView: TextView
    private lateinit var cellularTextView: TextView
    private lateinit var batteryTextView: TextView
    private lateinit var clockTextView: TextView

    // Custom dropdown layouts inside workspaceContainer
    private var activeDropdownView: View? = null
    private var activeSubmenuView: View? = null

    private var currentScale = 1.0f
    private var isRemoteSharingActive = false
    private lateinit var remoteBanner: LinearLayout

    fun updateRemoteSharingStatus(active: Boolean) {
        clockHandler.post {
            isRemoteSharingActive = active
            remoteBanner.visibility = if (active) View.VISIBLE else View.GONE
        }
    }

    fun updatePresentationScale(scale: Float) {
        clockHandler.post {
            currentScale = scale
            applyUiScale()
        }
    }

    fun updateCursorStyle(colorName: String, sizeName: String) {
        clockHandler.post {
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
        }
    }

    private fun applyUiScale() {
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
                dpToPx(22)
            )
            setBackgroundColor(Color.parseColor("#0c0c14"))
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(14), 0, dpToPx(14), 0)
        }

        // Left Container
        leftContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            gravity = Gravity.LEFT or Gravity.CENTER_VERTICAL
        }

        logoText = TextView(context).apply {
            text = "⬡"
            setTextColor(Color.parseColor("#f8fafc"))
            textSize = 11f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setOnClickListener { showLxqtAppDrawer(this) }
        }
        registerTooltipHover(logoText) { "App Menu" }
        leftContainer.addView(logoText)
        topBar.addView(leftContainer)

        // Center Container
        val centerContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
            gravity = Gravity.CENTER
        }

        clockTextView = TextView(context).apply {
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

        // Right Container
        val rightContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
        }

        // Spotlight Icon on presentation TopBar
        spotlightBtn = TextView(context).apply {
            text = "🔍"
            setTextColor(Color.parseColor("#94a3b8"))
            textSize = 8.5f
            setPadding(dpToPx(5), 0, dpToPx(5), 0)
            setOnClickListener { toggleSpotlightSearch() }
        }
        rightContainer.addView(spotlightBtn)

        wifiTextView = TextView(context).apply {
            text = "🛜"
            setTextColor(Color.parseColor("#94a3b8"))
            textSize = 8.5f
            setPadding(dpToPx(2), 0, 0, 0)
            setOnClickListener { showWifiDropdown() }
        }
        rightContainer.addView(wifiTextView)

        cellularTextView = TextView(context).apply {
            text = "📶 " + getCellularNetworkType()
            setTextColor(Color.parseColor("#94a3b8"))
            textSize = 8.5f
            setPadding(dpToPx(2), 0, 0, 0)
            setOnClickListener { showCellularDropdown() }
        }
        rightContainer.addView(cellularTextView)

        batteryTextView = TextView(context).apply {
            text = "🔋"
            setTextColor(Color.parseColor("#94a3b8"))
            textSize = 8.5f
            setPadding(dpToPx(4), 0, dpToPx(4), 0)
            setOnClickListener { showBatteryDropdown() }
        }
        rightContainer.addView(batteryTextView)
        topBar.addView(rightContainer)

        registerTooltipHover(spotlightBtn) { "Spotlight Search" }
        registerTooltipHover(wifiTextView) { "Connected to: " + getWifiSSID() }
        registerTooltipHover(cellularTextView) { "Cellular: " + getCellularNetworkType() }
        registerTooltipHover(batteryTextView) { "Battery: " + getBatteryPct() + "%" }

        rootLayout.addView(topBar)

        // Overhead Remote session active banner
        remoteBanner = LinearLayout(context).apply {
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

        val bannerText = TextView(context).apply {
            text = "🔴 Remote Control Active — Connected to Tech Support"
            setTextColor(Color.WHITE)
            textSize = 9f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        remoteBanner.addView(bannerText)
        rootLayout.addView(remoteBanner)

        rootLayout.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
            setBackgroundColor(Color.parseColor("#1a1a24"))
        })

        // 2. Tab Bar
        tabScroll = HorizontalScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(28)
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

        // Initialize Floating Tooltip View
        tooltipView = TextView(context).apply {
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

        // 4. Spotlight Search Overlay
        setupSpotlightSearch()

        setContentView(workspaceContainer)

        // Default to Home tab
        openOrSwitchTab("home", "file:///android_asset/homepage/index.html", "Dashboard")

        // Start clock timer
        clockHandler.post(clockRunnable)
    }

    // Lubuntu LXQt-style App Drawer cascading menu implementation
    private fun showLxqtAppDrawer(anchorView: View) {
        dismissActiveDropdown()

        val popupView = LinearLayout(context).apply {
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
            MacMenuItem("Restart Shell") { dismiss() }
        )

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
                                    MacMenuItem("Spotlight Search") { toggleSpotlightSearch() }
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
            popupView.bringToFront()
            cursorView.bringToFront()
        }
    }

    private fun showSubMenu(anchorView: View, items: List<MacMenuItem>) {
    }

    // GNOME-style Calendar & Notification panel for secondary presentation screens inside workspaceContainer
    private fun showGnomeCalendarDropdown(anchorView: View) {
        dismissActiveDropdown()

        val scale = currentScale
        val popupWidth = dpToPx((380 * scale).toInt())
        val popupHeight = dpToPx((230 * scale).toInt())
        
        val rootDropdown = LinearLayout(context).apply {
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

        // Left Column: Notifications
        val notificationsLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.1f).apply {
                rightMargin = dpToPx((12 * scale).toInt())
            }
        }

        val notifHeader = TextView(context).apply {
            text = "Notifications"
            setTextColor(Color.parseColor("#94a3b8"))
            textSize = 9.5f * scale
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dpToPx((8 * scale).toInt()))
        }
        notificationsLayout.addView(notifHeader)

        val notifScroll = android.widget.ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            isVerticalScrollBarEnabled = false
        }
        val notifList = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        val addNotification = { titleStr: String, textStr: String, timeStr: String ->
            val item = LinearLayout(context).apply {
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
            
            val tRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            val titleText = TextView(context).apply {
                text = titleStr
                setTextColor(Color.WHITE)
                textSize = 8.5f * scale
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val timeText = TextView(context).apply {
                text = timeStr
                setTextColor(Color.parseColor("#64748b"))
                textSize = 7f * scale
            }
            tRow.addView(titleText)
            tRow.addView(timeText)
            item.addView(tRow)

            val descText = TextView(context).apply {
                text = textStr
                setTextColor(Color.parseColor("#94a3b8"))
                textSize = 8f * scale
                setPadding(0, dpToPx((2 * scale).toInt()), 0, 0)
            }
            item.addView(descText)
            notifList.addView(item)
        }

        addNotification("System Update", "Anodyne Desktop is up to date.", "Just now")
        addNotification("Battery Status", "Running on " + getBatteryPowerSource(), "10m ago")

        notifScroll.addView(notifList)
        notificationsLayout.addView(notifScroll)
        rootDropdown.addView(notificationsLayout)

        val divider = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                dpToPx((1 * scale).toInt().coerceAtLeast(1)),
                ViewGroup.LayoutParams.MATCH_PARENT
            ).apply {
                rightMargin = dpToPx((12 * scale).toInt())
            }
            setBackgroundColor(Color.parseColor("#2a2a3a"))
        }
        rootDropdown.addView(divider)

        // Right Column: Calendar
        val calendarLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.0f)
        }

        val calHeader = TextView(context).apply {
            val sdf = SimpleDateFormat("MMMM yyyy", Locale.US)
            text = sdf.format(Date())
            setTextColor(Color.WHITE)
            textSize = 9.5f * scale
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, dpToPx((8 * scale).toInt()))
        }
        calendarLayout.addView(calHeader)

        val daysGrid = android.widget.GridLayout(context).apply {
            columnCount = 7
            rowCount = 6
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }

        val dayLabels = listOf("Su", "Mo", "Tu", "We", "Th", "Fr", "Sa")
        for (day in dayLabels) {
            val label = TextView(context).apply {
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
            val blank = View(context).apply {
                layoutParams = android.widget.GridLayout.LayoutParams().apply {
                    width = 0
                    height = dpToPx((18 * scale).toInt())
                    columnSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f)
                }
            }
            daysGrid.addView(blank)
        }

        for (day in 1..maxDays) {
            val cell = TextView(context).apply {
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
        }
    }

    fun showTooltip(text: String, x: Float, y: Float) {
        clockHandler.post {
            tooltipView.text = text
            tooltipView.visibility = View.VISIBLE
            tooltipView.x = x + dpToPx(12)
            tooltipView.y = y + dpToPx(16)
            tooltipView.bringToFront()
            cursorView.bringToFront()
        }
    }

    fun hideTooltip() {
        clockHandler.post {
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
        leftContainer.post {
            leftContainer.removeAllViews()
            leftContainer.addView(logoText)
        }
    }

    private fun registerPwaMenus(tabId: String, appName: String, json: String) {
        webViewContainer.post {
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

    // View-based Dynamic Custom Menu inside Presentation workspaceContainer
    private fun showMacMenu(anchorView: View, menuItems: List<MacMenuItem>, isSubMenu: Boolean = false, subMenuX: Float = 0f, subMenuY: Float = 0f) {
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

        val popupView = LinearLayout(context).apply {
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
                val sep = View(context).apply {
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
                val row = TextView(context).apply {
                    text = item.title
                    setTextColor(Color.parseColor("#e2e8f0"))
                    textSize = textSz
                    setPadding(padLeft, padTop, padRight, padBottom)
                    gravity = Gravity.CENTER_VERTICAL
                    val hoverBg = android.graphics.drawable.StateListDrawable().apply {
                        addState(intArrayOf(android.R.attr.state_focused), android.graphics.drawable.ColorDrawable(Color.parseColor("#3584e4")))
                        addState(intArrayOf(android.R.attr.state_pressed), android.graphics.drawable.ColorDrawable(Color.parseColor("#3584e4")))
                        addState(intArrayOf(), android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
                    }
                    background = hoverBg
                    isClickable = true
                    isFocusable = true

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
            popupView.bringToFront()
            cursorView.bringToFront()
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

    private fun showCellularDropdown() {
        showMacMenu(cellularTextView, listOf(
            MacMenuItem("Carrier: Anodyne Mobile"),
            MacMenuItem("Signal Strength: Excellent"),
            MacMenuItem("Network Type: " + getCellularNetworkType()),
            MacMenuItem(isSeparator = true),
            MacMenuItem("Data Usage: 14.2 GB used this month")
        ))
    }

    private fun getCellularNetworkType(): String {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val activeNetwork = cm.activeNetwork ?: return "4G"
            val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return "4G"
            
            if (capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)) {
                val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager
                if (androidx.core.app.ActivityCompat.checkSelfPermission(context, android.Manifest.permission.READ_PHONE_STATE) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
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

    private fun getBatteryPct(): Int {
        try {
            val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            return batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        } catch (e: Exception) {}
        return 100
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

    // Spotlight Search layout mapping for Presentation parity
    private fun setupSpotlightSearch() {
        spotlightOverlay = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#99000000"))
            visibility = View.GONE
            setOnClickListener { hideSpotlightSearch() }
        }

        val searchPanel = LinearLayout(context).apply {
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

        val searchIcon = TextView(context).apply {
            text = "🔍"
            setTextColor(Color.parseColor("#94a3b8"))
            textSize = 16f
            setPadding(0, 0, dpToPx(12), 0)
        }
        searchPanel.addView(searchIcon)

        spotlightInput = EditText(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            background = null
            hint = "Spotlight Search..."
            setHintTextColor(Color.parseColor("#475569"))
            setTextColor(Color.WHITE)
            textSize = 14f
            maxLines = 1
            isSingleLine = true
            isFocusable = false
        }
        searchPanel.addView(spotlightInput)
        spotlightOverlay.addView(searchPanel)
        workspaceContainer.addView(spotlightOverlay)
    }

    private fun toggleSpotlightSearch() {
    }

    fun showSpotlightSearch() {
        spotlightOverlay.visibility = View.VISIBLE
        spotlightInput.setText("")
        cursorView.bringToFront()
    }

    fun hideSpotlightSearch() {
        spotlightOverlay.visibility = View.GONE
    }

    fun showVirtualKeyboard() {}

    fun hideVirtualKeyboard() {}

    fun movePresentationCursor(dx: Float, dy: Float) {
        clockHandler.post {
            val maxW = workspaceContainer.width.toFloat()
            val maxH = workspaceContainer.height.toFloat()

            cursorX = (cursorX + dx).coerceIn(0f, maxW)
            cursorY = (cursorY + dy).coerceIn(0f, maxH)

            cursorView.x = cursorX
            cursorView.y = cursorY
            cursorView.bringToFront()

            dispatchHoverAtCursor()
        }
    }

    fun performPresentationClick(isRightClick: Boolean) {
        clockHandler.post {
            val webView = getActiveWebView()
            val cx = cursorX
            val cy = cursorY
            val offset = topBar.height + tabScroll.height + dpToPx(2)

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
                    return@post
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
                    return@post
                }
            }

            if (webView != null && cy >= offset) {
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
        val isAlreadyInstalled = (context as? MainActivity)?.dynamicPwas?.any { it.url == pageUrl } == true
        
        if (!isSystemPage && !isAlreadyInstalled) {
            menuItems.add(MacMenuItem("Install Page as PWA") {
                AlertDialog.Builder(context)
                    .setTitle("Install Application")
                    .setMessage("Do you want to install \"$pageTitle\" to your App Drawer?")
                    .setPositiveButton("Install") { _, _ ->
                        val id = "pwa_" + System.currentTimeMillis()
                        (context as? MainActivity)?.registerDynamicPwaFromWeb(id, pageTitle, pageUrl, "Internet")
                        android.widget.Toast.makeText(context, "\"$pageTitle\" has been installed to the App Drawer!", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            })
        }

        showMacMenu(webView, menuItems, isSubMenu = true, subMenuX = x, subMenuY = y)
    }

    fun scrollPresentation(dy: Float) {
        clockHandler.post {
            val webView = getActiveWebView() ?: return@post
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

            webView.evaluateJavascript(js, null)
        }
    }

    private fun dispatchHoverAtCursor() {
        val cx = cursorX
        val cy = cursorY

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

    private fun updateClockAndStatus() {
        val sdf = SimpleDateFormat("EEE MMM d  H:mm", Locale.US)
        clockTextView.text = sdf.format(Date())
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

            // Disable long click to prevent mobile selection handle tropes
            setOnLongClickListener { true }

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    Log.d(TAG, "Casting WebView page finished loading: $url")

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
                            
                            view.postWebMessage(android.webkit.WebMessage("init-ipc", arrayOf(webPort)), android.net.Uri.parse(url))
                        } catch (e: Exception) {
                            Log.e(TAG, "Error initializing WebMessagePort IPC channel in presentation", e)
                        }
                    }

                    val metrics = context.resources.displayMetrics
                    val width = metrics.widthPixels

                    val targetWidth = if (width >= 1920) 1920 else 1280

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
                            
                            // Focus state detection for virtual keyboard integration (presentation parity)
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
            allowFileAccess = false
            allowContentAccess = false
            allowUniversalAccessFromFileURLs = false
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = false
            displayZoomControls = false
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        }

        val sysContext = AndroidSysContext(context,
            launchTabCallback = { appId, targetUrl, title ->
                openOrSwitchTab(appId, targetUrl, title)
            },
            evaluateJs = { js ->
                newWebView.post {
                    newWebView.evaluateJavascript(js, null)
                }
            },
            showKeyboardCallback = {
                showVirtualKeyboard()
            },
            hideKeyboardCallback = {
                hideVirtualKeyboard()
            },
            setMenusCallback = { appName, json ->
                registerPwaMenus(url, appName, json)
            }
        )
        newWebView.loadUrl(url)

        return newWebView
    }

    private fun handleIpcMessage(webView: WebView, payload: String) {
        try {
            val json = org.json.JSONObject(payload)
            val msgId = json.optString("id", "")
            val action = json.optString("action", "")
            val args = json.optJSONArray("args")
            
            val result = (context as? MainActivity)?.executeIpcActionFromPresentation(action, args)
            
            val responseJs = """
                window.dispatchEvent(new CustomEvent('anodyneIpcResponse', {
                    detail: { id: '$msgId', result: ${result?.toString() ?: "null"} }
                }));
            """.trimIndent()
            webView.post {
                webView.evaluateJavascript(responseJs, null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing IPC message in presentation: $payload", e)
        }
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
                AlertDialog.Builder(context)
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

            val tabItem = LinearLayout(context).apply {
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

            val titleText = TextView(context).apply {
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
                val closeBtn = TextView(context).apply {
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

    private fun dpToPx(dp: Int): Int {
        val density = context.resources.displayMetrics.density
        return (dp * density).toInt()
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
