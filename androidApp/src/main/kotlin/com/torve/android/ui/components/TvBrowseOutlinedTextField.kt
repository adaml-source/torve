package com.torve.android.ui.components

import android.content.Context
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.torve.android.ui.theme.AmberLight
import kotlinx.coroutines.delay

/**
 * A normal mobile text field that becomes browse-first on TV.
 * D-pad focus never opens the keyboard; Center/OK explicitly enters and exits editing.
 */
@Composable
fun TvBrowseOutlinedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    tvEnabled: Boolean,
    modifier: Modifier = Modifier,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    singleLine: Boolean = false,
    enabled: Boolean = true,
) {
    if (!tvEnabled) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier,
            label = label,
            placeholder = placeholder,
            visualTransformation = visualTransformation,
            singleLine = singleLine,
            enabled = enabled,
        )
        return
    }

    val context = LocalContext.current
    val view = LocalView.current
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    var editMode by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    var consumeBackUp by remember { mutableStateOf(false) }
    val fieldShape = OutlinedTextFieldDefaults.shape

    fun hideKeyboard() {
        val inputMethodManager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        inputMethodManager.hideSoftInputFromWindow(view.windowToken, 0)
    }

    LaunchedEffect(editMode) {
        if (editMode) {
            focusRequester.requestFocus()
            delay(50)
            val inputMethodManager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            inputMethodManager.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    OutlinedTextField(
        value = value,
        onValueChange = { if (editMode) onValueChange(it) },
        modifier = modifier
            .onFocusChanged { state ->
                focused = state.isFocused
                if (!state.isFocused && editMode) {
                    editMode = false
                    hideKeyboard()
                }
            }
            .zIndex(if (focused) 1f else 0f)
            .border(
                BorderStroke(2.dp, if (focused) AmberLight else Color.Transparent),
                fieldShape,
            )
            .focusRequester(focusRequester)
            .onPreviewKeyEvent { event ->
                val isConfirm = event.key == Key.DirectionCenter ||
                    event.key == Key.Enter ||
                    event.key == Key.NumPadEnter
                if (isConfirm) {
                    if (event.type == KeyEventType.KeyDown) {
                        editMode = !editMode
                        if (!editMode) hideKeyboard()
                    }
                    return@onPreviewKeyEvent true
                }
                if (event.key == Key.Back) {
                    if (event.type == KeyEventType.KeyDown && editMode) {
                        editMode = false
                        consumeBackUp = true
                        hideKeyboard()
                        return@onPreviewKeyEvent true
                    }
                    if (event.type == KeyEventType.KeyUp && consumeBackUp) {
                        consumeBackUp = false
                        return@onPreviewKeyEvent true
                    }
                }
                if (!editMode && event.type == KeyEventType.KeyDown) {
                    val direction = when (event.key) {
                        Key.DirectionUp -> FocusDirection.Up
                        Key.DirectionDown -> FocusDirection.Down
                        Key.DirectionLeft -> FocusDirection.Left
                        Key.DirectionRight -> FocusDirection.Right
                        else -> null
                    }
                    if (direction != null) {
                        return@onPreviewKeyEvent focusManager.moveFocus(direction)
                    }
                }
                false
            },
        label = label,
        placeholder = placeholder,
        visualTransformation = visualTransformation,
        singleLine = singleLine,
        enabled = enabled,
        readOnly = !editMode,
        shape = fieldShape,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(
            onDone = {
                editMode = false
                hideKeyboard()
            },
        ),
    )
}
