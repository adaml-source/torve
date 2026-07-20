package com.torve.desktop.ui.onboarding

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.torve.desktop.DesktopReleaseInfo
import com.torve.desktop.auth.DesktopAuthController
import com.torve.desktop.auth.DesktopAuthPhase
import com.torve.desktop.auth.DesktopAuthUiState
import com.torve.desktop.ui.theme.TorveDesktopThemeTokens

private enum class AuthEntryMode { SIGN_IN, REGISTER }

private data class FeatureItem(
    val assetPath: String,
    val label: String,
    val width: Dp,
    val height: Dp,
)

private val Gold = Color(0xFFF0B747)
private val GoldSoft = Color(0xFFF1C15A)
private val GoldDeep = Color(0xFFD79631)
private val FeatureGoldLight = Color(0xFFF5C663)

private val Features = listOf(
    FeatureItem("assets/aisearch.png", "AI Search", 60.dp, 60.dp),
    FeatureItem("assets/addon.png", "Addons", 62.dp, 62.dp),
    FeatureItem("assets/IPTV.png", "IPTV", 62.dp, 62.dp),
    FeatureItem("assets/crossdevice.png", "Cross-device\nsync", 64.dp, 60.dp),
)

@Composable
fun DesktopPremiumAuthScreen(
    authState: DesktopAuthUiState,
    authController: DesktopAuthController,
    releaseInfo: DesktopReleaseInfo,
    onExit: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF03070E))
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyDown && keyEvent.key == Key.Escape) {
                    onExit()
                    true
                } else {
                    false
                }
            },
    ) {
        TorveAuthBackground(modifier = Modifier.fillMaxSize())

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val scale = minOf(maxWidth.value / 1920f, maxHeight.value / 1080f)
                .coerceIn(0.68f, 1.0f)
            val showHero = maxWidth.value >= 1180f
            var entryMode by remember { mutableStateOf(AuthEntryMode.SIGN_IN) }
            val isBusy = authState.phase == DesktopAuthPhase.LOADING ||
                authState.phase == DesktopAuthPhase.RESTORING_SESSION

            if (showHero) {
                LeftHero(
                    scale = scale,
                    modifier = Modifier
                        .offset(x = scaledDp(118f, scale), y = scaledDp(190f, scale))
                        .size(width = scaledDp(720f, scale), height = scaledDp(790f, scale)),
                )

                AuthCardContent(
                    authState = authState,
                    authController = authController,
                    entryMode = entryMode,
                    onChangeMode = { entryMode = it },
                    isBusy = isBusy,
                    onExit = onExit,
                    scale = scale,
                    modifier = Modifier
                        .offset(x = scaledDp(955f, scale), y = scaledDp(100f, scale))
                        .size(
                            width = scaledDp(740f, scale),
                            height = scaledDp(if (entryMode == AuthEntryMode.REGISTER) 880f else 830f, scale),
                        ),
                )
            } else {
                AuthCardContent(
                    authState = authState,
                    authController = authController,
                    entryMode = entryMode,
                    onChangeMode = { entryMode = it },
                    isBusy = isBusy,
                    onExit = onExit,
                    scale = scale,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = scaledDp(24f, scale))
                        .size(
                            width = scaledDp(740f, scale),
                            height = scaledDp(if (entryMode == AuthEntryMode.REGISTER) 880f else 830f, scale),
                        ),
                )
            }

            Footer(
                releaseInfo = releaseInfo,
                scale = scale,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
fun TorveAuthBackground(modifier: Modifier = Modifier) {
    Box(modifier = modifier.background(Color(0xFF03070E))) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            Image(
                painter = painterResource("horizonglow.png"),
                contentDescription = null,
                modifier = Modifier
                    .offset(x = (-40).dp)
                    .size(width = maxWidth + 80.dp, height = maxHeight),
                alignment = Alignment.Center,
                contentScale = ContentScale.Crop,
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(maxHeight * 0.30f)
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.18f),
                        ),
                    ),
            )
        }
    }
}

