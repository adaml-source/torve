package com.torve.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.torve.android.ui.theme.Amber
import com.torve.android.ui.theme.Torve

/**
 * Compact pill-shaped search field matching the app's existing
 * `Torve.colors.inputBackground` + 10dp-corner pattern used on the
 * Channels LIVE tab. Used in three places that previously rendered a
 * full-height Material3 OutlinedTextField (too tall, off-design):
 *  - mobile Channels → GUIDE sub-tab
 *  - TV Channels sidebar
 *  - TV Sports header
 *
 * On TV the [showFocusRing] flag draws an Amber border when the field
 * is focused so the user can see the D-pad cursor landed here.
 *
 * Submit (Enter / IME action Search) calls [onSubmit]. Pass `null` to
 * disable submit handling for fields that filter live as the user
 * types.
 */
@Composable
fun TorveSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    onSubmit: (() -> Unit)? = null,
    showFocusRing: Boolean = false,
) {
    var isFocused by remember { mutableStateOf(false) }
    val borderColor: Color = if (showFocusRing && isFocused) Amber else Color.Transparent
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Torve.colors.inputBackground)
            .border(1.dp, borderColor, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = Torve.colors.textPrimary),
            cursorBrush = SolidColor(Amber),
            keyboardOptions = KeyboardOptions(imeAction = if (onSubmit != null) ImeAction.Search else ImeAction.Done),
            keyboardActions = KeyboardActions(
                onSearch = { onSubmit?.invoke() },
                onDone = { onSubmit?.invoke() },
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = 28.dp)  // leaves room for the trailing icon
                .onFocusChanged { isFocused = it.isFocused },
            decorationBox = { innerTextField ->
                Box {
                    if (value.isEmpty()) {
                        Text(
                            placeholder,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Torve.colors.textHint,
                        )
                    }
                    innerTextField()
                }
            },
        )
        if (value.isNotEmpty()) {
            IconButton(
                onClick = { onValueChange("") },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .size(20.dp),
            ) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = "Clear",
                    tint = Torve.colors.textTertiary,
                    modifier = Modifier.size(16.dp),
                )
            }
        } else {
            Icon(
                Icons.Rounded.Search,
                contentDescription = null,
                tint = Torve.colors.textTertiary,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .size(16.dp),
            )
        }
    }
}
