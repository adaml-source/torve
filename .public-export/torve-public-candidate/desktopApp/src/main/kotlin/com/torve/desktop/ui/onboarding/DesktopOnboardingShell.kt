package com.torve.desktop.ui.onboarding

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.torve.desktop.DesktopReleaseInfo
import com.torve.desktop.admission.DesktopAdmissionRequirement
import com.torve.desktop.admission.DesktopAdmissionSnapshot
import com.torve.desktop.auth.DesktopAuthController
import com.torve.desktop.transfer.SecretsTransferReceiveScreen
import com.torve.presentation.transfer.SecretsTransferReceiverViewModel
import com.torve.desktop.auth.DesktopAuthPhase
import com.torve.desktop.auth.DesktopAuthUiState
import com.torve.desktop.ui.components.TorveBadge
import com.torve.desktop.ui.components.TorveBadgeTone
import com.torve.desktop.ui.components.TorveBanner
import com.torve.desktop.ui.components.TorveBannerTone
import com.torve.desktop.ui.components.TorveGhostButton
import com.torve.desktop.ui.components.TorvePageHeader
import com.torve.desktop.ui.components.TorvePill
import com.torve.desktop.ui.components.TorvePrimaryButton
import com.torve.desktop.ui.components.TorveSecondaryButton
import com.torve.desktop.ui.components.TorveSectionCard
import com.torve.desktop.ui.components.TorveTextField
import com.torve.desktop.ui.theme.TorveDesktopThemeTokens
import com.torve.domain.model.DebridServiceType
import com.torve.domain.model.StreamQuality
import com.torve.presentation.setup.SetupIntent
import com.torve.presentation.setup.SetupStep
import com.torve.presentation.setup.SetupUiState
import com.torve.presentation.setup.SetupWizardCoordinator
import com.torve.presentation.setup.SetupWizardViewModel
import kotlinx.coroutines.launch

private enum class DesktopAuthEntryMode {
    SIGN_IN,
    REGISTER,
}

@Composable
fun DesktopOnboardingShell(
    authState: DesktopAuthUiState,
    authController: DesktopAuthController,
    setupWizardViewModel: SetupWizardViewModel,
    setupWizardCoordinator: SetupWizardCoordinator,
    onboardingDeepLink: DesktopOnboardingDeepLink,
    releaseInfo: DesktopReleaseInfo,
    admission: DesktopAdmissionSnapshot?,
    onExit: () -> Unit,
    onCompleteOnboarding: suspend () -> Result<Unit>,
) {
    val colors = TorveDesktopThemeTokens.colors
    val radii = TorveDesktopThemeTokens.radii
    val setupState by setupWizardViewModel.state.collectAsState()

    // Unauthenticated → premium two-zone Torve login (DesktopAuthScreen.kt).
    // Once a session exists we fall through to the legacy rail+card
    // layout used for the post-sign-in setup wizard.
    if (authState.user == null) {
        DesktopPremiumAuthScreen(
            authState = authState,
            authController = authController,
            releaseInfo = releaseInfo,
            onExit = onExit,
        )
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF050810)),
    ) {
        DesktopOnboardingBackground(modifier = Modifier.fillMaxSize())

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 72.dp, vertical = 56.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier
                    .widthIn(max = 1240.dp)
                    .fillMaxWidth(),
                color = Color(0xFF0B111D).copy(alpha = 0.94f),
                shape = RoundedCornerShape(32.dp),
                border = BorderStroke(
                    1.dp,
                    Color(0xFF26314D).copy(alpha = 0.82f),
                ),
                shadowElevation = 34.dp,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(34.dp),
                    horizontalArrangement = Arrangement.spacedBy(30.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    DesktopOnboardingRail(
                        authState = authState,
                        releaseInfo = releaseInfo,
                        admission = admission,
                        modifier = Modifier.width(390.dp),
                    )

                    Surface(
                        modifier = Modifier.weight(1f),
                        color = Color(0xFF0E1524).copy(alpha = 0.96f),
                        shape = RoundedCornerShape(26.dp),
                        border = BorderStroke(
                            1.dp,
                            Color(0xFF26314D).copy(alpha = 0.78f),
                        ),
                        shadowElevation = 20.dp,
                    ) {
                        DesktopSetupPane(
                            authState = authState,
                            setupWizardCoordinator = setupWizardCoordinator,
                            onboardingDeepLink = onboardingDeepLink,
                            onCompleteOnboarding = onCompleteOnboarding,
                            onSignOut = authController::signOut,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(30.dp),
                        )
                    }
                }
            }
        }

        Text(
            text = "Version ${releaseInfo.versionLabel} • ${releaseInfo.channel}",
            style = MaterialTheme.typography.labelSmall,
            color = colors.textMuted.copy(alpha = 0.72f),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 28.dp, bottom = 22.dp),
        )
    }
}

