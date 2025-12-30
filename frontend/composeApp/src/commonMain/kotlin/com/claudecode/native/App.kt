package com.claudecode.native

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.claudecode.native.di.appModule
import com.claudecode.native.ui.navigation.Screen
import com.claudecode.native.ui.screen.ChatScreen
import com.claudecode.native.ui.screen.LoginScreen
import com.claudecode.native.ui.screen.ProjectListScreen
import org.koin.compose.KoinApplication

@Composable
fun App() {
    KoinApplication(application = {
        modules(appModule)
    }) {
        MaterialTheme {
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
 * Main navigation composable that handles screen routing.
 *
 * Uses state-based navigation with [Screen] sealed class.
 */
@Composable
fun AppNavigation() {
    var currentScreen: Screen by remember { mutableStateOf(Screen.Login) }
    var selectedConversationId: String? by remember { mutableStateOf(null) }

    when (val screen = currentScreen) {
        is Screen.Login -> {
            LoginScreen(
                onLoginSuccess = {
                    currentScreen = Screen.ProjectList
                }
            )
        }

        is Screen.ProjectList -> {
            ProjectListScreen(
                onConversationSelected = { conversationId ->
                    selectedConversationId = conversationId
                    currentScreen = Screen.Chat(conversationId)
                }
            )
        }

        is Screen.Chat -> {
            ChatScreen(
                conversationId = screen.conversationId,
                onBack = {
                    currentScreen = Screen.ProjectList
                }
            )
        }
    }
}