@Composable
private fun LeftHero(
    scale: Float,
    modifier: Modifier = Modifier,
) {
    val colors = TorveDesktopThemeTokens.colors

    Box(modifier = modifier) {
        Image(
            painter = painterResource("torve_wordmark.png"),
            contentDescription = "Torve",
            modifier = Modifier
                .offset(x = scaledDp(0f, scale), y = scaledDp(0f, scale))
                .size(width = scaledDp(340f, scale), height = scaledDp(95f, scale)),
            contentScale = ContentScale.Fit,
        )

        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(color = Color(0xFFE0E7F2).copy(alpha = 0.88f))) {
                    append("Your media universe,\n")
                }
                withStyle(SpanStyle(color = Color(0xFFEFB747))) {
                    append("unified.")
                }
            },
            style = MaterialTheme.typography.displayMedium.copy(
                fontSize = scaledSp(52f, scale),
                lineHeight = scaledSp(59f, scale),
                fontWeight = FontWeight.Light,
            ),
            modifier = Modifier.offset(x = scaledDp(7f, scale), y = scaledDp(145f, scale)),
        )

        Text(
            text = "Sign in to continue watching, searching,\nand managing your Torve setup across\nall your devices.",
            style = MaterialTheme.typography.bodyLarge.copy(
                fontSize = scaledSp(22f, scale),
                lineHeight = scaledSp(32f, scale),
                fontWeight = FontWeight.Normal,
            ),
            color = Color(0xFFAAB4C7),
            modifier = Modifier
                .offset(x = scaledDp(10f, scale), y = scaledDp(285f, scale))
                .widthIn(max = scaledDp(520f, scale)),
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(scaledDp(56f, scale)),
            modifier = Modifier.offset(x = scaledDp(12f, scale), y = scaledDp(400f, scale)),
        ) {
            Features.forEach { feature ->
                FeatureCard(
                    feature = feature,
                    scale = scale,
                )
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(scaledDp(14f, scale)),
            modifier = Modifier.offset(x = scaledDp(10f, scale), y = scaledDp(710f, scale)),
        ) {
            Icon(
                imageVector = Icons.Filled.Shield,
                contentDescription = null,
                tint = Color(0xFFC8D2E4),
                modifier = Modifier.size(scaledDp(36f, scale)),
            )
            Column(verticalArrangement = Arrangement.spacedBy(scaledDp(4f, scale))) {
                Text(
                    text = "Secure. Private. Yours.",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = scaledSp(16f, scale),
                        lineHeight = scaledSp(20f, scale),
                        fontWeight = FontWeight.Bold,
                    ),
                    color = Color(0xFFF4F6FA),
                )
                Text(
                    text = "Your data is encrypted and never shared.",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = scaledSp(15f, scale),
                        lineHeight = scaledSp(20f, scale),
                    ),
                    color = colors.textSecondary.copy(alpha = 0.88f),
                )
            }
        }
    }
}

