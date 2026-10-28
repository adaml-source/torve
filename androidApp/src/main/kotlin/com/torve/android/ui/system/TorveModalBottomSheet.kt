package com.torve.android.ui.system

import android.os.Build
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat

/** All phone sheets use the app palette, including Android's three-button navigation area. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TorveModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    sheetMaxWidth: Dp = BottomSheetDefaults.SheetMaxWidth,
    shape: Shape = BottomSheetDefaults.ExpandedShape,
    containerColor: Color = BottomSheetDefaults.ContainerColor,
    contentColor: Color = contentColorFor(containerColor),
    tonalElevation: Dp = 0.dp,
    scrimColor: Color = BottomSheetDefaults.ScrimColor,
    dragHandle: @Composable (() -> Unit)? = { BottomSheetDefaults.DragHandle() },
    contentWindowInsets: @Composable () -> WindowInsets = { BottomSheetDefaults.windowInsets },
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(onDismissRequest, modifier, sheetState, sheetMaxWidth, shape, containerColor,
        contentColor, tonalElevation, scrimColor, dragHandle, contentWindowInsets = contentWindowInsets) {
        val view = LocalView.current
        val background = MaterialTheme.colorScheme.background
        SideEffect {
            val dialogWindow = generateSequence(view.parent) { it.parent }
                .filterIsInstance<DialogWindowProvider>().firstOrNull()?.window
            if (dialogWindow != null) {
                @Suppress("DEPRECATION")
                dialogWindow.navigationBarColor = background.toArgb()
                if (Build.VERSION.SDK_INT >= 29) dialogWindow.isNavigationBarContrastEnforced = false
                WindowCompat.getInsetsController(dialogWindow, view).isAppearanceLightNavigationBars = background.luminance() > .5f
            }
        }
        content()
    }
}
