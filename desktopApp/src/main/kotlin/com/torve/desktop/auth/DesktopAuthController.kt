package com.torve.desktop.auth

import com.torve.data.auth.AuthClient
import com.torve.data.auth.AuthEvent
import com.torve.data.auth.AuthUser
import com.torve.presentation.session.AccountSessionCoordinator
import com.torve.presentation.session.AccountSessionState
import com.torve.presentation.subscription.SubscriptionAccessPresentation
import com.torve.presentation.subscription.SubscriptionUiState
import com.torve.presentation.subscription.SubscriptionViewModel
import com.torve.presentation.subscription.accessPresentation
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class DesktopAuthPhase {
    LOGGED_OUT,
    LOADING,
    LOGGED_IN,
    AUTH_ERROR,
    RESTORING_SESSION,
}

data class DesktopAuthUiState(
    val phase: DesktopAuthPhase = DesktopAuthPhase.RESTORING_SESSION,
    val displayName: String = "",
    val email: String = "",
    val password: String = "",
    val user: AuthUser? = null,
    val statusMessage: String = "Restoring saved session...",
    val authError: String? = null,
    val subscriptionState: SubscriptionUiState = SubscriptionUiState(),
    val accessState: SubscriptionAccessPresentation = SubscriptionUiState().accessPresentation(),
    val accountSessionState: AccountSessionState = AccountSessionState(),
)

