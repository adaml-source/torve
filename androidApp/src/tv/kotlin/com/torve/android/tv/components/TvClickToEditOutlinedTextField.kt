package com.torve.android.tv.components

import android.content.Context
import android.view.inputmethod.InputMethodManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.torve.android.ui.theme.Charcoal
import com.torve.android.ui.theme.Graphite
import com.torve.android.ui.theme.Snow
import com.torve.android.ui.theme.AmberLight

@Composable
fun TvClickToEditOutlinedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: @Composable (() -> Unit),
    singleLine: Boolean,
    modifier: Modifier = Modifier,
    placeholder: @Composable (() -> Unit)? = null,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    visualTransformation: androidx.compose.ui.text.input.VisualTransformation =
        androidx.compose.ui.text.input.VisualTransformation.None,
) {
    val context = LocalContext.current
    val view = LocalView.current
    var editMode by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val internalFocusRequester = remember { FocusRequester() }
    val outlineColor = MaterialTheme.colorScheme.outline
    val borderColor by animateColorAsState(
        targetValue = when {
            editMode -> outlineColor
            focused -> AmberLight
            else -> outlineColor
        },
        label = "tfBorder",
    )

    fun showKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }

    fun hideKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(view.windowToken, 0)
    }

    // When entering edit mode, re-request focus and force-show keyboard via IMM
    LaunchedEffect(editMode) {
        if (editMode) {
            internalFocusRequester.requestFocus()
            kotlinx.coroutines.delay(50)
            showKeyboard()
        }
    }

    Box(
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        if (!editMode) {
                            editMode = true
                            true
                        } else {
                            editMode = false
                            hideKeyboard()
                            true
                        }
                    }

                    Key.Back -> {
                        if (editMode) {
                            editMode = false
                            hideKeyboard()
                            true
                        } else {
                            false
                        }
                    }

                    else -> false
                }
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = {
                    editMode = true
                },
            ),
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = { newValue ->
                if (editMode) {
                    onValueChange(newValue)
                }
            },
            readOnly = !editMode,
            singleLine = singleLine,
            minLines = minLines,
            maxLines = maxLines,
            label = label,
            placeholder = placeholder,
            visualTransformation = visualTransformation,
            keyboardOptions = KeyboardOptions(
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(
                onDone = {
                    editMode = false
                    hideKeyboard()
                },
            ),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedBorderColor = borderColor,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(internalFocusRequester)
                .onFocusChanged { state ->
                    if (!state.isFocused && editMode) {
                        editMode = false
                        hideKeyboard()
                    }
                },
        )
    }
}

/**
 * Compact TV search variant with the same click-to-edit/Back/IME contract as
 * [TvClickToEditOutlinedTextField]. Its pill geometry is fixed while focus is
 * animated inside the bounds, so the surrounding header never jumps.
 */
@Composable
fun TvClickToEditSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Search",
    onNavigateDown: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val view = LocalView.current
    var editMode by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val internalFocusRequester = remember { FocusRequester() }
    val shape = RoundedCornerShape(999.dp)
    val borderColor by animateColorAsState(
        targetValue = if (focused) AmberLight else Snow.copy(alpha = 0.14f),
        label = "tvSearchFieldBorder",
    )
    val backgroundColor by animateColorAsState(
        targetValue = if (focused) Graphite.copy(alpha = 0.96f) else Charcoal.copy(alpha = 0.72f),
        label = "tvSearchFieldBackground",
    )

    fun showKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }

    fun hideKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(view.windowToken, 0)
    }

    LaunchedEffect(editMode) {
        if (editMode) {
            internalFocusRequester.requestFocus()
            kotlinx.coroutines.delay(50)
            showKeyboard()
        }
    }

    Box(
        modifier = modifier
            .height(42.dp)
            .onFocusChanged { focused = it.hasFocus }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionDown -> {
                        if (editMode) {
                            editMode = false
                            hideKeyboard()
                        }
                        onNavigateDown?.invoke()
                        onNavigateDown != null
                    }

                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        if (!editMode) {
                            editMode = true
                        } else {
                            editMode = false
                            hideKeyboard()
                        }
                        true
                    }

                    Key.Back -> {
                        if (editMode) {
                            editMode = false
                            hideKeyboard()
                            true
                        } else {
                            false
                        }
                    }

                    else -> false
                }
            }
            .clip(shape)
            .background(backgroundColor)
            .border(if (focused) 2.dp else 1.dp, borderColor, shape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = { editMode = true },
            )
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = editMode,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.merge(
                TextStyle(color = Snow, fontWeight = FontWeight.Medium),
            ),
            cursorBrush = SolidColor(AmberLight),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = {
                    editMode = false
                    hideKeyboard()
                },
            ),
            interactionSource = interactionSource,
            decorationBox = { innerTextField ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = if (focused) AmberLight else Snow.copy(alpha = 0.72f),
                        modifier = Modifier.size(18.dp),
                    )
                    Box(modifier = Modifier.weight(1f)) {
                        if (value.isBlank()) {
                            Text(
                                text = placeholder,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Snow.copy(alpha = 0.58f),
                            )
                        }
                        innerTextField()
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(internalFocusRequester)
                .onFocusChanged { state ->
                    if (!state.hasFocus && editMode) {
                        editMode = false
                        hideKeyboard()
                    }
                },
        )
    }
}
