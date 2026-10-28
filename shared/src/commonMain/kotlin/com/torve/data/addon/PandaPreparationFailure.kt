package com.torve.data.addon

/** Only known public error codes become UI messages; upstream bodies can contain credentials. */
object PandaPreparationFailure {
    fun message(code: String?): String? = when (code) {
        "nzb_access_denied" -> "The indexer refused this NZB download. Check its API key and download access, or choose another source."
        "nzb_rate_limited" -> "The indexer's download limit has been reached. Try again later or choose another indexer."
        "nzb_not_found" -> "This NZB is no longer available. Choose another source."
        "nzb_invalid" -> "The indexer returned a webpage or an error instead of an NZB. Check its download access."
        "nzb_too_large" -> "This NZB exceeds the download size limit. Choose another source."
        "nzb_fetch_failed" -> "The NZB could not be downloaded from the indexer. Try again or choose another source."
        "nzb_download_failed" -> "TorBox could not complete this download. It may be missing Usenet articles; choose another source."
        "nzb_episode_missing" -> "The download does not contain this episode. Choose another source."
        "nzb_video_missing" -> "This download contains no playable video. Choose another source."
        "download_client_auth" -> "Check your TorBox API key and Usenet access."
        "download_client_limit", "download_client_rate_limited" -> "TorBox has reached a download or account limit. Check its download queue."
        "download_client_unavailable", "download_client_busy" -> "TorBox is unavailable. Try again shortly."
        "download_client_rejected", "download_client_link_failed", "nzb_preparation_failed" -> "This video could not be prepared. Try again or choose another source."
        else -> null
    }
}
