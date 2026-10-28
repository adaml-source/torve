package com.torve.android.remote

import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.*

@Composable
fun TorveRemotePopup(alignment: Alignment = Alignment.TopStart, offset: IntOffset = IntOffset.Zero,
    onDismissRequest: (() -> Unit)? = null, properties: PopupProperties = PopupProperties(), content: @Composable () -> Unit) {
    Popup(alignment, offset, onDismissRequest, properties) {
        TorveRemoteInputSurface(if (properties.dismissOnBackPress) onDismissRequest else null); content()
    }
}

@Composable
fun TorveRemotePopup(popupPositionProvider: PopupPositionProvider, onDismissRequest: (() -> Unit)? = null,
    properties: PopupProperties = PopupProperties(), content: @Composable () -> Unit) {
    Popup(popupPositionProvider, onDismissRequest, properties) {
        TorveRemoteInputSurface(if (properties.dismissOnBackPress) onDismissRequest else null); content()
    }
}

@Composable
fun TorveRemoteDialog(onDismissRequest: () -> Unit, properties: DialogProperties = DialogProperties(), content: @Composable () -> Unit) {
    Dialog(onDismissRequest, properties) {
        TorveRemoteInputSurface(if (properties.dismissOnBackPress) onDismissRequest else ({})); content()
    }
}
