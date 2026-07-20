package com.torve.desktop.ui.theme

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font

val InterFontFamily: FontFamily = FontFamily(
    Font(resource = "fonts/Inter-Regular.otf", weight = FontWeight.Normal, style = FontStyle.Normal),
    Font(resource = "fonts/Inter-Medium.otf", weight = FontWeight.Medium, style = FontStyle.Normal),
    Font(resource = "fonts/Inter-SemiBold.otf", weight = FontWeight.SemiBold, style = FontStyle.Normal),
    Font(resource = "fonts/Inter-Bold.otf", weight = FontWeight.Bold, style = FontStyle.Normal),
)
