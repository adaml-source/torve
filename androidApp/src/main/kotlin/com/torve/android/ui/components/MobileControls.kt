package com.torve.android.ui.components

import com.torve.android.ui.community.parityLabel as localizedUiText

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.torve.android.R

/** Compact captions keep the full destination name available to accessibility services. */
internal fun mobileBottomLabelRes(fullLabelRes: Int): Int = when (fullLabelRes) {
    R.string.nav_settings -> R.string.nav_settings_short
    R.string.nav_home -> R.string.nav_home_short
    R.string.nav_calendar -> R.string.nav_calendar_short
    R.string.nav_library -> R.string.nav_library_short
    R.string.nav_channels -> R.string.nav_channels_short
    R.string.nav_search -> R.string.nav_search_short
    else -> fullLabelRes
}

/** The outer bar owns six dp of inset; every destination shares the same center line. */
@Composable
internal fun MobileBottomDestination(label: String, icon: ImageVector, selected: Boolean,
    onClick: () -> Unit, modifier: Modifier = Modifier, showLabel: Boolean = true, accessibilityLabel: String = label) {
    val color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier.height(56.dp).testTag("bottom_$label")
        .then(if (selected) Modifier.mobileGlassSurface(RoundedCornerShape(28.dp), selected = true, quiet = true) else Modifier)
        .selectable(selected, role = Role.Tab, onClick = onClick).semantics(mergeDescendants = true) { contentDescription = accessibilityLabel },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(icon, null, Modifier.size(22.dp).testTag("bottom_icon_$label"), tint = color)
        if (showLabel) {
            Spacer(Modifier.height(3.dp))
            Text(localizedUiText(label), color = color, fontSize = 10.sp, lineHeight = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall, modifier = Modifier.testTag("bottom_label_$label"))
        }
    }
}

/** Icons remain visible; only the active or keyboard-focused destination shows its name. */
@Composable
internal fun MobileLibraryDestination(label: String, icon: ImageVector, selected: Boolean,
    onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val expanded = selected || focused
    val color = if (expanded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Row(modifier.animateContentSize().heightIn(min = 48.dp)
        .onFocusChanged { focused = it.isFocused }
        .mobileGlassSurface(RoundedCornerShape(24.dp), selected = expanded, quiet = !expanded)
        .selectable(selected, role = Role.Tab, onClick = onClick)
        .semantics(mergeDescendants = true) { contentDescription = label }
        .padding(horizontal = if (expanded) 16.dp else 13.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, null, Modifier.size(22.dp), tint = color)
        if (expanded) Text(localizedUiText(label), color = color, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

/** A compact visible pill inside a full-size touch target. */
@Composable
internal fun MobileFilterPill(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.heightIn(min = 48.dp).selectable(selected, role = Role.Tab, onClick = onClick), contentAlignment = Alignment.Center) {
        Box(Modifier.mobileGlassSurface(RoundedCornerShape(18.dp), selected = selected, quiet = !selected)
            .heightIn(min = 34.dp).padding(horizontal = 14.dp, vertical = 7.dp), contentAlignment = Alignment.Center) {
            Text(localizedUiText(label), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun MobileGlassTextField(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier,
    label: (@Composable () -> Unit)? = null, placeholder: (@Composable () -> Unit)? = null,
    singleLine: Boolean = true, keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    readOnly: Boolean = false,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    leadingIcon: (@Composable () -> Unit)? = null, trailingIcon: (@Composable () -> Unit)? = null) {
    var focused by remember { mutableStateOf(false) }
    OutlinedTextField(value, onValueChange, modifier.heightIn(min = 52.dp)
        .onFocusChanged { focused = it.isFocused }.mobileGlassSurface(RoundedCornerShape(20.dp), selected = focused, quiet = true),
        label = label, placeholder = placeholder, singleLine = singleLine, readOnly = readOnly,
        shape = RoundedCornerShape(20.dp), visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
        leadingIcon = leadingIcon, trailingIcon = trailingIcon,
        colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
            focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent,
            focusedTextColor = MaterialTheme.colorScheme.onSurface, unfocusedTextColor = MaterialTheme.colorScheme.onSurface))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MobileGlassFilterChip(selected: Boolean, onClick: () -> Unit, label: @Composable () -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true, leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null, shape: Shape = RoundedCornerShape(18.dp),
    colors: SelectableChipColors = FilterChipDefaults.filterChipColors(), border: BorderStroke? = null) {
    FilterChip(selected, onClick, label, modifier.mobileGlassSurface(shape, selected = selected, quiet = true),
        enabled = enabled, leadingIcon = leadingIcon, trailingIcon = trailingIcon, shape = shape,
        colors = FilterChipDefaults.filterChipColors(containerColor = Color.Transparent, selectedContainerColor = Color.Transparent,
            labelColor = MaterialTheme.colorScheme.onSurfaceVariant, selectedLabelColor = MaterialTheme.colorScheme.primary),
        border = null)
}
