package com.torve.android.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.torve.android.test.TorveTestHostActivity
import com.torve.android.tv.focus.TvFocusTargetId
import com.torve.android.tv.focus.rememberRegisteredTvFocusRequester
import com.torve.android.tv.focus.rememberTvModalFocusRestoreController
import org.junit.Rule
import org.junit.Test

class TvModalFocusRestoreUiTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<TorveTestHostActivity>()

    @Test
    fun dismissingModal_restoresExactOriginatingItem() {
        composeRule.setContent {
            ModalRestoreHarness(removeOriginOnDismiss = false)
        }

        composeRule.onNodeWithTag("open_row_2_item_2").performClick()
        composeRule.onNodeWithTag("modal_close").performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            runCatching {
                composeRule.onNodeWithTag("open_row_2_item_2").assertIsFocused()
                true
            }.getOrDefault(false)
        }
    }

    @Test
    fun removedOrigin_fallsBackToNearestSiblingInSameRow() {
        composeRule.setContent {
            ModalRestoreHarness(removeOriginOnDismiss = true)
        }

        composeRule.onNodeWithTag("open_row_2_item_2").performClick()
        composeRule.onNodeWithTag("modal_close").performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            runCatching {
                composeRule.onNodeWithTag("open_row_2_item_1").assertIsFocused()
                true
            }.getOrDefault(false)
        }
    }
}

@Composable
private fun ModalRestoreHarness(
    removeOriginOnDismiss: Boolean,
) {
    val listState = rememberLazyListState()
    val controller = rememberTvModalFocusRestoreController(key = "test_modal_restore")
    var showModal by remember { mutableStateOf(false) }
    var removeOrigin by remember { mutableStateOf(false) }

    LaunchedEffect(showModal, controller.pendingRestore?.restoreToken, removeOrigin) {
        if (showModal) return@LaunchedEffect
        controller.restorePendingFocus(
            screenId = "test_screen",
            outerListState = listState,
        )
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF101010))) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "row_1_item_1") {
                FocusableHarnessCard(
                    controller = controller,
                    target = TvFocusTargetId("test_screen", "row_1", "item_1", rowIndex = 0, itemIndex = 0),
                    tag = "open_row_1_item_1",
                    onClick = {
                        controller.captureOrigin(
                            target = TvFocusTargetId("test_screen", "row_1", "item_1", rowIndex = 0, itemIndex = 0),
                            outerListState = listState,
                        )
                        showModal = true
                    },
                )
            }
            item(key = "row_1_item_2") {
                FocusableHarnessCard(
                    controller = controller,
                    target = TvFocusTargetId("test_screen", "row_1", "item_2", rowIndex = 0, itemIndex = 1),
                    tag = "open_row_1_item_2",
                    onClick = {
                        controller.captureOrigin(
                            target = TvFocusTargetId("test_screen", "row_1", "item_2", rowIndex = 0, itemIndex = 1),
                            outerListState = listState,
                        )
                        showModal = true
                    },
                )
            }
            item(key = "row_2_item_1") {
                FocusableHarnessCard(
                    controller = controller,
                    target = TvFocusTargetId("test_screen", "row_2", "item_1", rowIndex = 1, itemIndex = 0),
                    tag = "open_row_2_item_1",
                    onClick = {
                        controller.captureOrigin(
                            target = TvFocusTargetId("test_screen", "row_2", "item_1", rowIndex = 1, itemIndex = 0),
                            outerListState = listState,
                        )
                        showModal = true
                    },
                )
            }
            if (!removeOrigin) {
                item(key = "row_2_item_2") {
                    FocusableHarnessCard(
                        controller = controller,
                        target = TvFocusTargetId("test_screen", "row_2", "item_2", rowIndex = 1, itemIndex = 1),
                        tag = "open_row_2_item_2",
                        onClick = {
                            controller.captureOrigin(
                                target = TvFocusTargetId("test_screen", "row_2", "item_2", rowIndex = 1, itemIndex = 1),
                                outerListState = listState,
                            )
                            showModal = true
                        },
                    )
                }
            }
        }

        if (showModal) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(40.dp)
                    .background(Color(0xFF2A2A2A), RoundedCornerShape(16.dp))
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Modal")
                Box(
                    modifier = Modifier
                        .testTag("modal_close")
                        .background(Color(0xFF444444), RoundedCornerShape(10.dp))
                        .clickable {
                            if (removeOriginOnDismiss) {
                                removeOrigin = true
                            }
                            showModal = false
                            controller.requestRestore()
                        }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text("Close")
                }
            }
        }
    }
}

@Composable
private fun FocusableHarnessCard(
    controller: com.torve.android.tv.focus.TvModalFocusRestoreController,
    target: TvFocusTargetId,
    tag: String,
    onClick: () -> Unit,
) {
    val requester = rememberRegisteredTvFocusRequester(
        controller = controller,
        target = target,
    )

    Box(
        modifier = Modifier
            .testTag(tag)
            .fillMaxWidth()
            .focusRequester(requester)
            .focusable()
            .onFocusChanged {
                if (it.isFocused) {
                    controller.markFocused(target)
                }
            }
            .background(Color(0xFF3A3A3A), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text("${target.rowKey}:${target.itemKey}", color = Color.White)
    }
}
