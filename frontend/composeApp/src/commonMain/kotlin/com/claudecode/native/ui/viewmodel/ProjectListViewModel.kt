package com.claudecode.native.ui.viewmodel

import com.claudecode.native.data.api.ConversationApi
import com.claudecode.native.data.api.ProjectApi
import com.claudecode.native.data.model.Conversation
import com.claudecode.native.data.model.Project
import com.claudecode.native.data.repository.FavoriteRepository
import com.claudecode.native.util.toUserMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Project with its conversations for display.
 */
data class ProjectWithConversations(
    val project: Project,
    val conversations: List<Conversation> = emptyList()
)

/**
 * UI state for the project list screen.
 */
data class ProjectListUiState(
    val projects: List<ProjectWithConversations> = emptyList(),
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
 * Displays projects from database (synced from Claude CLI history on login).
 * Supports:
 * - Loading projects from database
 * - Expanding projects to show conversations
 * - Favoriting projects (star icon)
 * - Search/filter projects
 *
 * @param projectApi API client for project operations
 * @param conversationApi API client for conversation operations
 * @param favoriteRepository Repository for managing favorites
 * @param scope Injected coroutine scope for lifecycle management
 */
class ProjectListViewModel(
    private val projectApi: ProjectApi,
    private val conversationApi: ConversationApi,
    private val favoriteRepository: FavoriteRepository,
    private val scope: CoroutineScope
) {
    private val _uiState = MutableStateFlow(ProjectListUiState())
    val uiState: StateFlow<ProjectListUiState> = _uiState.asStateFlow()

    init {
        // Observe favorites changes
        scope.launch {
            favoriteRepository.favorites.collect { favorites ->
                _uiState.value = _uiState.value.copy(favorites = favorites)
            }
        }
        loadProjects()
    }

    /**
     * Loads all projects from database.
     */
    fun loadProjects() {
        scope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val projects = projectApi.getProjects()
                // Fetch conversations for each project in parallel
                val projectsWithConversations = projects.map { project ->
                    async {
                        val conversations = try {
                            conversationApi.getConversations(project.id)
                        } catch (e: Exception) {
                            emptyList()
                        }
                        ProjectWithConversations(project, conversations)
                    }
                }.awaitAll()

                // Sort by most recent conversation or project update
                val sortedProjects = projectsWithConversations.sortedByDescending { pwc ->
                    pwc.conversations.maxOfOrNull { it.updatedAt } ?: pwc.project.updatedAt
                }

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
     */
    fun refresh() {
        scope.launch {
            _uiState.value = _uiState.value.copy(isRefreshing = true, error = null)
            try {
                val projects = projectApi.getProjects()
                val projectsWithConversations = projects.map { project ->
                    async {
                        val conversations = try {
                            conversationApi.getConversations(project.id)
                        } catch (e: Exception) {
                            emptyList()
                        }
                        ProjectWithConversations(project, conversations)
                    }
                }.awaitAll()

                val sortedProjects = projectsWithConversations.sortedByDescending { pwc ->
                    pwc.conversations.maxOfOrNull { it.updatedAt } ?: pwc.project.updatedAt
                }

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
     * Returns filtered projects based on search query.
     */
    fun getFilteredProjects(): List<ProjectWithConversations> {
        val query = _uiState.value.searchQuery.lowercase()
        val projects = _uiState.value.projects

        if (query.isEmpty()) return projects

        return projects.filter { pwc ->
            pwc.project.name.lowercase().contains(query) ||
            pwc.project.path.lowercase().contains(query) ||
            pwc.conversations.any { it.title?.lowercase()?.contains(query) == true }
        }
    }

    /**
     * Handles conversation click - navigates to chat screen.
     *
     * @param conversationId The conversation ID to navigate to
     * @param onNavigate Callback with conversation ID for navigation
     */
    fun onConversationClick(conversationId: String, onNavigate: (String) -> Unit) {
        onNavigate(conversationId)
    }

    /**
     * Creates a new project manually (for FAB action).
     *
     * @param name The project name
     * @param path The local filesystem path
     */
    fun createProject(name: String, path: String) {
        if (name.isBlank() || path.isBlank()) {
            _uiState.value = _uiState.value.copy(error = "Name and path are required")
            return
        }

        scope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                projectApi.createProject(name, path)
                // Refresh to show the new project
                refresh()
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
     * Creates a new conversation in a project.
     *
     * @param projectId The project ID
     * @param title The conversation title
     * @param onCreated Callback with conversation ID when created
     */
    fun createConversation(projectId: String, title: String, onCreated: (String) -> Unit) {
        scope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val conversation = conversationApi.createConversation(projectId, title)
                _uiState.value = _uiState.value.copy(isLoading = false)
                onCreated(conversation.id)
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
     * Clears the current error message.
     */
    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}
