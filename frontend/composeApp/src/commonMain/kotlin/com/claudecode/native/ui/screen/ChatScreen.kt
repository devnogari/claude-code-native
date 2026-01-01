package com.claudecode.native.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.ArrowDownward
import kotlinx.coroutines.launch
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalFocusManager
import com.claudecode.native.data.websocket.ConnectionState
import com.claudecode.native.ui.component.MessageBubble
import com.claudecode.native.ui.component.QueuedMessageBubble
import com.claudecode.native.ui.component.ProcessingIndicator
import com.claudecode.native.ui.component.SlashCommand
import com.claudecode.native.ui.component.SlashCommandMenu
import com.claudecode.native.ui.component.StreamingBubble
import com.claudecode.native.ui.component.ThinkingBubble
import com.claudecode.native.ui.component.toSlashCommand
import com.claudecode.native.ui.viewmodel.ChatViewModel
import com.claudecode.native.ui.viewmodel.ContentBlock
import org.koin.compose.koinInject
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    conversationId: String,
    viewModel: ChatViewModel = koinInject(),
    onBack: () -> Unit = {}
) {
    ChatScreenContent(
        conversationId = conversationId,
        viewModel = viewModel,
        onBack = onBack
    )
}

/**
 * Content composable for the chat screen.
 *
 * This is the main implementation used by both the standalone screen
 * and the adaptive layout. Extracted to allow reuse in split-screen mode.
 *
 * @param conversationId The conversation to connect to
 * @param viewModel ViewModel injected via Koin
 * @param onBack Callback when user wants to navigate back
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreenContent(
    conversationId: String,
    viewModel: ChatViewModel = koinInject(),
    onBack: () -> Unit = {}
) {
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    // Track if user is at bottom of the list (for showing scroll button and auto-scroll)
    val isAtBottom by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val lastVisibleItem = layoutInfo.visibleItemsInfo.lastOrNull()
            val totalItems = layoutInfo.totalItemsCount
            // Consider at bottom if: no items, last item visible, or very few items
            totalItems == 0 ||
                lastVisibleItem == null ||
                lastVisibleItem.index >= totalItems - 1 ||
                totalItems <= 3
        }
    }

    // Track if user manually scrolled up (to disable auto-scroll)
    var userScrolledUp by remember { mutableStateOf(false) }

    // Detect when user scrolls away from bottom
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to isAtBottom }
            .collect { (scrolling, atBottom) ->
                if (scrolling && !atBottom) {
                    userScrolledUp = true
                } else if (atBottom) {
                    userScrolledUp = false
                }
            }
    }

    // Menu and dialog states
    var showMenu by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var deleteMessage by remember { mutableStateOf<String?>(null) }

    val messages by viewModel.messages.collectAsState()
    val isStreaming by viewModel.isStreaming.collectAsState()
    val streamingContent by viewModel.streamingContent.collectAsState()
    val streamingTools by viewModel.streamingTools.collectAsState()
    val streamingBlocks by viewModel.streamingBlocks.collectAsState()
    val queuedMessages by viewModel.queuedMessages.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val error by viewModel.error.collectAsState()

    // Command state
    val availableCommands by viewModel.availableCommands.collectAsState()
    val commandsLoading by viewModel.commandsLoading.collectAsState()
    val slashCommands = availableCommands.map { it.toSlashCommand() }

    // Track if initial connection is complete to avoid duplicate sync on first resume
    var hasInitialized by remember { mutableStateOf(false) }

    // Connect when screen is displayed
    LaunchedEffect(conversationId) {
        viewModel.connect(conversationId)
        hasInitialized = true
    }

    // Load commands when connected (with slight delay to ensure project path is set)
    LaunchedEffect(connectionState) {
        if (connectionState == ConnectionState.Connected) {
            kotlinx.coroutines.delay(500) // Wait for project info to be loaded
            viewModel.loadCommands()
        }
    }

    // Sync messages when app returns to foreground (skip initial resume)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, conversationId) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && hasInitialized) {
                println("ChatScreen: ON_RESUME - syncing messages")
                viewModel.syncOnForeground()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.disconnect()
        }
    }

    // Filter out empty messages (no blocks) for display count
    val filteredMessagesCount = messages.count { it.blocks.isNotEmpty() }

    // Scroll to bottom when conversation first loads
    var hasScrolledToBottom by remember(conversationId) { mutableStateOf(false) }
    LaunchedEffect(conversationId, messages.size) {
        if (!hasScrolledToBottom && messages.isNotEmpty()) {
            // Wait for layout to stabilize
            kotlinx.coroutines.delay(100)
            try {
                // Verify layout is ready before scrolling
                val expectedCount = filteredMessagesCount
                val totalItems = listState.layoutInfo.totalItemsCount
                if (totalItems > 0 && totalItems >= expectedCount) {
                    listState.scrollToItem(totalItems - 1)
                    hasScrolledToBottom = true
                }
            } catch (e: Exception) {
                // Ignore layout exceptions
            }
        }
    }

    // Track if there are new messages when not at bottom
    var hasNewMessages by remember { mutableStateOf(false) }

    // Swipe to go back state
    var swipeOffset by remember { mutableStateOf(0f) }
    val swipeThreshold = 100f  // Minimum swipe distance to trigger back

    // Single auto-scroll effect: scroll to bottom when new content arrives (unless user scrolled up)
    // Wait for layout to stabilize before scrolling to prevent jumps during message reload
    LaunchedEffect(messages.size, streamingBlocks.size, isStreaming) {
        // Small delay to let LazyColumn layout stabilize after message list changes
        kotlinx.coroutines.delay(50)

        // Calculate expected item count (filtered messages + streaming bubble if active)
        val expectedCount = filteredMessagesCount + (if (isStreaming) 1 else 0) + queuedMessages.size
        val totalItems = listState.layoutInfo.totalItemsCount

        // Only scroll if layout is consistent with expected count
        if (totalItems > 0 && totalItems >= expectedCount - 1) {
            if (!userScrolledUp) {
                // Auto-scroll to bottom
                try {
                    listState.animateScrollToItem(totalItems - 1)
                } catch (e: Exception) {
                    try {
                        listState.scrollToItem(totalItems - 1)
                    } catch (_: Exception) {}
                }
                hasNewMessages = false
            } else {
                // User scrolled up, show new message indicator
                hasNewMessages = true
            }
        }
    }

    // Clear new messages indicator when user reaches bottom
    LaunchedEffect(isAtBottom) {
        if (isAtBottom) {
            hasNewMessages = false
        }
    }

    // Delete confirmation dialog
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete Session") },
            text = { Text("This will delete the Claude CLI session files and clear chat history. Start fresh?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        viewModel.deleteSession(
                            onSuccess = {
                                deleteMessage = "Session deleted successfully"
                            },
                            onError = { errorMsg ->
                                deleteMessage = errorMsg
                            }
                        )
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Delete result message
    if (deleteMessage != null) {
        LaunchedEffect(deleteMessage) {
            kotlinx.coroutines.delay(3000)
            deleteMessage = null
        }
    }

    Scaffold(
        modifier = Modifier.pointerInput(Unit) {
            detectHorizontalDragGestures(
                onDragStart = { swipeOffset = 0f },
                onDragEnd = {
                    if (swipeOffset > swipeThreshold) {
                        onBack()
                    }
                    swipeOffset = 0f
                },
                onDragCancel = { swipeOffset = 0f },
                onHorizontalDrag = { _, dragAmount ->
                    // Only track right swipes (positive drag from left edge)
                    if (dragAmount > 0 || swipeOffset > 0) {
                        swipeOffset = (swipeOffset + dragAmount).coerceAtLeast(0f)
                    }
                }
            )
        },
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("Chat")
                        // Thinking indicator in title bar
                        androidx.compose.animation.AnimatedVisibility(
                            visible = isStreaming,
                            enter = fadeIn(),
                            exit = fadeOut()
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = "Thinking...",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    // Stop button when streaming
                    androidx.compose.animation.AnimatedVisibility(
                        visible = isStreaming,
                        enter = fadeIn(),
                        exit = fadeOut()
                    ) {
                        TextButton(
                            onClick = { viewModel.stopGeneration() },
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Text("Stop")
                        }
                    }
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(
                                Icons.Default.MoreVert,
                                contentDescription = "More options"
                            )
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Delete Session") },
                                onClick = {
                                    showMenu = false
                                    showDeleteDialog = true
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            )
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Connection status bar
            ConnectionStatusBar(
                connectionState = connectionState,
                modifier = Modifier.fillMaxWidth(),
                onRetry = { viewModel.retryConnection() }
            )

            // Delete result message
            if (deleteMessage != null) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    color = if (deleteMessage?.contains("success") == true)
                        MaterialTheme.colorScheme.primaryContainer
                    else
                        MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        text = deleteMessage ?: "",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        color = if (deleteMessage?.contains("success") == true)
                            MaterialTheme.colorScheme.onPrimaryContainer
                        else
                            MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

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

            // Messages list - filter out empty messages (no blocks) to avoid blank spaces
            val filteredMessages = messages.filter { it.blocks.isNotEmpty() }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        // Clear focus (dismiss keyboard) when tapping on message area
                        detectTapGestures(onTap = { focusManager.clearFocus() })
                    }
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    state = listState,
                    contentPadding = PaddingValues(
                        top = 16.dp,
                        bottom = 16.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(
                        items = filteredMessages,
                        key = { it.id }
                    ) { message ->
                        MessageBubble(message = message)
                    }

                    // Show streaming bubble when receiving a response
                    if (isStreaming) {
                        item(key = "streaming") {
                            StreamingBubble(
                                content = streamingContent,
                                tools = streamingTools,
                                blocks = streamingBlocks
                            )
                        }
                    }

                    // Show queued messages (user messages waiting to be processed)
                    if (queuedMessages.isNotEmpty()) {
                        items(
                            items = queuedMessages,
                            key = { "queued_${it.hashCode()}" }
                        ) { queuedContent ->
                            QueuedMessageBubble(content = queuedContent)
                        }
                    }
                }

                // Scroll to bottom button with new message preview
                // Shows when not at bottom or when there are new messages
                androidx.compose.animation.AnimatedVisibility(
                    visible = !isAtBottom || hasNewMessages,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 16.dp)
                        .zIndex(1f),
                    enter = fadeIn() + slideInVertically { it },
                    exit = fadeOut() + slideOutVertically { it }
                ) {
                    // Get latest message preview for the button
                    val latestMessage = messages.lastOrNull()
                    val previewText = when {
                        isStreaming && streamingContent.isNotEmpty() ->
                            streamingContent.take(50).replace("\n", " ") + "..."
                        latestMessage != null ->
                            latestMessage.content.take(50).replace("\n", " ") + if (latestMessage.content.length > 50) "..." else ""
                        else -> null
                    }

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // New message preview chip
                        if (hasNewMessages && previewText != null) {
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.widthIn(max = 280.dp)
                            ) {
                                Text(
                                    text = previewText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    maxLines = 1,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                )
                            }
                        }

                        // Scroll to bottom button
                        SmallFloatingActionButton(
                            onClick = {
                                coroutineScope.launch {
                                    val totalItems = listState.layoutInfo.totalItemsCount
                                    if (totalItems > 0) {
                                        try {
                                            listState.animateScrollToItem(totalItems - 1)
                                        } catch (e: Exception) {
                                            listState.scrollToItem(totalItems - 1)
                                        }
                                    }
                                    hasNewMessages = false
                                    userScrolledUp = false
                                }
                            },
                            containerColor = if (hasNewMessages) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                            contentColor = if (hasNewMessages) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            shape = CircleShape
                        ) {
                            Icon(
                                Icons.Default.ArrowDownward,
                                contentDescription = "Scroll to bottom"
                            )
                        }
                    }
                }
            }

            // Slash command menu - shows when input starts with "/"
            val showSlashMenu = inputText.startsWith("/") && !isStreaming
            val slashFilter = if (inputText.startsWith("/")) {
                inputText.removePrefix("/").takeWhile { !it.isWhitespace() }
            } else ""

            SlashCommandMenu(
                visible = showSlashMenu,
                filter = slashFilter,
                commands = slashCommands,
                isLoading = commandsLoading,
                onCommandSelected = { command ->
                    handleSlashCommand(
                        command = command,
                        viewModel = viewModel,
                        onClearInput = { inputText = "" },
                        onShowDeleteDialog = { showDeleteDialog = true }
                    )
                },
                modifier = Modifier.padding(bottom = 8.dp)
            )

            // Input area
            ChatInputBar(
                inputText = inputText,
                onInputChange = { inputText = it },
                isStreaming = isStreaming,
                isConnected = connectionState == ConnectionState.Connected,
                onSend = {
                    // Check if it's a slash command
                    if (inputText.startsWith("/")) {
                        val commandName = inputText.removePrefix("/").split(" ").firstOrNull() ?: ""
                        val command = slashCommands.find { it.name.equals(commandName, ignoreCase = true) }
                        if (command != null) {
                            handleSlashCommand(
                                command = command,
                                viewModel = viewModel,
                                onClearInput = { inputText = "" },
                                onShowDeleteDialog = { showDeleteDialog = true }
                            )
                        } else {
                            // Unknown command - send as regular message to Claude
                            viewModel.sendMessage(inputText)
                            inputText = ""
                        }
                    } else {
                        viewModel.sendMessage(inputText)
                        inputText = ""
                    }
                },
                onStop = { viewModel.stopGeneration() },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            )
        }
    }
}

/**
 * Connection status bar shown when not connected.
 */
