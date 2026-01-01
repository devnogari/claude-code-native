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
import com.claudecode.native.data.api.ClaudeHistoryApi
import com.claudecode.native.data.repository.ThemeRepository
import com.claudecode.native.di.appModule
import com.claudecode.native.ui.layout.AdaptiveProjectLayout
import com.claudecode.native.ui.navigation.BrowserHistory
import com.claudecode.native.ui.navigation.Screen
import com.claudecode.native.data.storage.TokenStorage
import com.claudecode.native.ui.screen.ChatScreen
import com.claudecode.native.ui.screen.ChatScreenContent
import com.claudecode.native.ui.screen.HostSetupScreen
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
        path == "/host-setup" -> Screen.HostSetup
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
 * Checks for host configuration first, then for saved token on startup.
 */
@Composable
fun AppNavigation() {
    val apiClient: ApiClient = koinInject()
    val claudeHistoryApi: ClaudeHistoryApi = koinInject()

    // Track if we've completed initial checks
    var isInitializing by remember { mutableStateOf(true) }

    // Initialize from current browser path
    val initialPath = BrowserHistory.getCurrentPath()
    var currentScreen: Screen by remember { mutableStateOf(parsePathToScreen(initialPath)) }

    // Check for host configuration and saved token on startup
    LaunchedEffect(Unit) {
        // First check if server host is configured
        val serverHost = TokenStorage.getServerHost()
        if (serverHost == null) {
            // No host configured, show host setup screen
            currentScreen = Screen.HostSetup
            isInitializing = false
            return@LaunchedEffect
        }

        // Host is configured, update ApiClient server host
        apiClient.updateServerHost(serverHost)

        // Now check for saved token
        val savedToken = apiClient.getAuthToken()
        if (savedToken != null) {
            // Try to validate the token by making an API call
            try {
                claudeHistoryApi.getProjects()
                // Token is valid, navigate to ProjectList
                currentScreen = Screen.ProjectList
            } catch (e: Exception) {
                // Token is invalid, clear it and stay on login
                apiClient.clearAuthToken()
                currentScreen = Screen.Login
            }
        } else {
            currentScreen = Screen.Login
        }
        isInitializing = false
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

    // Show loading indicator while initializing
    if (isInitializing) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
        }
        return
    }

    when (val screen = currentScreen) {
        is Screen.HostSetup -> {
            HostSetupScreen(
                onHostConfigured = {
                    // After host is configured, update ApiClient and go to login
                    val configuredHost = TokenStorage.getServerHost()
                    if (configuredHost != null) {
                        apiClient.updateServerHost(configuredHost)
                    }
                    currentScreen = Screen.Login
                }
            )
        }

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

            // State to trigger project list refresh
            var refreshTrigger by remember { mutableStateOf(0) }

            AdaptiveProjectLayout(
                selectedConversationId = selectedConversationId,
                onConversationSelected = { conversationId ->
                    currentScreen = Screen.Chat(conversationId)
                },
                onSettingsClick = {
                    currentScreen = Screen.Settings
                },
                onSessionCreated = {
                    // Trigger project list refresh when a new session is created
                    refreshTrigger++
                },
                listContent = { onConversationSelected, onSettingsClick ->
                    ProjectListScreenContent(
                        onConversationSelected = onConversationSelected,
                        onSettingsClick = onSettingsClick,
                        refreshTrigger = refreshTrigger
                    )
                },
                detailContent = { conversationId, onBack, onSessionCreated ->
                    ChatScreenContent(
                        conversationId = conversationId,
                        onBack = onBack,
                        onSessionCreated = onSessionCreated
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
    }
}

