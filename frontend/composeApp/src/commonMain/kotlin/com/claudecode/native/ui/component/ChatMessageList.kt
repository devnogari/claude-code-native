package com.claudecode.native.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.claudecode.native.ui.viewmodel.ChatMessage
import com.claudecode.native.ui.viewmodel.ImageSource
import com.claudecode.native.ui.viewmodel.QueuedMessage
import com.claudecode.native.ui.viewmodel.QueuedMessageSource

/**
 * Chat message list with queued messages, pagination, and scroll-to-bottom button.
 *
 * Features:
 * - Displays chat messages in reverse order (newest at bottom)
 * - Shows queued messages waiting to be processed
 * - Pagination loading indicator at top
 * - Scroll-to-bottom FAB with new message preview
 * - Tap to dismiss keyboard
 *
 * @param messages List of chat messages to display
 * @param queuedMessages List of queued messages waiting to be sent
 * @param listState LazyListState for scroll control
 * @param isLoadingMore Whether more messages are being loaded (pagination)
 * @param isAtBottom Whether the list is scrolled to the bottom
 * @param hasNewMessages Whether there are new unread messages
 * @param onScrollToBottom Callback to scroll to the bottom of the list
 * @param onCancelQueuedMessage Callback to cancel a queued message
 * @param modifier Modifier for the list container
 */
@Composable
fun ChatMessageList(
    messages: List<ChatMessage>,
    queuedMessages: List<QueuedMessage>,
    listState: LazyListState,
    isLoadingMore: Boolean,
    isAtBottom: Boolean,
    hasNewMessages: Boolean,
    onScrollToBottom: () -> Unit,
    onCancelQueuedMessage: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val focusManager = LocalFocusManager.current

    // Filter out empty messages (no blocks) for display
    val filteredMessages = remember(messages) {
        messages.filter { msg -> msg.blocks.isNotEmpty() }
    }
    // Pre-compute reversed list for LazyColumn with reverseLayout
    val reversedMessages = remember(filteredMessages) {
        filteredMessages.reversed()
    }

    Box(
        modifier = modifier
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
                            { onCancelQueuedMessage(queuedMsg.id) }
                        } else null
                    )
                }
            }

            // Messages in reverse order (newest at index 0 = bottom)
            items(
                items = reversedMessages,
                key = { it.id }
            ) { message ->
                MessageBubble(message = message)
            }

            // Loading indicator at top (oldest messages) for pagination
            // With reverseLayout=true, this appears at the top of the visible list
            if (isLoadingMore) {
                item(key = "loading_more") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                    }
                }
            }
        }

        // Scroll to bottom button with new message preview
        // Shows when not at bottom or when there are new messages
        AnimatedVisibility(
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
                    onClick = onScrollToBottom,
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
}
