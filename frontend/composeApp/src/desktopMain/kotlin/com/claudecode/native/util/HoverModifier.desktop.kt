package com.claudecode.native.util

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent

/**
 * Desktop implementation - uses pointer event handlers for hover detection.
 */
@OptIn(ExperimentalComposeUiApi::class)
actual fun Modifier.onHover(
    onEnter: () -> Unit,
    onExit: () -> Unit
): Modifier = this
    .onPointerEvent(PointerEventType.Enter) { onEnter() }
    .onPointerEvent(PointerEventType.Exit) { onExit() }
