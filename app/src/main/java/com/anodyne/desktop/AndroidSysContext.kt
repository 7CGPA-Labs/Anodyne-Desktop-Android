package com.anodyne.desktop

import android.app.ActivityManager
import android.content.Context
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import android.webkit.JavascriptInterface

class AndroidSysContext(
    private val context: Context,
    private val launchTabCallback: (String, String, String) -> Unit = { _, _, _ -> },
    private val evaluateJs: (String) -> Unit = {},
    private val showKeyboardCallback: () -> Unit = {},
    private val hideKeyboardCallback: () -> Unit = {},
    private val setMenusCallback: (String, String) -> Unit = { _, _ -> }
) {

    @JavascriptInterface
    fun getBatteryLevel(): Int {
        return try {
            val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting battery level", e)
            -1
        }
    }

    @JavascriptInterface
    fun getWifiSSID(): String {
        return try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val info = wifiManager.connectionInfo
            if (info != null && info.ssid != null) {
                var ssid = info.ssid
                if (ssid.startsWith("\"") && ssid.endsWith("\"")) {
                    ssid = ssid.substring(1, ssid.length - 1)
                }
                ssid
            } else {
                "Unknown"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting Wifi SSID", e)
            "N/A"
        }
    }

    @JavascriptInterface
    fun launchAppTab(appId: String, targetUrl: String, title: String) {
        launchTabCallback(appId, targetUrl, title)
    }

    @JavascriptInterface
    fun switchTab(appId: String) {
        launchTabCallback(appId, "", "")
    }

    @JavascriptInterface
    fun getStorageInfo(): String {
        return try {
            val path = context.filesDir
            val stat = android.os.StatFs(path.path)
            val blockSize = stat.blockSizeLong
            val totalBlocks = stat.blockCountLong
            val availableBlocks = stat.availableBlocksLong
            
            val totalGb = (totalBlocks * blockSize).toDouble() / (1024 * 1024 * 1024)
            val availGb = (availableBlocks * blockSize).toDouble() / (1024 * 1024 * 1024)
            val usedGb = totalGb - availGb
            
            String.format(java.util.Locale.US, "%.1f GB used of %.1f GB", usedGb, totalGb)
        } catch (e: Exception) {
            Log.e(TAG, "Error calculating storage details", e)
            "N/A"
        }
    }

    @JavascriptInterface
    fun getRamUsage(): String {
        return try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memoryInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memoryInfo)
            
            val totalGb = memoryInfo.totalMem.toDouble() / (1024 * 1024 * 1024)
            val usedGb = (memoryInfo.totalMem - memoryInfo.availMem).toDouble() / (1024 * 1024 * 1024)
            
            String.format(java.util.Locale.US, "%.1f GB / %.1f GB", usedGb, totalGb)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting RAM usage details", e)
            "N/A"
        }
    }

    @JavascriptInterface
    fun getSystemCore(): String {
        val release = Build.VERSION.RELEASE
        val kernel = System.getProperty("os.version") ?: ""
        return "Android $release ($kernel)"
    }

    // --- Native to Web Event Dispatchers ---

    fun notifyJobProgress(jobId: String, progress: Int) {
        evaluateJs("if (window.onNativeJobProgressChanged) { window.onNativeJobProgressChanged('$jobId', $progress); }")
    }

    @JavascriptInterface
    fun showFloatingKeyboard() {
        showKeyboardCallback()
    }

    @JavascriptInterface
    fun hideFloatingKeyboard() {
        hideKeyboardCallback()
    }

    @JavascriptInterface
    fun setAppMenus(appName: String, menusJson: String) {
        setMenusCallback(appName, menusJson)
    }

    // --- Settings PWA JavascriptInterface Binders ---
    @JavascriptInterface
    fun getZramDiskSize(): String = "2.0 GB"

    @JavascriptInterface
    fun getZramAlgorithm(): String = "zstd"

    @JavascriptInterface
    fun getSystemSwappiness(): String = "60"

    @JavascriptInterface
    fun getStorageStatus(): String = getStorageInfo()

    @JavascriptInterface
    fun executeSystemCommand(action: String) {
        Log.i(TAG, "Settings: Executed system command action: $action")
    }

    @JavascriptInterface
    fun getAccessPin(): String {
        return (context as? MainActivity)?.getAccessPin() ?: "000000"
    }

    @JavascriptInterface
    fun setUiScale(scale: Float) {
        (context as? MainActivity)?.setUiScaleFromWeb(scale)
    }

    @JavascriptInterface
    fun setCursorStyle(color: String, size: String) {
        (context as? MainActivity)?.setCursorStyleFromWeb(color, size)
    }

    @JavascriptInterface
    fun setPointerSpeed(speed: Float) {
        (context as? MainActivity)?.setPointerSpeedFromWeb(speed)
    }

    @JavascriptInterface
    fun setScrollDirectionNatural(natural: Boolean) {
        (context as? MainActivity)?.setScrollDirectionNaturalFromWeb(natural)
    }

    @JavascriptInterface
    fun setOverscanPadding(padding: Int) {
        (context as? MainActivity)?.setOverscanPaddingFromWeb(padding)
    }

    @JavascriptInterface
    fun stopRemoteControlSession() {
        (context as? MainActivity)?.stopRemoteControlSessionFromWeb()
    }

    @JavascriptInterface
    fun getGpsLocation(): String {
        return (context as? MainActivity)?.getGpsLocationFromNative() ?: "Locating..."
    }

    companion object {
        private const val TAG = "AndroidSysContext"
    }
}
