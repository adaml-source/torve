package com.torve.android.tv.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.torve.android.ui.theme.Graphite
import com.torve.android.ui.theme.Silver
import com.torve.data.metadata.TmdbMappers
import com.torve.domain.repository.MetadataRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

private val personPortraits = mutableStateMapOf<Int, List<String>>()
private val portraitLookupBudget = Semaphore(2)

/** A failed catalogue photo can use another real profile photo, never movie art. */
@Composable
internal fun TvPersonPortrait(personId: Int, name: String, profileUrl: String?, modifier: Modifier = Modifier) {
    val metadata: MetadataRepository = koinInject()
    var failed by remember(personId, profileUrl) { mutableStateOf(emptySet<String>()) }
    var lookedUp by remember(personId, profileUrl) { mutableStateOf(false) }
    val candidates = (listOfNotNull(profileUrl?.takeIf { it.isNotBlank() }) + personPortraits[personId].orEmpty()).distinct()
    val selected = candidates.firstOrNull { it !in failed }
    LaunchedEffect(personId, selected, lookedUp) {
        if (personId <= 0 || selected != null || lookedUp) return@LaunchedEffect
        val alternatives = try {
            portraitLookupBudget.withPermit {
                withContext(Dispatchers.IO) {
                    metadata.getPersonImageUrls(personId).ifEmpty {
                        listOfNotNull(TmdbMappers.profileUrl(metadata.getPersonDetail(personId).profilePath, "w342"))
                    }
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { emptyList() }
        lookedUp = true
        personPortraits[personId] = alternatives.distinct().take(6)
        while (personPortraits.size > 120) personPortraits.remove(personPortraits.keys.first())
    }
    Box(modifier.background(Graphite), contentAlignment = Alignment.Center) {
        if (selected != null) AsyncImage(
            selected, name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
            onError = { failed = failed + selected },
        ) else Icon(Icons.Default.Person, name, tint = Silver, modifier = Modifier.size(48.dp))
    }
}
