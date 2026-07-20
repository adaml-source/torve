package com.torve.android.cast

interface CastService {
    val isAvailable: Boolean
    val isCasting: Boolean
    fun requestCast(url: String, title: String = "", posterUrl: String? = null)
    fun showCastDialog()
    fun release()
}
