@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
package com.anodyne.desktop

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DesktopUiHelper {

    // Scales a dimension in dp to raw pixels
    fun dpToPx(context: Context, dp: Int): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }

    // Dynamic builder for the Gnome Calendar and Notification center dropdown panel
    fun buildGnomeCalendarDropdownView(
        context: Context,
        scale: Float,
        mainAct: MainActivity,
        onTriggerMediaAction: (String) -> Unit
    ): LinearLayout {
        val rootDropdown = LinearLayout(context).apply {
            tag = "gnome_calendar"
            orientation = LinearLayout.HORIZONTAL
            setPadding(dpToPx(context, (12 * scale).toInt()), dpToPx(context, (12 * scale).toInt()), dpToPx(context, (12 * scale).toInt()), dpToPx(context, (12 * scale).toInt()))
            val borderDrawable = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#f912121a")) // Glassmorphism
                setStroke((1.5f * scale).toInt().coerceAtLeast(1), Color.parseColor("#2a2a3a"))
                cornerRadius = dpToPx(context, (10 * scale).toInt()).toFloat()
            }
            background = borderDrawable
            elevation = dpToPx(context, (12 * scale).toInt()).toFloat()
        }

        // --- Left Column: Notifications ---
        val notificationsLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.1f).apply {
                rightMargin = dpToPx(context, (12 * scale).toInt())
            }
        }

        // Header and Clear All layout
        val headerLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dpToPx(context, (8 * scale).toInt()))
        }
        val notifHeader = TextView(context).apply {
            text = "Notifications"
            setTextColor(Color.parseColor("#94a3b8"))
            textSize = 9.5f * scale
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerLayout.addView(notifHeader)

        val activeList = mainAct.notificationsList

        if (activeList.isNotEmpty()) {
            val clearAllBtn = TextView(context).apply {
                text = "Clear All"
                setTextColor(Color.parseColor("#3584e4"))
                textSize = 8f * scale
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dpToPx(context, 4), dpToPx(context, 2), dpToPx(context, 4), dpToPx(context, 2))
                setOnClickListener {
                    mainAct.notificationsList.clear()
                    mainAct.runOnUiThread {
                        mainAct.refreshGnomeCalendarDropdownIfVisible()
                    }
                }
            }
            headerLayout.addView(clearAllBtn)
        }
        notificationsLayout.addView(headerLayout)

        val notifScroll = ScrollView(context).apply {
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

        // Group and render notifications
        val groups = activeList.groupBy { it.source }
        for ((source, items) in groups) {
            val isExpanded = mainAct.expandedSources.contains(source)
            
            if (items.size > 1) {
                val groupHeader = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dpToPx(context, 2), dpToPx(context, 4), dpToPx(context, 2), dpToPx(context, 4))
                }
                val sourceTitle = TextView(context).apply {
                    text = "$source (${items.size})"
                    setTextColor(Color.parseColor("#e2e8f0"))
                    textSize = 8f * scale
                    typeface = Typeface.DEFAULT_BOLD
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                groupHeader.addView(sourceTitle)
                
                val toggleBtn = TextView(context).apply {
                    text = if (isExpanded) "Collapse" else "Expand (${items.size - 1} more)"
                    setTextColor(Color.parseColor("#64748b"))
                    textSize = 7.5f * scale
                    setPadding(dpToPx(context, 4), dpToPx(context, 2), dpToPx(context, 4), dpToPx(context, 2))
                    setOnClickListener {
                        if (isExpanded) {
                            mainAct.expandedSources.remove(source)
                        } else {
                            mainAct.expandedSources.add(source)
                        }
                        mainAct.runOnUiThread {
                            mainAct.refreshGnomeCalendarDropdownIfVisible()
                        }
                    }
                }
                groupHeader.addView(toggleBtn)
                notifList.addView(groupHeader)
            }

            val visibleItems = if (isExpanded || items.size <= 1) items else listOf(items.first())
            for (notif in visibleItems) {
                val itemCard = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dpToPx(context, (6 * scale).toInt()), dpToPx(context, (6 * scale).toInt()), dpToPx(context, (6 * scale).toInt()), dpToPx(context, (6 * scale).toInt()))
                    val bg = android.graphics.drawable.GradientDrawable().apply {
                        setColor(Color.parseColor("#1a1a26"))
                        cornerRadius = dpToPx(context, (5 * scale).toInt()).toFloat()
                    }
                    background = bg
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        bottomMargin = dpToPx(context, (4 * scale).toInt())
                    }
                }
                
                val topRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                
                val titleView = TextView(context).apply {
                    text = if (items.size <= 1) "[$source] ${notif.title}" else notif.title
                    setTextColor(Color.WHITE)
                    textSize = 8.2f * scale
                    typeface = Typeface.DEFAULT_BOLD
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }
                topRow.addView(titleView)
                
                val closeBtn = TextView(context).apply {
                    text = "×"
                    setTextColor(Color.parseColor("#64748b"))
                    textSize = 10f * scale
                    setPadding(dpToPx(context, 4), 0, dpToPx(context, 4), 0)
                    setOnClickListener {
                        mainAct.notificationsList.remove(notif)
                        mainAct.runOnUiThread {
                            mainAct.refreshGnomeCalendarDropdownIfVisible()
                        }
                    }
                }
                topRow.addView(closeBtn)
                itemCard.addView(topRow)
                
                val descView = TextView(context).apply {
                    text = notif.text
                    setTextColor(Color.parseColor("#94a3b8"))
                    textSize = 7.8f * scale
                    setPadding(0, dpToPx(context, (2 * scale).toInt()), 0, 0)
                }
                itemCard.addView(descView)
                
                if (notif.progress in 0..100) {
                    val progressLayout = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, dpToPx(context, (4 * scale).toInt()), 0, 0)
                    }
                    val bar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                        max = 100
                        progress = notif.progress
                        layoutParams = LinearLayout.LayoutParams(0, dpToPx(context, (6 * scale).toInt()), 1f).apply {
                            rightMargin = dpToPx(context, (6 * scale).toInt())
                        }
                    }
                    val pct = TextView(context).apply {
                        text = "${notif.progress}%"
                        setTextColor(Color.WHITE)
                        textSize = 7.5f * scale
                    }
                    progressLayout.addView(bar)
                    progressLayout.addView(pct)
                    itemCard.addView(progressLayout)
                }
                
                notifList.addView(itemCard)
            }
        }

        notifScroll.addView(notifList)
        notificationsLayout.addView(notifScroll)

        // Media Player Widget inside notification center
        if (mainAct.mediaPlaybackState != "none" && mainAct.mediaTitle.isNotEmpty()) {
            val mediaCard = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dpToPx(context, (8 * scale).toInt()), dpToPx(context, (8 * scale).toInt()), dpToPx(context, (8 * scale).toInt()), dpToPx(context, (8 * scale).toInt()))
                val bg = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.parseColor("#13131c"))
                    setStroke(dpToPx(context, 1), Color.parseColor("#2a2a3e"))
                    cornerRadius = dpToPx(context, (8 * scale).toInt()).toFloat()
                }
                background = bg
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dpToPx(context, (8 * scale).toInt())
                }
                gravity = Gravity.CENTER_VERTICAL
            }

            val artImage = ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    dpToPx(context, (32 * scale).toInt()),
                    dpToPx(context, (32 * scale).toInt())
                ).apply {
                    rightMargin = dpToPx(context, (8 * scale).toInt())
                }
                scaleType = ImageView.ScaleType.CENTER_CROP
                if (mainAct.mediaArtworkUrl.isNotEmpty()) {
                    val imgView = this
                    val urlStr = mainAct.mediaArtworkUrl
                    Thread {
                        try {
                            val stream = java.net.URL(urlStr).openStream()
                            val bmp = android.graphics.BitmapFactory.decodeStream(stream)
                            mainAct.runOnUiThread {
                                imgView.setImageBitmap(bmp)
                            }
                        } catch (e: Exception) {
                            mainAct.runOnUiThread {
                                imgView.setImageResource(android.R.drawable.ic_media_play)
                            }
                        }
                    }.start()
                } else {
                    setImageResource(android.R.drawable.ic_media_play)
                }
            }
            mediaCard.addView(artImage)

            val infoLayout = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val trackTitle = TextView(context).apply {
                text = mainAct.mediaTitle
                setTextColor(Color.WHITE)
                textSize = 8.5f * scale
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            val trackArtist = TextView(context).apply {
                text = if (mainAct.mediaArtist.isNotEmpty()) mainAct.mediaArtist else "Unknown Artist"
                setTextColor(Color.parseColor("#94a3b8"))
                textSize = 7.5f * scale
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            infoLayout.addView(trackTitle)
            infoLayout.addView(trackArtist)
            mediaCard.addView(infoLayout)

            val controlsLayout = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }

            if (mainAct.mediaHasPrev) {
                val prevBtn = TextView(context).apply {
                    text = "⏮"
                    setTextColor(Color.WHITE)
                    textSize = 14f * scale
                    setPadding(dpToPx(context, (6 * scale).toInt()), 0, dpToPx(context, (6 * scale).toInt()), 0)
                    setOnClickListener {
                        onTriggerMediaAction("previoustrack")
                    }
                }
                controlsLayout.addView(prevBtn)
            }

            val playPauseBtn = TextView(context).apply {
                text = if (mainAct.mediaPlaybackState == "playing") "⏸" else "▶"
                setTextColor(Color.WHITE)
                textSize = 14f * scale
                setPadding(dpToPx(context, (6 * scale).toInt()), 0, dpToPx(context, (6 * scale).toInt()), 0)
                setOnClickListener {
                    if (mainAct.mediaPlaybackState == "playing") {
                        onTriggerMediaAction("pause")
                    } else {
                        onTriggerMediaAction("play")
                    }
                }
            }
            controlsLayout.addView(playPauseBtn)

            if (mainAct.mediaHasNext) {
                val nextBtn = TextView(context).apply {
                    text = "⏭"
                    setTextColor(Color.WHITE)
                    textSize = 14f * scale
                    setPadding(dpToPx(context, (6 * scale).toInt()), 0, dpToPx(context, (6 * scale).toInt()), 0)
                    setOnClickListener {
                        onTriggerMediaAction("nexttrack")
                    }
                }
                controlsLayout.addView(nextBtn)
            }

            mediaCard.addView(controlsLayout)
            notificationsLayout.addView(mediaCard)
        }

        rootDropdown.addView(notificationsLayout)

        val divider = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                dpToPx(context, (1 * scale).toInt().coerceAtLeast(1)),
                ViewGroup.LayoutParams.MATCH_PARENT
            ).apply {
                rightMargin = dpToPx(context, (12 * scale).toInt())
            }
            setBackgroundColor(Color.parseColor("#2a2a3a"))
        }
        rootDropdown.addView(divider)

        // --- Right Column: Calendar ---
        val calendarLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.0f)
        }

        val calHeader = TextView(context).apply {
            val sdf = SimpleDateFormat("MMMM yyyy", Locale.US)
            text = sdf.format(Date())
            setTextColor(Color.WHITE)
            textSize = 9.5f * scale
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, dpToPx(context, (8 * scale).toInt()))
        }
        calendarLayout.addView(calHeader)

        val daysGrid = android.widget.GridLayout(context).apply {
            columnCount = 7
            rowCount = 7
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val daysOfWeek = listOf("Su", "Mo", "Tu", "We", "Th", "Fr", "Sa")
        for (dayName in daysOfWeek) {
            val label = TextView(context).apply {
                text = dayName
                setTextColor(Color.parseColor("#64748b"))
                textSize = 7.5f * scale
                gravity = Gravity.CENTER
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
                    height = dpToPx(context, (18 * scale).toInt())
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
                    height = dpToPx(context, (16 * scale).toInt())
                    columnSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f)
                }

                if (day == currentDay) {
                    setTextColor(Color.WHITE)
                    val bg = android.graphics.drawable.GradientDrawable().apply {
                        setColor(Color.parseColor("#3584e4"))
                        cornerRadius = dpToPx(context, (8 * scale).toInt()).toFloat()
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

        return rootDropdown
    }

    // Dynamic builder for generating Web Page Context Menu item entries based on selection or elements
    fun buildWebPageContextMenuItems(
        context: Context,
        webView: WebView,
        selectedText: String,
        openOrSwitchTab: (String, String, String) -> Unit,
        showToast: (String) -> Unit
    ): List<MacMenuItem> {
        val pageUrl = webView.url ?: ""
        val pageTitle = webView.title ?: "Web App"
        val hitTest = webView.hitTestResult
        val hitType = hitTest.type
        val hitExtra = hitTest.extra ?: ""
        val mainAct = context as? MainActivity

        val menuItems = mutableListOf<MacMenuItem>()

        if (selectedText.isNotEmpty()) {
            menuItems.add(MacMenuItem("📋 Copy") {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("Selected Text", selectedText)
                clipboard.setPrimaryClip(clip)
                showToast("Text copied to clipboard")
            })
            val searchLabel = if (selectedText.length > 20) selectedText.take(17) + "..." else selectedText
            menuItems.add(MacMenuItem("🔍 Search Google for \"$searchLabel\"") {
                try {
                    val searchUrl = "https://www.google.com/search?q=" + java.net.URLEncoder.encode(selectedText, "UTF-8")
                    openOrSwitchTab("search_" + System.currentTimeMillis(), searchUrl, "Google Search")
                } catch (e: Exception) {
                    LogHelper.e("DesktopUiHelper", "Search URL encode error", e)
                }
            })
            menuItems.add(MacMenuItem("🌐 Translate") {
                try {
                    val transUrl = "https://translate.google.com/?sl=auto&text=" + java.net.URLEncoder.encode(selectedText, "UTF-8")
                    openOrSwitchTab("trans_" + System.currentTimeMillis(), transUrl, "Translate")
                } catch (e: Exception) {
                    LogHelper.e("DesktopUiHelper", "Translate URL encode error", e)
                }
            })
            menuItems.add(MacMenuItem(isSeparator = true))
        } else if (hitType == WebView.HitTestResult.SRC_ANCHOR_TYPE || hitType == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
            menuItems.add(MacMenuItem("🔗 Open Link in New Tab") {
                openOrSwitchTab("link_" + System.currentTimeMillis(), hitExtra, "New Tab")
            })
            menuItems.add(MacMenuItem("📋 Copy Link Address") {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("Link Address", hitExtra)
                clipboard.setPrimaryClip(clip)
                showToast("Link address copied")
            })
            menuItems.add(MacMenuItem(isSeparator = true))
        } else if (hitType == WebView.HitTestResult.IMAGE_TYPE || hitType == WebView.HitTestResult.IMAGE_ANCHOR_TYPE) {
            menuItems.add(MacMenuItem("🖼️ Open Image in New Tab") {
                openOrSwitchTab("img_" + System.currentTimeMillis(), hitExtra, "Image")
            })
            menuItems.add(MacMenuItem("📋 Copy Image Link") {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("Image URL", hitExtra)
                clipboard.setPrimaryClip(clip)
                showToast("Image URL copied")
            })
            menuItems.add(MacMenuItem(isSeparator = true))
        }

        // Standard Page operations
        menuItems.add(MacMenuItem("🔄 Reload Page") {
            webView.reload()
        })
        if (webView.canGoBack()) {
            menuItems.add(MacMenuItem("⬅️ Back") {
                webView.goBack()
            })
        }
        if (webView.canGoForward()) {
            menuItems.add(MacMenuItem("➡️ Forward") {
                webView.goForward()
            })
        }

        menuItems.add(MacMenuItem("🖨️ Print Page") {
            if (mainAct != null) {
                webView.post {
                    webView.evaluateJavascript("window.print()", null)
                }
            }
        })

        // Web PWA installation logic
        if (!pageUrl.startsWith("file:///android_asset/") && pageUrl.isNotEmpty()) {
            menuItems.add(MacMenuItem("📥 Install Page as PWA") {
                if (mainAct != null) {
                    val id = "pwa_" + System.currentTimeMillis()
                    mainAct.registerDynamicPwaFromWeb(id, pageTitle, pageUrl, "Internet")
                    showToast("\"$pageTitle\" has been installed to the App Drawer!")
                }
            })
        }

        return menuItems
    }
}

// Simple internal logger helper to avoid direct dependency on MainActivity.TAG
object LogHelper {
    fun e(tag: String, msg: String, t: Throwable? = null) {
        android.util.Log.e(tag, msg, t)
    }
}
