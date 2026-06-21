package com.torve.android.device

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration

object DeviceFormFactor {
    fun isTv(context: Context): Boolean {
        val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        if (uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) {
            return true
        }

        val packageManager = context.packageManager
        if (
            packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            packageManager.hasSystemFeature("android.hardware.type.television") ||
            packageManager.hasSystemFeature("amazon.hardware.fire_tv")
        ) {
            return true
        }

        // Some Android TV forks don't expose the standard TV feature flags.
        val hasTouchscreen = packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
        val hasTelephony = packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
        val hasAnyCamera = packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
        return !hasTouchscreen && !hasTelephony && !hasAnyCamera
    }
}
