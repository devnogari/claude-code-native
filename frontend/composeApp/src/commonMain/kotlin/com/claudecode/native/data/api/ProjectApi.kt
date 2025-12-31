package com.claudecode.native.data.api

import com.claudecode.native.data.model.CreateProjectRequest
import com.claudecode.native.data.model.Project
import com.claudecode.native.data.model.UpdateProjectRequest

/**
 * API client for project management operations.
 * Handles CRUD operations for projects.
 */
class ProjectApi(private val client: ApiClient) {

    /**
     * Retrieves all projects for the authenticated user.
     *
     * @return List of projects
     */
    suspend fun getProjects(): List<Project> {
        return client.get("/projects")
    }

    /**
     * Retrieves a specific project by ID.
     *
     * @param id The project ID
     * @return The project details
     */
    suspend fun getProject(id: String): Project {
        return client.get("/projects/$id")
    }

    /**
     * Creates a new project.
     *
     * @param name The project name
     * @param path The local filesystem path for the project
     * @return The created project
     */
    suspend fun createProject(name: String, path: String): Project {
        return client.post("/projects", CreateProjectRequest(name, path))
    }

    /**
     * Updates an existing project.
     *
     * @param id The project ID
     * @param name The new project name (optional)
     * @return The updated project
     */
    suspend fun updateProject(id: String, name: String? = null): Project {
        return client.put("/projects/$id", UpdateProjectRequest(name))
    }

    /**
     * Deletes a project.
     *
     * @param id The project ID to delete
     */
    suspend fun deleteProject(id: String) {
        client.delete("/projects/$id")
    }

    /**
     * Syncs Claude CLI history to database.
     * Imports projects, conversations, and messages from ~/.claude/projects/
     *
     * @return Sync result with counts of created/updated items
     */
    suspend fun sync(): SyncResult {
        return client.postEmpty("/sync")
    }
}

/**
 * Result of a sync operation.
 */
@kotlinx.serialization.Serializable
data class SyncResult(
    val projects_created: Int = 0,
    val projects_updated: Int = 0,
    val conversations_created: Int = 0,
    val messages_created: Int = 0
)
