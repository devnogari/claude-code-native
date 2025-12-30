package com.claudecode.native.di

import com.claudecode.native.data.api.ApiClient
import com.claudecode.native.data.api.AuthApi
import com.claudecode.native.data.api.ConversationApi
import com.claudecode.native.data.api.ProjectApi
import com.claudecode.native.data.websocket.WebSocketClient
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import org.koin.dsl.module

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
    single { CoroutineScope(SupervisorJob() + Dispatchers.Default) }

    // HTTP Client for REST API operations
    single {
        ApiClient(baseUrl = "http://localhost:8080/api/v1")
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
    }

    // API Services
    single { AuthApi(get()) }
    single { ProjectApi(get()) }
    single { ConversationApi(get()) }

    // WebSocket Client for real-time communication
    single {
        WebSocketClient(
            httpClient = get(),
            scope = get(),
            baseUrl = "ws://localhost:8080/api/v1"
        )
    }
}
