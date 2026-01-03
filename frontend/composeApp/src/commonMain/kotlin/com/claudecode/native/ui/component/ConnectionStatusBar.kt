package com.claudecode.native.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.claudecode.native.data.websocket.ConnectionState

/**
 * Status bar showing WebSocket connection state.
 *
 * Displays different states:
 * - Connecting: Shows progress indicator with "Connecting..." text
 * - Reconnecting: Shows progress with attempt count
 * - Error: Shows error message with retry button
 * - Disconnected: Shows "Disconnected" text
 * - Connected: Hidden (no status bar shown)
 *
 * @param connectionState Current WebSocket connection state
 * @param modifier Modifier for the status bar container
 * @param onRetry Callback when retry button is clicked (for error state)
 */
@Composable
fun ConnectionStatusBar(
    connectionState: ConnectionState,
    modifier: Modifier = Modifier,
    onRetry: () -> Unit = {}
) {
    when (connectionState) {
        is ConnectionState.Connecting -> {
            Surface(
                modifier = modifier,
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Text(
                        text = "Connecting...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }

        is ConnectionState.Reconnecting -> {
            Surface(
                modifier = modifier,
                color = MaterialTheme.colorScheme.tertiaryContainer
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                    Text(
                        text = "Reconnecting... (attempt ${connectionState.attempt})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
            }
        }

        is ConnectionState.Error -> {
            Surface(
                modifier = modifier,
                color = MaterialTheme.colorScheme.errorContainer
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Connection error: ${connectionState.message}",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    TextButton(
                        onClick = onRetry,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        )
                    ) {
                        Text("Retry")
                    }
                }
            }
        }

        is ConnectionState.Disconnected -> {
            Surface(
                modifier = modifier,
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Text(
                    text = "Disconnected",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        is ConnectionState.Connected -> {
            // No status bar when connected
        }
    }
}
