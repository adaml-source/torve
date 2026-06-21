package com.torve.desktop.vlc

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

internal sealed interface PlayerMenuNode {
    val id: String
    val enabled: Boolean
}

internal data class ActionItem(
    override val id: String,
    val label: String,
    val hint: String? = null,
    override val enabled: Boolean = true,
    val onActivate: () -> Unit,
) : PlayerMenuNode

internal data class ToggleItem(
    override val id: String,
    val label: String,
    val hint: String? = null,
    val checked: Boolean,
    override val enabled: Boolean = true,
    val onToggle: () -> Unit,
) : PlayerMenuNode

internal data class ChoiceGroupItem(
    override val id: String,
    val label: String,
    val hint: String? = null,
    val selected: Boolean,
    override val enabled: Boolean = true,
    val onSelect: () -> Unit,
) : PlayerMenuNode

internal data class SubmenuItem(
    override val id: String,
    val label: String,
    val hint: String? = null,
    override val enabled: Boolean = true,
    val children: () -> List<PlayerMenuNode>,
) : PlayerMenuNode

internal data class SeparatorItem(
    override val id: String,
) : PlayerMenuNode {
    override val enabled: Boolean = false
}

private val MenuSurface = Color(0xF0141824)
private val MenuHover = Color(0xFF283246)
private val MenuActive = Color(0xFF32405A)
private val MenuText = Color(0xFFF5F7FA)
private val MenuTextMuted = Color(0xFF96A1B6)
private val MenuTextDisabled = Color(0xFF6C7588)
private val MenuDivider = Color(0x1FFFFFFF)
private val MenuAccent = Color(0xFFE8A838)

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun TorvePlayerContextMenu(
    visible: Boolean,
    anchor: IntOffset,
    containerSize: IntSize,
    items: List<PlayerMenuNode>,
    onDismissRequest: () -> Unit,
) {
    if (!visible) return

    val focusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val openPath = remember { mutableStateListOf<String>() }
    val focusedIds = remember { mutableStateMapOf<String, String>() }
    val rowBounds = remember { mutableStateMapOf<String, IntRect>() }
    val menuSizes = remember { mutableStateMapOf<String, IntSize>() }
    var hoverOpenJob by remember { mutableStateOf<Job?>(null) }

    fun menuKey(path: List<String>): String = if (path.isEmpty()) "root" else path.joinToString("/")

    fun visibleItems(path: List<String>): List<PlayerMenuNode> {
        var current = items
        path.forEach { segment ->
            val submenu = current.filterIsInstance<SubmenuItem>().firstOrNull { it.id == segment } ?: return emptyList()
            current = submenu.children()
        }
        return current
    }

    fun focusableItems(path: List<String>): List<PlayerMenuNode> {
        return visibleItems(path).filter {
            when (it) {
                is SeparatorItem -> false
                is SubmenuItem -> it.enabled && it.children().isNotEmpty()
                else -> it.enabled
            }
        }
    }

    fun focusedNode(path: List<String>): PlayerMenuNode? {
        val nodes = focusableItems(path)
        val focusedId = focusedIds[menuKey(path)]
        return nodes.firstOrNull { it.id == focusedId } ?: nodes.firstOrNull()
    }

    fun setFocus(path: List<String>, node: PlayerMenuNode?) {
        node ?: return
        focusedIds[menuKey(path)] = node.id
    }

    fun openSubmenu(path: List<String>, submenu: SubmenuItem) {
        val nextPath = path + submenu.id
        openPath.clear()
        openPath.addAll(nextPath)
        setFocus(nextPath, focusableItems(nextPath).firstOrNull())
    }

    fun closeToDepth(depth: Int) {
        while (openPath.size > depth) {
            openPath.removeLast()
        }
    }

    fun activate(node: PlayerMenuNode, path: List<String>) {
        when (node) {
            is ActionItem -> {
                node.onActivate()
                onDismissRequest()
            }
            is ToggleItem -> {
                node.onToggle()
                onDismissRequest()
            }
            is ChoiceGroupItem -> {
                node.onSelect()
                onDismissRequest()
            }
            is SubmenuItem -> {
                if (node.enabled && node.children().isNotEmpty()) {
                    openSubmenu(path, node)
                }
            }
            is SeparatorItem -> Unit
        }
    }

    fun moveFocus(path: List<String>, delta: Int) {
        val nodes = focusableItems(path)
        if (nodes.isEmpty()) return
        val currentId = focusedIds[menuKey(path)]
        val currentIndex = nodes.indexOfFirst { it.id == currentId }.takeIf { it >= 0 } ?: 0
        val nextIndex = (currentIndex + delta).mod(nodes.size)
        focusedIds[menuKey(path)] = nodes[nextIndex].id
    }

    fun deepestPath(): List<String> {
        var depth = openPath.size
        while (depth > 0) {
            val path = openPath.take(depth)
            if (focusableItems(path).isNotEmpty()) return path
            depth--
        }
        return emptyList()
    }

    LaunchedEffect(visible) {
        if (!visible) return@LaunchedEffect
        rowBounds.clear()
        menuSizes.clear()
        focusedIds.clear()
        closeToDepth(0)
        setFocus(emptyList(), focusableItems(emptyList()).firstOrNull())
        focusRequester.requestFocus()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val activePath = deepestPath()
                val activeNode = focusedNode(activePath)
                when (event.key) {
                    Key.DirectionDown -> {
                        moveFocus(activePath, 1)
                        true
                    }
                    Key.DirectionUp -> {
                        moveFocus(activePath, -1)
                        true
                    }
                    Key.DirectionRight -> {
                        if (activeNode is SubmenuItem) {
                            openSubmenu(activePath, activeNode)
                            true
                        } else {
                            false
                        }
                    }
                    Key.DirectionLeft -> {
                        if (activePath.isNotEmpty()) {
                            closeToDepth(activePath.size - 1)
                            true
                        } else {
                            false
                        }
                    }
                    Key.Enter, Key.NumPadEnter -> {
                        activeNode?.let { activate(it, activePath) }
                        true
                    }
                    Key.Escape -> {
                        onDismissRequest()
                        true
                    }
                    else -> false
                }
            }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = onDismissRequest,
                )
        )

        val levels = buildList {
            add(emptyList<String>())
            openPath.indices.forEach { depth -> add(openPath.take(depth + 1)) }
        }

        levels.forEach { path ->
            val nodes = visibleItems(path)
            if (nodes.isNotEmpty()) {
                val menuPosition = rememberMenuPosition(
                    path = path,
                    anchor = anchor,
                    containerSize = containerSize,
                    rowBounds = rowBounds,
                    menuSizes = menuSizes,
                )
                MenuPanel(
                    path = path,
                    items = nodes,
                    position = menuPosition,
                    menuSizes = menuSizes,
                    rowBounds = rowBounds,
                    isOpen = { node ->
                        node is SubmenuItem && openPath.getOrNull(path.size) == node.id
                    },
                    isFocused = { node ->
                        focusedIds[menuKey(path)] == node.id
                    },
                    onDismissSubmenus = { closeToDepth(path.size) },
                    onFocus = { node ->
                        setFocus(path, node)
                        if (node is SubmenuItem && node.enabled && node.children().isNotEmpty()) {
                            hoverOpenJob?.cancel()
                            hoverOpenJob = scope.launch {
                                delay(120)
                                openSubmenu(path, node)
                            }
                        } else {
                            hoverOpenJob?.cancel()
                            closeToDepth(path.size)
                        }
                    },
                    onActivate = { node ->
                        hoverOpenJob?.cancel()
                        activate(node, path)
                    },
                )
            }
        }
    }
}

