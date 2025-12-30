package com.claudecode.native.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.claudecode.native.data.websocket.ConnectionState
import com.claudecode.native.ui.component.MessageBubble
import com.claudecode.native.ui.component.StreamingBubble
import com.claudecode.native.ui.viewmodel.ChatViewModel
import org.koin.compose.koinInject

/**
 * Chat screen for real-time messaging with Claude.
 *
 * Features:
 * - Real-time message streaming via WebSocket
 * - Connection status indicator
 * - Auto-scroll to latest messages
 * - Send/Stop button based on streaming state
 *
 * @param conversationId The conversation to connect to
 * @param viewModel ViewModel injected via Koin
 * @param onBack Callback when user wants to navigate back
 */
@Composable
fun ChatScreen(
    conversationId: String,
    viewModel: ChatViewModel = koinInject(),
    onBack: () -> Unit = {}
) {
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    val messages by viewModel.messages.collectAsState()
    val isStreaming by viewModel.isStreaming.collectAsState()
    val streamingContent by viewModel.streamingContent.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val error by viewModel.error.collectAsState()

    // Connect when screen is displayed
    LaunchedEffect(conversationId) {
        viewModel.connect(conversationId)
    }

    // Disconnect when leaving the screen
    DisposableEffect(Unit) {
        onDispose {
            viewModel.disconnect()
        }
    }

    // Auto-scroll to bottom when new messages arrive or streaming content updates
    LaunchedEffect(messages.size, streamingContent) {
        if (messages.isNotEmpty() || streamingContent.isNotEmpty()) {
            val targetIndex = if (isStreaming) messages.size else messages.size - 1
            if (targetIndex >= 0) {
                listState.animateScrollToItem(targetIndex.coerceAtLeast(0))
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Connection status bar
        ConnectionStatusBar(
            connectionState = connectionState,
            modifier = Modifier.fillMaxWidth()
        )

        // Error snackbar
        if (error != null) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                shape = MaterialTheme.shapes.small
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = error ?: "",
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { viewModel.clearError() }) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Dismiss",
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
        }

        // Messages list
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            state = listState,
            contentPadding = PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(
                items = messages,
                key = { it.id }
            ) { message ->
                MessageBubble(message = message)
            }

            // Show streaming bubble when receiving a response
            if (isStreaming) {
                item(key = "streaming") {
                    StreamingBubble(content = streamingContent)
                }
            }
        }

        // Input area
        ChatInputBar(
            inputText = inputText,
            onInputChange = { inputText = it },
            isStreaming = isStreaming,
            isConnected = connectionState == ConnectionState.Connected,
            onSend = {
                viewModel.sendMessage(inputText)
                inputText = ""
            },
            onStop = { viewModel.stopGeneration() },
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        )
    }
}

/**
 * Connection status bar shown when not connected.
 */
@Composable
private fun ConnectionStatusBar(
    connectionState: ConnectionState,
    modifier: Modifier = Modifier
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

        is ConnectionState.Error -> {
            Surface(
                modifier = modifier,
                color = MaterialTheme.colorScheme.errorContainer
            ) {
                Text(
                    text = "Connection error: ${connectionState.message}",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
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

/**
 * Input bar for composing and sending messages.
 */
@Composable
private fun ChatInputBar(
    inputText: String,
    onInputChange: (String) -> Unit,
    isStreaming: Boolean,
    isConnected: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        tonalElevation = 2.dp,
        shape = MaterialTheme.shapes.large
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = inputText,
                onValueChange = onInputChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Type a message...") },
                enabled = !isStreaming && isConnected,
                singleLine = false,
                maxLines = 4
            )

            if (isStreaming) {
                // Stop button during streaming
                IconButton(
                    onClick = onStop,
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Stop generation"
                    )
                }
            } else {
                // Send button when not streaming
                IconButton(
                    onClick = onSend,
                    enabled = inputText.isNotBlank() && isConnected,
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send message"
                    )
                }
            }
        }
    }
}
