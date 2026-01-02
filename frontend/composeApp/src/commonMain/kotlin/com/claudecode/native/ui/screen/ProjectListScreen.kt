package com.claudecode.native.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.claudecode.native.data.model.ClaudeProject
import com.claudecode.native.data.model.ClaudeSession
import com.claudecode.native.ui.viewmodel.ProjectListViewModel
import com.claudecode.native.util.showFolderChooser
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.koin.compose.koinInject

/**
 * Project list screen displaying Claude Code history as the main project list.
 *
 * Features:
 * - Shows projects from ~/.claude/projects/ history
 * - Search filter for projects and sessions
 * - Favorite projects with star icon
 * - Click to expand and view sessions
 * - Session click auto-registers project on server and navigates to chat
 * - FAB to manually add new project
 *
 * @param viewModel ViewModel injected via Koin
 * @param onConversationSelected Callback when a conversation is ready (after server registration)
 * @param onSettingsClick Callback when settings is clicked
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectListScreen(
    viewModel: ProjectListViewModel = koinInject(),
    onConversationSelected: (String) -> Unit,
    onSettingsClick: () -> Unit = {}
) {
    ProjectListScreenContent(
        viewModel = viewModel,
        onConversationSelected = onConversationSelected,
        onSettingsClick = onSettingsClick
    )
}

/**
 * Content composable for the project list screen.
 *
 * This is the main implementation used by both the standalone screen
 * and the adaptive layout. Extracted to allow reuse in split-screen mode.
 *
 * @param viewModel ViewModel injected via Koin
 * @param onConversationSelected Callback when a conversation is ready
 * @param onSettingsClick Callback when settings is clicked
 * @param refreshTrigger Counter to trigger refresh when incremented (for external refresh requests)
 * @param pendingSessionRefresh Session info (sessionId, encodedPath) to wait for in the projects API before refreshing
 * @param onPendingSessionRefreshConsumed Callback when pendingSessionRefresh has been consumed (for clearing state)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectListScreenContent(
    viewModel: ProjectListViewModel = koinInject(),
    onConversationSelected: (String) -> Unit,
    onSettingsClick: () -> Unit = {},
    refreshTrigger: Int = 0,
    pendingSessionRefresh: Pair<String, String>? = null,  // (sessionId, encodedPath)
    onPendingSessionRefreshConsumed: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    // Prevent TextField from receiving focus during initial composition (iOS keyboard fix)
    var canFocusInput by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(300)
        canFocusInput = true
    }

    var showCreateDialog by remember { mutableStateOf(false) }
    var deleteSessionTarget by remember { mutableStateOf<Triple<String, String, String?>?>(null) } // sessionId, projectPath, sourceEncodedPath
    var deleteProjectTarget by remember { mutableStateOf<Pair<String, String>?>(null) } // encodedPath, projectName
    var deleteResultMessage by remember { mutableStateOf<String?>(null) }
    var showBulkDeleteDialog by remember { mutableStateOf(false) }

    // Selection mode states from ViewModel
    val selectionModeProjectId = uiState.selectionModeProjectId
    val selectedSessions = uiState.selectedSessions
    val isBulkDeleting = uiState.isBulkDeleting

    // Refresh projects when refreshTrigger changes (e.g., when a new session is created)
    // When pendingSessionRefresh is provided, use polling-based refresh to wait for session to appear
    LaunchedEffect(refreshTrigger, pendingSessionRefresh) {
        if (refreshTrigger > 0) {
            if (pendingSessionRefresh != null) {
                // Use polling-based refresh to wait for session to appear in projects API
                val (sessionId, encodedPath) = pendingSessionRefresh
                println("ProjectListScreen: Refreshing with polling for session $sessionId in $encodedPath")
                viewModel.refreshAfterSessionCreated(sessionId, encodedPath)
                // Clear the pending state after polling completes (whether successful or not)
                onPendingSessionRefreshConsumed()
            } else {
                // Legacy fallback: simple refresh with delay
                println("ProjectListScreen: Refreshing due to external trigger (with delay)")
                kotlinx.coroutines.delay(500)
                viewModel.refresh()
            }
        }
    }

    // Auto-dismiss delete result message
    if (deleteResultMessage != null) {
        LaunchedEffect(deleteResultMessage) {
            kotlinx.coroutines.delay(3000)
            deleteResultMessage = null
        }
    }

    // Delete session confirmation dialog
    if (deleteSessionTarget != null) {
        AlertDialog(
            onDismissRequest = { deleteSessionTarget = null },
            title = { Text("Delete Session") },
            text = { Text("Delete Claude CLI session files for this conversation? This allows starting fresh.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        val (convId, path, sourceEncodedPath) = deleteSessionTarget!!
                        deleteSessionTarget = null
                        viewModel.deleteSession(
                            conversationId = convId,
                            projectPath = path,
                            sourceEncodedPath = sourceEncodedPath,
                            onSuccess = {
                                deleteResultMessage = "Session deleted"
                                viewModel.refresh() // Refresh list to reflect deletion
                            },
                            onError = { error ->
                                deleteResultMessage = error
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
                TextButton(onClick = { deleteSessionTarget = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Delete project confirmation dialog
    if (deleteProjectTarget != null) {
        AlertDialog(
            onDismissRequest = { deleteProjectTarget = null },
            title = { Text("Delete Project") },
            text = {
                Text("Delete project \"${deleteProjectTarget!!.second}\" from the list? This removes the project from cache but does not delete any files on disk.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val (encodedPath, _) = deleteProjectTarget!!
                        deleteProjectTarget = null
                        viewModel.deleteProject(
                            encodedPath = encodedPath,
                            onSuccess = {
                                deleteResultMessage = "Project deleted"
                                viewModel.refresh() // Refresh list to reflect deletion
                            },
                            onError = { error ->
                                deleteResultMessage = error
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
                TextButton(onClick = { deleteProjectTarget = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Bulk delete confirmation dialog
    if (showBulkDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showBulkDeleteDialog = false },
            title = { Text("Delete ${selectedSessions.size} Sessions") },
            text = {
                Text("Delete ${selectedSessions.size} selected session(s)? This will remove the Claude CLI session files.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showBulkDeleteDialog = false
                        viewModel.deleteSelectedSessions(
                            onSuccess = { count ->
                                deleteResultMessage = "$count session(s) deleted"
                            },
                            onError = { error ->
                                deleteResultMessage = error
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
                TextButton(onClick = { showBulkDeleteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Projects") },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showCreateDialog = true }
            ) {
                Icon(Icons.Default.Add, contentDescription = "Create Project")
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .pointerInput(Unit) {
                    detectTapGestures(onTap = {
                        focusManager.clearFocus()
                        keyboardController?.hide()
                    })
                }
        ) {
            // Search bar
            OutlinedTextField(
                value = uiState.searchQuery,
                onValueChange = { viewModel.updateSearchQuery(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .focusProperties { canFocus = canFocusInput },
                placeholder = { Text("Search projects...") },
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
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = { focusManager.clearFocus() }
                )
            )

            // Delete result message
            if (deleteResultMessage != null) {
                val isSuccess = deleteResultMessage == "Session deleted" || deleteResultMessage == "Project deleted"
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    color = if (isSuccess)
                        MaterialTheme.colorScheme.primaryContainer
                    else
                        MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        text = deleteResultMessage ?: "",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        color = if (isSuccess)
                            MaterialTheme.colorScheme.onPrimaryContainer
                        else
                            MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            // Error banner
            if (uiState.error != null) {
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
                            text = uiState.error ?: "",
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
                    EmptyProjectsState(
                        onCreateClick = { showCreateDialog = true },
                        modifier = Modifier.fillMaxSize()
                    )
                }

                else -> {
                    val filteredProjects = viewModel.getFilteredProjects()

                    PullToRefreshBox(
                        isRefreshing = uiState.isRefreshing,
                        onRefresh = { viewModel.refresh() },
                        modifier = Modifier.fillMaxSize()
                    ) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                start = 8.dp,
                                end = 8.dp,
                                top = 8.dp,
                                bottom = 88.dp // FAB space
                            ),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(
                                items = filteredProjects,
                                key = { it.id }
                            ) { project ->
                                ProjectItem(
                                    project = project,
                                    isFavorite = project.path in uiState.favorites,
                                    isExpanded = project.id in uiState.expandedProjects,
                                    isLoading = uiState.isLoading || isBulkDeleting,
                                    isInSelectionMode = selectionModeProjectId == project.id,
                                    selectedSessions = if (selectionModeProjectId == project.id) selectedSessions else emptySet(),
                                    onToggleExpand = { viewModel.toggleProjectExpanded(project.id) },
                                    onToggleFavorite = { viewModel.toggleFavorite(project.path) },
                                    onDeleteProject = {
                                        deleteProjectTarget = Pair(project.encodedPath, project.name)
                                    },
                                    onSessionClick = { session ->
                                        viewModel.onSessionClick(session.id, project.encodedPath) { sessionId, encodedPath ->
                                            onConversationSelected("$sessionId?project=$encodedPath")
                                        }
                                    },
                                    onToggleSessionFavorite = { session ->
                                        viewModel.toggleSessionFavorite(session.id, project.path)
                                    },
                                    onDeleteSession = { session ->
                                        deleteSessionTarget = Triple(session.id, project.path, session.sourceEncodedPath)
                                    },
                                    onNewSession = {
                                        viewModel.startNewSession(project.encodedPath) { sessionId, encodedPath ->
                                            onConversationSelected("$sessionId?project=$encodedPath")
                                        }
                                    },
                                    onEnterSelectionMode = { viewModel.enterSelectionMode(project.id) },
                                    onExitSelectionMode = { viewModel.exitSelectionMode() },
                                    onToggleSessionSelection = { sessionId -> viewModel.toggleSessionSelection(sessionId) },
                                    onSelectAll = { selectAll -> viewModel.selectAllSessions(selectAll) },
                                    onDeleteSelected = { showBulkDeleteDialog = true }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Create project dialog
    if (showCreateDialog) {
        CreateProjectDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { name, path ->
                viewModel.createProject(name, path)
                showCreateDialog = false
            }
        )
    }
}

/**
 * Empty state shown when no projects/history exist.
 */
