package com.claudecode.native.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import claude_code_native.composeapp.generated.resources.Res
import claude_code_native.composeapp.generated.resources.noto_sans_kr_regular
import org.jetbrains.compose.resources.Font

/**
 * Desktop implementation using custom Noto Sans KR font (TTF format).
 */
@Composable
actual fun appFontFamily(): FontFamily = FontFamily(
    Font(Res.font.noto_sans_kr_regular, FontWeight.Normal)
)

/**
 * Desktop doesn't need fallback preloading - system fonts handle missing glyphs.
 */
@Composable
actual fun PreloadFallbackFont() {
    // No-op on Desktop
}
