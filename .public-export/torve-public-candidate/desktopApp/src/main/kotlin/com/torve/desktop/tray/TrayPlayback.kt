package com.torve.desktop.tray

/**
 * Implementation lives in Main.kt - this file only reserves the package so
 * tray.kt stays uncoupled from the private DesktopRuntime data class. The
 * actual CW handler is passed as a lambda into `DesktopSystemTray` at
 * install time.
 */