@Composable
private fun rememberMenuPosition(
    path: List<String>,
    anchor: IntOffset,
    containerSize: IntSize,
    rowBounds: Map<String, IntRect>,
    menuSizes: Map<String, IntSize>,
): IntOffset {
    val density = LocalDensity.current
    val defaultSize = with(density) { IntSize(252.dp.roundToPx(), 320.dp.roundToPx()) }
    val menuSize = menuSizes[pathKey(path)] ?: defaultSize
    if (path.isEmpty()) {
        return clampMenuOffset(anchor, menuSize, containerSize)
    }
    val parentRect = rowBounds[path.last()] ?: return clampMenuOffset(anchor, menuSize, containerSize)
    val gap = with(density) { 6.dp.roundToPx() }
    val menuMargin = with(density) { 8.dp.roundToPx() }
    val openRight = parentRect.right + gap + menuSize.width <= containerSize.width - menuMargin
    val targetX = if (openRight) {
        parentRect.right + gap
    } else {
        parentRect.left - gap - menuSize.width
    }
    val targetY = parentRect.top - gap
    return clampMenuOffset(IntOffset(targetX, targetY), menuSize, containerSize)
}

@Composable
private fun MenuPanel(
    path: List<String>,
    items: List<PlayerMenuNode>,
    position: IntOffset,
    menuSizes: MutableMap<String, IntSize>,
    rowBounds: MutableMap<String, IntRect>,
    isOpen: (PlayerMenuNode) -> Boolean,
    isFocused: (PlayerMenuNode) -> Boolean,
    onDismissSubmenus: () -> Unit,
    onFocus: (PlayerMenuNode) -> Unit,
    onActivate: (PlayerMenuNode) -> Unit,
) {
    Surface(
        modifier = Modifier
            .offset { position }
            .width(252.dp)
            .onGloballyPositioned { menuSizes[pathKey(path)] = it.size },
        shape = RoundedCornerShape(14.dp),
        color = MenuSurface,
        shadowElevation = 18.dp,
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .background(MenuSurface)
                .padding(vertical = 8.dp)
        ) {
            items.forEach { node ->
                when (node) {
                    is SeparatorItem -> {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 4.dp, horizontal = 8.dp),
                            color = MenuDivider,
                        )
                    }
                    else -> {
                        MenuRow(
                            node = node,
                            active = isFocused(node) || isOpen(node),
                            onFocus = {
                                onDismissSubmenus()
                                onFocus(node)
                            },
                            onActivate = { onActivate(node) },
                            onBounds = { rowBounds[node.id] = it },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun MenuRow(
    node: PlayerMenuNode,
    active: Boolean,
    onFocus: () -> Unit,
    onActivate: () -> Unit,
    onBounds: (IntRect) -> Unit,
) {
    val label = when (node) {
        is ActionItem -> node.label
        is ToggleItem -> node.label
        is ChoiceGroupItem -> node.label
        is SubmenuItem -> node.label
        is SeparatorItem -> ""
    }
    val hint = when (node) {
        is ActionItem -> node.hint
        is ToggleItem -> node.hint
        is ChoiceGroupItem -> node.hint
        is SubmenuItem -> node.hint
        is SeparatorItem -> null
    }
    val enabled = when (node) {
        is ActionItem -> node.enabled
        is ToggleItem -> node.enabled
        is ChoiceGroupItem -> node.enabled
        is SubmenuItem -> node.enabled && node.children().isNotEmpty()
        is SeparatorItem -> false
    }
    val showCheck = when (node) {
        is ToggleItem -> node.checked
        is ChoiceGroupItem -> node.selected
        else -> false
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp)
            .padding(horizontal = 8.dp)
            .background(
                color = when {
                    !enabled -> MenuSurface
                    active && node is SubmenuItem -> MenuActive
                    active -> MenuHover
                    else -> Color.Transparent
                },
                shape = RoundedCornerShape(10.dp),
            )
            .onGloballyPositioned {
                onBounds(it.boundsInRoot().toIntRect())
            }
            .onPointerEvent(PointerEventType.Enter) { onFocus() }
            .clickable(
                enabled = enabled,
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onActivate,
            )
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(modifier = Modifier.width(14.dp), contentAlignment = Alignment.Center) {
            if (showCheck) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = MenuAccent,
                )
            }
        }
        Text(
            text = label,
            color = when {
                !enabled -> MenuTextDisabled
                else -> MenuText
            },
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        hint?.let {
            Text(
                text = it,
                color = if (enabled) MenuTextMuted else MenuTextDisabled,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (node is SubmenuItem) {
            Text(
                text = ">",
                color = if (enabled) MenuTextMuted else MenuTextDisabled,
                fontSize = 12.sp,
                modifier = Modifier.width(8.dp),
            )
        } else {
            Spacer(modifier = Modifier.width(8.dp))
        }
    }
}

@Composable
private fun clampMenuOffset(offset: IntOffset, menuSize: IntSize, containerSize: IntSize): IntOffset {
    val margin = with(LocalDensity.current) { 8.dp.roundToPx() }
    val maxX = (containerSize.width - menuSize.width - margin).coerceAtLeast(margin)
    val maxY = (containerSize.height - menuSize.height - margin).coerceAtLeast(margin)
    return IntOffset(
        x = offset.x.coerceIn(margin, maxX),
        y = offset.y.coerceIn(margin, maxY),
    )
}

private fun Rect.toIntRect(): IntRect {
    return IntRect(
        left = left.roundToInt(),
        top = top.roundToInt(),
        right = right.roundToInt(),
        bottom = bottom.roundToInt(),
    )
}

private fun pathKey(path: List<String>): String = if (path.isEmpty()) "root" else path.joinToString("/")
