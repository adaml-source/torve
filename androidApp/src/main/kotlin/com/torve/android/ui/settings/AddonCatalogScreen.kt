package com.torve.android.ui.settings

import androidx.compose.runtime.Composable

// Empty compatibility inventory for retained TV focus state. No provider directory is offered.
internal data class PopularAddon(val url: String, val actionUrl: String = "")
internal val POPULAR_ADDONS: List<PopularAddon> = emptyList()

/** Legacy route retained for links; the product surface is now Extensions. */
@Composable
fun AddonCatalogScreen(onBack: () -> Unit, onManagePandaClick: () -> Unit = {}) {
    com.torve.android.ui.community.ExtensionsScreen(onBack = onBack)
}
