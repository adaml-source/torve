package com.torve.android.ui.tv

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.KeyEvent as AndroidKeyEvent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import com.torve.android.R
import com.torve.android.test.TorveTestHostActivity
import com.torve.android.tv.components.TvStreamSortControls
import com.torve.android.tv.focus.TvSettingsFocusStateMachine
import com.torve.android.tv.focus.focusTvSettingsRow
import com.torve.android.tv.focus.tvSettingsRowNavigation
import com.torve.android.tv.screens.TvSettingsCategory
import com.torve.android.tv.screens.TvSettingsScreen
import com.torve.android.tv.screens.TvListPickerOverlay
import com.torve.data.addon.ParsedStream
import com.torve.data.addon.StreamSortMode
import com.torve.data.addon.sortStreamResults
import com.torve.data.auth.AuthClient
import com.torve.data.account.AccountSettingsRepository
import com.torve.data.account.AccountSettingsSyncState
import com.torve.data.account.AccountSettingsRefreshResult
import com.torve.domain.repository.DeviceLocalSettingsRepository
import com.torve.domain.security.SecureStorage
import com.torve.domain.providerhealth.ProviderHealthCategory
import com.torve.domain.providerhealth.ProviderHealthEntry
import com.torve.domain.providerhealth.ProviderHealthRepository
import com.torve.domain.providerhealth.ProviderHealthStatus
import com.torve.presentation.providerhealth.ProviderHealthCoordinator
import io.ktor.client.HttpClient
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

