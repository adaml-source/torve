package com.streamvault.data.trakt

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

data class TraktDeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUrl: String,
    val interval: Int,
    val expiresIn: Int,
)

data class TraktTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Int,
    val createdAt: Long,
)

data class TraktUser(
    val username: String,
    val name: String? = null,
    val vip: Boolean = false,
    val joined: String? = null,
    val avatar: String? = null,
)

// --- API response models ---

@Serializable
data class TraktDeviceCodeResponse(
    @SerialName("device_code") val deviceCode: String = "",
    @SerialName("user_code") val userCode: String = "",
    @SerialName("verification_url") val verificationUrl: String = "",
    val interval: Int = 5,
    @SerialName("expires_in") val expiresIn: Int = 600,
)

@Serializable
data class TraktTokenResponse(
    @SerialName("access_token") val accessToken: String = "",
    @SerialName("refresh_token") val refreshToken: String = "",
    @SerialName("expires_in") val expiresIn: Int = 0,
    @SerialName("created_at") val createdAt: Long = 0,
    @SerialName("token_type") val tokenType: String = "",
)

@Serializable
data class TraktUserResponse(
    val username: String = "",
    val name: String? = null,
    val vip: Boolean = false,
    @SerialName("joined_at") val joinedAt: String? = null,
    val images: TraktImages? = null,
)

@Serializable
data class TraktImages(
    val avatar: TraktImageSize? = null,
)

@Serializable
data class TraktImageSize(
    val full: String? = null,
)

@Serializable
data class TraktIds(
    val trakt: Int? = null,
    val slug: String? = null,
    val imdb: String? = null,
    val tmdb: Int? = null,
)

@Serializable
data class TraktHistoryMovie(
    val ids: TraktIds,
)

@Serializable
data class TraktHistoryShow(
    val ids: TraktIds,
)

@Serializable
data class TraktHistoryBody(
    val movies: List<TraktHistoryMovie>? = null,
    val shows: List<TraktHistoryShow>? = null,
)

@Serializable
data class TraktScrobbleBody(
    val movie: TraktScrobbleMovie? = null,
    val show: TraktScrobbleShow? = null,
    val episode: TraktScrobbleEpisode? = null,
    val progress: Double = 0.0,
)

@Serializable
data class TraktScrobbleMovie(
    val ids: TraktIds,
)

@Serializable
data class TraktScrobbleShow(
    val ids: TraktIds,
)

@Serializable
data class TraktScrobbleEpisode(
    val season: Int,
    val number: Int,
)
