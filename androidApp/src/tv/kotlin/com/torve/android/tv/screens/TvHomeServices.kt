package com.torve.android.tv.screens

internal data class TvHomeServices(
    val outcomeViewModel: com.torve.presentation.tvhome.TvHomeOutcomeViewModel,
    val playbackRouter: com.torve.presentation.tvhome.TvHomePlaybackRouter,
    val networkMonitor: com.torve.platform.NetworkMonitor,
    val lanLibraryConsumer: com.torve.presentation.lanlibrary.LanLibraryConsumer,
    val catalogGroups: com.torve.data.catalog.CatalogGroupRepository,
    val watching: com.torve.presentation.watching.WatchingHubViewModel,
)

internal fun loadTvHomeServices(): TvHomeServices {
    val koin = org.koin.java.KoinJavaComponent.getKoin()
    return TvHomeServices(koin.get(), koin.get(), koin.get(), koin.get(), koin.get(), koin.get())
}