@Composable
private fun FeatureCard(
    feature: FeatureItem,
    scale: Float,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(scaledDp(16f, scale)),
        modifier = Modifier.width(scaledDp(102f, scale)),
    ) {
        val shape = RoundedCornerShape(scaledDp(16f, scale))
        Box(
            modifier = Modifier
                .size(scaledDp(88f, scale))
                .shadow(
                    elevation = scaledDp(18f, scale),
                    shape = shape,
                    clip = false,
                )
                .background(Color.White.copy(alpha = 0.045f), shape)
                .border(BorderStroke(scaledDp(1f, scale), Color.White.copy(alpha = 0.065f)), shape),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(feature.assetPath),
                contentDescription = feature.label.replace("\n", " "),
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(
                    width = feature.width * scale,
                    height = feature.height * scale,
                ),
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(scaledDp(1f, scale))
                    .background(FeatureGoldLight.copy(alpha = 0.05f)),
            )
        }
        Text(
            text = feature.label,
            style = MaterialTheme.typography.labelLarge.copy(
                fontSize = scaledSp(17f, scale),
                lineHeight = scaledSp(22f, scale),
                fontWeight = FontWeight.SemiBold,
            ),
            color = Color(0xFFF4F6FA),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun AuthCardContent(
    authState: DesktopAuthUiState,
    authController: DesktopAuthController,
    entryMode: AuthEntryMode,
    onChangeMode: (AuthEntryMode) -> Unit,
    isBusy: Boolean,
    onExit: () -> Unit,
    scale: Float,
    modifier: Modifier = Modifier,
) {
    val colors = TorveDesktopThemeTokens.colors
    val shape = RoundedCornerShape(scaledDp(30f, scale))
    val isRegister = entryMode == AuthEntryMode.REGISTER
    val cardTopPadding = if (isRegister) 50f else 70f
    val logoTitleGap = if (isRegister) 37f else 35f
    val afterSubtitleGap = if (isRegister) 36f else 24f
    val labelBottomGap = if (isRegister) 10f else 12f
    val fieldGap = if (isRegister) 18f else 24f
    val inputHeight = if (isRegister) 64f else 66f
    val primaryHeight = 66f
    val secondaryHeight = if (isRegister) 62f else 66f
    val passwordToPrimaryGap = if (isRegister) 28f else 28f
    val primaryToDividerGap = if (isRegister) 30f else 30f
    val dividerToSecondaryGap = if (isRegister) 22f else 22f
    val bottomPadding = if (isRegister) 52f else 52f

    Box(
        modifier = modifier
            .shadow(
                elevation = scaledDp(44f, scale),
                shape = shape,
                clip = false,
            )
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color(0xD1111928),
                        Color(0xE6080D18),
                    ),
                ),
                shape = shape,
            )
            .border(BorderStroke(scaledDp(1f, scale), Color(0x387D91B4)), shape),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(scaledDp(1f, scale))
                .background(Color.White.copy(alpha = 0.045f)),
        )

        ExitButton(
            onClick = onExit,
            scale = scale,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = scaledDp(24f, scale), end = scaledDp(24f, scale)),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = scaledDp(68f, scale),
                    top = scaledDp(cardTopPadding, scale),
                    end = scaledDp(68f, scale),
                    bottom = 0.dp,
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(
                painter = painterResource("torve_wordmark.png"),
                contentDescription = "Torve",
                modifier = Modifier.size(width = scaledDp(310f, scale), height = scaledDp(87f, scale)),
                contentScale = ContentScale.Fit,
            )

            Spacer(Modifier.height(scaledDp(logoTitleGap, scale)))

            Text(
                text = if (entryMode == AuthEntryMode.SIGN_IN) "Welcome back" else "Create your account",
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontSize = scaledSp(34f, scale),
                    lineHeight = scaledSp(41f, scale),
                    fontWeight = FontWeight.Bold,
                ),
                color = Color(0xFFF4F7FB),
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(scaledDp(10f, scale)))

            Text(
                text = if (entryMode == AuthEntryMode.SIGN_IN) {
                    "Sign in to continue to Torve."
                } else {
                    "Save your library and sync settings across every device."
                },
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = scaledSp(19f, scale),
                    lineHeight = scaledSp(27f, scale),
                    fontWeight = FontWeight.Normal,
                ),
                color = Color(0xFFA8B2C4),
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(scaledDp(afterSubtitleGap, scale)))

            if (isRegister) {
                AuthFieldLabel("Display name", scale)
                Spacer(Modifier.height(scaledDp(labelBottomGap, scale)))
                AuthInput(
                    value = authState.displayName,
                    onValueChange = authController::updateDisplayName,
                    placeholder = "Your name",
                    leadingIcon = Icons.Filled.Person,
                    height = inputHeight,
                    scale = scale,
                )
                Spacer(Modifier.height(scaledDp(fieldGap, scale)))
            }

            AuthFieldLabel("Email", scale)
            Spacer(Modifier.height(scaledDp(labelBottomGap, scale)))
            AuthInput(
                value = authState.email,
                onValueChange = authController::updateEmail,
                placeholder = "Enter your email",
                leadingIcon = Icons.Filled.Email,
                keyboardType = KeyboardType.Email,
                height = inputHeight,
                scale = scale,
            )

            Spacer(Modifier.height(scaledDp(fieldGap, scale)))

            AuthFieldLabel("Password", scale)
            Spacer(Modifier.height(scaledDp(labelBottomGap, scale)))
            var passwordVisible by remember { mutableStateOf(false) }
            AuthInput(
                value = authState.password,
                onValueChange = authController::updatePassword,
                placeholder = "Enter your password",
                leadingIcon = Icons.Filled.Lock,
                keyboardType = KeyboardType.Password,
                visualTransformation = if (passwordVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            imageVector = if (passwordVisible) {
                                Icons.Filled.VisibilityOff
                            } else {
                                Icons.Filled.Visibility
                            },
                            contentDescription = if (passwordVisible) "Hide password" else "Show password",
                            tint = Color(0xFFB5BED0),
                            modifier = Modifier.size(scaledDp(22f, scale)),
                        )
                    }
                },
                onSubmit = {
                    if (entryMode == AuthEntryMode.REGISTER) authController.register()
                    else authController.signIn()
                },
                height = inputHeight,
                scale = scale,
            )

            if (entryMode == AuthEntryMode.SIGN_IN) {
                Spacer(Modifier.height(scaledDp(16f, scale)))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TorveAuthLinkButton(
                        text = "Forgot password?",
                        onClick = { /* recovery flow not wired yet */ },
                        scale = scale,
                    )
                }
            }

            authState.authError?.takeIf { it.isNotBlank() }?.let { message ->
                Spacer(Modifier.height(scaledDp(10f, scale)))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = scaledSp(14f, scale),
                        lineHeight = scaledSp(18f, scale),
                    ),
                    color = colors.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(scaledDp(passwordToPrimaryGap, scale)))

            GoldCtaButton(
                text = when {
                    isBusy -> "Working"
                    entryMode == AuthEntryMode.REGISTER -> "Create account"
                    else -> "Sign in"
                },
                enabled = !isBusy,
                onClick = {
                    if (authState.email.isNotBlank() && authState.password.isNotBlank()) {
                        if (entryMode == AuthEntryMode.REGISTER) authController.register()
                        else authController.signIn()
                    }
                },
                height = primaryHeight,
                scale = scale,
            )

            Spacer(Modifier.height(scaledDp(primaryToDividerGap, scale)))

            DividerWithLabel(label = "or", scale = scale)

            Spacer(Modifier.height(scaledDp(dividerToSecondaryGap, scale)))

            SecondaryAccountAction(
                inviteText = if (entryMode == AuthEntryMode.SIGN_IN) {
                    "New to Torve?"
                } else {
                    "Already have an account?"
                },
                actionText = if (entryMode == AuthEntryMode.SIGN_IN) "Create account" else "Sign in",
                enabled = !isBusy,
                onClick = {
                    onChangeMode(
                        if (entryMode == AuthEntryMode.SIGN_IN) AuthEntryMode.REGISTER
                        else AuthEntryMode.SIGN_IN,
                    )
                },
                height = secondaryHeight,
                scale = scale,
            )

            Spacer(Modifier.height(scaledDp(bottomPadding, scale)))

            if (isBusy) {
                Spacer(Modifier.height(scaledDp(14f, scale)))
                CircularProgressIndicator(
                    modifier = Modifier.size(scaledDp(18f, scale)),
                    strokeWidth = scaledDp(2f, scale),
                    color = Gold,
                )
            }
        }
    }
}

