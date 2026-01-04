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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.claudecode.native.data.api.ApiClient
import com.claudecode.native.data.api.ApiException
import com.claudecode.native.data.api.ClaudeHistoryApi
import com.claudecode.native.data.repository.ServerRepository
import com.claudecode.native.data.repository.ThemeRepository
import com.claudecode.native.di.appModule
import com.claudecode.native.ui.layout.AdaptiveProjectLayout
import com.claudecode.native.ui.navigation.BrowserHistory
import com.claudecode.native.ui.navigation.Screen
import com.claudecode.native.ui.screen.ChatScreen
import com.claudecode.native.ui.screen.ChatScreenContent
import com.claudecode.native.ui.screen.HostSetupScreen
import com.claudecode.native.ui.screen.LoginScreen
import com.claudecode.native.ui.screen.ProjectListScreen
import com.claudecode.native.ui.screen.ProjectListScreenContent
import com.claudecode.native.ui.screen.SettingsScreen
import com.claudecode.native.ui.theme.AppTheme
import com.claudecode.native.ui.viewmodel.ServerViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.compose.KoinApplication
import org.koin.compose.koinInject

/** Timeout for waiting for server configuration to complete. */
private const val SERVER_SETUP_TIMEOUT_MS = 5000L

/** Error message shown when server configuration times out. */
private const val SERVER_SETUP_TIMEOUT_ERROR = "Server configuration timed out. Please try again."

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
 * Validates the auth token by making an API call.
 * Only clears the token on 401 (Unauthorized) errors.
 * Network errors and other exceptions preserve the token for retry.
 *
 * @return true if token is valid, false otherwise
 */
private suspend fun validateTokenOrClearOnUnauthorized(
    claudeHistoryApi: ClaudeHistoryApi,
    serverViewModel: ServerViewModel
): Boolean {
    return try {
        claudeHistoryApi.getProjects()
        true
    } catch (e: CancellationException) {
        // Rethrow cancellation to allow proper coroutine cancellation
        throw e
    } catch (e: ApiException) {
        // Only clear token on 401 (Unauthorized) - token is actually invalid
        if (e.isUnauthorized()) {
            serverViewModel.clearCurrentToken()
        }
        false
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        // Catch-all for network errors (connection refused, timeout, DNS failures)
        // and any other pre-request exceptions that aren't wrapped in ApiException.
        // These are transient errors - preserve the token so user can retry.
        false
    }
}

/**
 * Determines the appropriate starting screen based on token validity.
 * If there's a saved token, validates it and returns ProjectList or Login accordingly.
 * If no token exists, returns Login screen.
 *
 * @return The appropriate screen to navigate to
 */
private suspend fun determineAuthScreen(
    serverRepository: ServerRepository,
    claudeHistoryApi: ClaudeHistoryApi,
    serverViewModel: ServerViewModel
): Screen {
    val currentServer = serverRepository.currentServer
    return if (currentServer?.authToken != null) {
        val isValid = validateTokenOrClearOnUnauthorized(claudeHistoryApi, serverViewModel)
        if (isValid) Screen.ProjectList else Screen.Login
    } else {
        Screen.Login
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
    val serverRepository: ServerRepository = koinInject()
    val serverViewModel: ServerViewModel = koinInject()
    val scope = rememberCoroutineScope()

    // Track if we've completed initial checks
    var isInitializing by remember { mutableStateOf(true) }

    // Initialize from current browser path
    val initialPath = BrowserHistory.getCurrentPath()
    var currentScreen: Screen by remember { mutableStateOf(parsePathToScreen(initialPath)) }

    // Handle server switching - refresh screen when server changes
    val serverSwitched by serverViewModel.serverSwitched.collectAsState()
    LaunchedEffect(serverSwitched) {
        if (serverSwitched) {
            serverViewModel.resetServerSwitchedState()
            // Check if new server has valid token and navigate accordingly
            currentScreen = determineAuthScreen(serverRepository, claudeHistoryApi, serverViewModel)
        }
    }

    // Check for server configuration and saved token on startup
    LaunchedEffect(Unit) {
        // Check if any server is configured (migration happens automatically in ServerRepository)
        val currentServer = serverRepository.currentServer
        if (currentServer == null) {
            // No server configured, show host setup screen
            currentScreen = Screen.HostSetup
            isInitializing = false
            return@LaunchedEffect
        }

        // Initialize ApiClient with current server
        serverViewModel.initializeWithCurrentServer()

        // Check for saved token and navigate accordingly
        currentScreen = determineAuthScreen(serverRepository, claudeHistoryApi, serverViewModel)
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
                    // After host is configured, wait for server to be added and go to login
                    // Note: addServer() is async and also initializes API clients for first server,
                    // so we only need to wait for state update, no separate initialization needed
                    scope.launch {
                        // Wait for server to be added, with timeout to prevent hanging on errors
                        val serverState = withTimeoutOrNull(SERVER_SETUP_TIMEOUT_MS) {
                            serverRepository.state.first { it.currentServer != null }
                        }

                        if (serverState != null) {
                            currentScreen = Screen.Login
                        } else {
                            // Server setup timed out - atomically set error only if none already set
                            // (addServer might have set a more specific error)
                            serverViewModel.setErrorIfNull(SERVER_SETUP_TIMEOUT_ERROR)
                        }
                    }
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

            // State to trigger project list refresh with optional session info for polling
            var refreshTrigger by remember { mutableStateOf(0) }
            var pendingSessionRefresh by remember { mutableStateOf<Pair<String, String>?>(null) }

            AdaptiveProjectLayout(
                selectedConversationId = selectedConversationId,
                onConversationSelected = { conversationId ->
                    // Empty string signals navigation back to project list (e.g., after delete session)
                    if (conversationId.isEmpty()) {
                        currentScreen = Screen.ProjectList
                    } else {
                        currentScreen = Screen.Chat(conversationId)
                    }
                },
                onSettingsClick = {
                    currentScreen = Screen.Settings
                },
                onSessionCreated = { sessionId, encodedPath ->
                    // Trigger project list refresh when a new session is created
                    // Pass session info for polling-based refresh
                    pendingSessionRefresh = Pair(sessionId, encodedPath)
                    refreshTrigger++
                },
                onNewSession = {
                    // Extract project from current conversationId and start new draft session
                    selectedConversationId?.let { convId ->
                        val encodedPath = convId.substringAfter("project=", "")
                        if (encodedPath.isNotEmpty()) {
                            currentScreen = Screen.Chat("draft?project=$encodedPath")
                        } else {
                            println("App: Cannot create new session - no project path in conversationId: $convId")
                        }
                    }
                },
                listContent = { onConversationSelected, onSettingsClick ->
                    ProjectListScreenContent(
                        onConversationSelected = onConversationSelected,
                        onSettingsClick = onSettingsClick,
                        refreshTrigger = refreshTrigger,
                        pendingSessionRefresh = pendingSessionRefresh,
                        onPendingSessionRefreshConsumed = {
                            pendingSessionRefresh = null
                        }
                    )
                },
                detailContent = { conversationId, onBack, onSessionCreated, onNewSession ->
                    ChatScreenContent(
                        conversationId = conversationId,
                        onBack = onBack,
                        onSessionCreated = onSessionCreated,
                        onNewSession = onNewSession
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