@Composable
private fun ConnectionStatusBar(
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
                modifier = Modifier
                    .weight(1f)
                    .onPreviewKeyEvent { keyEvent ->
                        // Desktop: Enter to send (without Shift), Shift+Enter for newline
                        if (keyEvent.key == Key.Enter && keyEvent.type == KeyEventType.KeyDown) {
                            if (!keyEvent.isShiftPressed && inputText.isNotBlank() && isConnected) {
                                onSend()
                                true // Consume the event
                            } else {
                                false // Let Shift+Enter pass through for newline
                            }
                        } else {
                            false
                        }
                    },
                placeholder = {
                    Text(
                        if (!isConnected) "Read-only (viewing history)"
                        else if (isStreaming) "Type to queue message..."
                        else "Type a message..."
                    )
                },
                enabled = true,  // Always enabled - allow typing to queue messages during streaming
                singleLine = false,
                maxLines = 4,
                // iOS: Use keyboard send action
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(
                    onSend = {
                        if (inputText.isNotBlank() && isConnected) {
                            onSend()
                        }
                    }
                )
            )

            // Show both Stop button (when streaming) and Send button (always)
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
            }

            // Send button - always visible, queues message during streaming
            IconButton(
                onClick = onSend,
                enabled = inputText.isNotBlank() && isConnected,
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = if (isStreaming) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    contentColor = if (isStreaming) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onPrimary
                    },
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = if (isStreaming) "Queue message" else "Send message"
                )
            }
        }
    }
}

