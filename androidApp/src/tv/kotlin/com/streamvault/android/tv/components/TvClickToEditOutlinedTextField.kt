package com.streamvault.android.tv.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController

@Composable
fun TvClickToEditOutlinedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: @Composable (() -> Unit),
    singleLine: Boolean,
    modifier: Modifier = Modifier,
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    var editMode by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }

    OutlinedTextField(
        value = value,
        onValueChange = { newValue ->
            if (editMode) {
                onValueChange(newValue)
            }
        },
        readOnly = !editMode,
        singleLine = singleLine,
        label = label,
        modifier = modifier
            .onFocusChanged { state ->
                if (!state.isFocused && editMode) {
                    editMode = false
                    keyboardController?.hide()
                }
            }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        if (!editMode) {
                            editMode = true
                            keyboardController?.show()
                            true
                        } else {
                            false
                        }
                    }

                    Key.Back -> {
                        if (editMode) {
                            editMode = false
                            keyboardController?.hide()
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
                    keyboardController?.show()
                },
            ),
    )
}