@Composable
private fun EmptyProjectsState(
    onCreateClick: () -> Unit,
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
            text = "Start using Claude Code to see your projects here",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onCreateClick) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Add Project Manually")
        }
    }
}

/**
 * Individual project item with expandable sessions.
 */
@Composable
private fun ProjectItem(
    project: ClaudeProject,
    isFavorite: Boolean,
    isExpanded: Boolean,
    isLoading: Boolean,
    isInSelectionMode: Boolean = false,
    selectedSessions: Set<String> = emptySet(),
    onToggleExpand: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDeleteProject: () -> Unit,
    onSessionClick: (ClaudeSession) -> Unit,
    onToggleSessionFavorite: (ClaudeSession) -> Unit,
    onDeleteSession: (ClaudeSession) -> Unit,
    onNewSession: () -> Unit,
    onEnterSelectionMode: () -> Unit = {},
    onExitSelectionMode: () -> Unit = {},
    onToggleSessionSelection: (String) -> Unit = {},
    onSelectAll: (Boolean) -> Unit = {},
    onDeleteSelected: () -> Unit = {}
) {
    val sessions = project.sessions
    val allSelected = sessions.isNotEmpty() && sessions.all { it.id in selectedSessions }
    val someSelected = selectedSessions.isNotEmpty()

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
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (isExpanded) Icons.Default.FolderOpen else Icons.Default.Folder,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    tint = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.width(12.dp))

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
                            text = "${sessions.size} sessions",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        project.lastAccessed?.let { lastAccessed ->
                            Text(
                                text = " - ${formatTimeAgo(lastAccessed)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // Favorite toggle
                IconButton(
                    onClick = onToggleFavorite,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        if (isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                        contentDescription = if (isFavorite) "Remove from favorites" else "Add to favorites",
                        modifier = Modifier.size(20.dp),
                        tint = if (isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Delete project button
                IconButton(
                    onClick = onDeleteProject,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete project",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                    )
                }

                // Expand indicator
                Icon(
                    if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (isExpanded) "Collapse" else "Expand",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Expanded sessions list
            if (isExpanded) {
                HorizontalDivider()

                // Selection mode toolbar or New Session button
                if (isInSelectionMode) {
                    // Selection mode toolbar
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Select all checkbox
                                IconButton(
                                    onClick = { onSelectAll(!allSelected) },
                                    modifier = Modifier.size(40.dp)
                                ) {
                                    Icon(
                                        if (allSelected) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank,
                                        contentDescription = if (allSelected) "Deselect all" else "Select all",
                                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                }
                                Text(
                                    text = if (selectedSessions.isEmpty()) "Select sessions"
                                           else "${selectedSessions.size} selected",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }

                            Row {
                                // Delete selected button
                                TextButton(
                                    onClick = onDeleteSelected,
                                    enabled = someSelected && !isLoading,
                                    colors = ButtonDefaults.textButtonColors(
                                        contentColor = MaterialTheme.colorScheme.error
                                    )
                                ) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Delete")
                                }

                                // Cancel selection mode
                                TextButton(
                                    onClick = onExitSelectionMode
                                ) {
                                    Text("Cancel")
                                }
                            }
                        }
                    }
                } else {
                    // Normal mode: New Session button + Select button
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            // New Session button
                            TextButton(
                                onClick = onNewSession,
                                enabled = !isLoading
                            ) {
                                Icon(
                                    Icons.Default.Add,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("New Session")
                            }

                            // Select button (only show if there are sessions)
                            if (sessions.isNotEmpty()) {
                                TextButton(
                                    onClick = onEnterSelectionMode,
                                    enabled = !isLoading
                                ) {
                                    Icon(
                                        Icons.Outlined.CheckBoxOutlineBlank,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Select")
                                }
                            }
                        }
                    }
                }

                if (sessions.isNotEmpty()) {
                    HorizontalDivider()
                }

                sessions.forEach { session ->
                    SessionItem(
                        session = session,
                        isLoading = isLoading,
                        isInSelectionMode = isInSelectionMode,
                        isSelected = session.id in selectedSessions,
                        onClick = {
                            if (isInSelectionMode) {
                                onToggleSessionSelection(session.id)
                            } else {
                                onSessionClick(session)
                            }
                        },
                        onToggleFavorite = { onToggleSessionFavorite(session) },
                        onDeleteSession = { onDeleteSession(session) }
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
private fun SessionItem(
    session: ClaudeSession,
    isLoading: Boolean,
    isInSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDeleteSession: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !isLoading, onClick = onClick),
        color = if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Show checkbox in selection mode, chat icon otherwise
            if (isInSelectionMode) {
                Icon(
                    if (isSelected) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank,
                    contentDescription = if (isSelected) "Deselect" else "Select",
                    modifier = Modifier.size(24.dp),
                    tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Icon(
                    Icons.AutoMirrored.Filled.Chat,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

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
                            text = " - ${formatTimeAgo(updatedAt)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Hide action buttons in selection mode
            if (!isInSelectionMode) {
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

                // Delete session button
                IconButton(
                    onClick = onDeleteSession,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete session",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                    )
                }

                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = "Open session",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Dialog for creating a new project manually.
 */
@Composable
private fun CreateProjectDialog(
    onDismiss: () -> Unit,
    onCreate: (name: String, path: String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var path by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Project") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Project Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = path,
                    onValueChange = { path = it },
                    label = { Text("Project Path") },
                    placeholder = { Text("/path/to/project") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        IconButton(
                            onClick = {
                                showFolderChooser("Select Project Folder")?.let { selectedPath ->
                                    path = selectedPath
                                    if (name.isBlank()) {
                                        name = selectedPath.substringAfterLast("/").substringAfterLast("\\")
                                    }
                                }
                            }
                        ) {
                            Icon(
                                Icons.Default.FolderOpen,
                                contentDescription = "Browse folder"
                            )
                        }
                    }
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onCreate(name, path) },
                enabled = name.isNotBlank() && path.isNotBlank()
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
