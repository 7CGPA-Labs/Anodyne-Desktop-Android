# Implementation Plan - Anodyne Desktop (Android Host Pivot)

This document outlines the transition of the **Anodyne OS** product concept (a web-first lightweight interface) to a native Android application container: **Anodyne Desktop**.

---

## Architecture Mapping: Retained, Replaced, Eliminated

Based on the **Anodyne OS Development Journal**, the transition maps as follows:

| Retained (Look & Feel) | Replaced (Android Host Framework) | Eliminated (Lower-level OS Details) |
| :--- | :--- | :--- |
| **Momentum Kiosk UI**: Minimalist clock, greeting text, quotes, Developer news feed, and background job visualizers. | **Qt WebEngine $\rightarrow$ Android WebView**: Replaced custom Qt compositor and `QWebEngineView` with a full-screen, hardware-accelerated `android.webkit.WebView`. | **Kernel/Hardware Configuration Scripts**: TPM LUKS2 partition scripts, dm-verity verification, zRAM swap configurations, and custom network policy routing rules (now handled by native Android OS layers). |
| **Glassmorphic Theme**: CSS design system variables, fonts (*Plus Jakarta Sans*, *JetBrains Mono*, *Space Grotesk*), and translucent card visual containers. | **Qt/QWebChannel Bridge $\rightarrow$ Android JS Bridge**: Injected `window.sysContext` via `webView.addJavascriptInterface(...)` using Kotlin `@JavascriptInterface` annotations. | **Custom URL Scheme Handler**: `AnodyneUrlSchemeHandler` (`anodyne://`) is eliminated. Android loads local assets using the secure, built-in `file:///android_asset/` protocol. |
| **Offline Vector Assets**: Local Web Awesome icons and vector assets embedded inside the application package (no CDNs/network calls for UI). | **Linux D-Bus Telemetry $\rightarrow$ Android System APIs**: Replaced calls to `nmcli`, `upower`, and sysfs directories with: <br> • Battery: `BatteryManager` <br> • Disk/Storage: `StatFs` <br> • Wi-Fi SSID: `WifiManager`. | **Seccomp-BPF & cgroups Jails**: Custom seccomp filters and cgroups limits are eliminated, as Android's WebView process isolation natively sandboxes renderer threads. |
| **Background WebAssembly (Wasm)**: Web Worker pipelines running CPU-intensive operations (like the WASM calculation panel) inside isolated JS sandboxes. | **Command-line App Launching $\rightarrow$ Android Intents**: Replaced native terminal commands (like launching file managers or system settings panels) with Android `Intent` routing. | **KaiOS Engine & Keypad Translators**: B2G/Gecko runtime engine and spatial key translators for feature-phone apps are eliminated. |
| **OSX-Style Layout Frame**: Minimal top navigation strip for status controls and contextual settings menus. | **CageBreak / Sway Compositors $\rightarrow$ Presentation API**: Replaced sway compositor display layouts with Android's native `DisplayManager` and `Presentation` API. | **D-Bus Notification Servers & PAM Auth**: Standard Linux desktop notification daemons and PAM authentication layers are eliminated. |

---

## Proposed Changes

### Component 1: Frontend Asset Sync & Bridge Compatibility

We will synchronize the previous WebOS project's `web-apps/` resources to our Android project workspace.

#### [NEW] [web-assets/](file:///home/gagan/Projects/Anodyne-Desktop-Android/web-assets)
- Synchronized local repository copy of the frontend dashboard and widget files (`files/`, `homepage/`, `settings/`, `web-awesome/`, and `test.html`) copied from `/home/gagan/Projects/WebOS-Appliance/web-apps/`.

#### [NEW] [app/src/main/assets/](file:///home/gagan/Projects/Anodyne-Desktop-Android/app/src/main/assets)
- Copy all files from `web-assets/` directly into `app/src/main/assets/` to build them into the Android package assets.

#### [MODIFY] [homepage/script.js](file:///home/gagan/Projects/Anodyne-Desktop-Android/app/src/main/assets/homepage/script.js) (and repository copy)
- Update `initializeAnodyneIPCBridge` to check if `window.sysContext` is injected directly (Android WebView style).
- If present, define global callback hooks on the `window` object:
  ```javascript
  window.onNativeJobProgressChanged = function(jobId, progress) {
      updateVisualTaskBar(jobId, progress);
  };
  window.onNativeJobFinished = function(jobId, success, message) {
      completeVisualTaskBar(jobId, message);
  };
  sysContext.logWebEvent("Dashboard UI successfully integrated with Android Hard Layer.");
  ```
