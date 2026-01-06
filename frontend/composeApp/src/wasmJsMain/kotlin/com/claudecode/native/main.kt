// SPDX-License-Identifier: MIT
// Copyright (c) 2024-2025 Claude Code Native Contributors

package com.claudecode.native

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import kotlinx.browser.document

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ComposeViewport(document.body!!) {
        App()
    }
}
