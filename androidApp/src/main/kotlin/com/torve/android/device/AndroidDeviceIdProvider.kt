package com.torve.android.device

import android.content.Context
import android.provider.Settings
import com.torve.android.BuildConfig
import com.torve.android.sync.storage.InstallationIdStore
import com.torve.domain.device.DeviceIdProvider

class AndroidDeviceIdProvider(private val context: Context) : DeviceIdProvider {
    private val installationIdStore = InstallationIdStore(context)

    override fun getDeviceId(): String = installationIdStore.getOrCreateInstallationId()

    override fun getDeviceName(): String = android.os.Build.MODEL ?: "Android Device"

    override fun getDeviceType(): String =
        if (context.packageManager.hasSystemFeature("android.software.leanback")) "tv" else "phone"

    override fun getPlatform(): String =
        if (context.packageManager.hasSystemFeature("android.software.leanback")) "android_tv" else "android"

    override fun getAppVersion(): String? = BuildConfig.VERSION_NAME

    override fun getStableDeviceId(): String? {
        return try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                ?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }
}
