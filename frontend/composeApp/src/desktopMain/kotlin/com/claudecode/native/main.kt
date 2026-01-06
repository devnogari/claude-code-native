// SPDX-License-Identifier: MIT
// Copyright (c) 2024-2025 Claude Code Native Contributors

package com.claudecode.native

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.unit.dp

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Claude Code Native",
        state = rememberWindowState(width = 1200.dp, height = 800.dp)
    ) {
        App()
    }
}
