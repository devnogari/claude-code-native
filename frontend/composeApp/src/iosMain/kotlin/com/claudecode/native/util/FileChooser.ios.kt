package com.claudecode.native.util

/**
 * iOS folder chooser - returns null as iOS doesn't have a traditional file system UI.
 * For iOS, file access would typically use UIDocumentPickerViewController.
 */
actual fun showFolderChooser(title: String): String? {
    // iOS doesn't have a simple folder picker like desktop
    // Would need UIDocumentPickerViewController with async/callback pattern
    return null
}
