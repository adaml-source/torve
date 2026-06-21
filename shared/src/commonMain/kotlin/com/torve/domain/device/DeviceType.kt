package com.torve.domain.device

enum class DeviceType(
    val wireValue: String,
    val displayLabel: String,
) {
    PHONE("phone", "Phone"),
    TABLET("tablet", "Tablet"),
    TV("tv", "TV"),
    DESKTOP("desktop", "Desktop"),
    UNKNOWN("unknown", "Device"),
    ;

    companion object {
        fun fromWireValue(raw: String?): DeviceType {
            return when (raw?.trim()?.lowercase()) {
                PHONE.wireValue -> PHONE
                TABLET.wireValue -> TABLET
                TV.wireValue -> TV
                DESKTOP.wireValue -> DESKTOP
                else -> UNKNOWN
            }
        }
    }
}
