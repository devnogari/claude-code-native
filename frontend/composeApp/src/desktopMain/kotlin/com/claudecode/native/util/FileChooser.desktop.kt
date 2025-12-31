package com.claudecode.native.util

import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.JFileChooser
import javax.swing.UIManager

actual fun showFolderChooser(title: String): String? {
    // Use system look and feel for native appearance
    try {
        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
    } catch (_: Exception) {
        // Ignore, use default look and feel
    }

    val chooser = JFileChooser().apply {
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        dialogTitle = title
        isAcceptAllFileFilterUsed = false
        currentDirectory = File(System.getProperty("user.home"))
    }

    return when (chooser.showOpenDialog(null)) {
        JFileChooser.APPROVE_OPTION -> chooser.selectedFile.absolutePath
        else -> null
    }
}
