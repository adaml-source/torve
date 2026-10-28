package com.torve.android.ui.panda

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.torve.android.R
import com.torve.android.ui.theme.Obsidian
import com.torve.android.ui.theme.Snow

val LocalPandaTvClickToEditFields = compositionLocalOf { false }

/** TV navigation owns one button per field. Editing has its own modal focus scope. */
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
    colors: TextFieldColors = pandaTextFieldColors(),
) {
    if (!LocalPandaTvClickToEditFields.current) {
        TextField(value, onValueChange,
            modifier.then(entryFocusRequester?.let { Modifier.focusRequester(it) } ?: Modifier),
            label = label, placeholder = placeholder, singleLine = singleLine, shape = shape,
            visualTransformation = visualTransformation, keyboardOptions = keyboardOptions,
            trailingIcon = trailingIcon, colors = pandaTextFieldColors())
        return
    }

    var editing by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf(value) }
    PandaButton(
        onClick = { draft = value; editing = true },
        modifier = modifier.then(entryFocusRequester?.let { Modifier.focusRequester(it) } ?: Modifier),
    ) {
        Column(Modifier.fillMaxWidth()) {
            label?.let { ProvideTextStyle(MaterialTheme.typography.labelMedium) { it() }; Spacer(Modifier.height(6.dp)) }
            if (value.isNotBlank()) {
                Text(visualTransformation.filter(AnnotatedString(value)).text.text, maxLines = 2)
            } else if (placeholder != null) {
                placeholder()
            } else {
                Text(stringResource(R.string.panda_field_edit))
            }
        }
    }
    if (editing) {
        val inputRequester = remember { FocusRequester() }
        val keyboard = LocalSoftwareKeyboardController.current
        val finish = { onValueChange(draft); keyboard?.hide(); editing = false }
        AlertDialog(
            onDismissRequest = { keyboard?.hide(); editing = false },
            containerColor = Obsidian,
            titleContentColor = Snow,
            textContentColor = Snow,
            title = label,
            text = {
                // AlertDialog subcomposes into its own window. Request focus only
                // after the field in that window has joined the composition.
                LaunchedEffect(Unit) { withFrameNanos { }; inputRequester.requestFocus(); keyboard?.show() }
                TextField(draft, { draft = it }, Modifier.fillMaxWidth().focusRequester(inputRequester),
                    placeholder = placeholder, singleLine = singleLine, shape = shape,
                    visualTransformation = visualTransformation,
                    keyboardOptions = keyboardOptions.copy(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { finish() }),
                    trailingIcon = trailingIcon, colors = pandaTextFieldColors())
            },
            confirmButton = { PandaButton(finish) { Text(stringResource(R.string.common_save)) } },
            dismissButton = { PandaButton({ keyboard?.hide(); editing = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}
