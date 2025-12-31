package com.claudecode.native.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily

/**
 * iOS uses system fonts which include full Unicode support.
 */
@Composable
actual fun appFontFamily(): FontFamily = FontFamily.Default

/**
 * iOS system fonts handle fallback automatically.
 */
@Composable
actual fun PreloadFallbackFont() {
    // No-op on iOS - system fonts handle all glyphs
}
