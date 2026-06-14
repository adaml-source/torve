package com.torve.android.ui.panda

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.torve.android.BuildConfig
import com.torve.android.R
import com.torve.android.ui.components.BackButton
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.Silver
import com.torve.android.ui.theme.Snow
import com.torve.presentation.panda.PandaSetupStep
import com.torve.presentation.panda.PandaSetupViewModel
import com.torve.presentation.panda.progressStepCount
import com.torve.presentation.panda.progressStepNumber
import org.koin.compose.koinInject

@Composable
fun PandaSetupScreen(
    onBack: () -> Unit,
    onComplete: () -> Unit,
    viewModel: PandaSetupViewModel = koinInject(),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val pandaConfigUrl = remember { "${BuildConfig.PANDA_BASE_URL.trimEnd('/')}/configure" }

    // Intercept system back: go one step back within the wizard, or exit on first step
    BackHandler(enabled = state.currentStep != PandaSetupStep.SETUP_TYPE) {
        viewModel.previousStep()
    }

    val stepNumber = state.progressStepNumber()
    val totalSteps = state.progressStepCount()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .padding(horizontal = 20.dp),
    ) {
        // Header
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BackButton(onClick = {
                if (state.currentStep == PandaSetupStep.SETUP_TYPE) onBack() else viewModel.previousStep()
            })
            Spacer(Modifier.width(12.dp))
            Text(
                stringResource(R.string.panda_setup_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = Snow,
            )
        }

        // Edit mode banner
        if (state.isEditMode) {
            Text(
                stringResource(R.string.panda_setup_editing),
                style = MaterialTheme.typography.bodySmall,
                color = Amber,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        // Progress
        LinearProgressIndicator(
            progress = { stepNumber.toFloat() / totalSteps },
            modifier = Modifier.fillMaxWidth(),
            color = Amber,
        )
        Spacer(Modifier.height(16.dp))

        // Step content
        AnimatedContent(
            targetState = state.currentStep,
            modifier = Modifier.weight(1f),
            transitionSpec = {
                slideInHorizontally { it } togetherWith slideOutHorizontally { -it }
            },
            label = "panda_step",
        ) { step ->
            when (step) {
                PandaSetupStep.SETUP_TYPE -> PandaSetupTypeStep(state, viewModel)
                PandaSetupStep.PROVIDER -> PandaProviderStep(state, viewModel)
                PandaSetupStep.AUTH -> PandaAuthStep(state, viewModel)
                PandaSetupStep.SOURCES -> PandaSourcesStep(state, viewModel)
                PandaSetupStep.USENET -> PandaUsenetStep(state, viewModel)
                PandaSetupStep.QUALITY -> PandaQualityStep(state, viewModel)
                PandaSetupStep.REVIEW -> PandaReviewStep(state, viewModel, onComplete)
            }
        }

        // Bottom bar: navigation + web fallback
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.currentStep != PandaSetupStep.SETUP_TYPE) {
                    OutlinedButton(onClick = { viewModel.previousStep() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.common_back))
                    }
                } else {
                    Spacer(Modifier.width(1.dp))
                }

                if (state.currentStep in listOf(PandaSetupStep.SOURCES, PandaSetupStep.USENET, PandaSetupStep.QUALITY) ||
                    (state.currentStep == PandaSetupStep.AUTH && state.authConnected)
                ) {
                    Button(onClick = { viewModel.nextStep() }) {
                        Text(stringResource(R.string.panda_setup_next))
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(18.dp))
                    }
                } else {
                    Spacer(Modifier.width(1.dp))
                }
            }

            // Web fallback
            TextButton(
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(pandaConfigUrl))
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                },
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Text(
                    stringResource(R.string.panda_setup_web_fallback),
                    color = Silver,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
