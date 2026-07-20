package com.torve.android.tv.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.torve.android.R
import com.torve.android.ui.panda.PandaAuthStep
import com.torve.android.ui.panda.LocalPandaTvClickToEditFields
import com.torve.android.ui.panda.PandaProviderStep
import com.torve.android.ui.panda.PandaQualityStep
import com.torve.android.ui.panda.PandaReviewStep
import com.torve.android.ui.panda.PandaSourcesStep
import com.torve.android.ui.panda.PandaSetupTypeStep
import com.torve.android.ui.panda.PandaUsenetStep
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.Gunmetal
import com.torve.android.ui.theme.Obsidian
import com.torve.android.ui.theme.Snow
import com.torve.presentation.panda.PandaSetupStep
import com.torve.presentation.panda.PandaSetupViewModel
import com.torve.presentation.panda.progressStepCount
import com.torve.presentation.panda.progressStepNumber
import org.koin.compose.koinInject

@Composable
fun TvPandaSetupScreen(
    onBack: () -> Unit,
    onComplete: () -> Unit,
    viewModel: PandaSetupViewModel = koinInject(),
) {
    val state by viewModel.state.collectAsState()

    BackHandler(enabled = state.currentStep != PandaSetupStep.SETUP_TYPE) {
        viewModel.previousStep()
    }

    val stepNumber = state.progressStepNumber()
    val totalSteps = state.progressStepCount()
    val stepEntryFocusRequester = remember { FocusRequester() }
    val closeButtonFocusRequester = remember { FocusRequester() }
    val backButtonFocusRequester = remember { FocusRequester() }
    val nextButtonFocusRequester = remember { FocusRequester() }
    val bottomExitButtonFocusRequester = remember { FocusRequester() }
    val canAdvance = when (state.currentStep) {
        PandaSetupStep.SETUP_TYPE,
        PandaSetupStep.PROVIDER -> false
        PandaSetupStep.AUTH -> state.authConnected
        PandaSetupStep.SOURCES,
        PandaSetupStep.USENET,
        PandaSetupStep.QUALITY -> true
        PandaSetupStep.REVIEW -> false
    }

    LaunchedEffect(state.currentStep, canAdvance, state.addonInstalled, state.isSaving) {
        // Focus must land on an actual focus target. The old implementation
        // requested focus on the content container, which is not itself a TV
        // control and could leave the setup flow without a usable focus owner.
        repeat(6) { attempt ->
            withFrameNanos { }
            if (runCatching { stepEntryFocusRequester.requestFocus() }.isSuccess) {
                return@LaunchedEffect
            }
            if (canAdvance && runCatching { nextButtonFocusRequester.requestFocus() }.isSuccess) {
                return@LaunchedEffect
            }
            if (state.currentStep == PandaSetupStep.REVIEW &&
                attempt >= 2 &&
                runCatching { bottomExitButtonFocusRequester.requestFocus() }.isSuccess
            ) {
                return@LaunchedEffect
            }
            if (attempt >= 2 && runCatching { closeButtonFocusRequester.requestFocus() }.isSuccess) {
                return@LaunchedEffect
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Obsidian),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 48.dp, vertical = 32.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.panda_setup_title),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = Snow,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "$stepNumber / $totalSteps",
                    style = MaterialTheme.typography.titleMedium,
                    color = Amber,
                    modifier = Modifier.padding(end = 16.dp),
                )
                // Top-right exit out of the wizard. The bottom-row
                // back button only walks one step at a time and the
                // user previously had no obvious way to bail without
                // pressing the system back button repeatedly.
                OutlinedButton(
                    onClick = onBack,
                    modifier = Modifier.focusRequester(closeButtonFocusRequester),
                ) {
                    Icon(Icons.Filled.Close, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.tv_panda_close_setup))
                }
            }

            Spacer(Modifier.height(16.dp))
            LinearProgressIndicator(
                progress = { stepNumber.toFloat() / totalSteps },
                modifier = Modifier.fillMaxWidth(),
                color = Amber,
            )
            Spacer(Modifier.height(24.dp))

            // Some step screens (PandaSourcesStep, PandaUsenetStep)
            // host their own LazyColumn for indexer rows. Wrapping them
            // in a verticalScroll meant those LazyColumns saw an
            // infinite max-height and crashed with "Vertically
            // scrollable component was measured with an infinity
            // maximum height constraints". Drop the outer scroll —
            // the Box's weight(1f) bounds the height already, and the
            // step screens that need scrolling provide their own.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                CompositionLocalProvider(LocalPandaTvClickToEditFields provides true) {
                    when (state.currentStep) {
                        PandaSetupStep.SETUP_TYPE -> PandaSetupTypeStep(
                            state = state,
                            viewModel = viewModel,
                            entryFocusRequester = stepEntryFocusRequester,
                        )
                        PandaSetupStep.PROVIDER -> PandaProviderStep(
                            state = state,
                            viewModel = viewModel,
                            entryFocusRequester = stepEntryFocusRequester,
                        )
                        PandaSetupStep.AUTH -> PandaAuthStep(
                            state = state,
                            viewModel = viewModel,
                            entryFocusRequester = stepEntryFocusRequester,
                        )
                        PandaSetupStep.SOURCES -> PandaSourcesStep(
                            state = state,
                            viewModel = viewModel,
                            entryFocusRequester = stepEntryFocusRequester,
                        )
                        PandaSetupStep.USENET -> PandaUsenetStep(
                            state = state,
                            viewModel = viewModel,
                            entryFocusRequester = stepEntryFocusRequester,
                        )
                        PandaSetupStep.QUALITY -> PandaQualityStep(
                            state = state,
                            viewModel = viewModel,
                            entryFocusRequester = stepEntryFocusRequester,
                        )
                        PandaSetupStep.REVIEW -> PandaReviewStep(
                            state = state,
                            viewModel = viewModel,
                            onComplete = onComplete,
                            entryFocusRequester = stepEntryFocusRequester,
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TvPandaOutlinedNavButton(
                    text = stringResource(R.string.common_back),
                    onClick = {
                        if (state.currentStep == PandaSetupStep.SETUP_TYPE) onBack()
                        else viewModel.previousStep()
                    },
                    focusRequester = backButtonFocusRequester,
                    leadingIcon = {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, Modifier.size(20.dp))
                    },
                )

                when {
                    canAdvance -> {
                        TvPandaPrimaryNavButton(
                            text = stringResource(R.string.panda_setup_next),
                            onClick = { viewModel.nextStep() },
                            focusRequester = nextButtonFocusRequester,
                            trailingIcon = {
                                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(20.dp))
                            },
                        )
                    }
                    state.currentStep == PandaSetupStep.REVIEW -> {
                        TvPandaOutlinedNavButton(
                            text = stringResource(R.string.tv_panda_exit_setup),
                            onClick = onBack,
                            focusRequester = bottomExitButtonFocusRequester,
                            leadingIcon = {
                                Icon(Icons.Filled.Close, null, Modifier.size(18.dp))
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TvPandaPrimaryNavButton(
    text: String,
    onClick: () -> Unit,
    focusRequester: FocusRequester,
    trailingIcon: @Composable (() -> Unit)? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(12.dp)
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Obsidian),
        shape = shape,
        interactionSource = interactionSource,
        modifier = Modifier
            .focusRequester(focusRequester)
            .clip(shape)
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) Snow else Amber.copy(alpha = 0.35f),
                shape = shape,
            ),
    ) {
        Text(text, fontWeight = FontWeight.SemiBold)
        trailingIcon?.let {
            Spacer(Modifier.width(8.dp))
            it()
        }
    }
}

@Composable
private fun TvPandaOutlinedNavButton(
    text: String,
    onClick: () -> Unit,
    focusRequester: FocusRequester,
    leadingIcon: @Composable (() -> Unit)? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(12.dp)
    OutlinedButton(
        onClick = onClick,
        shape = shape,
        border = null,
        interactionSource = interactionSource,
        modifier = Modifier
            .focusRequester(focusRequester)
            .clip(shape)
            .background(
                color = if (focused) Gunmetal else Obsidian,
                shape = shape,
            )
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) Snow else Amber.copy(alpha = 0.45f),
                shape = shape,
            ),
    ) {
        leadingIcon?.let {
            it()
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = if (focused) Snow else Amber, fontWeight = FontWeight.SemiBold)
    }
}
