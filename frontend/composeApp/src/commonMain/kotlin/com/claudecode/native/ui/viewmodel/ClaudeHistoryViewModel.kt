package com.claudecode.native.ui.viewmodel

import com.claudecode.native.data.api.ClaudeHistoryApi
import com.claudecode.native.data.model.ClaudeMessage
import com.claudecode.native.data.model.ClaudeProject
import com.claudecode.native.data.model.ClaudeSession
import com.claudecode.native.util.toUserMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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
    val isLoading: Boolean = false,
    val error: String? = null
)

/**
 * ViewModel for managing Claude Code history data and UI state.
 */
class ClaudeHistoryViewModel(
    private val claudeHistoryApi: ClaudeHistoryApi,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) {
    private val _uiState = MutableStateFlow(ClaudeHistoryUiState())
    val uiState: StateFlow<ClaudeHistoryUiState> = _uiState.asStateFlow()

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
     * Selects a session and loads its messages.
     */
    fun selectSession(project: ClaudeProject, session: ClaudeSession) {
        scope.launch {
            _uiState.value = _uiState.value.copy(
                selectedProject = project,
                selectedSession = session,
                messages = emptyList(),
                isLoading = true,
                error = null
            )
            try {
                val messages = claudeHistoryApi.getSessionMessages(project.encodedPath, session.id)
                _uiState.value = _uiState.value.copy(
                    messages = messages,
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
     * Clears the selected session.
     */
    fun clearSelection() {
        _uiState.value = _uiState.value.copy(
            selectedProject = null,
            selectedSession = null,
            messages = emptyList()
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
}