@OptIn(ExperimentalTestApi::class)
class TvConnectionsAndStreamSortTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<TorveTestHostActivity>()
    private var railFocused by mutableStateOf(false)
    private lateinit var settingsController: TvSettingsFocusStateMachine

    private fun workflowRow(title: String) = composeRule.onNode(hasText(title) and hasClickAction())

    private fun waitForScreenWindow() {
        composeRule.waitUntil(timeoutMillis = 5_000) { composeRule.activity.hasWindowFocus() }
        composeRule.waitForIdle()
    }

    private fun remoteKey(keyCode: Int, action: Int, downTime: Long) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.injectInputEvent(
            AndroidKeyEvent(downTime, SystemClock.uptimeMillis(), action, keyCode, 0), true,
        )
    }

    private fun openConnections(
        mode: String,
        syncError: String? = null,
        healthEntries: MutableStateFlow<List<ProviderHealthEntry>> = MutableStateFlow(emptyList()),
        onAttentionConnection: (String) -> Unit = {},
    ) {
        railFocused = false
        composeRule.activity.getSharedPreferences("tv_prefs", Context.MODE_PRIVATE)
            .edit().putString("setup_mode", mode).apply()
        val localValues = mutableMapOf(
            AuthClient.KEY_AUTH_EMAIL to "remote-navigation@example.invalid",
            AuthClient.KEY_AUTH_USER_ID to "local-ui-test",
            AuthClient.KEY_AUTH_IS_VERIFIED to "true",
        )
        val secureValues = mutableMapOf(AuthClient.KEY_AUTH_ACCESS_TOKEN to "local-ui-test-session")
        val authClient = AuthClient(
            localSettingsRepository = object : DeviceLocalSettingsRepository {
                override suspend fun getString(key: String) = localValues[key]
                override suspend fun setString(key: String, value: String) { localValues[key] = value }
                override suspend fun remove(key: String) { localValues.remove(key) }
            },
            secureStorage = object : SecureStorage {
                override suspend fun getString(key: String) = secureValues[key]
                override suspend fun putString(key: String, value: String) { secureValues[key] = value }
                override suspend fun remove(key: String) { secureValues.remove(key) }
            },
            httpClient = org.koin.java.KoinJavaComponent.getKoin().get<HttpClient>(),
            baseUrlProvider = { "http://127.0.0.1:1" },
            deviceRegistrationProvider = { error("UI tests do not register devices") },
        )
        runBlocking { authClient.getAuthenticatedUser() }
        composeRule.setContent {
            MaterialTheme {
                TvSettingsScreen(
                    railFocusRequester = remember { FocusRequester() },
                    onFirstContentRequester = {},
                    onContentFocused = { railFocused = false },
                    isRailFocused = railFocused,
                    settingsFocusController = remember { TvSettingsFocusStateMachine(TvSettingsCategory.ACCOUNT) }
                        .also { settingsController = it },
                    authClient = authClient,
                    onNavigateToAutomationConnections = onAttentionConnection,
                    providerHealthCoordinator = remember {
                        ProviderHealthCoordinator(object : ProviderHealthRepository {
                            override val entries = healthEntries
                            override suspend fun load() = Unit
                            override suspend fun upsert(entry: ProviderHealthEntry) {
                                healthEntries.value = healthEntries.value.filterNot { it.providerKey == entry.providerKey } + entry
                            }
                            override suspend fun remove(category: ProviderHealthCategory, providerKey: String) {
                                healthEntries.value = healthEntries.value.filterNot { it.category == category && it.providerKey == providerKey }
                            }
                            override suspend fun clear() { healthEntries.value = emptyList() }
                        })
                    },
                    accountSettingsRepository = remember {
                        object : AccountSettingsRepository {
                            override val state = MutableStateFlow(AccountSettingsSyncState(lastError = syncError))
                            override suspend fun syncAfterSignIn() = AccountSettingsRefreshResult(skipped = true)
                            override suspend fun refreshIfStale(force: Boolean) = AccountSettingsRefreshResult(skipped = true)
                            override fun scheduleSharedSettingSync(key: String, value: String?) = Unit
                            override suspend fun clearSessionState() = Unit
                        }
                    },
                )
            }
        }
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.tv_settings_category_connections)).performClick()
        val syncTitle = composeRule.activity.getString(R.string.tv_settings_account_sync_status)
        composeRule.onNodeWithTag("tv_settings_list").performScrollToNode(hasText(syncTitle))
        composeRule.onNodeWithText(syncTitle).performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        composeRule.onNodeWithText(syncTitle).assertIsFocused()
    }

    @Test
    fun customPresetPickerStartsOnAChoiceAndShowsCompleteDescriptions() {
        val description = "Cached sources, compatible codecs and a balance of quality and startup speed."
        var selected: String? = null
        composeRule.setContent {
            MaterialTheme {
                TvListPickerOverlay(
                    title = "Playback preset",
                    items = listOf("BALANCED" to "Balanced", "FAST_START" to "Fast start"),
                    selectedKey = "CUSTOM",
                    descriptions = mapOf("BALANCED" to description, "FAST_START" to "Compatible sources up to 1080p."),
                    onSelect = { selected = it },
                    onDismiss = {},
                )
            }
        }
        waitForScreenWindow()
        workflowRow("Balanced").assertIsFocused()
        composeRule.onNodeWithText(description).assertIsDisplayed()
        workflowRow("Balanced").performKeyInput { pressKey(Key.DirectionDown) }
        workflowRow("Fast start").assertIsFocused()
        workflowRow("Fast start").performKeyInput { pressKey(Key.DirectionCenter) }
        assertEquals("FAST_START", selected)
    }

    @Test
    fun choosingAPlaybackPresetUpdatesTheRowAndReturnsRemoteFocus() {
        openConnections("TV_ONLY")
        composeRule.onNodeWithTag("tv_settings_list").performScrollToNode(hasText(composeRule.activity.getString(R.string.tv_settings_category_playback)))
        val playbackCategory = composeRule.onNodeWithText(composeRule.activity.getString(R.string.tv_settings_category_playback))
        playbackCategory.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        playbackCategory.assertIsFocused()
        playbackCategory.performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.waitUntil(5_000) { settingsController.selectedCategory == TvSettingsCategory.PLAYBACK }
        composeRule.onNodeWithTag("tv_settings_list").performScrollToNode(hasText("Playback preset"))
        val presetRow = composeRule.onNode(hasText("Playback preset") and hasClickAction())
        presetRow.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        presetRow.performKeyInput { pressKey(Key.DirectionCenter) }
        val fastStart = composeRule.onNode(hasText("Fast start", substring = true) and hasClickAction())
        fastStart.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        fastStart.assertIsFocused()
        composeRule.waitForIdle()
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(composeRule.activity.getExternalFilesDir(null), "playback-presets.png").outputStream().use {
            screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        fastStart.performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.waitUntil(5_000) { runCatching { presetRow.assertIsFocused(); true }.getOrDefault(false) }
        composeRule.onNodeWithText("Fast start", substring = true).assertExists()
    }

    @Test
    fun attentionRowsOpenTheAffectedServiceAndRecoverFocusWhenTheIssueClears() {
        val seerr = ProviderHealthEntry(ProviderHealthCategory.REQUEST_MANAGER, "seerr:request_manager", "Seerr", ProviderHealthStatus.RED, message = "Server is unreachable")
        val trakt = ProviderHealthEntry(ProviderHealthCategory.TRAKT, "trakt", "Trakt", ProviderHealthStatus.YELLOW, message = "Reauthorize this connection")
        val entries = MutableStateFlow(listOf(seerr, trakt))
        var openedTarget: String? = null
        openConnections("TV_ONLY", healthEntries = entries, onAttentionConnection = { openedTarget = it })
        composeRule.onNodeWithTag("tv_settings_list").performScrollToNode(hasText("Seerr needs attention"))
        composeRule.onNodeWithText("Seerr needs attention").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        composeRule.onNodeWithText("Seerr needs attention").performKeyInput { pressKey(Key.DirectionCenter) }
        assertEquals("seerr", openedTarget)
        composeRule.runOnIdle { entries.value = listOf(seerr.copy(status = ProviderHealthStatus.GREEN), trakt) }
        composeRule.onAllNodesWithText("Seerr needs attention").assertCountEquals(0)
        composeRule.onAllNodes(isFocused()).assertCountEquals(1)
        assertEquals(TvSettingsCategory.CONNECTIONS, settingsController.selectedCategory)
        composeRule.onNodeWithTag("tv_settings_list").performScrollToNode(hasText("Trakt needs attention"))
        composeRule.onNodeWithText("Trakt needs attention").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        composeRule.onNodeWithText("Trakt needs attention").performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.tv_settings_trakt)).assertIsFocused()
    }

    @Test
    fun arrAttentionOpensArrConnectionsRatherThanSeerrOrGeneralAdministration() {
        val entries = MutableStateFlow(listOf(ProviderHealthEntry(ProviderHealthCategory.REQUEST_MANAGER, "automation:admin", "ARR", ProviderHealthStatus.RED, message = "Check Sonarr")))
        var openedTarget: String? = null
        openConnections("TV_ONLY", healthEntries = entries, onAttentionConnection = { openedTarget = it })
        composeRule.onNodeWithTag("tv_settings_list").performScrollToNode(hasText("ARR needs attention"))
        composeRule.onNodeWithText("ARR needs attention").performClick()
        assertEquals("automation", openedTarget)
    }

    @Test
    fun remoteCanTraverseAttentionRowsThatAreInitiallyOffscreen() {
        val entries = MutableStateFlow((0..11).map { index ->
            ProviderHealthEntry(ProviderHealthCategory.USENET_INDEXER, "indexer-$index", "Provider ${index.toString().padStart(2, '0')}", ProviderHealthStatus.RED, message = "Review the connection")
        })
        openConnections("TV_ONLY", healthEntries = entries)
        composeRule.onNodeWithTag("tv_settings_list").performScrollToNode(hasText("Provider 00 needs attention"))
        composeRule.onNodeWithText("Provider 00 needs attention").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        for (index in 1..11) {
            composeRule.onNodeWithText("Provider ${(index - 1).toString().padStart(2, '0')} needs attention").performKeyInput { pressKey(Key.DirectionDown) }
            composeRule.onNodeWithText("Provider ${index.toString().padStart(2, '0')} needs attention").assertIsFocused()
        }
        composeRule.onNodeWithText("Provider 11 needs attention").performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithText("*Arr media automation").assertIsFocused()
        composeRule.onNodeWithText("*Arr media automation").performKeyInput { pressKey(Key.DirectionUp) }
        composeRule.onNodeWithText("Provider 11 needs attention").assertIsFocused()
    }

    @Test
    fun nativeRemoteCanReleaseOkInPopupAndReopenItAfterBack() {
        openConnections("TV_ONLY")
        val syncTitle = composeRule.activity.getString(R.string.tv_settings_account_sync_status)
        val receiveTitle = composeRule.activity.getString(R.string.settings_receive_credentials)
        composeRule.onNodeWithText(syncTitle).performKeyInput { pressKey(Key.DirectionDown) }
        repeat(2) {
            val selectDownTime = SystemClock.uptimeMillis()
            remoteKey(AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.ACTION_DOWN, selectDownTime)
            workflowRow("Watch now").assertIsFocused()
            remoteKey(AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.ACTION_UP, selectDownTime)
            workflowRow("Watch now").assertIsFocused()
            val backDownTime = SystemClock.uptimeMillis()
            remoteKey(AndroidKeyEvent.KEYCODE_BACK, AndroidKeyEvent.ACTION_DOWN, backDownTime)
            workflowRow("Watch now").assertIsFocused()
            remoteKey(AndroidKeyEvent.KEYCODE_BACK, AndroidKeyEvent.ACTION_UP, backDownTime)
            waitForScreenWindow()
            composeRule.onNodeWithText("Connection center").assertIsFocused()
        }
        composeRule.onNodeWithText("Connection center").performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithText(receiveTitle).assertIsFocused()
    }

    @Test
    fun movingBetweenVisibleRowsUsesNormalFocusScrollingWithoutSnappingToTheTop() {
        lateinit var listState: LazyListState
        var furthestFirstIndex = 0
        composeRule.setContent {
            MaterialTheme {
                val state = rememberLazyListState(initialFirstVisibleItemIndex = 2, initialFirstVisibleItemScrollOffset = 24)
                listState = state
                val requesters = remember { List(12) { FocusRequester() } }
                val scope = rememberCoroutineScope()
                var focusedIndex by remember { mutableStateOf(-1) }
                LaunchedEffect(state) {
                    snapshotFlow { state.firstVisibleItemIndex }.collect {
                        furthestFirstIndex = maxOf(furthestFirstIndex, it)
                    }
                }
                LazyColumn(Modifier.fillMaxSize(), state = state) {
                    items(12) { index ->
                        Text("Navigation row $index", modifier = Modifier.fillMaxWidth().height(70.dp)
                            .focusRequester(requesters[index])
                            .onFocusChanged { if (it.isFocused) focusedIndex = index }
                            .tvSettingsRowNavigation(
                                onDown = if (index < 11) ({ scope.launch {
                                    focusTvSettingsRow(state, index + 1, requesters[index + 1]) { focusedIndex == index + 1 }
                                }; Unit }) else null,
                                onUp = if (index > 0) ({ scope.launch {
                                    focusTvSettingsRow(state, index - 1, requesters[index - 1]) { focusedIndex == index - 1 }
                                }; Unit }) else null,
                            )
                            .clickable { })
                    }
                }
            }
        }
        composeRule.onNodeWithText("Navigation row 4").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        composeRule.onNodeWithText("Navigation row 4").assertIsFocused()
        var before = 0 to 0
        composeRule.runOnIdle {
            before = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
            furthestFirstIndex = listState.firstVisibleItemIndex
        }
        composeRule.onNodeWithText("Navigation row 4").performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithText("Navigation row 5").assertIsFocused()
        composeRule.runOnIdle { assertTrue("Navigation must not snap row 5 to the top", furthestFirstIndex < 5) }
        composeRule.onNodeWithText("Navigation row 5").performKeyInput { pressKey(Key.DirectionUp) }
        composeRule.onNodeWithText("Navigation row 4").assertIsFocused()
        composeRule.runOnIdle { assertEquals(before, listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset) }
    }

    @Test
    fun repeatedConnectionCenterVisitsRestoreFocusEvenWhenRailTakesItDuringPopup() {
        openConnections("TV_ONLY")
        val syncTitle = composeRule.activity.getString(R.string.tv_settings_account_sync_status)
        val receiveTitle = composeRule.activity.getString(R.string.settings_receive_credentials)
        composeRule.onNodeWithText(syncTitle).performKeyInput { pressKey(Key.DirectionDown) }
        repeat(3) {
            composeRule.onNodeWithText("Connection center").assertIsFocused()
            composeRule.onNodeWithText("Connection center").performKeyInput { pressKey(Key.DirectionCenter) }
            workflowRow("Watch now").assertIsFocused()
            composeRule.runOnIdle { railFocused = true }
            workflowRow("Watch now").performKeyInput { pressKey(Key.Back) }
            waitForScreenWindow()
            composeRule.onNodeWithText("Connection center").assertIsFocused()
            composeRule.runOnIdle { assertEquals(false, railFocused) }
            composeRule.onNodeWithText("Connection center").performKeyInput { pressKey(Key.DirectionDown) }
            composeRule.onNodeWithText(receiveTitle).assertIsFocused()
            composeRule.onNodeWithText(receiveTitle).performKeyInput { pressKey(Key.DirectionUp) }
        }
        composeRule.onNodeWithText("Connection center").performKeyInput { pressKey(Key.DirectionUp) }
        composeRule.onNodeWithText(syncTitle).assertIsFocused()
    }

    @Test
    fun accountSyncErrorRemainsInOrderBeforeCenterAndReceive() {
        openConnections("TV_ONLY", syncError = "sync_failed")
        val syncTitle = composeRule.activity.getString(R.string.tv_settings_account_sync_status)
        val errorTitle = composeRule.activity.getString(R.string.tv_settings_sync_error)
        val receiveTitle = composeRule.activity.getString(R.string.settings_receive_credentials)
        composeRule.onNodeWithText(syncTitle).performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithText(errorTitle).assertIsFocused()
        composeRule.onNodeWithText(errorTitle).performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithText("Connection center").assertIsFocused()
        composeRule.onNodeWithText("Connection center").performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithText(receiveTitle).assertIsFocused()
    }

    @Test
    fun tvOnlyDownReachesConnectionCenterThenReceiveAndTraktWithoutSkipping() {
        openConnections("TV_ONLY")
        val syncTitle = composeRule.activity.getString(R.string.tv_settings_account_sync_status)
        val receiveTitle = composeRule.activity.getString(R.string.settings_receive_credentials)
        composeRule.onNodeWithText(syncTitle).performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithText("Connection center").assertIsFocused()
        composeRule.onNodeWithText("Connection center").performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithText(receiveTitle).assertIsFocused()
        composeRule.onNodeWithText(receiveTitle).performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithText("Trakt").assertIsFocused()
        composeRule.onNodeWithText("Trakt").performKeyInput { pressKey(Key.DirectionUp) }
        composeRule.onNodeWithText(receiveTitle).assertIsFocused()
    }

    @Test
    fun phonePairedCenterExplainsMissingSetupAndBackRestoresItsFocus() {
        openConnections("ANDROID_PHONE")
        val syncTitle = composeRule.activity.getString(R.string.tv_settings_account_sync_status)
        val receiveTitle = composeRule.activity.getString(R.string.settings_receive_credentials)
        composeRule.onNodeWithText(syncTitle).performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithText("Connection center").performKeyInput { pressKey(Key.DirectionCenter) }
        workflowRow("Watch now").assertIsFocused()
        workflowRow("Watch now").performKeyInput { pressKey(Key.DirectionDown) }
        workflowRow("My library").assertIsFocused()
        workflowRow("My library").performKeyInput { pressKey(Key.DirectionRight) }
        composeRule.onNodeWithText("Open media-server settings").assertIsFocused()
        composeRule.onNodeWithText("Open media-server settings").performKeyInput { pressKey(Key.DirectionLeft) }
        workflowRow("My library").assertIsFocused()
        composeRule.waitForIdle()
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(composeRule.activity.getExternalFilesDir(null), "connection-center.png").outputStream().use {
            screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        workflowRow("My library").performKeyInput { pressKey(Key.Back) }
        waitForScreenWindow()
        composeRule.onNodeWithText("Connection center").assertIsFocused()
        composeRule.onNodeWithText("Connection center").performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithText(receiveTitle).assertIsFocused()
        composeRule.onAllNodesWithText(receiveTitle).assertCountEquals(1)
        composeRule.onNodeWithText(receiveTitle).performKeyInput { pressKey(Key.DirectionUp) }
        composeRule.onNodeWithText("Connection center").performKeyInput { pressKey(Key.DirectionCenter) }
        workflowRow("Watch now").performKeyInput { repeat(3) { pressKey(Key.DirectionDown) } }
        workflowRow("Tracking and sync").assertIsFocused()
        workflowRow("Tracking and sync").performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.onNodeWithText("Connect Trakt").assertIsFocused()
        composeRule.onNodeWithText("Connect Trakt").performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithText("Connect SIMKL").performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithText("Open tracking settings").assertIsFocused()
        composeRule.onNodeWithText("Open tracking settings").performKeyInput { pressKey(Key.DirectionCenter) }
        waitForScreenWindow()
        composeRule.onNodeWithText("Trakt").assertIsFocused()
    }

    @Test
    fun sizeSortingRetainsRemoteFocusWhileResultsReorderAndArrive() {
        var mode by mutableStateOf(StreamSortMode.DEFAULT)
        var streams by mutableStateOf(listOf(
            ParsedStream("test", "1080p", "unknown", size = null, score = 100),
            ParsedStream("test", "1080p", "small", size = "900 MB", score = 90),
            ParsedStream("test", "4K", "large", size = "20 GB", score = 10),
        ))
        composeRule.setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize()) {
                    TvStreamSortControls(mode, { mode = it })
                    sortStreamResults(streams, mode).forEach { Text(it.title) }
                }
            }
        }
        composeRule.onNodeWithText("Largest first").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        composeRule.onNodeWithText("Largest first").performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.onNodeWithText("Largest first").assertIsFocused()
        composeRule.runOnIdle {
            assertEquals(listOf("large", "small", "unknown"), sortStreamResults(streams, mode).map { it.title })
            streams = streams + ParsedStream("test", "4K", "largest", size = "30 GiB")
        }
        composeRule.onNodeWithText("Largest first").assertIsFocused()
        composeRule.onNodeWithText("Largest first").performKeyInput { pressKey(Key.DirectionRight); pressKey(Key.DirectionCenter) }
        composeRule.onNodeWithText("Smallest first").assertIsFocused()
        composeRule.runOnIdle {
            assertEquals(listOf("small", "large", "largest", "unknown"), sortStreamResults(streams, mode).map { it.title })
        }
    }
}
