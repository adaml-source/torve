package com.torve.android.tv.components

import android.content.Context
import android.view.inputmethod.InputMethodManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
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
