package com.claudecode.native.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.claudecode.native.ui.component.ServerManagementSection
import com.claudecode.native.ui.viewmodel.ServerViewModel
import com.claudecode.native.ui.viewmodel.SettingsViewModel
import org.koin.compose.koinInject

/** Settings screen: Server management, appearance, data/storage, account, and app info. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = koinInject(),
    serverViewModel: ServerViewModel = koinInject(),
    onBack: () -> Unit,
    onLogout: () -> Unit
) {
    val isDarkMode by viewModel.isDarkMode.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    val message by viewModel.message.collectAsState()
    val logoutRequested by viewModel.logoutRequested.collectAsState()

    val serverError by serverViewModel.error.collectAsState()
    val serverMessage by serverViewModel.message.collectAsState()

    var showLogoutDialog by remember { mutableStateOf(false) }
    var showClearCacheDialog by remember { mutableStateOf(false) }

    LaunchedEffect(logoutRequested) {
        if (logoutRequested) {
            viewModel.resetLogoutState()
            onLogout()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues).imePadding()) {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Error/Message banners
                MessageBanner(error, MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer) { viewModel.clearError() }
                MessageBanner(message, MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer) { viewModel.clearMessage() }
                MessageBanner(serverError, MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer) { serverViewModel.clearError() }
                MessageBanner(serverMessage, MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer) { serverViewModel.clearMessage() }

                // Server Management Section (extracted component)
                ServerManagementSection(
                    viewModel = serverViewModel,
                    sectionWrapper = { title, content -> SettingsSection(title, content) }
                )

                // Appearance Section
                SettingsSection(title = "Appearance") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Dark Mode", style = MaterialTheme.typography.bodyLarge)
                            Text("Enable dark theme", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = isDarkMode, onCheckedChange = { viewModel.setDarkMode(it) })
                    }
                }

                // Data & Storage Section
                SettingsSection(title = "Data & Storage") {
                    OutlinedButton(onClick = { showClearCacheDialog = true }, modifier = Modifier.fillMaxWidth(), enabled = !isLoading) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Clear Cache")
                    }
                }

                // Account Section
                SettingsSection(title = "Account") {
                    Button(
                        onClick = { showLogoutDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        enabled = !isLoading
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Logout")
                    }
                }

                // App Info Section
                SettingsSection(title = "About") {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Version", style = MaterialTheme.typography.bodyMedium)
                        Text("${SettingsViewModel.APP_VERSION} (${SettingsViewModel.BUILD_NUMBER})", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Application", style = MaterialTheme.typography.bodyMedium)
                        Text("Claude Code Native", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }

    // Logout confirmation dialog
    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = { Text("Logout") },
            text = { Text("Are you sure you want to logout? You will need to sign in again to continue.") },
            confirmButton = {
                Button(
                    onClick = { showLogoutDialog = false; viewModel.logout() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Logout") }
            },
            dismissButton = { TextButton(onClick = { showLogoutDialog = false }) { Text("Cancel") } }
        )
    }

    // Clear cache confirmation dialog
    if (showClearCacheDialog) {
        AlertDialog(
            onDismissRequest = { showClearCacheDialog = false },
            title = { Text("Clear Cache") },
            text = { Text("This will clear all cached data. You may need to reload some content.") },
            confirmButton = {
                Button(onClick = { showClearCacheDialog = false; viewModel.clearCache() }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { showClearCacheDialog = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun MessageBanner(
    message: String?,
    containerColor: Color,
    contentColor: Color,
    onDismiss: () -> Unit
) {
    if (message != null) {
        Surface(modifier = Modifier.fillMaxWidth(), color = containerColor, shape = MaterialTheme.shapes.small) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(message, color = contentColor, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = contentColor)
                }
            }
        }
    }
}

@Composable
fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}
