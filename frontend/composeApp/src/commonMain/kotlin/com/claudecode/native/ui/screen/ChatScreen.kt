package com.claudecode.native.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.ArrowDownward
import kotlinx.coroutines.launch
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import com.claudecode.native.data.websocket.ConnectionState
import com.claudecode.native.ui.component.AttachedImagesPreview
import com.claudecode.native.ui.component.MessageBubble
import com.claudecode.native.ui.component.QueuedMessageBubble
import com.claudecode.native.ui.component.ProcessingIndicator
import com.claudecode.native.ui.component.SlashCommand
import com.claudecode.native.ui.component.SlashCommandMenu
import com.claudecode.native.ui.component.StatusLine
import com.claudecode.native.ui.component.toSlashCommand
import com.claudecode.native.ui.viewmodel.AttachedImage
import com.claudecode.native.ui.viewmodel.ChatViewModel
import com.claudecode.native.ui.viewmodel.ContentBlock
import com.claudecode.native.ui.viewmodel.ImageSource
import com.claudecode.native.ui.viewmodel.QueuedMessageSource
import com.claudecode.native.util.ImagePicker
import com.claudecode.native.util.PickedImage
import org.koin.compose.koinInject
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Image
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.claudecode.native.util.DropState
import com.claudecode.native.util.imageDropTarget

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
 * @param onSessionCreated Callback when a new session is created from draft mode (sessionId, encodedPath for refreshing sidebar)
 * @param onNewSession Callback when user wants to start a new session (disabled during draft sessions)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreenContent(
    conversationId: String,
    viewModel: ChatViewModel = koinInject(),
    onBack: () -> Unit = {},
    onSessionCreated: (sessionId: String, encodedPath: String) -> Unit = { _, _ -> },
    onNewSession: () -> Unit = {}
) {
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    // Prevent TextField from receiving focus during initial composition (iOS keyboard fix)
    var canFocusInput by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(300)
        canFocusInput = true
    }

    // Track if user is at bottom of the list (for showing scroll button and auto-scroll)
    // With reverseLayout=true, index 0 is at the bottom (most recent messages)
    val isAtBottom by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val firstVisibleItem = layoutInfo.visibleItemsInfo.firstOrNull()
            val totalItems = layoutInfo.totalItemsCount
            // Consider at bottom if: no items, first item (index 0) visible, or very few items
            totalItems == 0 ||
                firstVisibleItem == null ||
                firstVisibleItem.index == 0 ||
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
    val queuedMessages by viewModel.queuedMessages.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val error by viewModel.error.collectAsState()
    val conversationTitle by viewModel.conversationTitle.collectAsState()
    val scrollToBottomSignal by viewModel.scrollToBottomSignal.collectAsState()
    val isDraftSession by viewModel.isDraftSession.collectAsState()
    val sessionCreatedEvent by viewModel.sessionCreatedEvent.collectAsState()
    // Re-collect progressStatus when conversation changes (progressStatus is a computed property
    // that returns different StateFlow based on currentConversationId)
    val progressStatus by remember(conversationId) { viewModel.progressStatus }.collectAsState()
    val attachedImages by viewModel.attachedImages.collectAsState()

    // Image picker
    val imagePicker = remember { ImagePicker() }

    // Drag and drop state
    var dropState by remember { mutableStateOf(DropState()) }

    // Notify when a new session is created from draft mode
    LaunchedEffect(sessionCreatedEvent) {
        sessionCreatedEvent?.let { sessionInfo ->
            println("ChatScreen: Session created event received: sessionId=${sessionInfo.sessionId}, encodedPath=${sessionInfo.encodedPath}")
            onSessionCreated(sessionInfo.sessionId, sessionInfo.encodedPath)
            viewModel.clearSessionCreatedEvent()
        }
    }

    // Command state
    val availableCommands by viewModel.availableCommands.collectAsState()
    val commandsLoading by viewModel.commandsLoading.collectAsState()
    val slashCommands = availableCommands.map { it.toSlashCommand() }

    // Track if initial connection is complete to avoid duplicate sync on first resume
    var hasInitialized by remember { mutableStateOf(false) }

    // Track if we're in the initial loading phase (before first connect attempt completes)
    // This prevents showing "Disconnected" status bar briefly on session start
    var isInitialLoading by remember(conversationId) { mutableStateOf(true) }

    // Connect when screen is displayed
    LaunchedEffect(conversationId) {
        println("ChatScreen: LaunchedEffect(${conversationId.take(20)}) - calling connect()")
        viewModel.connect(conversationId)
        hasInitialized = true
        // Mark initial loading complete after connection attempt starts
        // Small delay to let connection state transition to Connecting
        kotlinx.coroutines.delay(100)
        isInitialLoading = false
        println("ChatScreen: LaunchedEffect(${conversationId.take(20)}) - connect() returned")
    }

    // Note: Commands are loaded automatically by ChatViewModel when project path is set
    // (in loadMessages/loadMessagesFromFilesystem after currentProjectPath is determined)

    // Sync messages when app returns to foreground (skip initial resume)
    // NOTE: Keyed on Unit to prevent spurious disconnects on iOS
    // On iOS, LocalLifecycleOwner.current can change during keyboard events or view transitions,
    // which would trigger onDispose and disconnect the WebSocket unexpectedly.
    // Using Unit ensures disconnect only happens when ChatScreen is truly removed from composition.
    val lifecycleOwner = LocalLifecycleOwner.current
    // Use rememberUpdatedState to ensure syncOnForeground always uses the latest callback
    val currentHasInitialized by rememberUpdatedState(hasInitialized)
    DisposableEffect(Unit) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && currentHasInitialized) {
                println("ChatScreen: ON_RESUME - syncing messages")
                viewModel.syncOnForeground()
            }
        }
        // Capture the lifecycle owner at effect creation time
        val capturedLifecycleOwner = lifecycleOwner
        capturedLifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            capturedLifecycleOwner.lifecycle.removeObserver(observer)
            // Only disconnect when truly leaving ChatScreen (e.g., navigating to settings)
            // Not when switching between rooms
            viewModel.disconnect()
        }
    }

    // Filter out empty messages (no blocks) for display count
    val filteredMessagesCount = messages.count { it.blocks.isNotEmpty() }

    // With reverseLayout=true, no initial scroll needed - list starts at bottom automatically

    // Track if there are new messages when not at bottom
    var hasNewMessages by remember { mutableStateOf(false) }

    // Swipe to go back state
    var swipeOffset by remember { mutableStateOf(0f) }
    val swipeThreshold = 100f  // Minimum swipe distance to trigger back

    // Single auto-scroll effect: scroll to bottom when new content arrives (unless user scrolled up)
    // With reverseLayout=true, index 0 is at the bottom (most recent)
    LaunchedEffect(messages.size, isStreaming) {
        // Small delay to let LazyColumn layout stabilize after message list changes
        kotlinx.coroutines.delay(50)

        val totalItems = listState.layoutInfo.totalItemsCount
        if (totalItems > 0) {
            if (!userScrolledUp) {
                // Auto-scroll to bottom (index 0 with reverseLayout)
                try {
                    listState.animateScrollToItem(0)
                } catch (e: Exception) {
                    try {
                        listState.scrollToItem(0)
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

    // Scroll to bottom when ViewModel signals (on send message, streaming complete, etc.)
    // With reverseLayout=true, index 0 is at the bottom
    LaunchedEffect(scrollToBottomSignal) {
        if (scrollToBottomSignal > 0) {
            // Small delay for layout to stabilize
            kotlinx.coroutines.delay(50)
            val totalItems = listState.layoutInfo.totalItemsCount
            if (totalItems > 0) {
                try {
                    listState.animateScrollToItem(0)
                } catch (e: Exception) {
                    println("ChatScreen: Animate scroll failed, falling back to immediate scroll. Error: ${e.message}")
                    try {
                        listState.scrollToItem(0)
                    } catch (scrollError: Exception) {
                        println("ChatScreen: Immediate scroll also failed. Error: ${scrollError.message}")
                    }
                }
                userScrolledUp = false
                hasNewMessages = false
            }
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
        modifier = Modifier
            .imageDropTarget(
                enabled = connectionState == ConnectionState.Connected || isDraftSession,
                onDragStateChange = { dropState = it },
                onImageDropped = { images ->
                    images.forEach { picked ->
                        viewModel.addAttachedImage(
                            data = picked.data,
                            mediaType = picked.mediaType,
                            fileName = picked.fileName
                        )
                    }
                }
            )
            .pointerInput(Unit) {
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
                    Text(
                        text = conversationTitle?.take(50)?.let {
                            if (it.length < (conversationTitle?.length ?: 0)) "$it..." else it
                        } ?: "Chat",
                        maxLines = 1,
                        style = MaterialTheme.typography.titleMedium
                    )
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
                    // New Session button - disabled for draft sessions (not yet created)
                    IconButton(
                        onClick = onNewSession,
                        enabled = !isDraftSession
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = "New Session",
                            tint = if (isDraftSession)
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            else
                                MaterialTheme.colorScheme.onSurface
                        )
                    }
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                // Connection status bar
            // - Hide during initial loading to avoid "Disconnected" flash
            // - Hide for draft sessions (not connected until first message sent)
            if (!isInitialLoading && !isDraftSession) {
                ConnectionStatusBar(
                    connectionState = connectionState,
                    modifier = Modifier.fillMaxWidth(),
                    onRetry = { viewModel.retryConnection() }
                )
            }

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

            // Messages list - filter out empty messages
            val filteredMessages = messages.filter { msg ->
                msg.blocks.isNotEmpty()
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        // Clear focus (dismiss keyboard) when tapping on message area
                        detectTapGestures(onTap = { focusManager.clearFocus() })
                    }
            ) {
                // reverseLayout=true: items stack from bottom, index 0 is at the bottom
                // This eliminates the initial scroll animation when entering a chat room
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    state = listState,
                    reverseLayout = true,
                    contentPadding = PaddingValues(
                        top = 16.dp,
                        bottom = 16.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // With reverseLayout, we need to add items in reverse order
                    // Most recent items (queued) go first (at index 0 = bottom)

                    // Show queued messages (user messages waiting to be processed)
                    if (queuedMessages.isNotEmpty()) {
                        val reversedQueue = queuedMessages.reversed()
                        val totalQueueSize = queuedMessages.size
                        itemsIndexed(
                            items = reversedQueue,
                            key = { _, msg -> "queued_${msg.id}" }
                        ) { index, queuedMsg ->
                            // Position in queue (1-based, from original order)
                            // reversedQueue[0] is the last item in queue
                            val queuePosition = totalQueueSize - index
                            // Convert AttachedImages to ImageSource for display
                            val imageSources = queuedMsg.images.map { img ->
                                ImageSource.Base64(
                                    data = kotlin.io.encoding.Base64.encode(img.data),
                                    mediaType = img.mediaType
                                )
                            }
                            QueuedMessageBubble(
                                content = queuedMsg.content,
                                images = imageSources,
                                position = queuePosition,
                                isFromCli = queuedMsg.source == QueuedMessageSource.CLI,
                                onCancel = if (queuedMsg.source == QueuedMessageSource.LOCAL) {
                                    { viewModel.cancelQueuedMessage(queuedMsg.id) }
                                } else null
                            )
                        }
                    }

                    // Messages in reverse order (newest at index 0 = bottom)
                    items(
                        items = filteredMessages.reversed(),
                        key = { it.id }
                    ) { message ->
                        MessageBubble(message = message)
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

                        // Scroll to bottom button (with reverseLayout, index 0 is bottom)
                        SmallFloatingActionButton(
                            onClick = {
                                coroutineScope.launch {
                                    val totalItems = listState.layoutInfo.totalItemsCount
                                    if (totalItems > 0) {
                                        try {
                                            listState.animateScrollToItem(0)
                                        } catch (e: Exception) {
                                            listState.scrollToItem(0)
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
            // Allow during streaming too (user can queue slash commands)
            val showSlashMenu = inputText.startsWith("/")
            val slashFilter = if (inputText.startsWith("/")) {
                inputText.removePrefix("/").takeWhile { !it.isWhitespace() }
            } else ""

            SlashCommandMenu(
                visible = showSlashMenu,
                filter = slashFilter,
                commands = slashCommands,
                isLoading = commandsLoading,
                onCommandSelected = { command ->
                    // Parse args from current input (if any text after command name)
                    val parts = inputText.removePrefix("/").split(" ")
                    val commandArgs = if (parts.size > 1) parts.drop(1) else emptyList()
                    handleSlashCommand(
                        command = command,
                        args = commandArgs,
                        viewModel = viewModel,
                        onClearInput = { inputText = "" },
                        onShowDeleteDialog = { showDeleteDialog = true },
                        onResetScroll = { userScrolledUp = false }
                    )
                },
                modifier = Modifier.padding(bottom = 8.dp)
            )

            // Status line (Claude Code style progress indicator)
            // Note: ESC key handling for stop is in ChatInputBar
            StatusLine(status = progressStatus)

            // Input area
            // Allow input in draft mode even when not connected (session will be created on first send)
            ChatInputBar(
                inputText = inputText,
                onInputChange = { inputText = it },
                isStreaming = isStreaming,
                isConnected = connectionState == ConnectionState.Connected || isDraftSession,
                attachedImages = attachedImages,
                onAttachImages = {
                    coroutineScope.launch {
                        val pickedImages = imagePicker.pickImages()
                        pickedImages.forEach { picked ->
                            viewModel.addAttachedImage(
                                data = picked.data,
                                mediaType = picked.mediaType,
                                fileName = picked.fileName
                            )
                        }
                    }
                },
                onRemoveImage = { imageId -> viewModel.removeAttachedImage(imageId) },
                onSend = {
                    // Check if it's a slash command
                    if (inputText.startsWith("/")) {
                        val parts = inputText.removePrefix("/").split(" ")
                        val commandName = parts.firstOrNull() ?: ""
                        val commandArgs = if (parts.size > 1) parts.drop(1) else emptyList()
                        val command = slashCommands.find { it.name.equals(commandName, ignoreCase = true) }
                        if (command != null) {
                            handleSlashCommand(
                                command = command,
                                args = commandArgs,
                                viewModel = viewModel,
                                onClearInput = { inputText = "" },
                                onShowDeleteDialog = { showDeleteDialog = true },
                                onResetScroll = { userScrolledUp = false }
                            )
                        } else {
                            // Unknown command - send as regular message to Claude
                            viewModel.sendMessage(inputText)
                            inputText = ""
                            userScrolledUp = false  // Reset to enable auto-scroll for response
                        }
                    } else {
                        viewModel.sendMessage(inputText)
                        inputText = ""
                        userScrolledUp = false  // Reset to enable auto-scroll for response
                    }
                },
                onStop = { viewModel.stopGeneration() },
                canFocus = canFocusInput,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            )
            }

            // Drag and drop overlay
            AnimatedVisibility(
                visible = dropState.isHovering,
                modifier = Modifier.fillMaxSize(),
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
                        .border(
                            BorderStroke(3.dp, MaterialTheme.colorScheme.primary),
                            RoundedCornerShape(8.dp)
                        )
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.Image,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Drop images here",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
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
 * Input bar for composing and sending messages with image attachment support.
 */
@Composable
private fun ChatInputBar(
    inputText: String,
    onInputChange: (String) -> Unit,
    isStreaming: Boolean,
    isConnected: Boolean,
    attachedImages: List<AttachedImage> = emptyList(),
    onAttachImages: () -> Unit = {},
    onRemoveImage: (String) -> Unit = {},
    onSend: () -> Unit,
    onStop: () -> Unit,
    canFocus: Boolean = true,
    modifier: Modifier = Modifier
) {
    val hasContent = inputText.isNotBlank() || attachedImages.isNotEmpty()

    Surface(
        modifier = modifier,
        tonalElevation = 2.dp,
        shape = MaterialTheme.shapes.large
    ) {
        Column(
            modifier = Modifier.padding(8.dp)
        ) {
            // Attached images preview row
            if (attachedImages.isNotEmpty()) {
                AttachedImagesPreview(
                    images = attachedImages,
                    onRemove = onRemoveImage,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Image attachment button (enabled even during streaming for queued messages)
                IconButton(
                    onClick = onAttachImages,
                    enabled = isConnected
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = "Attach image",
                        tint = if (isConnected) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        }
                    )
                }

                OutlinedTextField(
                    value = inputText,
                    onValueChange = onInputChange,
                    modifier = Modifier
                        .weight(1f)
                        .focusProperties { this.canFocus = canFocus }
                        .onPreviewKeyEvent { keyEvent ->
                            when {
                                // ESC to stop streaming (Desktop)
                                keyEvent.key == Key.Escape && keyEvent.type == KeyEventType.KeyDown && isStreaming -> {
                                    onStop()
                                    true // Consume the event
                                }
                                // Desktop: Enter to send (without Shift), Shift+Enter for newline
                                keyEvent.key == Key.Enter && keyEvent.type == KeyEventType.KeyDown -> {
                                    if (keyEvent.isShiftPressed) {
                                        // Shift+Enter: Insert newline manually
                                        onInputChange(inputText + "\n")
                                        true // Consume the event
                                    } else if (hasContent && isConnected) {
                                        // Enter without Shift: Send message
                                        onSend()
                                        true // Consume the event
                                    } else {
                                        // Empty input: do nothing
                                        true
                                    }
                                }
                                else -> false
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
                            if (hasContent && isConnected) {
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
                    enabled = hasContent && isConnected,
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
}

/**
 * Handles slash command execution.
 *
 * For builtin commands, uses the backend API for execution.
 * For custom commands (project/user), sends the processed content as a message.
 *
 * @param command The slash command to execute
 * @param args Arguments passed to the command (parsed from input after command name)
 * @param viewModel The chat view model for actions
 * @param onClearInput Callback to clear the input field
 * @param onShowDeleteDialog Callback to show the delete session dialog
 * @param onResetScroll Callback to reset scroll state for auto-scroll
 */
private fun handleSlashCommand(
    command: SlashCommand,
    args: List<String>,
    viewModel: ChatViewModel,
    onClearInput: () -> Unit,
    onShowDeleteDialog: () -> Unit,
    onResetScroll: () -> Unit
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
        args = args,
        onBuiltinResult = { response ->
            // Most builtin commands are handled internally by ChatViewModel
            // (displaying results as local assistant messages).
            // This callback is only for commands that need custom UI handling.
            println("ChatScreen: Unhandled builtin command action: ${response.action}")
        }
    )
    onClearInput()
    onResetScroll()  // Reset scroll state to enable auto-scroll for command results
}