/**
 * Handles slash command execution.
 *
 * For builtin commands, uses the backend API for execution.
 * For custom commands (project/user), sends the processed content as a message.
 *
 * @param command The slash command to execute
 * @param viewModel The chat view model for actions
 * @param onClearInput Callback to clear the input field
 * @param onShowDeleteDialog Callback to show the delete session dialog
 */
private fun handleSlashCommand(
    command: SlashCommand,
    viewModel: ChatViewModel,
    onClearInput: () -> Unit,
    onShowDeleteDialog: () -> Unit
) {
    // Handle local-only commands that don't need API
    when (command.name.lowercase()) {
        "clear" -> {
            viewModel.clearMessages()
            onClearInput()
            return
        }
        "reset" -> {
            onShowDeleteDialog()
            onClearInput()
            return
        }
    }

    // Execute command via API
    val commandName = "/${command.name}"
    viewModel.executeCommand(
        commandName = commandName,
        commandPath = command.path,
        args = emptyList(),
        onBuiltinResult = { response ->
            // Handle builtin command results
            when (response.action?.lowercase()) {
                "help" -> {
                    // Help content is returned in response.content
                    response.content?.let { content ->
                        // Display help as a system message or send to Claude for formatting
                        viewModel.sendMessage(content)
                    }
                }
                "model" -> {
                    // Model switching would be handled here
                    response.content?.let { viewModel.sendMessage(it) }
                }
                "cost" -> {
                    // Cost info would be displayed
                    response.content?.let { viewModel.sendMessage(it) }
                }
                "memory" -> {
                    // Memory/CLAUDE.md info
                    response.content?.let { viewModel.sendMessage(it) }
                }
                "config" -> {
                    // Config info
                    response.content?.let { viewModel.sendMessage(it) }
                }
                "status" -> {
                    // Status info
                    response.content?.let { viewModel.sendMessage(it) }
                }
                "rewind" -> {
                    // Rewind action
                    response.content?.let { viewModel.sendMessage(it) }
                }
                else -> {
                    // Default: send any content as message
                    response.content?.let { viewModel.sendMessage(it) }
                }
            }
        }
    )
    onClearInput()
}