- Modify `launchApp(appKeyword)` to bridge legacy application keyword controls:
  ```javascript
  function launchApp(appKeyword) {
      if (window.sysContext) {
          if (appKeyword === 'files' || appKeyword === 'settings') {
              sysContext.executeSystemCommand(appKeyword);
          } else {
              sysContext.launchApp(appKeyword);
          }
      }
  }
  ```

---

### Component 2: Native Android Kotlin Source

The Kotlin codebase will be written under package `com.anodyne.desktop` inside the directory `app/src/main/java/com/anodyne/desktop/`.

#### [NEW] [AndroidSysContext.kt](file:///home/gagan/Projects/Anodyne-Desktop-Android/app/src/main/java/com/anodyne/desktop/AndroidSysContext.kt)
Create the JavaScript bridge class `com.anodyne.desktop.AndroidSysContext`:
- **Methods with `@JavascriptInterface`**:
  - `getBatteryLevel(): Int`: Queries the percentage of charge from `BatteryManager`.
  - `getStorageStatus(): String`: Uses `StatFs` of `Environment.getDataDirectory()` to calculate available space and return a formatted string (e.g. `"Healthy (12.4 GB / 64.0 GB)"`).
  - `getWifiSSID(): String`: Queries current SSID using `WifiManager.connectionInfo`. If permission is lacking or SSID is unknown, return `"Connected (Wi-Fi)"`.
  - `launchApp(packageName: String)`: Starts target application using `packageManager.getLaunchIntentForPackage`.
  - `executeSystemCommand(command: String)`: Resolves legacy system command calls:
    - `"settings"` $\rightarrow$ starts standard Android settings activity (`Settings.ACTION_SETTINGS`).
    - `"files"` $\rightarrow$ starts standard system file management picker (`Intent.ACTION_GET_CONTENT` or documents activity).
  - `logWebEvent(message: String)`: Forwards events to Android Logcat (`Log.d`).

#### [NEW] [MainActivity.kt](file:///home/gagan/Projects/Anodyne-Desktop-Android/app/src/main/java/com/anodyne/desktop/MainActivity.kt)
Create the primary activity:
- Inherit from `androidx.appcompat.app.AppCompatActivity`.
- Programmatically instantiate a full-screen, hardware-accelerated `WebView`.
- Configure layout to run in full-screen immersive mode (hiding action bar, title bar, status bar, and system navigation bar).
- Enable JavaScript, DOM storage, content/file access.
- Register `AndroidSysContext` under `"sysContext"`.
- Load `file:///android_asset/homepage/index.html`.
- Manage `DisplayManager.DisplayListener` to detect when external screens (Samsung DeX style) are connected, spawning or dismissing `DesktopPresentation` dynamically.

#### [NEW] [DesktopPresentation.kt](file:///home/gagan/Projects/Anodyne-Desktop-Android/app/src/main/java/com/anodyne/desktop/DesktopPresentation.kt)
Create the secondary display presentation container:
- Inherit from `android.app.Presentation`.
- Construct and host a separate full-screen, hardware-accelerated `WebView` loading the same homepage dashboard assets.
- Expose the `"sysContext"` bridge to the secondary view.

---

### Component 3: Configuration & Permissions

#### [MODIFY] [AndroidManifest.xml](file:///home/gagan/Projects/Anodyne-Desktop-Android/app/src/main/AndroidManifest.xml)
- Include permission declarations:
  - `<uses-permission android:name="android.permission.INTERNET" />`
  - `<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />`
  - `<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />`
  - `<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />` (Needed for Wi-Fi SSID resolution)
  - `<uses-permission android:name="android.permission.QUERY_ALL_PACKAGES" tools:ignore="QueryAllPackagesPermission" />` (Needed to query and launch installed apps by package name on API 30+)
- Declare `com.anodyne.desktop.MainActivity` as the primary launcher activity.
- Add `android:configChanges="orientation|keyboardHidden|screenSize|smallestScreenSize|screenLayout|density"` to avoid recreation on external display connections.

---

## Verification Plan

### Automated Build Verification
- Compile and build the project using `./gradlew assembleDebug` to verify that there are no compilation, packaging, or namespace issues.

### Manual Verification
- Verify that assets exist under `app/src/main/assets/homepage/index.html`.
- Review syntax of all Javascript and Kotlin changes.
