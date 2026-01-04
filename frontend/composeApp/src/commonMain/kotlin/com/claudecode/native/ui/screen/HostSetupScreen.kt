package com.claudecode.native.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.claudecode.native.ui.viewmodel.ServerViewModel
import org.koin.compose.koinInject

/**
 * Host setup screen for configuring the server host before login.
 *
 * @param onHostConfigured Callback when host is successfully configured
 * @param initialHost Optional initial host value (for editing)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostSetupScreen(
    serverViewModel: ServerViewModel = koinInject(),
    onHostConfigured: () -> Unit,
    initialHost: String? = null
) {
    var host by remember { mutableStateOf(initialHost ?: "localhost:8083") }
    var isValidating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val focusManager = LocalFocusManager.current

    fun saveAndProceed() {
        val trimmedHost = host.trim()
        if (trimmedHost.isEmpty()) {
            error = "Server host is required"
            return
        }

        // Basic validation - should contain host:port or just host
        if (!trimmedHost.matches(Regex("^[a-zA-Z0-9.-]+(:\\d+)?$"))) {
            error = "Invalid host format. Use 'hostname:port' or 'hostname'"
            return
        }

        // Add server to the list and proceed
        serverViewModel.addServer(
            name = "Default Server",
            host = trimmedHost,
            description = "Initial server configuration"
        )
        onHostConfigured()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Server Configuration") }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                Icons.Default.Dns,
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Configure Server",
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Enter the Claude Code Native server address",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(32.dp))

            OutlinedTextField(
                value = host,
                onValueChange = {
                    host = it
                    error = null
                },
                label = { Text("Server Host") },
                placeholder = { Text("localhost:8083") },
                singleLine = true,
                isError = error != null,
                supportingText = {
                    if (error != null) {
                        Text(error!!, color = MaterialTheme.colorScheme.error)
                    } else {
                        Text("Format: hostname:port (e.g., localhost:8083)")
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = {
                        focusManager.clearFocus()
                        saveAndProceed()
                    }
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = { saveAndProceed() },
                enabled = !isValidating && host.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isValidating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Icon(Icons.Default.Check, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Continue")
                }
            }
        }
    }
}
