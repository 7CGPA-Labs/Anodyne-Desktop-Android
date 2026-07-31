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

## Architecture Setup

- **Frontend**: A Momentum-style dashboard and local PWAs bundle located in `app/src/main/assets/`.
- **Bridge**: A custom Kotlin bridge class `AndroidSysContext` attached as `sysContext` inside the WebViews, mapping system metrics and tab routing.
- **Secondary Display**: Dynamic monitors auto-resolution detection and `DesktopPresentation` layouts via Android's `DisplayManager`.
