package com.claudecode.native.ui.navigation

/**
 * Sealed class representing all screens in the application.
 *
 * Provides type-safe navigation with route definitions.
 */
sealed class Screen(val route: String) {
    /**
     * Login screen - entry point for authentication.
     */
    data object Login : Screen("login")

    /**
     * Project list screen - displays available projects and conversations.
     */
    data object ProjectList : Screen("projects")

    /**
     * Chat screen - real-time messaging with Claude.
     *
     * @param conversationId The unique identifier for the conversation
     */
    data class Chat(val conversationId: String) : Screen("chat/$conversationId")

    /**
     * Settings screen - app configuration and preferences.
     */
    data object Settings : Screen("settings")

    /**
     * Claude Code history screen - browse local Claude Code conversations.
     */
    data object ClaudeHistory : Screen("claude-history")
}
