# Anodyne Desktop (Android Host Container)

This repository contains the source code for **Anodyne Desktop**, a web-first desktop launcher and secondary display application for Android.

## Project Scope & Purpose

> [!NOTE]
> **Look and Feel Emulation**: This Android Kotlin application is designed to emulate and retain the complete **look, feel, and multitasking workflow** of the original **Anodyne OS** Qt/QML hybrid desktop shell. 

### Feature Allocation

1. **Retained & Emulated in this App**:
   - The OS **multitasking visual layout** (Top status bar menu, horizontal tab-based switching, clock, widgets, lock screen overlays).
   - Dynamic UI scaling and DPI adaptations for both phone viewports and external cast displays (TVs/monitors).
   - High-performance, sandboxed local progressive web apps (PWAs) (Dashboard, Files, Settings) running offline with shared WebAssembly compute modules.

2. **Eliminated / Handled by the Underlying OS**:
   - Platform-level security and hardware gating (such as **TPM LUKS2 volume encryption**, **dm-verity boot block verification**, kernel-level **zRAM memory compression**, and wireless policy routing).
   - These features are eliminated from this application layer because they are standardly implemented at the underlying Android Linux kernel and operating system level, ensuring sandbox security constraints are fully respected.

---

## Architecture & Security Setup

* **Frontend**: A Momentum-style dashboard and local asset-based PWAs bundled inside `app/src/main/assets/`.
* **Secure Web-to-Native Bridge**: Replaced raw Javascript interface bindings with origin-verified `WebMessagePort` asynchronous channels (`window.anodyneIPC`). All local web apps communicate asynchronously via stringified JSON action-event structures.
* **Sandbox & File Security**: Enforces strict local directory access controls (`allowFileAccess = false`, `allowContentAccess = false`). File transfers are routed securely via system intent file pickers and standard public download streams.
* **WebRTC Remote Input Protection**: Sanitizes and clamps float coordinates to `[0.0, 1.0]` viewport boundaries and maps context inputs directly to native context dialog triggers to reject malicious script execution.
* **Secondary Display**: Emulates multi-monitor workspace switching and custom display scaling via Android's `DisplayManager` and native `DesktopPresentation` layouts.
* **Status Indicators**: Topbar features interactive Wi-Fi (SSID queries), cellular data (4G/5G signal indicators), battery metrics, active downloads tracking, and a native hardware session kill-switch overlay.

