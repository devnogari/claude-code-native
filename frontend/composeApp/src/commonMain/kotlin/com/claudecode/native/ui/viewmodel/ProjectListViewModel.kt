package com.claudecode.native.ui.viewmodel

import com.claudecode.native.data.api.ClaudeHistoryApi
import com.claudecode.native.data.model.ClaudeProject
import com.claudecode.native.data.model.ClaudeSession
import com.claudecode.native.data.repository.FavoriteRepository
import com.claudecode.native.util.toUserMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * UI state for the project list screen.
 */
data class ProjectListUiState(
    val projects: List<ClaudeProject> = emptyList(),
    val favorites: Set<String> = emptySet(),
    val expandedProjects: Set<String> = emptySet(),
    val searchQuery: String = "",
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val error: String? = null
)

/**
 * ViewModel for the project list screen.
 *
 * Displays projects directly from Claude CLI history (filesystem-based).
 * No database sync needed - reads directly from ~/.claude/projects/
 *
 * Supports:
 * - Loading projects from filesystem via ClaudeHistoryApi
 * - Expanding projects to show sessions
 * - Favoriting sessions
 * - Search/filter projects
 *
 * @param claudeHistoryApi API client for Claude history operations (filesystem-based)
 * @param favoriteRepository Repository for managing favorites
 * @param scope Injected coroutine scope for lifecycle management
 */
