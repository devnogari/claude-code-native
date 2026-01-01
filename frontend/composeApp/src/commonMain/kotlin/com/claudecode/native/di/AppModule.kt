package com.claudecode.native.di

import com.claudecode.native.data.api.ApiClient
import com.claudecode.native.data.api.AuthApi
import com.claudecode.native.data.api.ClaudeHistoryApi
import com.claudecode.native.data.api.ConversationApi
import com.claudecode.native.data.api.MessageApi
import com.claudecode.native.data.api.ProjectApi
import com.claudecode.native.data.repository.FavoriteRepository
import com.claudecode.native.data.repository.PreferencesRepository
import com.claudecode.native.data.repository.ThemeRepository
import com.claudecode.native.data.websocket.HistoryWatchClient
import com.claudecode.native.data.websocket.WebSocketClient
import com.claudecode.native.ui.viewmodel.ChatViewModel
import com.claudecode.native.ui.viewmodel.LoginViewModel
import com.claudecode.native.ui.viewmodel.ProjectListViewModel
import com.claudecode.native.ui.viewmodel.SettingsViewModel
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import org.koin.dsl.module
import org.koin.dsl.onClose

/**
 * Main Koin dependency injection module for the application.
 * Provides all core dependencies including:
 * - Application-wide coroutine scope
 * - HTTP clients (REST and WebSocket)
 * - API services
 * - WebSocket client
 */
val appModule = module {
    // Application-wide coroutine scope with SupervisorJob for independent child failure
    single {
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
    } onClose {
        it?.cancel()
    }

    // HTTP Client for REST API operations
    // Server host is loaded from storage or uses default (localhost:8083)
    single {
        ApiClient()
    } onClose {
        it?.close()
    }

    // HTTP Client for WebSocket connections (separate instance with WebSocket plugin)
    single {
        HttpClient {
            install(WebSockets)
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                })
            }
        }
    } onClose {
        it?.close()
    }

    // API Services
    single { AuthApi(get()) }
    single { ProjectApi(get()) }
    single { ConversationApi(get()) }
    single { MessageApi(get()) }
    single { ClaudeHistoryApi(get()) }

    // Repositories
    single { PreferencesRepository() }
    single { FavoriteRepository() }
    single { ThemeRepository(get()) }  // PreferencesRepository

    // ViewModels - use single to maintain state across recomposition (e.g., theme changes)
    single { LoginViewModel(get(), get(), get()) }  // AuthApi, ProjectApi, CoroutineScope
    single { ChatViewModel(get(), get(), get(), get(), get(), get(), get()) }  // WebSocketClient, ApiClient, ConversationApi, ProjectApi, ClaudeHistoryApi, HistoryWatchClient, CoroutineScope
    single { ProjectListViewModel(get(), get(), get()) }  // ClaudeHistoryApi, FavoriteRepository, CoroutineScope
    single { SettingsViewModel(get(), get(), get()) }  // ApiClient, ThemeRepository, CoroutineScope
    // WebSocket Client for real-time communication
    // URL is determined by stored server host in TokenStorage
    single {
        WebSocketClient(
            httpClient = get(),
            scope = get()
        )
    }

    // History Watch WebSocket Client for real-time session file changes
    single {
        HistoryWatchClient(
            httpClient = get(),
            scope = get()
        )
    }
}
