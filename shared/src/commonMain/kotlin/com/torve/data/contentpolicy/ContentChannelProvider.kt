package com.torve.data.contentpolicy

interface ContentChannelProvider {
    val channel: String?

    fun isGooglePlayChannel(): Boolean = channel.equals("google_play", ignoreCase = true)
}

class MutableContentChannelProvider(
    initialChannel: String? = null,
) : ContentChannelProvider {
    private var currentChannel: String? = initialChannel

    override val channel: String?
        get() = currentChannel

    fun update(channel: String?) {
        currentChannel = channel?.trim()?.ifBlank { null }
    }
}