class ProjectListViewModel(
    private val claudeHistoryApi: ClaudeHistoryApi,
    private val favoriteRepository: FavoriteRepository,
    private val scope: CoroutineScope
) {
    private val _uiState = MutableStateFlow(ProjectListUiState())
    val uiState: StateFlow<ProjectListUiState> = _uiState.asStateFlow()

    init {
        // Observe favorites changes and re-sort projects
        scope.launch {
            favoriteRepository.favorites.collect { favorites ->
                val currentProjects = _uiState.value.projects
                val sortedProjects = sortProjects(currentProjects, favorites)
                _uiState.value = _uiState.value.copy(
                    favorites = favorites,
                    projects = sortedProjects
                )
            }
        }
        loadProjects()
    }

    /**
     * Sorts projects: favorites first, then by most recent session update.
     */
    private fun sortProjects(projects: List<ClaudeProject>, favorites: Set<String>): List<ClaudeProject> {
        if (projects.isEmpty()) return projects

        return projects.map { project ->
            // Sort sessions within each project: favorites first, then by updatedAt
            val sortedSessions = project.sessions.sortedWith(
                compareByDescending<ClaudeSession> { it.isFavorite }
                    .thenByDescending { it.updatedAt }
            )
            project.copy(sessions = sortedSessions)
        }.sortedWith(
            compareByDescending<ClaudeProject> { it.path in favorites }
                .thenByDescending { project ->
                    project.sessions.firstOrNull()?.updatedAt ?: project.lastAccessed
                }
        )
    }

    /**
     * Loads all projects from filesystem via ClaudeHistoryApi.
     */
    fun loadProjects() {
        scope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val projects = claudeHistoryApi.getProjects()

                // Sort with favorites first (keep all projects including those without sessions)
                val currentFavorites = favoriteRepository.favorites.value
                val sortedProjects = sortProjects(projects, currentFavorites)

                _uiState.value = _uiState.value.copy(
                    projects = sortedProjects,
                    isLoading = false
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.toUserMessage()
                )
            }
        }
    }

    /**
     * Refreshes the project list (for pull-to-refresh).
     * Calls backend to refresh cache, then reloads projects.
     */
    fun refresh() {
        scope.launch {
            _uiState.value = _uiState.value.copy(isRefreshing = true, error = null)
            try {
                // Tell backend to refresh its cache
                try {
                    claudeHistoryApi.refresh()
                } catch (e: Exception) {
                    // Log but continue - cache refresh failure shouldn't block
                }

                val projects = claudeHistoryApi.getProjects()

                // Sort with favorites first (keep all projects including those without sessions)
                val currentFavorites = favoriteRepository.favorites.value
                val sortedProjects = sortProjects(projects, currentFavorites)

                _uiState.value = _uiState.value.copy(
                    projects = sortedProjects,
                    isRefreshing = false
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isRefreshing = false,
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
     * Toggles favorite status for a project.
     *
     * @param projectPath The project path to toggle
     */
    fun toggleFavorite(projectPath: String) {
        favoriteRepository.toggleFavorite(projectPath)
    }

    /**
     * Updates the search query and filters projects.
     */
    fun updateSearchQuery(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query)
    }

    /**
     * Returns filtered projects based on search query, sorted with favorites first.
     */
    fun getFilteredProjects(): List<ClaudeProject> {
        val query = _uiState.value.searchQuery.lowercase()
        val projects = _uiState.value.projects
        val currentFavorites = _uiState.value.favorites

        val filteredProjects = if (query.isEmpty()) {
            projects
        } else {
            projects.filter { project ->
                project.name.lowercase().contains(query) ||
                project.path.lowercase().contains(query) ||
                project.sessions.any { it.firstMessage.lowercase().contains(query) }
            }
        }

        return sortProjects(filteredProjects, currentFavorites)
    }

    /**
     * Handles session click - navigates to chat screen.
     *
     * @param sessionId The session ID to navigate to
     * @param encodedPath The encoded project path
     * @param onNavigate Callback with session ID and encoded path for navigation
     */
    fun onSessionClick(sessionId: String, encodedPath: String, onNavigate: (String, String) -> Unit) {
        onNavigate(sessionId, encodedPath)
    }

    /**
     * Toggles the favorite status of a session.
     *
     * @param sessionId The session ID
     * @param projectPath The project path
     */
    fun toggleSessionFavorite(sessionId: String, projectPath: String) {
        scope.launch {
            try {
                val response = claudeHistoryApi.toggleSessionFavorite(sessionId, projectPath)

                // Update local state
                val updatedProjects = _uiState.value.projects.map { project ->
                    if (project.path == projectPath) {
                        val updatedSessions = project.sessions.map { session ->
                            if (session.id == sessionId) {
                                session.copy(isFavorite = response.isFavorite)
                            } else session
                        }.sortedWith(
                            compareByDescending<ClaudeSession> { it.isFavorite }
                                .thenByDescending { it.updatedAt }
                        )
                        project.copy(sessions = updatedSessions)
                    } else project
                }
                _uiState.value = _uiState.value.copy(projects = updatedProjects)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.toUserMessage())
            }
        }
    }

    /**
     * Clears the current error message.
     */
    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    /**
     * Deletes a session's files.
     *
     * @param conversationId The session ID to delete (aliased as conversationId for compatibility)
     * @param projectPath The project path
     * @param sourceEncodedPath Optional encoded path where session file actually resides (for inherited sessions)
     * @param onSuccess Callback when deletion succeeds
     * @param onError Callback when deletion fails
     */
    fun deleteSession(
        conversationId: String,
        projectPath: String,
        sourceEncodedPath: String? = null,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        scope.launch {
            try {
                claudeHistoryApi.deleteSession(conversationId, projectPath, sourceEncodedPath)
                onSuccess()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(e.toUserMessage())
            }
        }
    }

    /**
     * Creates a new project.
     * Note: With filesystem-based API, projects are discovered from Claude CLI usage.
     * This method is kept for manual project registration if needed.
     *
     * @param name The project name
     * @param path The project path
     */
    fun createProject(name: String, path: String) {
        // With the filesystem-based approach, projects are auto-discovered.
        // This method could be used for manual registration if the backend supports it.
        // For now, just show a message that projects are auto-discovered.
        _uiState.value = _uiState.value.copy(
            error = "Projects are automatically discovered from Claude CLI usage. Start using Claude Code in '$path' to see it here."
        )
    }

    /**
     * Starts a new session for a project in draft mode.
     * The actual session will be created when the user sends the first message.
     * This prevents empty sessions from appearing in the sidebar.
     *
     * @param encodedPath The encoded project path
     * @param onNavigate Callback with session ID and encoded path for navigation
     */
    fun startNewSession(encodedPath: String, onNavigate: (String, String) -> Unit) {
        // Use "draft" as a special marker instead of generating a UUID
        // The actual session ID will be created when the first message is sent
        onNavigate(ChatViewModel.DRAFT_SESSION_MARKER, encodedPath)
    }
}