class DesktopAuthController(
    private val authClient: AuthClient,
    private val accountSessionCoordinator: AccountSessionCoordinator,
    private val subscriptionViewModel: SubscriptionViewModel,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val started = AtomicBoolean(false)

    private val _state = MutableStateFlow(DesktopAuthUiState())
    val state: StateFlow<DesktopAuthUiState> = _state.asStateFlow()

    fun start() {
        if (!started.compareAndSet(false, true)) return
        observeSharedState()
        restoreSession()
    }

    fun dispose() {
        scope.cancel()
    }

    fun updateEmail(value: String) {
        _state.update { current ->
            current.copy(
                email = value,
                authError = null,
                phase = if (current.phase == DesktopAuthPhase.AUTH_ERROR && current.user == null) {
                    DesktopAuthPhase.LOGGED_OUT
                } else {
                    current.phase
                },
            )
        }
    }

    fun updateDisplayName(value: String) {
        _state.update { current ->
            current.copy(
                displayName = value,
                authError = null,
                phase = if (current.phase == DesktopAuthPhase.AUTH_ERROR && current.user == null) {
                    DesktopAuthPhase.LOGGED_OUT
                } else {
                    current.phase
                },
            )
        }
    }

    fun updatePassword(value: String) {
        _state.update { current ->
            current.copy(
                password = value,
                authError = null,
                phase = if (current.phase == DesktopAuthPhase.AUTH_ERROR && current.user == null) {
                    DesktopAuthPhase.LOGGED_OUT
                } else {
                    current.phase
                },
            )
        }
    }

    fun signIn() {
        val email = state.value.email.trim()
        val password = state.value.password
        scope.launch {
            _state.update {
                it.copy(
                    phase = DesktopAuthPhase.LOADING,
                    authError = null,
                    statusMessage = "Signing in to Torve...",
                )
            }

            val result = authClient.login(email, password)
            if (!result.success) {
                _state.update {
                    it.copy(
                        phase = DesktopAuthPhase.AUTH_ERROR,
                        password = "",
                        authError = result.error ?: "Sign-in failed",
                        statusMessage = "Torve sign-in failed.",
                    )
                }
                return@launch
            }

            val bootstrap = runCatching { accountSessionCoordinator.bootstrapAfterSignIn() }
                .getOrElse {
                    com.torve.presentation.session.AccountSessionBootstrapResult(
                        isReady = false,
                        error = it.message ?: "Desktop bootstrap failed after sign-in",
                    )
                }

            subscriptionViewModel.refreshAccess()
            val user = authClient.getAuthenticatedUser() ?: result.user

            _state.update { current ->
                if (user == null) {
                    current.copy(
                        phase = DesktopAuthPhase.AUTH_ERROR,
                        password = "",
                        authError = "Sign-in succeeded, but the desktop session could not be restored.",
                        statusMessage = "Desktop sign-in did not finish cleanly.",
                    )
                } else {
                    current.copy(
                        phase = DesktopAuthPhase.LOGGED_IN,
                        displayName = "",
                        user = user,
                        password = "",
                        authError = bootstrap.error,
                        statusMessage = when {
                            bootstrap.deviceLimitReached -> {
                                bootstrap.error
                                    ?: "Signed in. This device needs an available account slot."
                            }

                            !bootstrap.error.isNullOrBlank() -> "Signed in with warnings."
                            else -> "Signed in."
                        },
                    )
                }
            }
        }
    }

    fun register() {
        val displayName = state.value.displayName.trim().ifBlank { null }
        val email = state.value.email.trim()
        val password = state.value.password
        scope.launch {
            _state.update {
                it.copy(
                    phase = DesktopAuthPhase.LOADING,
                    authError = null,
                    statusMessage = "Creating your Torve account...",
                )
            }

            val result = authClient.register(
                email = email,
                password = password,
                displayName = displayName,
            )
            if (!result.success) {
                _state.update {
                    it.copy(
                        phase = DesktopAuthPhase.AUTH_ERROR,
                        password = "",
                        authError = result.error ?: "Registration failed",
                        statusMessage = "Torve account creation failed.",
                    )
                }
                return@launch
            }

            val bootstrap = runCatching { accountSessionCoordinator.bootstrapAfterSignIn() }
                .getOrElse {
                    com.torve.presentation.session.AccountSessionBootstrapResult(
                        isReady = false,
                        error = it.message ?: "Desktop bootstrap failed after account creation",
                    )
                }

            subscriptionViewModel.refreshAccess()
            val user = authClient.getAuthenticatedUser() ?: result.user

            _state.update { current ->
                if (user == null) {
                    current.copy(
                        phase = DesktopAuthPhase.AUTH_ERROR,
                        password = "",
                        authError = "Account was created, but the desktop session could not be restored.",
                        statusMessage = "Desktop account creation did not finish cleanly.",
                    )
                } else {
                    current.copy(
                        phase = DesktopAuthPhase.LOGGED_IN,
                        displayName = "",
                        user = user,
                        password = "",
                        authError = bootstrap.error,
                        statusMessage = when {
                            bootstrap.deviceLimitReached -> {
                                bootstrap.error
                                    ?: "Signed in. This device needs an available account slot."
                            }

                            !bootstrap.error.isNullOrBlank() -> "Account created with warnings."
                            else -> "Account created."
                        },
                    )
                }
            }
        }
    }

    fun signOut() {
        scope.launch {
            _state.update {
                it.copy(
                    phase = DesktopAuthPhase.LOADING,
                    authError = null,
                    statusMessage = "Signing out...",
                )
            }

            val warnings = buildList {
                runCatching { authClient.logout() }
                    .exceptionOrNull()
                    ?.message
                    ?.let(::add)
                runCatching { accountSessionCoordinator.signOut() }
                    .exceptionOrNull()
                    ?.message
                    ?.let(::add)
            }

            subscriptionViewModel.refreshAccess()

            _state.update {
                it.copy(
                    phase = DesktopAuthPhase.LOGGED_OUT,
                    displayName = "",
                    user = null,
                    password = "",
                    authError = warnings.firstOrNull(),
                    statusMessage = if (warnings.isEmpty()) {
                        "Signed out. Local desktop session cleared."
                    } else {
                        "Signed out locally, but cleanup reported warnings."
                    },
                )
            }
        }
    }

    fun refreshSession() {
        scope.launch {
            _state.update {
                it.copy(
                    phase = DesktopAuthPhase.LOADING,
                    authError = null,
                    statusMessage = "Refreshing session tokens...",
                )
            }

            val refreshResult = authClient.refreshTokens()
            val user = authClient.getAuthenticatedUser()

            if (!refreshResult.success) {
                _state.update { current ->
                    current.copy(
                        phase = if (user == null) {
                            DesktopAuthPhase.AUTH_ERROR
                        } else {
                            DesktopAuthPhase.LOGGED_IN
                        },
                        user = user,
                        password = "",
                        authError = refreshResult.error ?: "Session refresh failed",
                        statusMessage = if (user == null) {
                            "Desktop session refresh failed."
                        } else {
                            "Token refresh failed, but the cached session is still present."
                        },
                    )
                }
                return@launch
            }

            val bootstrap = runCatching { accountSessionCoordinator.onAppForeground() }
                .getOrElse {
                    com.torve.presentation.session.AccountSessionBootstrapResult(
                        isReady = false,
                        error = it.message ?: "Session refresh bootstrap failed",
                    )
                }
            subscriptionViewModel.refreshAccess()

            _state.update { current ->
                current.copy(
                    phase = if (user == null) DesktopAuthPhase.LOGGED_OUT else DesktopAuthPhase.LOGGED_IN,
                    user = user,
                    password = "",
                    authError = bootstrap.error,
                    statusMessage = when {
                        user == null -> "Session refresh cleared the local session."
                        bootstrap.deviceLimitReached -> {
                            bootstrap.error
                                ?: "Session refreshed. This device needs an available account slot."
                        }

                        !bootstrap.error.isNullOrBlank() -> "Session refreshed with warnings."
                        else -> "Session refreshed."
                    },
                )
            }
        }
    }

    fun refreshAccess() {
        scope.launch {
            val user = authClient.getAuthenticatedUser()
            _state.update {
                it.copy(
                    phase = if (user == null) DesktopAuthPhase.LOGGED_OUT else DesktopAuthPhase.LOADING,
                    authError = null,
                    statusMessage = "Refreshing account access...",
                )
            }

            subscriptionViewModel.refreshAccess()

            _state.update {
                it.copy(
                    phase = if (user == null) DesktopAuthPhase.LOGGED_OUT else DesktopAuthPhase.LOGGED_IN,
                    user = user,
                    statusMessage = if (user == null) {
                        "No signed-in session is available."
                    } else {
                        "Account access refreshed."
                    },
                )
            }
        }
    }

    fun retrySessionRestore() {
        restoreSession()
    }

    private fun restoreSession() {
        scope.launch {
            _state.update {
                it.copy(
                    phase = DesktopAuthPhase.RESTORING_SESSION,
                    authError = null,
                    statusMessage = "Restoring saved session...",
                )
            }

            val restored = runCatching { accountSessionCoordinator.restoreSession() }
                .getOrElse { throwable ->
                    _state.update {
                        it.copy(
                            phase = DesktopAuthPhase.AUTH_ERROR,
                            displayName = "",
                            user = null,
                            password = "",
                            authError = throwable.message ?: "Desktop session restore failed",
                            statusMessage = "Desktop session restore failed.",
                        )
                    }
                    return@launch
                }

            subscriptionViewModel.refreshAccess()
            val user = authClient.getAuthenticatedUser()
            val sessionState = accountSessionCoordinator.state.value

            _state.update { current ->
                when {
                    user != null -> {
                        current.copy(
                            phase = DesktopAuthPhase.LOGGED_IN,
                            user = user,
                            password = "",
                            authError = sessionState.lastError,
                            statusMessage = when {
                                sessionState.deviceLimitReached -> {
                                    sessionState.deviceLimitMessage
                                        ?: "Session restored. This device needs an available account slot."
                                }

                                !sessionState.lastError.isNullOrBlank() -> "Session restored with warnings."
                                restored -> "Session restored."
                                else -> "Signed in, but desktop session bootstrap needs attention."
                            },
                        )
                    }

                    current.phase == DesktopAuthPhase.AUTH_ERROR && !current.authError.isNullOrBlank() -> {
                        current.copy(password = "")
                    }

                    else -> {
                        current.copy(
                            phase = DesktopAuthPhase.LOGGED_OUT,
                            displayName = "",
                            user = null,
                            password = "",
                            authError = null,
                            statusMessage = "Sign in to your Torve account to start the desktop preview.",
                        )
                    }
                }
            }
        }
    }

    private fun observeSharedState() {
        scope.launch {
            authClient.authUserFlow.collect { user ->
                _state.update { current ->
                    when {
                        user == null && current.phase == DesktopAuthPhase.LOGGED_IN -> {
                            current.copy(
                                user = null,
                                phase = DesktopAuthPhase.LOGGED_OUT,
                                displayName = "",
                                statusMessage = "Signed out.",
                            )
                        }

                        else -> current.copy(user = user)
                    }
                }
            }
        }

        scope.launch {
            subscriptionViewModel.state.collect { subscriptionState ->
                _state.update {
                    it.copy(
                        subscriptionState = subscriptionState,
                        accessState = subscriptionState.accessPresentation(),
                    )
                }
            }
        }

        scope.launch {
            accountSessionCoordinator.state.collect { accountSessionState ->
                _state.update { it.copy(accountSessionState = accountSessionState) }
            }
        }

        scope.launch {
            authClient.authEvents.collect { event ->
                when (event) {
                    is AuthEvent.SessionExpired -> {
                        _state.update {
                            it.copy(
                                phase = DesktopAuthPhase.AUTH_ERROR,
                                displayName = "",
                                user = null,
                                password = "",
                                authError = event.message,
                                statusMessage = "The Torve session expired.",
                            )
                        }
                    }
                    // Desktop has its own admission flow that drops a
                    // freshly-registered user into Onboarding via
                    // DesktopShellState; the Registered event is
                    // observed by the AdmissionController, not here.
                    is AuthEvent.Registered -> Unit
                }
            }
        }
    }
}
