package com.torve.android.ui.panda

import android.content.Context
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import kotlinx.coroutines.delay

val LocalPandaTvClickToEditFields = compositionLocalOf { false }

@Composable
internal fun PandaEditableOutlinedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    entryFocusRequester: FocusRequester? = null,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    singleLine: Boolean = true,
    shape: Shape = RoundedCornerShape(12.dp),
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    trailingIcon: @Composable (() -> Unit)? = null,
    colors: TextFieldColors,
) {
    if (!LocalPandaTvClickToEditFields.current) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier.then(
                entryFocusRequester?.let { Modifier.focusRequester(it) } ?: Modifier,
            ),
            label = label,
            placeholder = placeholder,
            singleLine = singleLine,
            shape = shape,
            visualTransformation = visualTransformation,
            keyboardOptions = keyboardOptions,
            trailingIcon = trailingIcon,
            colors = colors,
        )
        return
    }

    val context = LocalContext.current
    val view = LocalView.current
    val fallbackInputFocusRequester = remember { FocusRequester() }
    val inputFocusRequester = entryFocusRequester ?: fallbackInputFocusRequester
    val interactionSource = remember { MutableInteractionSource() }
    var editMode by remember { mutableStateOf(false) }

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
            inputFocusRequester.requestFocus()
            delay(50)
            showKeyboard()
        }
    }

    Box(
        modifier = modifier
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        if (!editMode) {
                            editMode = true
                            true
                        } else {
                            false
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
                onClick = { editMode = true },
            ),
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = { if (editMode) onValueChange(it) },
            readOnly = !editMode,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(inputFocusRequester)
                .onFocusChanged { state ->
                    if (!state.isFocused && editMode) {
                        editMode = false
                        hideKeyboard()
                    }
                },
            label = label,
            placeholder = placeholder,
            singleLine = singleLine,
            shape = shape,
            visualTransformation = visualTransformation,
            keyboardOptions = keyboardOptions,
            trailingIcon = trailingIcon,
            colors = colors,
        )
    }
}
