package com.torve.android.ui.product

import com.torve.android.ui.community.parityLabel as localizedUiText

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties

@Composable
internal fun MobileSearchField(query: String, onQuery: (String) -> Unit, onClear: () -> Unit, onSearch: () -> Unit,
    recent: List<String>, onForgetRecent: () -> Unit, modifier: Modifier = Modifier,
    placeholder: String = "Search movies and shows") {
    var focused by remember { mutableStateOf(false) }
    var dismissed by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    Box(modifier) {
        com.torve.android.ui.components.MobileGlassTextField(query, onQuery, singleLine = true, placeholder = { Text(placeholder, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch(); focusManager.clearFocus(); keyboard?.hide() }),
            leadingIcon = { Icon(Icons.Rounded.Search, null) }, trailingIcon = {
                if (query.isNotEmpty()) IconButton(onClick = onClear, modifier = Modifier.size(48.dp)) { Icon(Icons.Rounded.Close, localizedUiText("Clear search")) }
            }, modifier = Modifier.fillMaxWidth().testTag("mobile_search_input").onFocusChanged {
                if (!focused && it.isFocused) dismissed = false
                focused = it.isFocused
            })
        DropdownMenu(expanded = focused && !dismissed && query.isBlank() && recent.isNotEmpty(), onDismissRequest = { dismissed = true },
            properties = PopupProperties(focusable = false)) {
            Text(localizedUiText("Recent searches"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
            recent.forEach { term -> DropdownMenuItem(text = { Text(term) }, onClick = {
                onQuery(term)
                dismissed = true
                focusManager.clearFocus()
                keyboard?.hide()
            }) }
            DropdownMenuItem(text = { Text(localizedUiText("Clear recent searches")) }, onClick = { onForgetRecent(); dismissed = true })
        }
    }
}
