package com.torve.android.tv.screens

import com.torve.android.tv.components.tvSettingsLabel as localizedUiText

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.torve.android.remote.TorveRemoteDialog

internal enum class TvAccountEntryStep { EMAIL, PASSWORD, CONFIRM_PASSWORD }

/** Entry is explicit (OK on Email/Password); ordinary settings focus never opens an IME. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun TvAccountEntryDialog(
    step: TvAccountEntryStep, initialValue: String, registering: Boolean,
    onConfirm: (String) -> Unit, onDismiss: () -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val title = when (step) {
        TvAccountEntryStep.EMAIL -> "Email address"
        TvAccountEntryStep.PASSWORD -> "Password"
        TvAccountEntryStep.CONFIRM_PASSWORD -> "Confirm password"
    }
    val lastStep = step == TvAccountEntryStep.CONFIRM_PASSWORD ||
        (step == TvAccountEntryStep.PASSWORD && !registering)
    val confirm by rememberUpdatedState(onConfirm)
    TorveRemoteDialog(onDismissRequest = { keyboard?.hide(); onDismiss() }) {
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.widthIn(max = 560.dp).padding(28.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Text(localizedUiText(if (registering) "Create account" else "Sign in"), style = MaterialTheme.typography.headlineSmall)
                key(step) {
                    var value by remember { mutableStateOf(initialValue) }
                    val field = remember { FocusRequester() }
                    fun advance() { if (value.isNotBlank()) { if (lastStep) keyboard?.hide(); confirm(value) } }
                    OutlinedTextField(value, { value = it }, label = { Text(localizedUiText(title)) }, singleLine = true,
                        visualTransformation = if (step == TvAccountEntryStep.EMAIL) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = if (step == TvAccountEntryStep.EMAIL) KeyboardType.Email else KeyboardType.Password,
                            imeAction = if (lastStep) ImeAction.Go else ImeAction.Next),
                        keyboardActions = KeyboardActions(onNext = { advance() }, onGo = { advance() }, onDone = { advance() }),
                        modifier = Modifier.fillMaxWidth().focusRequester(field).testTag("account.entry.${step.name.lowercase()}"))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TextButton({ keyboard?.hide(); onDismiss() }) { Text(localizedUiText("Cancel")) }
                        Button({ advance() }, enabled = value.isNotBlank()) {
                            Text(localizedUiText(if (lastStep) { if (registering) "Create account" else "Sign in" } else "Next"))
                        }
                    }
                    LaunchedEffect(step) {
                        withFrameNanos { }
                        field.requestFocus()
                        withFrameNanos { }
                        keyboard?.show()
                    }
                }
            }
        }
    }
}