@Composable
private fun ExitButton(
    onClick: () -> Unit,
    scale: Float,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val focused by interactionSource.collectIsFocusedAsState()
    val pressed by interactionSource.collectIsPressedAsState()
    val shape = RoundedCornerShape(scaledDp(999f, scale))
    val active = hovered || focused
    val background = when {
        pressed -> Color.White.copy(alpha = 0.10f)
        active -> Color.White.copy(alpha = 0.075f)
        else -> Color.White.copy(alpha = 0.035f)
    }
    val borderColor = when {
        focused -> Gold.copy(alpha = 0.32f)
        else -> Color(0xFFA0AFCD).copy(alpha = 0.14f)
    }
    val iconColor = if (active || pressed) {
        Color(0xFFF5F8FF).copy(alpha = 0.88f)
    } else {
        Color(0xFFCDD7E8).copy(alpha = 0.62f)
    }
    Box(
        modifier = modifier
            .size(scaledDp(40f, scale))
            .background(color = background, shape = shape)
            .border(BorderStroke(scaledDp(1f, scale), borderColor), shape)
            .hoverable(interactionSource)
            .focusable(interactionSource = interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Close,
            contentDescription = "Quit app",
            tint = iconColor,
            modifier = Modifier.size(scaledDp(20f, scale)),
        )
    }
}

