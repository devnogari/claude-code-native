package com.claudecode.native.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.claudecode.native.data.model.ServerConfig

/**
 * Compact server switcher for the sidebar/top bar.
 * Shows current server and allows quick switching via dropdown.
 */
@Composable
fun ServerSwitcher(
    currentServer: ServerConfig?,
    servers: List<ServerConfig>,
    onServerSelected: (String) -> Unit,
    onManageServers: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        // Current server indicator
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled) { expanded = true },
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.small
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Dns,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = serverIconTint(isAuthenticated = currentServer?.authToken != null)
                )

                Spacer(modifier = Modifier.width(8.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = currentServer?.name ?: "No Server",
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = currentServer?.host ?: "Click to add server",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Connection status indicator
                if (currentServer != null) {
                    Spacer(modifier = Modifier.width(4.dp))
                    ServerStatusIndicator(
                        status = getServerStatus(currentServer.authToken),
                        size = 16.dp
                    )
                }

                Spacer(modifier = Modifier.width(4.dp))

                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = "Toggle server list",
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        // Dropdown menu
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            servers.forEach { server ->
                DropdownMenuItem(
                    text = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = server.name,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = server.host,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            // Current server indicator
                            if (server.id == currentServer?.id) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = "Current",
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }

                            // Login status
                            if (server.authToken != null) {
                                Spacer(modifier = Modifier.width(4.dp))
                                ServerStatusIndicator(
                                    status = ServerStatus.Online,
                                    size = 16.dp
                                )
                            }
                        }
                    },
                    onClick = {
                        expanded = false
                        if (server.id != currentServer?.id) {
                            onServerSelected(server.id)
                        }
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Dns,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                )
            }

            if (servers.isNotEmpty()) {
                HorizontalDivider()
            }

            // Manage servers option
            DropdownMenuItem(
                text = { Text("Manage Servers...") },
                onClick = {
                    expanded = false
                    onManageServers()
                },
                leadingIcon = {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                }
            )
        }
    }
}

/**
 * Compact server indicator showing just the current server name.
 * Used in tighter spaces like mobile headers.
 */
@Composable
fun ServerIndicator(
    currentServer: ServerConfig?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Dns,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = serverIconTint(isAuthenticated = currentServer?.authToken != null)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = currentServer?.name ?: "No Server",
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Icon(
                Icons.Default.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}