@Composable
private fun DesktopOnboardingBackground(modifier: Modifier = Modifier) {
    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            Color(0xFF121722),
                            Color(0xFF070A12),
                            Color(0xFF03060C),
                        ),
                        center = Offset(520f, 360f),
                        radius = 1120f,
                    ),
                ),
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            Color(0xFFD7A33A).copy(alpha = 0.16f),
                            Color(0xFFD7A33A).copy(alpha = 0.045f),
                            Color.Transparent,
                        ),
                        center = Offset(420f, 280f),
                        radius = 760f,
                    ),
                ),
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color(0xFF050810).copy(alpha = 0.12f),
                        0.55f to Color.Transparent,
                        1f to Color(0xFF02040A).copy(alpha = 0.86f),
                    ),
                ),
        )
    }
}

/**
 * Legacy single-card login retained for fallback only - replaced at
 * the entry point by DesktopPremiumAuthScreen (see DesktopAuthScreen.kt).
 * Kept private to avoid accidental external use; safe to delete in a
 * follow-up.
 */
@Suppress("unused")
@Composable
private fun DesktopPremiumLoginScreenLegacy(
    authState: DesktopAuthUiState,
    authController: DesktopAuthController,
    releaseInfo: DesktopReleaseInfo,
) {
    val colors = TorveDesktopThemeTokens.colors
    val radii = TorveDesktopThemeTokens.radii
    var entryMode by remember { mutableStateOf(DesktopAuthEntryMode.SIGN_IN) }
    val isBusy = authState.phase == DesktopAuthPhase.LOADING ||
        authState.phase == DesktopAuthPhase.RESTORING_SESSION

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                // Two-stop dark vignette tinted with the brand accent
                // - feels cinematic without committing a hero image.
                Brush.radialGradient(
                    colors = listOf(
                        colors.accent.copy(alpha = 0.18f),
                        colors.shellBackground,
                    ),
                    radius = 1400f,
                ),
            ),
    ) {
        // Subtle vertical gradient on top so the radial doesn't clip
        // square at the edges.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.0f to androidx.compose.ui.graphics.Color.Transparent,
                        0.6f to colors.shellBackground.copy(alpha = 0.0f),
                        1.0f to colors.shellBackground.copy(alpha = 0.85f),
                    ),
                ),
        )

        // Centered card.
        Box(
            modifier = Modifier.fillMaxSize().padding(48.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier.width(440.dp),
                color = colors.cardSurface.copy(alpha = 0.92f),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(radii.xl),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    colors.borderSubtle.copy(alpha = 0.7f),
                ),
                shadowElevation = 24.dp,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 36.dp, vertical = 40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    // Brand logo - TV-asset PNG of the Torve wordmark.
                    androidx.compose.foundation.Image(
                        painter = androidx.compose.ui.res.painterResource("torve_logo.png"),
                        contentDescription = "Torve",
                        modifier = Modifier.height(56.dp),
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                    )

                    Text(
                        text = if (entryMode == DesktopAuthEntryMode.SIGN_IN)
                            "Sign in to continue"
                        else
                            "Create your account",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        color = colors.textSecondary,
                    )

                    if (entryMode == DesktopAuthEntryMode.REGISTER) {
                        TorveTextField(
                            value = authState.displayName,
                            onValueChange = authController::updateDisplayName,
                            label = "Display name",
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    TorveTextField(
                        value = authState.email,
                        onValueChange = authController::updateEmail,
                        label = "Email",
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    )

                    TorveTextField(
                        value = authState.password,
                        onValueChange = authController::updatePassword,
                        label = "Password",
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        visualTransformation = PasswordVisualTransformation(),
                        onSubmit = {
                            if (entryMode == DesktopAuthEntryMode.REGISTER) authController.register()
                            else authController.signIn()
                        },
                    )

                    // Inline error - only when there's something to say,
                    // and styled as a single-line message rather than
                    // the chunky banner the old design used.
                    authState.authError?.takeIf { it.isNotBlank() }?.let { msg ->
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.error,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    Box(modifier = Modifier.fillMaxWidth()) {
                        TorvePrimaryButton(
                            text = when {
                                isBusy -> "Working"
                                entryMode == DesktopAuthEntryMode.REGISTER -> "Create account"
                                else -> "Sign in"
                            },
                            onClick = {
                                if (entryMode == DesktopAuthEntryMode.REGISTER) authController.register()
                                else authController.signIn()
                            },
                            enabled = !isBusy && authState.email.isNotBlank() && authState.password.isNotBlank(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    // Mode toggle as a quiet text link rather than a
                    // sibling button - the primary action stays the
                    // visual focus.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (entryMode == DesktopAuthEntryMode.SIGN_IN)
                                "Don't have an account?"
                            else
                                "Already have an account?",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textSecondary,
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        TorveGhostButton(
                            text = if (entryMode == DesktopAuthEntryMode.SIGN_IN) "Register" else "Sign in",
                            onClick = {
                                entryMode = if (entryMode == DesktopAuthEntryMode.SIGN_IN)
                                    DesktopAuthEntryMode.REGISTER
                                else DesktopAuthEntryMode.SIGN_IN
                            },
                            enabled = !isBusy,
                        )
                    }

                    if (isBusy) {
                        CircularProgressIndicator(
                            modifier = Modifier.width(18.dp).height(18.dp),
                            strokeWidth = 2.dp,
                            color = colors.accent,
                        )
                    }
                }
            }
        }

        // Tiny build chip in the corner - keeps the version visible
        // for support without intruding on the centerpiece.
        Text(
            text = "${releaseInfo.versionLabel} · ${releaseInfo.channel}",
            style = MaterialTheme.typography.labelSmall,
            color = colors.textMuted,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 20.dp, bottom = 16.dp),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DesktopOnboardingRail(
    authState: DesktopAuthUiState,
    releaseInfo: DesktopReleaseInfo,
    admission: DesktopAdmissionSnapshot?,
    modifier: Modifier = Modifier,
) {
    val colors = TorveDesktopThemeTokens.colors

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        androidx.compose.foundation.Image(
            painter = androidx.compose.ui.res.painterResource("torve_logo.png"),
            contentDescription = "Torve",
            modifier = Modifier.height(56.dp),
            contentScale = androidx.compose.ui.layout.ContentScale.Fit,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Finish your desktop setup.",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = colors.textPrimary,
        )

        Text(
            text = "Connect at least one playback path, choose your defaults, then enter the Torve desktop experience.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textSecondary,
        )

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TorvePill(
                text = "VOD",
                backgroundColor = colors.fieldSurface.copy(alpha = 0.86f),
                contentColor = colors.textSecondary,
            )
            TorvePill(
                text = "Live TV",
                backgroundColor = colors.fieldSurface.copy(alpha = 0.86f),
                contentColor = colors.textSecondary,
            )
            TorvePill(
                text = "Sync",
                backgroundColor = colors.fieldSurface.copy(alpha = 0.86f),
                contentColor = colors.textSecondary,
            )
            TorvePill(
                text = "Playback",
                backgroundColor = colors.fieldSurface.copy(alpha = 0.86f),
                contentColor = colors.textSecondary,
            )
        }

        // The legacy "Setup progress" 7-step checklist that used to
        // live here is gone post-A+B+E (onboarding is one screen now,
        // not a multi-step flow). The pills above + the admission
        // banner below cover the same intent more honestly.

        admission?.let { snapshot ->
            // Post-A+B+E admission policy: onboarding-completion alone
            // admits the user to Main. Zero-source users land in the
            // Home empty state with explicit CTAs ("Set up sources",
            // "Sync your watchlist with Trakt"), so a missing playback
            // path is *not* a true block on entering Torve. Surface
            // the two requirements with different tones to match the
            // actual policy:
            //   - ONBOARDING_COMPLETED is genuinely required (must
            //     finish or click "Skip for now")
            //   - PLAYBACK_PATH is informational only — Torve runs
            //     without it, but most features depend on it.
            if (DesktopAdmissionRequirement.ONBOARDING_COMPLETED in snapshot.missingRequirements) {
                TorveBanner(
                    title = "Finish onboarding",
                    description = "Set up with Panda or click \"Skip for now\" to enter Torve.",
                    tone = TorveBannerTone.Warning,
                )
            }
            if (DesktopAdmissionRequirement.PLAYBACK_PATH in snapshot.missingRequirements) {
                TorveBanner(
                    title = "For streaming, add a source later",
                    description = "Torve runs without one - addons and Plex/Jellyfin still work. Add a debrid provider, IPTV playlist, or NZB account in Settings whenever you're ready.",
                    tone = TorveBannerTone.Info,
                )
            }
        }

        TorveBanner(
            title = "Build",
            description = "${releaseInfo.versionLabel} • ${releaseInfo.channel}",
            tone = TorveBannerTone.Info,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DesktopAuthEntryPane(
    authState: DesktopAuthUiState,
    authController: DesktopAuthController,
    modifier: Modifier = Modifier,
) {
    var entryMode by remember { mutableStateOf(DesktopAuthEntryMode.SIGN_IN) }
    val isBusy = authState.phase == DesktopAuthPhase.LOADING || authState.phase == DesktopAuthPhase.RESTORING_SESSION

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        TorvePageHeader(
            title = "Welcome",
            subtitle = "Use an existing Torve account or create one here. Desktop admission stays locked until a valid authenticated session exists.",
        )

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (entryMode == DesktopAuthEntryMode.SIGN_IN) {
                TorveSecondaryButton(text = "Sign In", onClick = {}, enabled = false)
                TorveGhostButton(
                    text = "Register",
                    onClick = { entryMode = DesktopAuthEntryMode.REGISTER },
                )
            } else {
                TorveGhostButton(
                    text = "Sign In",
                    onClick = { entryMode = DesktopAuthEntryMode.SIGN_IN },
                )
                TorveSecondaryButton(text = "Register", onClick = {}, enabled = false)
            }
        }

        TorveBanner(
            title = if (authState.authError.isNullOrBlank()) "Session state" else "Authentication issue",
            description = authState.authError ?: authState.statusMessage,
            tone = if (authState.authError.isNullOrBlank()) TorveBannerTone.Info else TorveBannerTone.Error,
        )

        if (entryMode == DesktopAuthEntryMode.REGISTER) {
            TorveTextField(
                value = authState.displayName,
                onValueChange = authController::updateDisplayName,
                label = "Display Name (Optional)",
                modifier = Modifier.fillMaxWidth(),
            )
        }

        TorveTextField(
            value = authState.email,
            onValueChange = authController::updateEmail,
            label = "Email",
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        )

        TorveTextField(
            value = authState.password,
            onValueChange = authController::updatePassword,
            label = "Password",
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            visualTransformation = PasswordVisualTransformation(),
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TorvePrimaryButton(
                text = when {
                    isBusy -> "Working"
                    entryMode == DesktopAuthEntryMode.REGISTER -> "Create Account"
                    else -> "Sign In"
                },
                onClick = {
                    if (entryMode == DesktopAuthEntryMode.REGISTER) {
                        authController.register()
                    } else {
                        authController.signIn()
                    }
                },
                enabled = !isBusy && authState.email.isNotBlank() && authState.password.isNotBlank(),
            )
            TorveGhostButton(
                text = "Restore Session",
                onClick = authController::retrySessionRestore,
                enabled = !isBusy,
            )
            if (isBusy) {
                CircularProgressIndicator(
                    modifier = Modifier.width(18.dp).height(18.dp),
                    strokeWidth = 2.dp,
                )
            }
        }
    }
}

// `DesktopSetupMode` and the legacy guided-wizard branch were removed
// 2026-05-03 (onboarding simplification, Fix A in
// docs/onboarding-simplification-plan.md). The Panda-primary hub is
// the only onboarding surface now; individual source configuration
// happens post-admission in Settings → Integrations.

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DesktopSetupPane(
    authState: DesktopAuthUiState,
    setupWizardCoordinator: SetupWizardCoordinator,
    onboardingDeepLink: DesktopOnboardingDeepLink,
    onCompleteOnboarding: suspend () -> Result<Unit>,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val colors = TorveDesktopThemeTokens.colors
    var completionError by remember { mutableStateOf<String?>(null) }
    var isCompleting by remember { mutableStateOf(false) }
    var showQrReceive by remember { mutableStateOf(false) }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Desktop setup",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary,
                )
                Text(
                    text = "Signed in as ${authState.user?.email.orEmpty()}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary,
                )
            }
            TorveGhostButton(
                text = "Sign Out",
                onClick = onSignOut,
                enabled = !isCompleting,
            )
        }

        // Panda-primary hub. Onboarding is now a single decision:
        // "Set up with Panda" (recommended), "Skip for now", or
        // "Configure individual sources" (deep-links to Settings →
        // Integrations post-admission). The legacy four-card hub
        // and seven-step guided wizard were retired 2026-05-03.
        //
        // Helper: deep-link + complete onboarding. Errors land on
        // completionError so the hub banner shows them.
            val deepLinkAndComplete: (DesktopOnboardingDeepLink.Target, SetupIntent) -> Unit =
                { target, intent ->
                    setupWizardCoordinator.beginIntent(intent)
                    onboardingDeepLink.set(target)
                    completionError = null
                    scope.launch {
                        isCompleting = true
                        completionError = onCompleteOnboarding()
                            .exceptionOrNull()
                            ?.message
                        isCompleting = false
                    }
                }
            DesktopSetupIntentHub(
                onSetUpWithPanda = {
                    deepLinkAndComplete(
                        DesktopOnboardingDeepLink.Target.PandaSetup,
                        SetupIntent.USENET,
                    )
                },
                onSkipForNow = {
                    completionError = null
                    scope.launch {
                        isCompleting = true
                        completionError = onCompleteOnboarding()
                            .exceptionOrNull()
                            ?.message
                        isCompleting = false
                    }
                },
                onConfigureSourcesIndividually = {
                    deepLinkAndComplete(
                        DesktopOnboardingDeepLink.Target.Integrations,
                        SetupIntent.PLEX_JELLYFIN,
                    )
                },
                onShowQrReceive = { showQrReceive = true },
                isCompleting = isCompleting,
                completionError = completionError,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .fillMaxWidth(),
            )

            if (showQrReceive) {
                val receiverVm: SecretsTransferReceiverViewModel = remember {
                    org.koin.java.KoinJavaComponent.get(
                        SecretsTransferReceiverViewModel::class.java,
                    )
                }
                Dialog(onDismissRequest = { showQrReceive = false }) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = colors.cardSurface,
                        modifier = Modifier
                            .widthIn(min = 480.dp, max = 720.dp)
                            .padding(8.dp),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "Receive setup from another device",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = colors.textPrimary,
                                    modifier = Modifier.weight(1f),
                                )
                                TorveGhostButton(
                                    text = "Close",
                                    onClick = { showQrReceive = false },
                                )
                            }
                            SecretsTransferReceiveScreen(
                                viewModel = receiverVm,
                                onClose = { showQrReceive = false },
                            )
                        }
                    }
                }
            }

    }
}