@Composable
private fun AuthFieldLabel(
    text: String,
    scale: Float,
) {
    Box(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge.copy(
                fontSize = scaledSp(15f, scale),
                lineHeight = scaledSp(18f, scale),
                fontWeight = FontWeight.SemiBold,
            ),
            color = Color(0xFFC6CEDB),
        )
    }
}

@Composable
private fun AuthInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    leadingIcon: ImageVector,
    scale: Float,
    height: Float = 66f,
    keyboardType: KeyboardType = KeyboardType.Text,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: (@Composable () -> Unit)? = null,
    onSubmit: (() -> Unit)? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(scaledDp(14f, scale))
    val focusGlow = if (focused) Gold.copy(alpha = 0.08f) else Color.Transparent
    val keyHandler = if (onSubmit != null) {
        Modifier.onPreviewKeyEvent { keyEvent ->
            if (keyEvent.type == KeyEventType.KeyDown && keyEvent.key == Key.Enter) {
                onSubmit()
                true
            } else {
                false
            }
        }
    } else {
        Modifier
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(scaledDp(height, scale))
            .background(focusGlow, shape)
            .padding(if (focused) scaledDp(1f, scale) else 0.dp),
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxSize()
                .then(keyHandler),
            singleLine = true,
            interactionSource = interactionSource,
            placeholder = {
                Text(
                    text = placeholder,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = scaledSp(18f, scale),
                        lineHeight = scaledSp(22f, scale),
                    ),
                    color = Color(0xFF8F9BB2),
                )
            },
            leadingIcon = {
                Box(
                    modifier = Modifier.width(scaledDp(58f, scale)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = leadingIcon,
                        contentDescription = null,
                        tint = Color(0xFF94A0B5),
                        modifier = Modifier.size(scaledDp(21f, scale)),
                    )
                }
            },
            trailingIcon = trailingIcon,
            shape = shape,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            visualTransformation = visualTransformation,
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                fontSize = scaledSp(18f, scale),
                lineHeight = scaledSp(22f, scale),
                color = Color(0xFFF4F6FA),
            ),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color(0x8A060C18),
                unfocusedContainerColor = Color(0x8A060C18),
                disabledContainerColor = Color(0x8A060C18),
                focusedTextColor = Color(0xFFF4F6FA),
                unfocusedTextColor = Color(0xFFF4F6FA),
                cursorColor = Gold,
                focusedIndicatorColor = Color(0xFFE0A83F),
                unfocusedIndicatorColor = Color(0x5C8296B4),
                disabledIndicatorColor = Color(0x338296B4),
                focusedLeadingIconColor = Color(0xFF94A0B5),
                unfocusedLeadingIconColor = Color(0xFF94A0B5),
                focusedTrailingIconColor = Color(0xFFB5BED0),
                unfocusedTrailingIconColor = Color(0xFFB5BED0),
            ),
        )
    }
}

@Composable
private fun GoldCtaButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    scale: Float,
    height: Float = 66f,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val shape = RoundedCornerShape(scaledDp(14f, scale))
    val brush = Brush.verticalGradient(
        colors = if (hovered && enabled) {
            listOf(Color(0xFFF6D072), Color(0xFFE0A544))
        } else {
            listOf(GoldSoft, GoldDeep)
        },
    )
    val content = Color(0xFF151009)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(scaledDp(height, scale))
            .shadow(
                elevation = scaledDp(18f, scale),
                shape = shape,
                clip = false,
            )
            .clip(shape)
            .background(brush, shape)
            .hoverable(interactionSource)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = scaledSp(18f, scale),
                    lineHeight = scaledSp(22f, scale),
                    fontWeight = FontWeight.Bold,
                ),
                color = content,
            )
            Spacer(Modifier.width(scaledDp(16f, scale)))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(scaledDp(18f, scale)),
            )
        }
    }
}

