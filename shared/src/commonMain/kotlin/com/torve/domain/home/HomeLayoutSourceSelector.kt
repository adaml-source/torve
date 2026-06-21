package com.torve.domain.home

import com.torve.domain.repository.PreferencesRepository

/**
 * Picks which preference keys back the home-section layout.
 *
 * Mobile never exposes a control to flip [KEY_LAYOUT_SOURCE], so it always
 * reads the shared keys. Desktop exposes a toggle (Settings → Appearance):
 * when set to [DESKTOP_OWN], desktop reads/writes its own keys, decoupling
 * its home layout from the mobile one.
 */
class HomeLayoutSourceSelector(private val prefsRepo: PreferencesRepository) {

    suspend fun sectionConfigsKey(): String =
        if (isDesktopOwn()) KEY_SECTION_CONFIGS_DESKTOP else KEY_SECTION_CONFIGS_SHARED

    suspend fun homeLayoutOrderKey(): String =
        if (isDesktopOwn()) KEY_LAYOUT_ORDER_DESKTOP else KEY_LAYOUT_ORDER_SHARED

    private suspend fun isDesktopOwn(): Boolean =
        runCatching { prefsRepo.getString(KEY_LAYOUT_SOURCE) }.getOrNull() == DESKTOP_OWN

    companion object {
        const val KEY_LAYOUT_SOURCE = "home_layout_source"
        const val SHARED_WITH_MOBILE = "SHARED_WITH_MOBILE"
        const val DESKTOP_OWN = "DESKTOP_OWN"

        const val KEY_SECTION_CONFIGS_SHARED = "home_section_configs"
        const val KEY_SECTION_CONFIGS_DESKTOP = "home_section_configs_desktop"
        const val KEY_LAYOUT_ORDER_SHARED = "home_layout_order"
        const val KEY_LAYOUT_ORDER_DESKTOP = "home_layout_order_desktop"
    }
}
