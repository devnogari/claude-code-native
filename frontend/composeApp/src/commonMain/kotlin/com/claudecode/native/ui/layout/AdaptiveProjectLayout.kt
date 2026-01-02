package com.claudecode.native.ui.layout

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffold
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch

/**
 * Adaptive split-screen layout for project list and chat.
 *
 * On wide screens (desktop, tablets in landscape):
 * - Shows project list on the left pane
 * - Shows chat on the right pane (detail pane)
 *
 * On narrow screens (phones, tablets in portrait):
 * - Shows one pane at a time
 * - Navigates between list and detail views
 *
 * Features:
 * - Responsive layout using Material3 Adaptive
 * - Automatic pane visibility based on screen size
 * - Back navigation handling for single-pane mode
 * - Smooth animated transitions between panes
 *
 * @param selectedConversationId The currently selected conversation ID, or null if none selected
 * @param onConversationSelected Callback when a conversation is selected from the list
 * @param onSettingsClick Callback when settings is clicked
 * @param onSessionCreated Callback when a new session is created from draft mode (sessionId, encodedPath for refreshing sidebar)
 * @param onNewSession Callback when user wants to start a new session from chat screen
 * @param listContent Composable content for the project list pane
 * @param detailContent Composable content for the chat detail pane, receives the conversation ID
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun AdaptiveProjectLayout(
    selectedConversationId: String?,
    onConversationSelected: (String) -> Unit,
    onSettingsClick: () -> Unit,
    onSessionCreated: (sessionId: String, encodedPath: String) -> Unit = { _, _ -> },
    onNewSession: () -> Unit = {},
    listContent: @Composable (
        onConversationSelected: (String) -> Unit,
        onSettingsClick: () -> Unit
    ) -> Unit,
    detailContent: @Composable (
        conversationId: String,
        onBack: () -> Unit,
        onSessionCreated: (sessionId: String, encodedPath: String) -> Unit,
        onNewSession: () -> Unit
    ) -> Unit
) {
    val navigator = rememberListDetailPaneScaffoldNavigator<String>()
    val scope = rememberCoroutineScope()

    // Track the current conversation for the detail pane
    var currentConversation by remember { mutableStateOf(selectedConversationId) }

    // Navigate to detail pane when a conversation is selected
    LaunchedEffect(selectedConversationId) {
        if (selectedConversationId != null && selectedConversationId != currentConversation) {
            currentConversation = selectedConversationId
            navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, selectedConversationId)
        }
    }

    // Handle system back button on mobile platforms
    BackHandler(enabled = navigator.canNavigateBack()) {
        scope.launch {
            navigator.navigateBack()
        }
    }

    // Callback for handling conversation selection within the list pane
    val handleConversationSelected: (String) -> Unit = { conversationId ->
        currentConversation = conversationId
        onConversationSelected(conversationId)
        scope.launch {
            navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, conversationId)
        }
    }

    // Callback for handling back navigation from the detail pane
    // Clears currentConversation to properly reset state (important for delete session)
    val handleBack: () -> Unit = {
        currentConversation = null
        scope.launch {
            navigator.navigateBack()
        }
        // Also notify parent to update currentScreen state
        onConversationSelected("")  // Empty string signals navigation back to list
    }

    ListDetailPaneScaffold(
        directive = navigator.scaffoldDirective,
        value = navigator.scaffoldValue,
        listPane = {
            AnimatedPane {
                listContent(handleConversationSelected, onSettingsClick)
            }
        },
        detailPane = {
            AnimatedPane {
                val conversationId = currentConversation
                if (conversationId != null) {
                    detailContent(conversationId, handleBack, onSessionCreated, onNewSession)
                } else {
                    // Empty state when no conversation is selected
                    EmptyDetailPane()
                }
            }
        }
    )
}

/**
 * Empty state shown in the detail pane when no conversation is selected.
 *
 * This is displayed on wide screens when the user hasn't selected
 * a conversation from the list yet.
 */
@Composable
private fun EmptyDetailPane() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text("Select a conversation to start chatting")
    }
}
