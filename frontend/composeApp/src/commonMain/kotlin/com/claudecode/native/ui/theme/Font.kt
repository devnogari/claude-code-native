package com.claudecode.native.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily

/**
 * Platform-specific font family provider.
 * Desktop uses custom Noto Sans KR fonts, WASM uses system fonts.
 */
@Composable
expect fun appFontFamily(): FontFamily

/**
 * Preload fallback fonts for missing glyphs (symbols, emojis, etc.)
 * On WASM: Uses LocalFontFamilyResolver.preload() per official docs.
 * On Desktop: No-op (system fonts handle fallback).
 */
@Composable
expect fun PreloadFallbackFont()
