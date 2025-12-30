package com.claudecode.native.ui.viewmodel

import com.claudecode.native.data.api.ConversationApi
import com.claudecode.native.data.api.ProjectApi
import com.claudecode.native.data.model.Conversation
import com.claudecode.native.data.model.Project
import com.claudecode.native.util.toUserMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for the project list screen.
 *
 * Handles:
 * - Loading and displaying projects
 * - Creating new projects
 * - Deleting projects
 * - Loading conversations for a selected project
 *
 * @param projectApi API client for project operations
 * @param conversationApi API client for conversation operations
 * @param scope Injected coroutine scope for lifecycle management
 */
class ProjectListViewModel(
    private val projectApi: ProjectApi,
    private val conversationApi: ConversationApi,
    private val scope: CoroutineScope
) {
    private val _projects = MutableStateFlow<List<Project>>(emptyList())
    /** Flow of projects available to the user. */
    val projects: StateFlow<List<Project>> = _projects.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    /** True when loading projects or performing an operation. */
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    /** True when performing pull-to-refresh. */
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    /** Current error message, if any. */
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _selectedProject = MutableStateFlow<Project?>(null)
    /** Currently selected project. */
    val selectedProject: StateFlow<Project?> = _selectedProject.asStateFlow()

    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    /** Conversations for the selected project. */
    val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()

    private val _isLoadingConversations = MutableStateFlow(false)
    /** True when loading conversations. */
    val isLoadingConversations: StateFlow<Boolean> = _isLoadingConversations.asStateFlow()

    init {
        loadProjects()
    }

    /**
     * Loads all projects for the authenticated user.
     */
    fun loadProjects() {
        scope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                _projects.value = projectApi.getProjects()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = e.toUserMessage()
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Refreshes the project list (for pull-to-refresh).
     */
    fun refresh() {
        scope.launch {
            _isRefreshing.value = true
            _error.value = null
            try {
                _projects.value = projectApi.getProjects()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = e.toUserMessage()
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    /**
     * Creates a new project.
     *
     * @param name The project name
     * @param path The local filesystem path for the project
     */
    fun createProject(name: String, path: String) {
        if (name.isBlank()) {
            _error.value = "Project name is required"
            return
        }
        if (path.isBlank()) {
            _error.value = "Project path is required"
            return
        }

        scope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                val newProject = projectApi.createProject(name, path)
                _projects.value = _projects.value + newProject
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = e.toUserMessage()
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Deletes a project by ID.
     *
     * @param id The project ID to delete
     */
    fun deleteProject(id: String) {
        scope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                projectApi.deleteProject(id)
                _projects.value = _projects.value.filter { it.id != id }
                // Clear selection if deleted project was selected
                if (_selectedProject.value?.id == id) {
                    _selectedProject.value = null
                    _conversations.value = emptyList()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = e.toUserMessage()
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Selects a project and loads its conversations.
     *
     * @param project The project to select
     */
    fun selectProject(project: Project) {
        _selectedProject.value = project
        loadConversations(project.id)
    }

    /**
     * Loads conversations for a project.
     *
     * @param projectId The project ID to load conversations for
     */
    fun loadConversations(projectId: String) {
        scope.launch {
            _isLoadingConversations.value = true
            _error.value = null
            try {
                _conversations.value = conversationApi.getConversations(projectId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = e.toUserMessage()
            } finally {
                _isLoadingConversations.value = false
            }
        }
    }

    /**
     * Creates a new conversation in the selected project.
     *
     * @param title Optional title for the conversation
     * @return The created conversation ID via callback
     */
    fun createConversation(title: String? = null, onCreated: (String) -> Unit) {
        val project = _selectedProject.value
        if (project == null) {
            _error.value = "No project selected"
            return
        }

        scope.launch {
            _isLoadingConversations.value = true
            _error.value = null
            try {
                val conversation = conversationApi.createConversation(project.id, title)
                _conversations.value = _conversations.value + conversation
                onCreated(conversation.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = e.toUserMessage()
            } finally {
                _isLoadingConversations.value = false
            }
        }
    }

    /**
     * Clears the current error message.
     */
    fun clearError() {
        _error.value = null
    }

    /**
     * Clears the selected project and conversations.
     */
    fun clearSelection() {
        _selectedProject.value = null
        _conversations.value = emptyList()
    }
}
