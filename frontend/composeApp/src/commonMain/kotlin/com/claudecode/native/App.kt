package com.claudecode.native

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.claudecode.native.data.api.ApiClient
import com.claudecode.native.data.api.ProjectApi
import com.claudecode.native.data.repository.ThemeRepository
import com.claudecode.native.di.appModule
import com.claudecode.native.ui.layout.AdaptiveProjectLayout
import com.claudecode.native.ui.navigation.BrowserHistory
import com.claudecode.native.ui.navigation.Screen
import com.claudecode.native.ui.screen.ChatScreen
import com.claudecode.native.ui.screen.ChatScreenContent
import com.claudecode.native.ui.screen.LoginScreen
import com.claudecode.native.ui.screen.ProjectListScreen
import com.claudecode.native.ui.screen.ProjectListScreenContent
import com.claudecode.native.ui.screen.SettingsScreen
import com.claudecode.native.ui.theme.AppTheme
import org.koin.compose.KoinApplication
import org.koin.compose.koinInject

@Composable
fun App() {
    KoinApplication(application = {
        modules(appModule)
    }) {
        val themeRepository: ThemeRepository = koinInject()
        val isDarkMode by themeRepository.isDarkMode.collectAsState()

        AppTheme(darkTheme = isDarkMode) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                AppNavigation()
            }
        }
    }
}

/**
 * Parse URL path to Screen.
 */
private fun parsePathToScreen(path: String): Screen {
    return when {
        path == "/" || path == "/login" -> Screen.Login
        path == "/projects" -> Screen.ProjectList
        path == "/settings" -> Screen.Settings
        path.startsWith("/chat/") -> {
            val conversationId = path.removePrefix("/chat/")
            if (conversationId.isNotEmpty()) Screen.Chat(conversationId) else Screen.ProjectList
        }
        else -> Screen.Login
    }
}

/**
 * Main navigation composable that handles screen routing.
 *
 * Uses state-based navigation with [Screen] sealed class.
 * Syncs with browser URL on WASM platform.
 * Checks for saved token on startup and auto-navigates to ProjectList if valid.
 */
@Composable
fun AppNavigation() {
    val apiClient: ApiClient = koinInject()
    val projectApi: ProjectApi = koinInject()

    // Track if we've checked the token yet
    var isCheckingToken by remember { mutableStateOf(true) }

    // Initialize from current browser path
    val initialPath = BrowserHistory.getCurrentPath()
    var currentScreen: Screen by remember { mutableStateOf(parsePathToScreen(initialPath)) }

    // Check for saved token on startup
    LaunchedEffect(Unit) {
        val savedToken = apiClient.getAuthToken()
        if (savedToken != null) {
            // Try to validate the token by making an API call
            try {
                projectApi.getProjects()
                // Token is valid, navigate to ProjectList
                currentScreen = Screen.ProjectList
            } catch (e: Exception) {
                // Token is invalid, clear it and stay on login
                apiClient.clearAuthToken()
                currentScreen = Screen.Login
            }
        }
        isCheckingToken = false
    }

    // Handle browser back/forward
    DisposableEffect(Unit) {
        BrowserHistory.setOnPopState { path ->
            currentScreen = parsePathToScreen(path)
        }
        onDispose { }
    }

    // Update URL when screen changes
    LaunchedEffect(currentScreen) {
        BrowserHistory.pushState("/${currentScreen.route}")
    }

    // Show loading indicator while checking token
    if (isCheckingToken) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
        }
        return
    }

    when (val screen = currentScreen) {
        is Screen.Login -> {
            LoginScreen(
                onLoginSuccess = {
                    currentScreen = Screen.ProjectList
                }
            )
        }

        is Screen.ProjectList, is Screen.Chat -> {
            // Use adaptive layout for project list and chat screens
            val selectedConversationId = (screen as? Screen.Chat)?.conversationId

            AdaptiveProjectLayout(
                selectedConversationId = selectedConversationId,
                onConversationSelected = { conversationId ->
                    currentScreen = Screen.Chat(conversationId)
                },
                onSettingsClick = {
                    currentScreen = Screen.Settings
                },
                listContent = { onConversationSelected, onSettingsClick ->
                    ProjectListScreenContent(
                        onConversationSelected = onConversationSelected,
                        onSettingsClick = onSettingsClick
                    )
                },
                detailContent = { conversationId, onBack ->
                    ChatScreenContent(
                        conversationId = conversationId,
                        onBack = onBack
                    )
                }
            )
        }

        is Screen.Settings -> {
            SettingsScreen(
                onBack = {
                    currentScreen = Screen.ProjectList
                },
                onLogout = {
                    currentScreen = Screen.Login
                }
            )
        }

        is Screen.ClaudeHistory -> {
            // History is now integrated into ProjectListScreen
            currentScreen = Screen.ProjectList
        }
    }
}

