package com.torve.android.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.res.stringResource
import com.torve.android.R
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.torve.android.session.PostSignInRefresh
import com.torve.android.sync.SyncCoordinator
import com.torve.data.auth.AuthClient
import com.torve.presentation.session.AccountSessionCoordinator
import com.torve.presentation.device.DeviceGovernanceViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds
import org.koin.compose.koinInject

@Composable
fun LoginScreen(
    onLoginSuccess: (isNewRegistration: Boolean) -> Unit,
    onDeviceLimitReached: () -> Unit,
    onSkip: () -> Unit,
    authClient: AuthClient = koinInject(),
    accountSessionCoordinator: AccountSessionCoordinator = koinInject(),
    syncCoordinator: SyncCoordinator = koinInject(),
    deviceGovernanceViewModel: DeviceGovernanceViewModel = koinInject(),
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var isRegisterMode by remember { mutableStateOf(false) }
    var isForgotPasswordMode by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var successMessage by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }

    val peekTransformation = com.torve.android.ui.components.rememberPeekPasswordTransformation(password)
    var passwordRevealed by remember { mutableStateOf(false) }

    val resetSentText = stringResource(R.string.login_reset_sent)
    val accountCreatedText = stringResource(R.string.login_account_created)
    val deviceLimitText = stringResource(R.string.login_device_limit_reached)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Torve",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = when {
                isForgotPasswordMode -> stringResource(R.string.login_reset_password)
                isRegisterMode -> stringResource(R.string.login_create_account)
                else -> stringResource(R.string.login_sign_in)
            },
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )

        Spacer(Modifier.height(32.dp))

        if (isRegisterMode && !isForgotPasswordMode) {
            OutlinedTextField(
                value = displayName,
                onValueChange = { displayName = it },
                label = { Text(stringResource(R.string.login_display_name)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer(Modifier.height(12.dp))
        }

        OutlinedTextField(
            value = email,
            onValueChange = { email = it; error = null; successMessage = null },
            label = { Text(stringResource(R.string.login_email)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        )

        if (!isForgotPasswordMode) {
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = password,
                onValueChange = { password = it; error = null },
                label = { Text(stringResource(R.string.login_password)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = when {
                    passwordRevealed -> VisualTransformation.None
                    isRegisterMode -> VisualTransformation.None
                    else -> peekTransformation
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = if (password.isNotEmpty()) {
                    {
                        IconButton(onClick = { passwordRevealed = !passwordRevealed }) {
                            Icon(
                                imageVector = if (passwordRevealed) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                contentDescription = if (passwordRevealed) stringResource(R.string.login_hide_password) else stringResource(R.string.login_show_password),
                            )
                        }
                    }
                } else null,
            )
        }

        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        successMessage?.let {
            Spacer(Modifier.height(8.dp))
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        Spacer(Modifier.height(24.dp))

        if (isForgotPasswordMode) {
            Button(
                onClick = {
                    scope.launch {
                        isLoading = true
                        error = null
                        successMessage = null
                        val result = authClient.requestPasswordReset(email)
                        isLoading = false
                        if (result.success) {
                            successMessage = resetSentText
                        } else {
                            error = result.error
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading && email.isNotBlank(),
            ) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.login_send_reset_link))
                }
            }

            Spacer(Modifier.height(8.dp))

            TextButton(onClick = { isForgotPasswordMode = false; error = null; successMessage = null }) {
                Text(stringResource(R.string.login_back_to_sign_in))
            }
        } else {
            Button(
                onClick = {
                    scope.launch {
                        isLoading = true
                        error = null
                        val result = if (isRegisterMode) {
                            authClient.register(email, password, displayName.takeIf { it.isNotBlank() })
                        } else {
                            authClient.login(email, password)
                        }
                        if (result.success) {
                            if (isRegisterMode) {
                                successMessage = accountCreatedText
                                delay(2000)
                            }
                            val bootstrap = accountSessionCoordinator.bootstrapAfterSignIn()
                            deviceGovernanceViewModel.fetchAccessState()
                            PostSignInRefresh.enqueueAfterAccountRestore(context, accountSessionCoordinator)
                            syncCoordinator.refreshDevices()
                            isLoading = false
                            if (bootstrap.deviceLimitReached) {
                                error = bootstrap.error
                                    ?: deviceLimitText
                                onDeviceLimitReached()
                            } else {
                                // Proceed after successful login. Registration errors
                                // are stored in AccountSessionCoordinator.state.lastError
                                // and surfaced on the Settings/Manage Devices screens.
                                onLoginSuccess(isRegisterMode)
                            }
                        } else {
                            isLoading = false
                            error = result.error
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading && email.isNotBlank() && password.isNotBlank(),
            ) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(if (isRegisterMode) R.string.login_create_account else R.string.login_sign_in))
                }
            }

            if (!isRegisterMode) {
                Spacer(Modifier.height(4.dp))

                TextButton(onClick = { isForgotPasswordMode = true; error = null; successMessage = null }) {
                    Text(stringResource(R.string.login_forgot_password))
                }
            }

            Spacer(Modifier.height(8.dp))

            TextButton(onClick = { isRegisterMode = !isRegisterMode; error = null }) {
                Text(
                    stringResource(if (isRegisterMode) R.string.login_switch_to_signin else R.string.login_switch_to_register),
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        OutlinedButton(
            onClick = onSkip,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.login_skip))
        }
    }
}
