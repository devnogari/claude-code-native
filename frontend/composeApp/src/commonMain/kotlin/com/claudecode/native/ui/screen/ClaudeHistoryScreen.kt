package com.claudecode.native.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.claudecode.native.data.model.ClaudeMessage
import com.claudecode.native.data.model.ClaudeProject
import com.claudecode.native.data.model.ClaudeSession
import com.claudecode.native.ui.viewmodel.ClaudeHistoryViewModel
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.koin.compose.koinInject

/**
 * Screen for browsing Claude Code history from ~/.claude/projects/
 *
 * Features:
 * - List of Claude Code projects with expandable sessions
 * - Search filter for projects and sessions
 * - Click to view session messages inline
 * - Pagination and real-time updates via WebSocket
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClaudeHistoryScreen(
    viewModel: ClaudeHistoryViewModel = koinInject(),
    onBack: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.loadProjects()
    }

    // Clean up WebSocket when leaving screen
    DisposableEffect(Unit) {
        onDispose {
            viewModel.stopWatching()
        }
    }

    // Show message view if a session is selected, otherwise show project list
    if (uiState.selectedSession != null && uiState.selectedProject != null) {
        ClaudeHistoryMessagesScreen(
            viewModel = viewModel,
            project = uiState.selectedProject!!,
            session = uiState.selectedSession!!,
            messages = uiState.messages,
            isLoading = uiState.isLoading,
            isLoadingMore = uiState.isLoadingMore,
            hasMoreMessages = uiState.hasMoreMessages,
            totalMessages = uiState.totalMessages,
            isWatching = uiState.isWatching,
            error = uiState.error,
            onBack = { viewModel.clearSelection() },
            onLoadMore = { viewModel.loadMoreMessages() },
            onClearError = { viewModel.clearError() }
        )
    } else {
        ClaudeHistoryProjectListScreen(
            viewModel = viewModel,
            uiState = uiState,
            onBack = onBack
        )
    }
}

/**
 * Project list view for Claude history.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClaudeHistoryProjectListScreen(
    viewModel: ClaudeHistoryViewModel,
    uiState: com.claudecode.native.ui.viewmodel.ClaudeHistoryUiState,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Claude Code History") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.loadProjects() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
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
            // Search bar
            OutlinedTextField(
                value = uiState.searchQuery,
                onValueChange = { viewModel.updateSearchQuery(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("Search projects and sessions...") },
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = null)
                },
                trailingIcon = {
                    if (uiState.searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.updateSearchQuery("") }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true
            )

            // Error banner
            if (uiState.error != null) {
                ErrorBanner(
                    error = uiState.error,
                    onDismiss = { viewModel.clearError() }
                )
            }

            // Content
            when {
                uiState.isLoading && uiState.projects.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }

                uiState.projects.isEmpty() -> {
                    EmptyClaudeHistoryState(
                        modifier = Modifier.fillMaxSize()
                    )
                }

                else -> {
                    val filteredProjects = viewModel.getFilteredProjects()

                    PullToRefreshBox(
                        isRefreshing = uiState.isLoading,
                        onRefresh = { viewModel.loadProjects() },
                        modifier = Modifier.fillMaxSize()
                    ) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                start = 16.dp,
                                end = 16.dp,
                                top = 8.dp,
                                bottom = 16.dp
                            ),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(
                                items = filteredProjects,
                                key = { it.id }
                            ) { project ->
                                ClaudeProjectItem(
                                    project = project,
                                    sortedSessions = viewModel.getSortedSessions(project.sessions),
                                    isExpanded = project.id in uiState.expandedProjects,
                                    onToggleExpand = { viewModel.toggleProjectExpanded(project.id) },
                                    onSessionClick = { session ->
                                        viewModel.selectSession(project, session)
                                    },
                                    onToggleSessionFavorite = { session ->
                                        viewModel.toggleSessionFavorite(project, session)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Messages view for a selected Claude history session.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClaudeHistoryMessagesScreen(
    viewModel: ClaudeHistoryViewModel,
    project: ClaudeProject,
    session: ClaudeSession,
    messages: List<ClaudeMessage>,
    isLoading: Boolean,
    isLoadingMore: Boolean,
    hasMoreMessages: Boolean,
    totalMessages: Int,
    isWatching: Boolean,
    error: String?,
    onBack: () -> Unit,
    onLoadMore: () -> Unit,
    onClearError: () -> Unit
) {
    val listState = rememberLazyListState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = session.firstMessage.ifBlank { "Session ${session.id.take(8)}" },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${messages.size}/$totalMessages messages • ${project.name}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Real-time indicator
                    if (isWatching) {
                        Icon(
                            Icons.Default.Sync,
                            contentDescription = "Watching for updates",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(end = 8.dp)
                        )
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
            // Error banner
            if (error != null) {
                ErrorBanner(
                    error = error,
                    onDismiss = onClearError
                )
            }

            when {
                isLoading && messages.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }

                messages.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No messages in this session",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                else -> {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Load more button at top (for older messages)
                        if (hasMoreMessages) {
                            item(key = "load_more") {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isLoadingMore) {
                                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                    } else {
                                        OutlinedButton(onClick = onLoadMore) {
                                            Icon(
                                                Icons.Default.KeyboardArrowUp,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Load older messages")
                                        }
                                    }
                                }
                            }
                        }

                        // Messages
                        items(
                            items = messages,
                            key = { "${it.sessionId}_${it.timestamp?.toEpochMilliseconds() ?: 0}" }
                        ) { message ->
                            ClaudeMessageItem(message = message)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Individual message item in the Claude history.
 */
