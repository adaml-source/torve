package com.torve.android.tv.screens

import com.torve.android.tv.components.tvSettingsLabel as localizedUiText

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import com.torve.android.tv.components.TvDocumentModal
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.Charcoal
import com.torve.android.ui.theme.Silver
import com.torve.android.ui.theme.Snow
import com.torve.android.ui.theme.Steel
import java.time.Duration
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.delay

@Composable
internal fun rememberSportsToday(): LocalDate {
    var today by remember { mutableStateOf(LocalDate.now()) }
    LaunchedEffect(today) {
        val now = ZonedDateTime.now()
        val midnight = now.toLocalDate().plusDays(1).atStartOfDay(now.zone)
        delay(Duration.between(now, midnight).toMillis().coerceAtLeast(1_000))
        today = LocalDate.now()
    }
    return today
}

@Composable
internal fun TvSportsDateDialog(
    selectedMode: String,
    today: LocalDate,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    val selectedDate = sportsDateFromMode(selectedMode)
    val daysAgo = selectedDate?.let { ChronoUnit.DAYS.between(it, today).toInt().coerceAtLeast(0) } ?: 0
    var weekOffset by remember { mutableIntStateOf(daysAgo / 7) }
    var dayIndex by remember { mutableIntStateOf(daysAgo % 7) }
    val rangeRequesters = remember { SPORTS_DATE_PRESETS.associateWith { FocusRequester() } }
    val dayRequesters = remember { List(7) { FocusRequester() } }
    val olderRequester = remember { FocusRequester() }
    val newerRequester = remember { FocusRequester() }
    val initialRequester = if (selectedDate != null) dayRequesters[dayIndex]
        else rangeRequesters[selectedMode] ?: rangeRequesters.getValue(SPORTS_FILTER_RECENT)
    var rangeMode by remember { mutableStateOf(selectedMode.takeIf(SPORTS_DATE_PRESETS::contains) ?: SPORTS_FILTER_RECENT) }

    TvDocumentModal(localizedUiText("Sports dates"), initialRequester, onDismiss) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(localizedUiText("Dates use the match date when supplied; otherwise the date it was added. Your sport and search stay selected."),
                color = Silver, style = MaterialTheme.typography.bodySmall)
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(localizedUiText("Date range"), color = Silver, style = MaterialTheme.typography.labelLarge)
                    SPORTS_DATE_PRESETS.forEach { mode ->
                        SportsDateOption(
                            label = sportsDateFilterLabel(mode),
                            selected = selectedMode == mode,
                            modifier = Modifier.focusRequester(rangeRequesters.getValue(mode))
                                .focusProperties { right = dayRequesters[dayIndex] },
                            onFocused = { rangeMode = mode },
                            onClick = { onSelect(mode) },
                        )
                    }
                }
                Column(Modifier.weight(1.25f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(localizedUiText("Choose a day"), color = Silver, style = MaterialTheme.typography.labelLarge)
                    repeat(7) { index ->
                        val date = today.minusDays((weekOffset * 7L) + index)
                        SportsDateOption(
                            label = sportsDateChipLabel(date),
                            selected = selectedDate == date,
                            modifier = Modifier.focusRequester(dayRequesters[index])
                                .focusProperties {
                                    left = rangeRequesters.getValue(rangeMode)
                                    if (index == 6) down = olderRequester
                                },
                            onFocused = { dayIndex = index },
                            onClick = { onSelect(sportsDateMode(date)) },
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SportsDateOption(localizedUiText("‹ Older week"), false,
                            Modifier.weight(1f).focusRequester(olderRequester).focusProperties {
                                up = dayRequesters.last()
                                right = newerRequester
                            }, onClick = { weekOffset += 1 })
                        SportsDateOption(localizedUiText("Newer week ›"), false,
                            Modifier.weight(1f).focusRequester(newerRequester).focusProperties {
                                up = dayRequesters.last()
                                left = olderRequester
                            }, onClick = { weekOffset = (weekOffset - 1).coerceAtLeast(0) })
                    }
                }
            }
        }
    }
}

@Composable
private fun SportsDateOption(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit = {},
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Text(
        localizedUiText(label),
        color = if (selected) Amber else Snow,
        style = MaterialTheme.typography.bodySmall,
        modifier = modifier.fillMaxWidth()
            .background(Charcoal, RoundedCornerShape(8.dp))
            .border(2.dp, if (focused) Amber else Steel, RoundedCornerShape(8.dp))
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}
