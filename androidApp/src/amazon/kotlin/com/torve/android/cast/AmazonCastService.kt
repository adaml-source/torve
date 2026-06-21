package com.torve.android.cast

class AmazonCastService : CastService {
    override val isAvailable: Boolean = false
    override val isCasting: Boolean = false
    override fun requestCast(url: String, title: String, posterUrl: String?) { /* no-op */ }
    override fun showCastDialog() { /* no-op */ }
    override fun release() { /* no-op */ }
}
