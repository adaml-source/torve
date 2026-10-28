package com.torve.android.test

import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.torve.domain.repository.ChannelRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.java.KoinJavaComponent.getKoin

/** Debug-only, adb-driven probe that exercises the production IPTV/EPG repository path. */
class IptvEpgPerformanceProbeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            val mode = intent.getStringExtra(EXTRA_MODE)?.uppercase() ?: MODE_CATALOG
            val requestedPlaylistId = intent.getStringExtra(EXTRA_PLAYLIST_ID)
            val repository = getKoin().get<ChannelRepository>()
            val playlist = withContext(Dispatchers.IO) {
                repository.getPlaylists().firstOrNull { requestedPlaylistId == null || it.id == requestedPlaylistId }
            }
            if (playlist == null) {
                Log.i(TAG, "probe_complete mode=$mode result=no_playlist")
                finish()
                return@launch
            }

            val startedAt = SystemClock.elapsedRealtime()
            val before = withContext(Dispatchers.IO) {
                ProbeCounts(
                    catalogItems = repository.getTotalChannelCount(playlist.id),
                    epgProgrammes = repository.getEpg(playlist.id).programmes.size,
                )
            }
            val cachedUsableMs = SystemClock.elapsedRealtime() - startedAt
            Log.i(
                TAG,
                "probe_start mode=$mode playlistType=${playlist.type} cachedUsableMs=$cachedUsableMs " +
                    "catalogItems=${before.catalogItems} epgProgrammes=${before.epgProgrammes}",
            )

            val refreshStartedAt = SystemClock.elapsedRealtime()
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    when (mode) {
                        MODE_EPG -> repository.refreshEpg(playlist.id)
                        MODE_BOTH -> {
                            repository.refreshPlaylistCatalog(playlist.id)
                            repository.refreshEpg(playlist.id)
                        }
                        else -> repository.refreshPlaylistCatalog(playlist.id)
                    }
                }
            }
            val refreshMs = SystemClock.elapsedRealtime() - refreshStartedAt
            val after = withContext(Dispatchers.IO) {
                ProbeCounts(
                    catalogItems = repository.getTotalChannelCount(playlist.id),
                    epgProgrammes = repository.getEpg(playlist.id).programmes.size,
                )
            }
            Log.i(
                TAG,
                "probe_complete mode=$mode result=${if (result.isSuccess) "success" else "failure"} " +
                    "cachedUsableMs=$cachedUsableMs refreshMs=$refreshMs " +
                    "catalogBefore=${before.catalogItems} catalogAfter=${after.catalogItems} " +
                    "epgBefore=${before.epgProgrammes} epgAfter=${after.epgProgrammes} " +
                    "error=${result.exceptionOrNull()?.javaClass?.simpleName ?: "none"}",
            )
            finish()
        }
    }

    private data class ProbeCounts(
        val catalogItems: Long,
        val epgProgrammes: Int,
    )

    private companion object {
        private const val TAG = "TorvePerfProbe"
        private const val EXTRA_MODE = "mode"
        private const val EXTRA_PLAYLIST_ID = "playlist_id"
        private const val MODE_CATALOG = "CATALOG"
        private const val MODE_EPG = "EPG"
        private const val MODE_BOTH = "BOTH"
    }
}
