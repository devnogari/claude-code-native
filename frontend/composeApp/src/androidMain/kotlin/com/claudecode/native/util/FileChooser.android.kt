package com.claudecode.native.util

/**
 * Android implementation - returns null as folder chooser requires Activity context.
 * TODO: Implement using ActivityResultContracts.OpenDocumentTree
 */
actual fun showFolderChooser(title: String): String? {
    // Android folder picker requires Activity context and result callbacks
    // For now, return null - users can manually enter paths
    return null
}
