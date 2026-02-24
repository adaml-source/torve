package com.streamvault.android.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import com.streamvault.android.ui.components.BackButton
import com.streamvault.android.ui.home.icon
import com.streamvault.android.ui.theme.Amber
import com.streamvault.android.ui.theme.AmberSubtle
import com.streamvault.android.ui.theme.Ash
import com.streamvault.android.ui.theme.Gunmetal
import com.streamvault.android.ui.theme.Obsidian
import com.streamvault.android.ui.theme.Silver
import com.streamvault.android.ui.theme.Smoke
import com.streamvault.android.ui.theme.Snow
import com.streamvault.android.ui.theme.Steel
import com.streamvault.domain.model.CustomSection
import com.streamvault.domain.model.HomeSection
import com.streamvault.domain.model.HomeSectionConfig
import com.streamvault.domain.model.PosterOrientation
import com.streamvault.domain.model.PosterSize
import com.streamvault.presentation.home.HomeViewModel
import org.koin.compose.koinInject

@Composable
fun HomeLayoutScreen(
    onBack: () -> Unit,
    onAddCustomSection: () -> Unit = {},
    onEditCustomSection: (String) -> Unit = {},
    viewModel: HomeViewModel = koinInject(),
) {
    val sectionConfigs by viewModel.sectionConfigs.collectAsState()
    val customSections by viewModel.customSections.collectAsState()
    var orderedSections by remember(sectionConfigs) {
        mutableStateOf(sectionConfigs.sortedBy { it.order })
    }
    var expandedSection by remember { mutableStateOf<HomeSection?>(null) }

    // Drag state
    val listState = rememberLazyListState()
    var draggedIndex by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        // Header
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BackButton(onClick = onBack)
            Spacer(Modifier.width(12.dp))
            Text(
                "Home Layout",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = Snow,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = {
                viewModel.resetSections()
                orderedSections = HomeSection.entries.map {
                    HomeSectionConfig(it, it.defaultEnabled, it.defaultOrder)
                }.sortedBy { it.order }
                expandedSection = null
            }) {
                Text("Reset", color = Amber)
            }
        }

        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item(key = "sections_header") {
                Text(
                    "Hold and drag to reorder. Tap a section to customize its poster style.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Silver,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }

            itemsIndexed(
                items = orderedSections,
                key = { _, item -> item.section.name },
            ) { index, config ->
                val isHero = index == 0
                val isDragged = draggedIndex == index

                SectionRow(
                    config = config,
                    isHero = isHero,
                    isExpanded = expandedSection == config.section,
                    isDragged = isDragged,
                    dragOffset = if (isDragged) dragOffset else 0f,
                    onToggle = { enabled ->
                        val updated = orderedSections.map {
                            if (it.section == config.section) it.copy(enabled = enabled) else it
                        }
                        orderedSections = updated
                        viewModel.toggleSection(config.section, enabled)
                    },
                    onExpandToggle = {
                        expandedSection = if (expandedSection == config.section) null else config.section
                    },
                    onOrientationChange = { orientation ->
                        val updated = orderedSections.map {
                            if (it.section == config.section) it.copy(orientation = orientation) else it
                        }
                        orderedSections = updated
                        viewModel.updateSectionLayout(config.section, orientation, config.size)
                    },
                    onSizeChange = { size ->
                        val updated = orderedSections.map {
                            if (it.section == config.section) it.copy(size = size) else it
                        }
                        orderedSections = updated
                        viewModel.updateSectionLayout(config.section, config.orientation, size)
                    },
                    onDragStart = {
                        if (!isHero) draggedIndex = index
                    },
                    onDrag = { delta ->
                        if (draggedIndex < 0) return@SectionRow
                        dragOffset += delta
                        val currentItem = listState.layoutInfo.visibleItemsInfo
                            .find { it.index == draggedIndex + 1 } // +1 for header item
                            ?: return@SectionRow
                        val itemHeight = currentItem.size.toFloat()
                        // Check if we should swap
                        if (dragOffset > itemHeight * 0.5f && draggedIndex < orderedSections.size - 1) {
                            val list = orderedSections.toMutableList()
                            val item = list.removeAt(draggedIndex)
                            list.add(draggedIndex + 1, item)
                            orderedSections = list.mapIndexed { i, it -> it.copy(order = i) }
                            draggedIndex += 1
                            dragOffset -= itemHeight
                        } else if (dragOffset < -itemHeight * 0.5f && draggedIndex > 1) {
                            val list = orderedSections.toMutableList()
                            val item = list.removeAt(draggedIndex)
                            list.add(draggedIndex - 1, item)
                            orderedSections = list.mapIndexed { i, it -> it.copy(order = i) }
                            draggedIndex -= 1
                            dragOffset += itemHeight
                        }
                    },
                    onDragEnd = {
                        if (draggedIndex >= 0) {
                            viewModel.updateSectionOrder(orderedSections)
                        }
                        draggedIndex = -1
                        dragOffset = 0f
                    },
                    modifier = Modifier.animateItem(),
                )
            }

            // Custom sections header
            if (customSections.isNotEmpty()) {
                item(key = "custom_header") {
                    Text(
                        "Custom Sections",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Amber,
                        modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 8.dp),
                    )
                }
            }

            // Custom section rows
            val sortedCustomSections = customSections.sortedBy { it.order }
            itemsIndexed(
                items = sortedCustomSections,
                key = { _, item -> "custom_${item.id}" },
            ) { index, section ->
                CustomSectionRow(
                    section = section,
                    isFirst = index == 0,
                    isLast = index == sortedCustomSections.size - 1,
                    onToggle = { enabled ->
                        viewModel.updateCustomSection(section.copy(enabled = enabled))
                    },
                    onEdit = { onEditCustomSection(section.id) },
                    onMoveUp = { viewModel.moveCustomSection(section.id, -1) },
                    onMoveDown = { viewModel.moveCustomSection(section.id, 1) },
                )
            }

            // Add custom section button
            item(key = "add_custom") {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .clickable { onAddCustomSection() },
                    shape = RoundedCornerShape(12.dp),
                    color = Gunmetal.copy(alpha = 0.5f),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            Icons.Rounded.Add,
                            contentDescription = "Add",
                            tint = Amber,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Add Custom Section",
                            style = MaterialTheme.typography.bodyLarge,
                            color = Amber,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CustomSectionRow(
    section: CustomSection,
    isFirst: Boolean,
    isLast: Boolean,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 20.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Up/Down arrows
        Column {
            IconButton(
                onClick = onMoveUp,
                enabled = !isFirst,
                modifier = Modifier.size(24.dp),
            ) {
                Icon(
                    Icons.Rounded.KeyboardArrowUp,
                    contentDescription = "Move up",
                    tint = if (!isFirst) Silver else Smoke,
                    modifier = Modifier.size(20.dp),
                )
            }
            IconButton(
                onClick = onMoveDown,
                enabled = !isLast,
                modifier = Modifier.size(24.dp),
            ) {
                Icon(
                    Icons.Rounded.KeyboardArrowDown,
                    contentDescription = "Move down",
                    tint = if (!isLast) Silver else Smoke,
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        Spacer(Modifier.width(8.dp))

        Text(
            text = section.title,
            style = MaterialTheme.typography.bodyLarge,
            color = if (section.enabled) Snow else Ash,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )

        IconButton(
            onClick = onEdit,
            modifier = Modifier.size(32.dp),
        ) {
            Icon(
                Icons.Rounded.Edit,
                contentDescription = "Edit",
                tint = Silver,
                modifier = Modifier.size(18.dp),
            )
        }

        Switch(
            checked = section.enabled,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Amber,
                checkedTrackColor = AmberSubtle,
                uncheckedThumbColor = Steel,
                uncheckedTrackColor = Gunmetal,
            ),
        )
    }
}

@Composable
private fun SectionRow(
    config: HomeSectionConfig,
    isHero: Boolean,
    isExpanded: Boolean,
    isDragged: Boolean,
    dragOffset: Float,
    onToggle: (Boolean) -> Unit,
    onExpandToggle: () -> Unit,
    onOrientationChange: (PosterOrientation) -> Unit,
    onSizeChange: (PosterSize) -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .zIndex(if (isDragged) 1f else 0f)
            .graphicsLayer {
                translationY = dragOffset
                shadowElevation = if (isDragged) 8f else 0f
            }
            .fillMaxWidth()
            .background(
                if (isDragged) Gunmetal.copy(alpha = 0.8f)
                else MaterialTheme.colorScheme.background,
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 20.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Drag handle
            Icon(
                Icons.Rounded.DragHandle,
                contentDescription = "Drag to reorder",
                tint = if (isHero) Smoke else Silver,
                modifier = Modifier
                    .size(32.dp)
                    .padding(4.dp)
                    .then(
                        if (!isHero) {
                            Modifier.pointerInput(Unit) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { onDragStart() },
                                    onDrag = { change, offset ->
                                        change.consume()
                                        onDrag(offset.y)
                                    },
                                    onDragEnd = { onDragEnd() },
                                    onDragCancel = { onDragEnd() },
                                )
                            }
                        } else Modifier,
                    ),
            )

            Spacer(Modifier.width(8.dp))

            // Section icon
            Icon(
                imageVector = config.section.icon(),
                contentDescription = null,
                tint = if (config.enabled) Amber else Smoke,
                modifier = Modifier.size(20.dp),
            )

            Spacer(Modifier.width(12.dp))

            // Section name + expand chevron
            Text(
                text = config.customTitle ?: config.section.defaultTitle,
                style = MaterialTheme.typography.bodyLarge,
                color = if (config.enabled) Snow else Ash,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )

            // Expand/collapse button (not for Hero)
            if (!isHero) {
                IconButton(
                    onClick = onExpandToggle,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        if (isExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = if (isExpanded) "Collapse" else "Customize",
                        tint = Silver,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            // Toggle
            Switch(
                checked = config.enabled,
                onCheckedChange = onToggle,
                enabled = !isHero,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Amber,
                    checkedTrackColor = AmberSubtle,
                    uncheckedThumbColor = Steel,
                    uncheckedTrackColor = Gunmetal,
                ),
            )
        }

        // Expandable per-section layout picker
        AnimatedVisibility(
            visible = isExpanded && !isHero,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                shape = RoundedCornerShape(12.dp),
                color = Gunmetal.copy(alpha = 0.5f),
            ) {
                Column(Modifier.padding(16.dp)) {
                    // Orientation
                    Text("Orientation", color = Silver, style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PosterOrientation.entries.forEach { orientation ->
                            FilterChip(
                                selected = config.orientation == orientation,
                                onClick = { onOrientationChange(orientation) },
                                label = {
                                    Text(orientation.name.lowercase().replaceFirstChar { it.uppercase() })
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Amber,
                                    selectedLabelColor = Obsidian,
                                    containerColor = Gunmetal,
                                    labelColor = Silver,
                                ),
                                shape = RoundedCornerShape(20.dp),
                            )
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    // Size
                    Text("Card Size", color = Silver, style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PosterSize.entries.forEach { size ->
                            FilterChip(
                                selected = config.size == size,
                                onClick = { onSizeChange(size) },
                                label = {
                                    Text(size.name.lowercase().replaceFirstChar { it.uppercase() })
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Amber,
                                    selectedLabelColor = Obsidian,
                                    containerColor = Gunmetal,
                                    labelColor = Silver,
                                ),
                                shape = RoundedCornerShape(20.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
