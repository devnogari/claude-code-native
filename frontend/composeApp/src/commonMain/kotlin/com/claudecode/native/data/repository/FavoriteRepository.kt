package com.claudecode.native.data.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Repository for managing favorite projects.
 * Currently stores favorites in memory.
 * TODO: Persist to server or local storage for cross-session persistence.
 */
class FavoriteRepository {
    private val _favorites = MutableStateFlow<Set<String>>(emptySet())
    /** Flow of favorite project paths. */
    val favorites: StateFlow<Set<String>> = _favorites.asStateFlow()

    /**
     * Adds a project path to favorites.
     *
     * @param projectPath The project path to favorite
     */
    fun addFavorite(projectPath: String) {
        _favorites.value = _favorites.value + projectPath
    }

    /**
     * Removes a project path from favorites.
     *
     * @param projectPath The project path to unfavorite
     */
    fun removeFavorite(projectPath: String) {
        _favorites.value = _favorites.value - projectPath
    }

    /**
     * Toggles the favorite status of a project.
     *
     * @param projectPath The project path to toggle
     * @return True if now favorited, false if unfavorited
     */
    fun toggleFavorite(projectPath: String): Boolean {
        return if (projectPath in _favorites.value) {
            removeFavorite(projectPath)
            false
        } else {
            addFavorite(projectPath)
            true
        }
    }

    /**
     * Checks if a project is favorited.
     *
     * @param projectPath The project path to check
     * @return True if favorited
     */
    fun isFavorite(projectPath: String): Boolean {
        return projectPath in _favorites.value
    }
}
