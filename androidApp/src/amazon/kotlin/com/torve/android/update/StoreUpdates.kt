package com.torve.android.update

import android.content.Context
import androidx.appcompat.app.AppCompatActivity

/** Fire TV keeps its verified APK updater; Google Play libraries are absent here. */
internal object StoreUpdates {
    fun createController(activity: AppCompatActivity): StoreUpdateController = NoStoreUpdateController
    fun schedule(context: Context) = Unit
}
