package com.torve.android.device

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import com.torve.domain.device.DeviceIdProvider

class AndroidDeviceIdProvider(private val context: Context) : DeviceIdProvider {
    @SuppressLint("HardwareIds")
    override fun getDeviceId(): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)

    override fun getDeviceName(): String = android.os.Build.MODEL

    override fun getDeviceType(): String =
        if (context.packageManager.hasSystemFeature("android.software.leanback")) "tv" else "phone"

    override fun getPlatform(): String {
        val isTV = context.packageManager.hasSystemFeature("android.software.leanback")
        return when {
            com.torve.android.BuildConfig.FLAVOR.contains("amazon") && isTV -> "amazon_fire_tv"
            com.torve.android.BuildConfig.FLAVOR.contains("amazon") -> "amazon"
            isTV -> "google_play_tv"
            else -> "google_play_mobile"
        }
    }
}
