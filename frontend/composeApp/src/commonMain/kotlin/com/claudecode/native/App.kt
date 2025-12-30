package com.claudecode.native

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.claudecode.native.di.appModule
import com.claudecode.native.ui.navigation.Screen
import com.claudecode.native.ui.screen.ChatScreen
import com.claudecode.native.ui.screen.LoginScreen
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
            ProjectListPlaceholder(
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

/**
 * Placeholder for the project list screen.
 *
 * Displays a temporary UI until the full project list is implemented.
 *
 * @param onConversationSelected Callback when a conversation is selected
 */
@Composable
fun ProjectListPlaceholder(onConversationSelected: (String) -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Projects", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))
        Text("Project and conversation list will be implemented here.")
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = { onConversationSelected("test-conversation-id") }) {
            Text("Open Demo Chat")
        }
    }
}
