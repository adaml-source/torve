package com.torve.android.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.Torve

/**
 * Compact pill-shaped search field matching the app's existing
 * `Torve.colors.inputBackground` + 10dp-corner pattern used on the
 * Channels LIVE tab. Used in three places that previously rendered a
 * full-height Material3 OutlinedTextField (too tall, off-design):
 *  - mobile Channels → GUIDE sub-tab
 *  - TV Channels sidebar
 *  - TV Sports header
 *
 * On TV the [showFocusRing] flag draws an Amber border when the field
 * is focused so the user can see the D-pad cursor landed here.
 * With [editOnClick], TV focus does not automatically enter text mode
 * or open the keyboard; OK/click explicitly starts editing.
 *
 * Submit (Enter / IME action Search) calls [onSubmit]. Pass `null` to
 * disable submit handling for fields that filter live as the user
 * types.
 */
@Composable
fun TorveSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    onSubmit: (() -> Unit)? = null,
    showFocusRing: Boolean = false,
    editOnClick: Boolean = false,
    onMoveDownFromEdit: (() -> Unit)? = null,
    onMoveRightFromEdit: (() -> Unit)? = null,
    forceExitEditSignal: Int = 0,
    startEditingSignal: Int = 0,
    onEditingChanged: ((Boolean) -> Unit)? = null,
) {
    var isFocused by remember { mutableStateOf(false) }
    var isEditing by remember { mutableStateOf(!editOnClick) }
    val inputRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val borderColor: Color = if (showFocusRing && isFocused) Amber else Color.Transparent

    LaunchedEffect(editOnClick, isEditing) {
        if (editOnClick && isEditing) {
            runCatching { inputRequester.requestFocus() }
            keyboardController?.show()
        }
    }

    LaunchedEffect(isEditing) {
        onEditingChanged?.invoke(isEditing)
    }

    LaunchedEffect(forceExitEditSignal) {
        if (editOnClick && forceExitEditSignal > 0) {
            isEditing = false
            keyboardController?.hide()
        }
    }

    LaunchedEffect(startEditingSignal) {
        if (editOnClick && startEditingSignal > 0) {
            isEditing = true
        }
    }

    BackHandler(enabled = editOnClick && isEditing) {
        isEditing = false
        keyboardController?.hide()
    }

    Box(
        modifier = modifier
            .onFocusChanged {
                isFocused = it.hasFocus
                if (!it.hasFocus && editOnClick) {
                    isEditing = false
                    keyboardController?.hide()
                }
            }
            .clip(RoundedCornerShape(10.dp))
            .background(Torve.colors.inputBackground)
            .border(if (showFocusRing && isFocused) 3.dp else 1.dp, borderColor, RoundedCornerShape(10.dp))
            .clickable(
                enabled = editOnClick,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                isEditing = true
            }
            .then(if (editOnClick && !isEditing) Modifier.focusable() else Modifier)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        if (editOnClick && !isEditing) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = 28.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    text = value.ifBlank { placeholder },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (value.isBlank()) Torve.colors.textHint else Torve.colors.textPrimary,
                    maxLines = 1,
                )
            }
        } else {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = Torve.colors.textPrimary),
                cursorBrush = SolidColor(Amber),
                keyboardOptions = KeyboardOptions(imeAction = if (onSubmit != null) ImeAction.Search else ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onSearch = { onSubmit?.invoke() },
                    onDone = { onSubmit?.invoke() },
                ),
                modifier = Modifier
                    .then(if (editOnClick) Modifier.focusRequester(inputRequester) else Modifier)
                    .focusProperties { canFocus = !editOnClick || isEditing }
                    .onPreviewKeyEvent { event ->
                        if (!editOnClick || !isEditing || event.type != KeyEventType.KeyDown) {
                            return@onPreviewKeyEvent false
                        }
                        when (event.key) {
                            Key.DirectionDown -> {
                                if (onMoveDownFromEdit != null) {
                                    isEditing = false
                                    keyboardController?.hide()
                                    onMoveDownFromEdit()
                                    true
                                } else {
                                    false
                                }
                            }

                            Key.DirectionRight -> {
                                if (onMoveRightFromEdit != null) {
                                    isEditing = false
                                    keyboardController?.hide()
                                    onMoveRightFromEdit()
                                    true
                                } else {
                                    false
                                }
                            }

                            Key.Back -> {
                                isEditing = false
                                keyboardController?.hide()
                                true
                            }

                            else -> false
                        }
                    }
                    .fillMaxWidth()
                    .padding(end = 28.dp)  // leaves room for the trailing icon
                    .onFocusChanged {
                        if (!editOnClick) {
                            isFocused = it.isFocused
                        }
                    },
                decorationBox = { innerTextField ->
                    Box {
                        if (value.isEmpty()) {
                            Text(
                                placeholder,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Torve.colors.textHint,
                            )
                        }
                        innerTextField()
                    }
                },
            )
        }
        if (value.isNotEmpty()) {
            IconButton(
                onClick = {
                    onValueChange("")
                    if (editOnClick) {
                        isEditing = false
                        keyboardController?.hide()
                    }
                },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .size(20.dp),
            ) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = "Clear",
                    tint = Torve.colors.textTertiary,
                    modifier = Modifier.size(16.dp),
                )
            }
        } else {
            Icon(
                Icons.Rounded.Search,
                contentDescription = null,
                tint = Torve.colors.textTertiary,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .size(16.dp),
            )
        }
    }
}