/**
 * Renders one row of a device-code OAuth flow: a label, the value
 * itself shown as a monospaced selectable string, and one or more
 * action buttons (Copy link / Open in browser / Copy code).
 *
 * The value background uses the field surface so it's visually
 * distinct from prose copy, signalling "this is a thing you can
 * grab" rather than instructional text. Long URLs wrap rather than
 * truncate so the user can always read the full string.
 */
@Composable
private fun OnboardingDeviceCodeRow(
    label: String,
    value: String,
    actions: @Composable () -> Unit,
) {
    val colors = com.torve.desktop.ui.theme.TorveDesktopThemeTokens.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = colors.textSecondary,
            modifier = Modifier.width(48.dp),
        )
        Surface(
            modifier = Modifier.weight(1f),
            color = colors.fieldSurface,
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, colors.borderSubtle),
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textPrimary,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            actions()
        }
    }
}

/**
 * Best-effort clipboard write. AWT's Toolkit clipboard is the
 * cross-platform path on JVM; same pattern used elsewhere in the
 * desktop shell (Settings → "Copy DSN env var" button).
 */
private fun copyToClipboard(value: String) {
    if (value.isEmpty()) return
    runCatching {
        val sel = java.awt.datatransfer.StringSelection(value)
        java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(sel, sel)
    }
}

/**
 * Opens [url] in the user's default browser via AWT Desktop. No-op
 * on platforms / sessions where Desktop.browse isn't supported
 * (some headless / sandboxed environments). Failure is silent — the
 * user still has the URL on screen with a Copy button.
 */
private fun openInBrowser(url: String) {
    if (url.isEmpty()) return
    runCatching {
        java.awt.Desktop.getDesktop().browse(java.net.URI(url))
    }
}
