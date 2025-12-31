package com.claudecode.native.util

/**
 * Platform-specific folder chooser dialog.
 * Returns the selected folder path or null if cancelled.
 */
expect fun showFolderChooser(title: String = "Select Folder"): String?
