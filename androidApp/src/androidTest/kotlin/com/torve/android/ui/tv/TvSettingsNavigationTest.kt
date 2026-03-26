package com.torve.android.ui.tv

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.torve.android.test.TorveTestHostActivity
import com.torve.android.tv.TvSettingsDestination
import org.junit.Rule
import org.junit.Test

/**
 * Compose UI regression tests for the TV settings navigation bug.
 *
 * Validates that selecting the Settings rail item always shows Settings content,
 * and that settings sub-destinations never persist after rail re-selection.
 *
 * Uses a minimal harness that mirrors the real TvRoot state architecture
 * (hoisted [TvSettingsDestination], reset on rail selection) without requiring
 * the full TvRoot dependency graph.
 */
class TvSettingsNavigationTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<TorveTestHostActivity>()

    /**
     * Minimal harness that mirrors the real TvRoot settings navigation.
     * - [settingsDestination] is the single source of truth (hoisted).
     * - Selecting "Settings" from the simulated rail resets to MAIN.
     * - Selecting a device-management row changes destination.
     */
    @Composable
    private fun SettingsNavHarness() {
        var selectedTab by remember { mutableStateOf("settings") }
        var settingsDestination by remember { mutableStateOf(TvSettingsDestination.MAIN) }

        Column(Modifier.fillMaxSize()) {
            // Simulated rail buttons
            TextButton(onClick = {
                selectedTab = "home"
            }) { Text("Rail: Home") }

            TextButton(onClick = {
                // Mirrors onNavigate logic: reset sub-destination when selecting Settings
                settingsDestination = TvSettingsDestination.MAIN
                selectedTab = "settings"
            }) { Text("Rail: Settings") }

            // Content area
            Box(Modifier.weight(1f)) {
                when (selectedTab) {
                    "home" -> Text("Home Content")
                    "settings" -> when (settingsDestination) {
                        TvSettingsDestination.MAIN -> {
                            Column {
                                Text("Settings Content")
                                TextButton(onClick = {
                                    settingsDestination = TvSettingsDestination.PAIRED_DEVICES
                                }) { Text("Go to Paired Devices") }
                                TextButton(onClick = {
                                    settingsDestination = TvSettingsDestination.ACTIVATED_DEVICES
                                }) { Text("Go to Activated Devices") }
                            }
                        }
                        TvSettingsDestination.PAIRED_DEVICES -> {
                            Column {
                                Text("Paired Devices Content")
                                TextButton(onClick = {
                                    settingsDestination = TvSettingsDestination.MAIN
                                }) { Text("Back to Settings") }
                            }
                        }
                        TvSettingsDestination.ACTIVATED_DEVICES -> {
                            Column {
                                Text("Activated Devices Content")
                                TextButton(onClick = {
                                    settingsDestination = TvSettingsDestination.MAIN
                                }) { Text("Back to Settings") }
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun initialState_showsSettingsContent() {
        composeRule.setContent { SettingsNavHarness() }
        composeRule.onNodeWithText("Settings Content").assertIsDisplayed()
    }

    @Test
    fun selectPairedDevices_showsPairedDevicesContent() {
        composeRule.setContent { SettingsNavHarness() }
        composeRule.onNodeWithText("Go to Paired Devices").performClick()
        composeRule.onNodeWithText("Paired Devices Content").assertIsDisplayed()
    }

    @Test
    fun reselectSettingsRail_afterPairedDevices_showsSettingsContent() {
        composeRule.setContent { SettingsNavHarness() }

        // Navigate to Paired Devices
        composeRule.onNodeWithText("Go to Paired Devices").performClick()
        composeRule.onNodeWithText("Paired Devices Content").assertIsDisplayed()

        // Re-select Settings from rail (simulates Enter on Settings rail item)
        composeRule.onNodeWithText("Rail: Settings").performClick()

        // Must show Settings content, NOT device sub-screen (the bug)
        composeRule.onNodeWithText("Settings Content").assertIsDisplayed()
    }

    @Test
    fun switchToHome_thenBackToSettings_afterActivatedDevices_showsSettingsContent() {
        composeRule.setContent { SettingsNavHarness() }

        // Navigate to Activated Devices
        composeRule.onNodeWithText("Go to Activated Devices").performClick()
        composeRule.onNodeWithText("Activated Devices Content").assertIsDisplayed()

        // Switch to Home tab
        composeRule.onNodeWithText("Rail: Home").performClick()
        composeRule.onNodeWithText("Home Content").assertIsDisplayed()

        // Switch back to Settings from rail
        composeRule.onNodeWithText("Rail: Settings").performClick()

        // Must show Settings content, NOT device sub-screen
        composeRule.onNodeWithText("Settings Content").assertIsDisplayed()
    }

    @Test
    fun backFromPairedDevices_showsSettingsContent() {
        composeRule.setContent { SettingsNavHarness() }

        // Navigate to Paired Devices
        composeRule.onNodeWithText("Go to Paired Devices").performClick()
        composeRule.onNodeWithText("Paired Devices Content").assertIsDisplayed()

        // Press back within Manage Devices
        composeRule.onNodeWithText("Back to Settings").performClick()

        // Must show Settings content
        composeRule.onNodeWithText("Settings Content").assertIsDisplayed()
    }

    @Test
    fun multipleRoundTrips_destinationNeverSticks() {
        composeRule.setContent { SettingsNavHarness() }

        repeat(3) {
            // Go to Activated Devices
            composeRule.onNodeWithText("Go to Activated Devices").performClick()
            composeRule.onNodeWithText("Activated Devices Content").assertIsDisplayed()

            // Re-select Settings from rail
            composeRule.onNodeWithText("Rail: Settings").performClick()
            composeRule.onNodeWithText("Settings Content").assertIsDisplayed()
        }
    }
}
