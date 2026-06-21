package com.torve.domain.model

data class UserProfile(
    val id: String,
    val name: String,
    val avatarIndex: Int = 0,
    val isActive: Boolean = false,
    val pin: String? = null,
    val maxContentRating: ContentRating? = null,
    val createdAt: Long = 0,
)

enum class ContentRating(val label: String, val order: Int) {
    G("G", 0),
    PG("PG", 1),
    PG_13("PG-13", 2),
    R("R", 3),
    NC_17("NC-17", 4);

    companion object {
        fun fromString(s: String?): ContentRating? = when (s?.uppercase()) {
            "G" -> G
            "PG" -> PG
            "PG-13", "PG_13" -> PG_13
            "R" -> R
            "NC-17", "NC_17" -> NC_17
            else -> null
        }
    }
}
