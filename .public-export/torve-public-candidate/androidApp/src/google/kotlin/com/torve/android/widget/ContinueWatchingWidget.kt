package com.torve.android.widget

import android.content.Context
import android.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.torve.domain.model.WatchProgress
import com.torve.domain.repository.WatchProgressRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.java.KoinJavaComponent.getKoin

class ContinueWatchingWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val items = try {
            val repo: WatchProgressRepository = getKoin().get()
            withContext(Dispatchers.IO) {
                repo.getInProgress(5)
            }
        } catch (_: Exception) {
            emptyList()
        }

        provideContent {
            GlanceTheme {
                WidgetContent(items)
            }
        }
    }

    companion object {
        suspend fun updateWidget(context: Context) {
            ContinueWatchingWidget().updateAll(context)
        }
    }
}

@Composable
private fun WidgetContent(items: List<WatchProgress>) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(Color.parseColor("#1A1A2E"))
            .padding(12.dp),
    ) {
        Text(
            text = "Continue Watching",
            style = TextStyle(
                fontWeight = FontWeight.Bold,
                color = ColorProvider(Color.WHITE),
            ),
        )
        Spacer(modifier = GlanceModifier.height(8.dp))

        if (items.isEmpty()) {
            Text(
                text = "Nothing in progress",
                style = TextStyle(
                    color = ColorProvider(Color.parseColor("#888888")),
                ),
            )
        } else {
            LazyColumn {
                items(items, itemId = { it.mediaId.hashCode().toLong() }) { progress ->
                    WatchProgressRow(progress)
                }
            }
        }
    }
}

@Composable
private fun WatchProgressRow(progress: WatchProgress) {
    val percent = if (progress.durationMs > 0) {
        (progress.positionMs.toFloat() / progress.durationMs * 100).toInt()
    } else 0
    val title = if (progress.showTitle != null && progress.seasonNumber != null) {
        "${progress.showTitle} S${progress.seasonNumber}E${progress.episodeNumber}"
    } else {
        progress.title
    }

    Column(
        modifier = GlanceModifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Text(
            text = title,
            style = TextStyle(
                color = ColorProvider(Color.WHITE),
                fontWeight = FontWeight.Medium,
            ),
            maxLines = 1,
        )
        Spacer(modifier = GlanceModifier.height(2.dp))
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = GlanceModifier
                    .width((percent.coerceIn(1, 100) * 1.5f).dp)
                    .height(3.dp)
                    .background(Color.parseColor("#FFB300"))
                    .cornerRadius(2.dp),
            ) {}
            Box(
                modifier = GlanceModifier
                    .width(((100 - percent).coerceIn(0, 100) * 1.5f).dp)
                    .height(3.dp)
                    .background(Color.parseColor("#333333"))
                    .cornerRadius(2.dp),
            ) {}
            Spacer(modifier = GlanceModifier.width(8.dp))
            Text(
                text = "$percent%",
                style = TextStyle(
                    color = ColorProvider(Color.parseColor("#888888")),
                ),
            )
        }
    }
}

class ContinueWatchingWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ContinueWatchingWidget()
}
