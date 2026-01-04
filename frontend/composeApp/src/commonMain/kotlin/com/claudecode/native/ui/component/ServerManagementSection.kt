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
import com.claudecode.native.ui.viewmodel.ServerViewModel
import org.koin.compose.koinInject

/** Server management section for settings screen. Handles server CRUD operations. */
@Composable
fun ServerManagementSection(
    viewModel: ServerViewModel = koinInject(),
    sectionWrapper: @Composable (title: String, content: @Composable ColumnScope.() -> Unit) -> Unit
) {
    val state by viewModel.state.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var editingServer by remember { mutableStateOf<ServerConfig?>(null) }
    var deleteTarget by remember { mutableStateOf<ServerConfig?>(null) }

    sectionWrapper("Servers") {
        state.currentServer?.let { CurrentServerIndicator(it); Spacer(Modifier.height(12.dp)) }
        state.servers.sortedByDescending { it.lastConnectedAt ?: 0L }.forEach { server ->
            ServerListItem(server, server.id == state.currentServerId, { viewModel.switchToServer(server.id) }, { editingServer = server }, { deleteTarget = server }, !isLoading)
            Spacer(Modifier.height(8.dp))
        }
        if (state.servers.isEmpty()) {
            Text("No servers configured", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
        }
        OutlinedButton({ showAddDialog = true }, Modifier.fillMaxWidth(), !isLoading) {
            Icon(Icons.Default.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Add Server")
        }
    }

    if (showAddDialog) ServerEditDialog(null, { showAddDialog = false }) { n, h, d -> viewModel.addServer(n, h, d); showAddDialog = false }
    editingServer?.let { s -> ServerEditDialog(s, { editingServer = null }) { n, h, d -> viewModel.updateServer(s.id, n, h, d); editingServer = null } }
    deleteTarget?.let { s -> DeleteServerDialog(s.name, { deleteTarget = null }) { viewModel.deleteServer(s.id); deleteTarget = null } }
}

@Composable
private fun CurrentServerIndicator(server: ServerConfig) {
    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primaryContainer.copy(0.3f), shape = MaterialTheme.shapes.small) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.CheckCircle, "Current", Modifier.size(20.dp), MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text("Current: ${server.name}", style = MaterialTheme.typography.bodyMedium)
                Text(server.host, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (server.authToken != null) ServerStatusIndicator(ServerStatus.Online, size = 16.dp)
        }
    }
}

@Composable
private fun ServerListItem(server: ServerConfig, isCurrent: Boolean, onSwitch: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit, enabled: Boolean) {
    val bg = if (isCurrent) MaterialTheme.colorScheme.primaryContainer.copy(0.3f) else MaterialTheme.colorScheme.surfaceVariant.copy(0.5f)
    Surface(Modifier.fillMaxWidth().clickable(enabled && !isCurrent, onClick = onSwitch), color = bg, shape = MaterialTheme.shapes.small) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Dns, null, Modifier.size(24.dp), serverIconTint(server.authToken != null))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(server.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, false))
                    if (isCurrent) { Spacer(Modifier.width(8.dp)); ActiveBadge() }
                }
                Text(server.host, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (server.description.isNotBlank()) Text(server.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (server.authToken != null) { ServerStatusIndicator(ServerStatus.Online, size = 16.dp); Spacer(Modifier.width(4.dp)) }
            IconButton(onEdit, Modifier.size(32.dp), enabled) { Icon(Icons.Default.Edit, "Edit", Modifier.size(18.dp)) }
            IconButton(onDelete, Modifier.size(32.dp), enabled) { Icon(Icons.Default.Delete, "Delete", Modifier.size(18.dp), MaterialTheme.colorScheme.error.copy(0.7f)) }
        }
    }
}

@Composable
private fun ActiveBadge() = Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.primary) {
    Text("Active", Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimary)
}

@Composable
private fun ServerEditDialog(server: ServerConfig?, onDismiss: () -> Unit, onSave: (String, String, String) -> Unit) {
    var name by remember { mutableStateOf(server?.name ?: "") }
    var host by remember { mutableStateOf(server?.host ?: "") }
    var desc by remember { mutableStateOf(server?.description ?: "") }
    val isEdit = server != null
    AlertDialog(onDismiss, { Button({ onSave(name, host, desc) }, enabled = name.isNotBlank() && host.isNotBlank()) { Text(if (isEdit) "Save" else "Add") } },
        Modifier, { TextButton(onDismiss) { Text("Cancel") } }, null, { Text(if (isEdit) "Edit Server" else "Add Server") },
        { Column(Modifier, Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Name") }, placeholder = { Text("My Server") }, singleLine = true)
            OutlinedTextField(host, { host = it }, Modifier.fillMaxWidth(), label = { Text("Host") }, placeholder = { Text("192.168.1.100:8083") }, singleLine = true, supportingText = { Text("IP:port (e.g., localhost:8083)") })
            OutlinedTextField(desc, { desc = it }, Modifier.fillMaxWidth(), label = { Text("Description (optional)") }, placeholder = { Text("Production server") }, singleLine = true)
        } })
}

@Composable
private fun DeleteServerDialog(serverName: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(onDismiss, { Button(onConfirm, colors = ButtonDefaults.buttonColors(MaterialTheme.colorScheme.error)) { Text("Delete") } },
        Modifier, { TextButton(onDismiss) { Text("Cancel") } }, null, { Text("Delete Server") }, { Text("Delete \"$serverName\"? You'll need to add it again.") })
}
