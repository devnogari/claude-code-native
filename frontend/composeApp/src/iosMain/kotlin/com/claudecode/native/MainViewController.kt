package com.claudecode.native

import androidx.compose.ui.window.ComposeUIViewController
import com.claudecode.native.di.initKoin

fun MainViewController() = ComposeUIViewController {
    App()
}

fun doInitKoin() {
    initKoin()
}