@Composable
private fun DividerWithLabel(
    label: String,
    scale: Float,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(scaledDp(16f, scale)),
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .height(scaledDp(1f, scale))
                .background(Color(0x2E8296B4)),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = scaledSp(16f, scale),
                lineHeight = scaledSp(20f, scale),
            ),
            color = Color(0xFFA4AEC0),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(scaledDp(1f, scale))
                .background(Color(0x2E8296B4)),
        )
    }
}

@Composable
private fun SecondaryAccountAction(
    inviteText: String,
    actionText: String,
    enabled: Boolean,
    onClick: () -> Unit,
    scale: Float,
    height: Float = 66f,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val shape = RoundedCornerShape(scaledDp(14f, scale))
    val background = if (hovered) Color.White.copy(alpha = 0.045f) else Color.White.copy(alpha = 0.025f)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(scaledDp(height, scale))
            .background(background, shape)
            .border(BorderStroke(scaledDp(1f, scale), Color(0x388296B4)), shape)
            .hoverable(interactionSource)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Person,
                contentDescription = null,
                tint = Color(0xFFB5BED0),
                modifier = Modifier.size(scaledDp(20f, scale)),
            )
            Spacer(Modifier.width(scaledDp(12f, scale)))
            Text(
                text = inviteText,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = scaledSp(18f, scale),
                    lineHeight = scaledSp(22f, scale),
                    fontWeight = FontWeight.Normal,
                ),
                color = Color(0xFFB5BED0),
            )
            Spacer(Modifier.width(scaledDp(7f, scale)))
            Text(
                text = actionText,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = scaledSp(18f, scale),
                    lineHeight = scaledSp(22f, scale),
                    fontWeight = FontWeight.Bold,
                ),
                color = Gold,
            )
        }
    }
}

@Composable
fun TorveAuthLinkButton(
    text: String,
    onClick: () -> Unit,
    scale: Float = 1f,
    enabled: Boolean = true,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val color = when {
        !enabled -> Color(0xFF4A546A)
        hovered -> Color(0xFFF4C965)
        else -> Gold
    }

    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium.copy(
            fontSize = scaledSp(16f, scale),
            lineHeight = scaledSp(20f, scale),
            fontWeight = FontWeight.Bold,
            textDecoration = if (hovered) TextDecoration.Underline else TextDecoration.None,
        ),
        color = color,
        modifier = Modifier
            .hoverable(interactionSource)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = scaledDp(2f, scale), horizontal = scaledDp(2f, scale)),
    )
}

@Composable
private fun Footer(
    releaseInfo: DesktopReleaseInfo,
    scale: Float,
    modifier: Modifier = Modifier,
) {
    val versionLabel = releaseInfo.versionLabel
        .removePrefix("Version ")
        .trim()
    val footerStyle = MaterialTheme.typography.labelSmall.copy(
        fontSize = scaledSp(14f, scale),
        lineHeight = scaledSp(18f, scale),
        fontWeight = FontWeight.Normal,
    )
    val footerColor = Color(0xFF96A0B4).copy(alpha = 0.52f)

    Box(modifier = modifier) {
        Text(
            text = "© 2026 Torve. AGPL-3.0-or-later.",
            style = footerStyle,
            color = footerColor,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = scaledDp(34f, scale)),
        )
        Text(
            text = "Version $versionLabel • ${releaseInfo.channel}",
            style = footerStyle,
            color = footerColor,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = scaledDp(128f, scale), bottom = scaledDp(34f, scale)),
        )
    }
}

private fun scaledDp(value: Float, scale: Float) = (value * scale).dp

private fun scaledSp(value: Float, scale: Float) = (value * scale).sp
