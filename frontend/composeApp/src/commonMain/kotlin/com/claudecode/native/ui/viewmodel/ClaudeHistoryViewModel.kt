package com.claudecode.native.ui.viewmodel

import com.claudecode.native.data.api.ClaudeHistoryApi
import com.claudecode.native.data.model.ClaudeMessage
import com.claudecode.native.data.model.ClaudeProject
import com.claudecode.native.data.model.ClaudeSession
import com.claudecode.native.data.websocket.HistoryWatchClient
import com.claudecode.native.data.websocket.HistoryWatchEvent
import com.claudecode.native.util.toUserMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Sort options for sessions.
 */
enum class SessionSortOption {
    DEFAULT,     // Use backend sorting (favorites first, then by recent)
    RECENT,      // Sort by most recently updated (ignore favorites)
    FAVORITES    // Show favorites first, then by recent (client-side sorting)
}

/**
 * UI state for the Claude History screen.
 */
data class ClaudeHistoryUiState(
    val projects: List<ClaudeProject> = emptyList(),
    val selectedProject: ClaudeProject? = null,
    val selectedSession: ClaudeSession? = null,
    val messages: List<ClaudeMessage> = emptyList(),
    val expandedProjects: Set<String> = emptySet(),
    val searchQuery: String = "",
    val sortOption: SessionSortOption = SessionSortOption.DEFAULT,
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val hasMoreMessages: Boolean = false,
    val totalMessages: Int = 0,
    val currentOffset: Int = 0,
    val isWatching: Boolean = false,
    val error: String? = null
)

/**
 * ViewModel for managing Claude Code history data and UI state.
 */
