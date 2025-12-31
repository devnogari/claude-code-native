package com.claudecode.native.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import claude_code_native.composeapp.generated.resources.Res
import claude_code_native.composeapp.generated.resources.noto_sans_kr_regular
import claude_code_native.composeapp.generated.resources.noto_sans_regular
import claude_code_native.composeapp.generated.resources.noto_sans_symbols
import org.jetbrains.compose.resources.Font

/**
 * WASM implementation using multiple fonts for comprehensive glyph coverage:
 * - Noto Sans KR: Korean characters
 * - Noto Sans: Latin and common symbols
 * - Noto Sans Symbols: Special symbols (arrows, etc.)
 */
@Composable
actual fun appFontFamily(): FontFamily {
    val koreanFont = Font(Res.font.noto_sans_kr_regular, FontWeight.Normal)
    val latinFont = Font(Res.font.noto_sans_regular, FontWeight.Normal)
    val symbolFont = Font(Res.font.noto_sans_symbols, FontWeight.Normal)

    return remember(koreanFont, latinFont, symbolFont) {
        FontFamily(koreanFont, latinFont, symbolFont)
    }
}

/**
 * No-op on WASM.
 */
@Composable
actual fun PreloadFallbackFont() {
    // No-op
}
