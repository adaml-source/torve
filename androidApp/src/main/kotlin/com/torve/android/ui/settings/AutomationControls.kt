package com.torve.android.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button as MaterialButton
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox as MaterialCheckbox
import androidx.compose.material3.CheckboxColors
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.IconButton as MaterialIconButton
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.OutlinedButton as MaterialOutlinedButton
import androidx.compose.material3.Switch as MaterialSwitch
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.TextButton as MaterialTextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.torve.android.ui.components.TvBrowseOutlinedTextField
import com.torve.android.ui.theme.AmberLight

private val LocalAutomationTvControls = staticCompositionLocalOf { false }

@Composable
internal fun AutomationControlMode(
    tvEnabled: Boolean,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalAutomationTvControls provides tvEnabled, content = content)
}

@Composable
private fun Modifier.automationFocusHighlight(shape: Shape): Modifier {
    if (!LocalAutomationTvControls.current) return this
    var focused by remember { mutableStateOf(false) }
    return this
        .onFocusChanged { focused = it.isFocused }
        .zIndex(if (focused) 1f else 0f)
        .border(
            border = BorderStroke(2.dp, if (focused) AmberLight else Color.Transparent),
            shape = shape,
        )
}

@Composable
internal fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    content: @Composable RowScope.() -> Unit,
) {
    val shape = RoundedCornerShape(percent = 50)
    MaterialButton(
        onClick = onClick,
        modifier = modifier.automationFocusHighlight(shape),
        enabled = enabled,
        shape = shape,
        colors = colors,
        content = content,
    )
}

@Composable
internal fun OutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors(),
    content: @Composable RowScope.() -> Unit,
) {
    val shape = RoundedCornerShape(percent = 50)
    MaterialOutlinedButton(
        onClick = onClick,
        modifier = modifier.automationFocusHighlight(shape),
        enabled = enabled,
        shape = shape,
        colors = colors,
        content = content,
    )
}

@Composable
internal fun TextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val shape = RoundedCornerShape(percent = 50)
    MaterialTextButton(
        onClick = onClick,
        modifier = modifier.automationFocusHighlight(shape),
        enabled = enabled,
        shape = shape,
        content = content,
    )
}

@Composable
internal fun IconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: IconButtonColors = IconButtonDefaults.iconButtonColors(),
    content: @Composable () -> Unit,
) {
    MaterialIconButton(
        onClick = onClick,
        modifier = modifier.automationFocusHighlight(CircleShape),
        enabled = enabled,
        colors = colors,
        content = content,
    )
}

@Composable
internal fun Switch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: SwitchColors = SwitchDefaults.colors(),
) {
    MaterialSwitch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier.automationFocusHighlight(RoundedCornerShape(percent = 50)),
        enabled = enabled,
        colors = colors,
    )
}

@Composable
internal fun Checkbox(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: CheckboxColors = CheckboxDefaults.colors(),
) {
    MaterialCheckbox(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier.automationFocusHighlight(RoundedCornerShape(3.dp)),
        enabled = enabled,
        colors = colors,
    )
}

@Composable
internal fun OutlinedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: @Composable (() -> Unit)? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    singleLine: Boolean = false,
    enabled: Boolean = true,
) {
    TvBrowseOutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        tvEnabled = LocalAutomationTvControls.current,
        modifier = modifier,
        label = label,
        visualTransformation = visualTransformation,
        singleLine = singleLine,
        enabled = enabled,
    )
}