class ClaudeHistoryViewModel(
    private val claudeHistoryApi: ClaudeHistoryApi,
    private val historyWatchClient: HistoryWatchClient? = null,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) {
    private val _uiState = MutableStateFlow(ClaudeHistoryUiState())
    val uiState: StateFlow<ClaudeHistoryUiState> = _uiState.asStateFlow()

    private var authToken: String? = null

    init {
        // Collect WebSocket events
        historyWatchClient?.let { client ->
            scope.launch {
                client.events.collect { event ->
                    handleWatchEvent(event)
                }
            }
        }
    }

    /**
     * Sets the auth token for WebSocket connections.
     */
    fun setAuthToken(token: String) {
        authToken = token
    }

    /**
     * Loads all Claude Code projects from the backend.
     */
    fun loadProjects() {
        scope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val projects = claudeHistoryApi.getProjects()
                _uiState.value = _uiState.value.copy(
                    projects = projects,
                    isLoading = false
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.toUserMessage()
                )
            }
        }
    }

    /**
     * Toggles the expanded state of a project.
     */
    fun toggleProjectExpanded(projectId: String) {
        val currentExpanded = _uiState.value.expandedProjects
        val newExpanded = if (projectId in currentExpanded) {
            currentExpanded - projectId
        } else {
            currentExpanded + projectId
        }
        _uiState.value = _uiState.value.copy(expandedProjects = newExpanded)
    }

    /**
     * Selects a session and loads its messages with pagination.
     */
    fun selectSession(project: ClaudeProject, session: ClaudeSession) {
        // Stop watching previous session
        stopWatching()

        scope.launch {
            _uiState.value = _uiState.value.copy(
                selectedProject = project,
                selectedSession = session,
                messages = emptyList(),
                isLoading = true,
                hasMoreMessages = false,
                totalMessages = 0,
                currentOffset = 0,
                error = null
            )
            try {
                val response = claudeHistoryApi.getSessionMessages(project.encodedPath, session.id)
                _uiState.value = _uiState.value.copy(
                    messages = response.messages,
                    isLoading = false,
                    hasMoreMessages = response.hasMore,
                    totalMessages = response.total,
                    currentOffset = response.messages.size
                )

                // Start watching for new messages
                startWatching(project.encodedPath, session.id)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.toUserMessage()
                )
            }
        }
    }

    /**
     * Loads more messages for the current session (pagination).
     */
    fun loadMoreMessages() {
        val project = _uiState.value.selectedProject ?: return
        val session = _uiState.value.selectedSession ?: return
        if (_uiState.value.isLoadingMore || !_uiState.value.hasMoreMessages) return

        scope.launch {
            _uiState.value = _uiState.value.copy(isLoadingMore = true)
            try {
                val response = claudeHistoryApi.getSessionMessages(
                    project.encodedPath,
                    session.id,
                    offset = _uiState.value.currentOffset
                )
                _uiState.value = _uiState.value.copy(
                    messages = response.messages + _uiState.value.messages,  // Prepend older messages
                    isLoadingMore = false,
                    hasMoreMessages = response.hasMore,
                    currentOffset = _uiState.value.currentOffset + response.messages.size
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoadingMore = false,
                    error = e.toUserMessage()
                )
            }
        }
    }

    /**
     * Starts watching a session for real-time updates.
     */
    private fun startWatching(encodedPath: String, sessionId: String) {
        val token = authToken ?: return
        historyWatchClient?.connect(encodedPath, sessionId, token)
    }

    /**
     * Stops watching the current session.
     */
    fun stopWatching() {
        historyWatchClient?.disconnect()
        _uiState.value = _uiState.value.copy(isWatching = false)
    }

    /**
     * Handles WebSocket events from the history watch client.
     */
    private fun handleWatchEvent(event: HistoryWatchEvent) {
        when (event) {
            is HistoryWatchEvent.Connected -> {
                _uiState.value = _uiState.value.copy(isWatching = true)
            }
            is HistoryWatchEvent.NewMessages -> {
                // Append new messages to the end
                _uiState.value = _uiState.value.copy(
                    messages = _uiState.value.messages + event.messages,
                    totalMessages = _uiState.value.totalMessages + event.messages.size
                )
            }
            is HistoryWatchEvent.Error -> {
                _uiState.value = _uiState.value.copy(
                    isWatching = false,
                    error = event.message
                )
            }
            is HistoryWatchEvent.Disconnected -> {
                _uiState.value = _uiState.value.copy(isWatching = false)
            }
        }
    }

    /**
     * Clears the selected session.
     */
    fun clearSelection() {
        stopWatching()
        _uiState.value = _uiState.value.copy(
            selectedProject = null,
            selectedSession = null,
            messages = emptyList(),
            hasMoreMessages = false,
            totalMessages = 0,
            currentOffset = 0
        )
    }

    /**
     * Updates the search query and filters projects.
     */
    fun updateSearchQuery(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query)
    }

    /**
     * Returns filtered projects based on search query.
     */
    fun getFilteredProjects(): List<ClaudeProject> {
        val query = _uiState.value.searchQuery.lowercase()
        if (query.isEmpty()) return _uiState.value.projects

        return _uiState.value.projects.filter { project ->
            project.name.lowercase().contains(query) ||
            project.path.lowercase().contains(query) ||
            project.sessions.any { it.firstMessage.lowercase().contains(query) }
        }
    }

    /**
     * Clears any displayed error.
     */
    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    /**
     * Toggles the favorite status of a session.
     */
    fun toggleSessionFavorite(project: ClaudeProject, session: ClaudeSession) {
        scope.launch {
            try {
                val response = claudeHistoryApi.toggleSessionFavorite(session.id, project.path)

                // Update local state
                val updatedProjects = _uiState.value.projects.map { p ->
                    if (p.id == project.id) {
                        p.copy(
                            sessions = p.sessions.map { s ->
                                if (s.id == session.id) {
                                    s.copy(isFavorite = response.isFavorite)
                                } else s
                            }
                        )
                    } else p
                }
                _uiState.value = _uiState.value.copy(projects = updatedProjects)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.toUserMessage())
            }
        }
    }

    /**
     * Sets the sort option for sessions.
     */
    fun setSortOption(option: SessionSortOption) {
        _uiState.value = _uiState.value.copy(sortOption = option)
    }

    /**
     * Returns sorted sessions for a project based on current sort option.
     * DEFAULT uses backend order (favorites first, then by recent).
     */
    fun getSortedSessions(sessions: List<ClaudeSession>): List<ClaudeSession> {
        return when (_uiState.value.sortOption) {
            SessionSortOption.DEFAULT -> {
                // Use backend sorting order (already sorted: favorites first, then by recent)
                sessions
            }
            SessionSortOption.RECENT -> {
                sessions.sortedByDescending { it.updatedAt?.toEpochMilliseconds() ?: 0L }
            }
            SessionSortOption.FAVORITES -> {
                sessions.sortedWith(
                    compareByDescending<ClaudeSession> { it.isFavorite }
                        .thenByDescending { it.updatedAt?.toEpochMilliseconds() ?: 0L }
                )
            }
        }
    }
}
