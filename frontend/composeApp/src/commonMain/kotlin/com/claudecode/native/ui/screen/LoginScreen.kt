package com.claudecode.native.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.claudecode.native.ui.component.ServerSwitcher
import com.claudecode.native.ui.viewmodel.LoginUiState
import com.claudecode.native.ui.viewmodel.LoginViewModel
import com.claudecode.native.ui.viewmodel.ServerViewModel
import org.koin.compose.koinInject

@Composable
fun LoginScreen(
    viewModel: LoginViewModel = koinInject(),
    serverViewModel: ServerViewModel = koinInject(),
    onLoginSuccess: () -> Unit
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var isRegisterMode by remember { mutableStateOf(false) }
    var showAddServerDialog by remember { mutableStateOf(false) }

    val uiState by viewModel.uiState.collectAsState()
    val serverState by serverViewModel.state.collectAsState()

    LaunchedEffect(uiState) {
        if (uiState is LoginUiState.Success) {
            // Save token to current server
            val token = (uiState as LoginUiState.Success).response.token
            serverViewModel.saveCurrentToken(token)
            onLoginSuccess()
            viewModel.resetState()
        }
    }

    // Add Server Dialog
    if (showAddServerDialog) {
        AddServerDialog(
            onDismiss = { showAddServerDialog = false },
            onConfirm = { name, host ->
                serverViewModel.addServer(name, host)
                showAddServerDialog = false
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Claude Code Native",
            style = MaterialTheme.typography.headlineLarge
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Server Switcher
        ServerSwitcher(
            currentServer = serverState.currentServer,
            servers = serverState.servers,
            onServerSelected = { serverId ->
                serverViewModel.switchToServer(serverId)
                // Clear any previous login errors when switching server
                viewModel.clearError()
            },
            onManageServers = { showAddServerDialog = true },
            modifier = Modifier.widthIn(max = 400.dp),
            enabled = uiState !is LoginUiState.Loading
        )

        // Quick add server button if only one server exists
        if (serverState.servers.size == 1) {
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(
                onClick = { showAddServerDialog = true },
                enabled = uiState !is LoginUiState.Loading
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Add another server")
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("Username") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = uiState !is LoginUiState.Loading
        )

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = if (isRegisterMode) ImeAction.Next else ImeAction.Done
            ),
            keyboardActions = KeyboardActions(
                onDone = {
                    if (!isRegisterMode && username.isNotBlank() && password.isNotBlank()) {
                        viewModel.login(username, password)
                    }
                }
            ),
            enabled = uiState !is LoginUiState.Loading
        )

        if (isRegisterMode) {
            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = confirmPassword,
                onValueChange = { confirmPassword = it },
                label = { Text("Confirm Password") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        if (username.isNotBlank() && password.isNotBlank() && confirmPassword.isNotBlank()) {
                            viewModel.register(username, password, confirmPassword)
                        }
                    }
                ),
                enabled = uiState !is LoginUiState.Loading
            )
        }

        if (uiState is LoginUiState.Error) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = (uiState as LoginUiState.Error).message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = {
                if (isRegisterMode) {
                    viewModel.register(username, password, confirmPassword)
                } else {
                    viewModel.login(username, password)
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = uiState !is LoginUiState.Loading &&
                      username.isNotBlank() &&
                      password.isNotBlank() &&
                      (!isRegisterMode || confirmPassword.isNotBlank())
        ) {
            if (uiState is LoginUiState.Loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = MaterialTheme.colorScheme.onPrimary
                )
            } else {
                Text(if (isRegisterMode) "Register" else "Login")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        TextButton(
            onClick = {
                isRegisterMode = !isRegisterMode
                viewModel.clearError()
            }
        ) {
            Text(
                if (isRegisterMode) "Already have an account? Login"
                else "Don't have an account? Register"
            )
        }
    }
}

/**
 * Dialog for adding a new server from the login screen.
 */
@Composable
private fun AddServerDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, host: String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var hostError by remember { mutableStateOf<String?>(null) }

    val hostPattern = Regex("^[a-zA-Z0-9.-]+(:\\d+)?$")

    fun validateHost(): Boolean {
        val cleanedHost = host.trim()
            .removePrefix("http://")
            .removePrefix("https://")
            .substringBefore("/")

        return when {
            cleanedHost.isBlank() -> {
                hostError = "Host is required"
                false
            }
            !hostPattern.matches(cleanedHost) -> {
                hostError = "Invalid host format (e.g., localhost:8083)"
                false
            }
            else -> {
                hostError = null
                true
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Server") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Server Name") },
                    placeholder = { Text("My Server") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = host,
                    onValueChange = {
                        host = it
                        hostError = null
                    },
                    label = { Text("Host") },
                    placeholder = { Text("localhost:8083") },
                    singleLine = true,
                    isError = hostError != null,
                    supportingText = hostError?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (validateHost()) {
                        val serverName = name.trim().ifBlank { "Server" }
                        val cleanedHost = host.trim()
                            .removePrefix("http://")
                            .removePrefix("https://")
                            .substringBefore("/")
                        onConfirm(serverName, cleanedHost)
                    }
                },
                enabled = host.isNotBlank()
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
