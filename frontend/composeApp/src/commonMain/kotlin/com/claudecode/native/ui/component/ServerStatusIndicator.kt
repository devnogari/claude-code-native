package com.claudecode.native.ui.component

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Server authentication status.
 */
enum class ServerStatus {
    /** Server has a valid auth token (logged in) */
    Online,
    /** Server has no auth token (not logged in) */
    Offline,
    /** Status is unknown or server is null */
    Unknown
}

/**
 * Shared composable for displaying server authentication/connection status.
 *
 * Renders an icon with appropriate color based on the server status:
 * - Online (authenticated): Green checkmark
 * - Offline (not authenticated): Red warning
 * - Unknown: Gray indicator (no icon displayed by default)
 *
 * @param status The current server status
 * @param modifier Optional modifier for the icon
 * @param size Icon size (default: 16.dp)
 * @param showWhenUnknown Whether to show an icon when status is Unknown (default: false)
 */
@Composable
fun ServerStatusIndicator(
    status: ServerStatus,
    modifier: Modifier = Modifier,
    size: Dp = 16.dp,
    showWhenUnknown: Boolean = false
) {
    val (icon, contentDescription, tint) = when (status) {
        ServerStatus.Online -> Triple(
            Icons.Default.CheckCircle,
            "Connected",
            MaterialTheme.colorScheme.primary
        )
        ServerStatus.Offline -> Triple(
            Icons.Default.Warning,
            "Not logged in",
            MaterialTheme.colorScheme.error
        )
        ServerStatus.Unknown -> Triple(
            Icons.Default.Warning,
            "Unknown status",
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
    }

    if (status != ServerStatus.Unknown || showWhenUnknown) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = modifier.size(size),
            tint = tint
        )
    }
}

/**
 * Returns the appropriate tint color for a server icon (e.g., Dns icon) based on authentication status.
 *
 * @param isAuthenticated Whether the server has a valid auth token
 * @return The appropriate color for the server icon
 */
@Composable
fun serverIconTint(isAuthenticated: Boolean): Color {
    return if (isAuthenticated) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
}

/**
 * Helper function to determine ServerStatus from auth token.
 *
 * @param authToken The server's authentication token (nullable)
 * @return ServerStatus.Online if token exists, ServerStatus.Offline otherwise
 */
fun getServerStatus(authToken: String?): ServerStatus {
    return if (authToken != null) ServerStatus.Online else ServerStatus.Offline
}
