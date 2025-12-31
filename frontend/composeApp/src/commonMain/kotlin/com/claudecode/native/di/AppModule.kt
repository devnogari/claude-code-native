package com.claudecode.native.di

import com.claudecode.native.data.api.ApiClient
import com.claudecode.native.data.api.AuthApi
import com.claudecode.native.data.api.ClaudeHistoryApi
import com.claudecode.native.data.api.ConversationApi
import com.claudecode.native.data.api.MessageApi
import com.claudecode.native.data.api.ProjectApi
import com.claudecode.native.data.config.UrlConfig
import com.claudecode.native.data.repository.FavoriteRepository
import com.claudecode.native.data.websocket.WebSocketClient
import com.claudecode.native.ui.viewmodel.ChatViewModel
import com.claudecode.native.ui.viewmodel.ClaudeHistoryViewModel
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
    // URL is determined by platform-specific UrlConfig
    single {
        ApiClient(baseUrl = UrlConfig.apiBaseUrl)
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
    single { FavoriteRepository() }

    // ViewModels
    factory { LoginViewModel(get(), get(), get()) }  // AuthApi, ProjectApi, CoroutineScope
    factory { ChatViewModel(get(), get(), get(), get(), get()) }  // WebSocketClient, ApiClient, MessageApi, ConversationApi, CoroutineScope
    factory { ProjectListViewModel(get(), get(), get(), get()) }  // ProjectApi, ConversationApi, FavoriteRepository, CoroutineScope
    factory { SettingsViewModel(get(), get()) }  // ApiClient, CoroutineScope
    factory { ClaudeHistoryViewModel(get(), get()) }  // ClaudeHistoryApi, CoroutineScope

    // WebSocket Client for real-time communication
    // URL is determined by platform-specific UrlConfig
    single {
        WebSocketClient(
            httpClient = get(),
            scope = get(),
            baseUrl = UrlConfig.wsBaseUrl
        )
    }
}
