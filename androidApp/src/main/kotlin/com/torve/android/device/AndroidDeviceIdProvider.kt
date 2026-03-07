package com.torve.android.device

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import com.torve.domain.device.DeviceIdProvider

class AndroidDeviceIdProvider(private val context: Context) : DeviceIdProvider {
    @SuppressLint("HardwareIds")
    override fun getDeviceId(): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
}
