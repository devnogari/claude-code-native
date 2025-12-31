package com.claudecode.native.util

actual fun showFolderChooser(title: String): String? {
    // WASM doesn't support native file dialogs
    // User must type the path manually
    return null
}
