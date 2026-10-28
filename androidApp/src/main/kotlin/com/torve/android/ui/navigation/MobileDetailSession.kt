package com.torve.android.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import com.torve.presentation.detail.DetailViewModel
import org.koin.mp.KoinPlatform

/** Navigation retains the session across Player; changing account/profile releases it. */
internal class ScopedMobileSession<T>(private val create: () -> T, private val release: (T) -> Unit) {
    private var owner: String? = null
    private var value: T? = null
    fun get(ownerId: String): T {
        if (owner != ownerId || value == null) {
            clear()
            owner = ownerId
            value = create()
        }
        return requireNotNull(value)
    }
    fun clear() {
        value?.let(release)
        value = null
        owner = null
    }
}

private class MobileDetailSessionOwner : ViewModel() {
    val session = ScopedMobileSession({ KoinPlatform.getKoin().get<DetailViewModel>() }, DetailViewModel::clear)
    override fun onCleared() { session.clear() }
}

@Composable
internal fun rememberMobileDetailSession(entry: NavBackStackEntry, ownerId: String): DetailViewModel {
    val factory = remember {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = MobileDetailSessionOwner() as T
        }
    }
    val owner: MobileDetailSessionOwner = viewModel(viewModelStoreOwner = entry, factory = factory)
    return owner.session.get(ownerId)
}