@Composable
private fun ClaudeMessageItem(message: ClaudeMessage) {
    val role = message.message?.role ?: "unknown"
    val isUser = role == "user"
    val isAssistant = role == "assistant"

    val backgroundColor = when {
        isUser -> MaterialTheme.colorScheme.primaryContainer
        isAssistant -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.surface
    }

    val alignment = if (isUser) Alignment.End else Alignment.Start

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        // Role label
        Text(
            text = role.replaceFirstChar { it.uppercaseChar() },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
        )

        // Message content
        Surface(
            modifier = Modifier
                .widthIn(max = 600.dp)
                .fillMaxWidth(if (isUser) 0.85f else 0.95f),
            color = backgroundColor,
            shape = RoundedCornerShape(12.dp)
        ) {
            val contentText = extractTextContent(message.message?.content)
            Text(
                text = contentText,
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        }

        // Timestamp
        message.timestamp?.let { timestamp ->
            Text(
                text = formatTimeAgo(timestamp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
            )
        }
    }
}

/**
 * Extracts text content from the message content JsonElement.
 */
private fun extractTextContent(content: kotlinx.serialization.json.JsonElement?): String {
    if (content == null) return ""

    return when (content) {
        is JsonPrimitive -> cleanThinkingTags(content.content)
        is JsonArray -> {
            content.mapNotNull { element ->
                when (element) {
                    is JsonPrimitive -> element.content
                    is JsonObject -> {
                        // Handle content blocks like {type: "text", text: "..."}
                        val type = element["type"]?.jsonPrimitive?.content
                        when (type) {
                            "text" -> element["text"]?.jsonPrimitive?.content
                            // Filter out tool_use and tool_result - don't show them as messages
                            "tool_use", "tool_result" -> null
                            else -> null
                        }
                    }
                    else -> null
                }
            }.joinToString("\n").let { cleanThinkingTags(it) }
        }
        is JsonObject -> {
            val type = content["type"]?.jsonPrimitive?.content
            when (type) {
                "text" -> cleanThinkingTags(content["text"]?.jsonPrimitive?.content ?: "")
                // Filter out tool_use and tool_result
                "tool_use", "tool_result" -> ""
                else -> ""
            }
        }
    }
}

/**
 * Removes thinking tags from content.
 */
private fun cleanThinkingTags(text: String): String {
    return text
        .replace(Regex("<thinking>.*?</thinking>", RegexOption.DOT_MATCHES_ALL), "")
        .replace("</thinking>", "")
        .replace("<thinking>", "")
        .trim()
}

/**
 * Reusable error banner component.
 */
@Composable
private fun ErrorBanner(
    error: String?,
    onDismiss: () -> Unit
) {
    if (error == null) return

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
                text = error,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onDismiss) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Dismiss",
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
}

/**
 * Empty state when no Claude Code history exists.
 */
@Composable
private fun EmptyClaudeHistoryState(
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.History,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "No Claude Code history",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Start using Claude Code to see your conversations here",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Individual Claude Code project item with expandable sessions.
 */
@Composable
private fun ClaudeProjectItem(
    project: ClaudeProject,
    sortedSessions: List<ClaudeSession>,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onSessionClick: (ClaudeSession) -> Unit,
    onToggleSessionFavorite: (ClaudeSession) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isExpanded) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Column {
            // Project header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleExpand() }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (isExpanded) Icons.Default.FolderOpen else Icons.Default.Folder,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = project.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = project.path,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${project.sessions.size} sessions",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        project.lastAccessed?.let { lastAccessed ->
                            Text(
                                text = " • ${formatTimeAgo(lastAccessed)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Icon(
                    if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (isExpanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Expanded sessions list
            if (isExpanded && sortedSessions.isNotEmpty()) {
                HorizontalDivider()

                sortedSessions.forEach { session ->
                    ClaudeSessionItem(
                        session = session,
                        onClick = { onSessionClick(session) },
                        onToggleFavorite = { onToggleSessionFavorite(session) }
                    )
                }
            }
        }
    }
}

/**
 * Individual session item within a project.
 */
@Composable
private fun ClaudeSessionItem(
    session: ClaudeSession,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Chat,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = session.firstMessage.ifBlank { "Session ${session.id.take(8)}" },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${session.messageCount} messages",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    session.updatedAt?.let { updatedAt ->
                        Text(
                            text = " • ${formatTimeAgo(updatedAt)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Favorite button
            IconButton(
                onClick = onToggleFavorite,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = if (session.isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                    contentDescription = if (session.isFavorite) "Remove from favorites" else "Add to favorites",
                    tint = if (session.isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }

            Icon(
                Icons.Default.ChevronRight,
                contentDescription = "View session",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Formats an Instant to a human-readable "time ago" string.
 */
private fun formatTimeAgo(instant: Instant): String {
    val now = Clock.System.now()
    val duration = now - instant

    return when {
        duration.inWholeMinutes < 1 -> "just now"
        duration.inWholeMinutes < 60 -> "${duration.inWholeMinutes}m ago"
        duration.inWholeHours < 24 -> "${duration.inWholeHours}h ago"
        duration.inWholeDays < 7 -> "${duration.inWholeDays}d ago"
        duration.inWholeDays < 30 -> "${duration.inWholeDays / 7}w ago"
        else -> {
            val localDateTime = instant.toLocalDateTime(TimeZone.currentSystemDefault())
            "${localDateTime.month.ordinal + 1}/${localDateTime.day}/${localDateTime.year}"
        }
    }
}
