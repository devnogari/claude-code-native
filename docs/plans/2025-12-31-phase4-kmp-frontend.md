# Phase 4: KMP Frontend Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Build a Kotlin Multiplatform frontend with Compose UI that connects to the backend API and provides real-time chat with Claude CLI.

**Architecture:** Compose Multiplatform for shared UI code across Android, iOS, Desktop, and Web. Ktor Client for HTTP REST and WebSocket communication. Koin for dependency injection. MVVM pattern with StateFlow for reactive state management.

**Tech Stack:**
- Kotlin 2.0+ with Compose Multiplatform 1.7+
- Ktor Client 3.0+ (HTTP + WebSocket)
- Koin 4.0+ (Dependency Injection)
- Kotlinx Serialization (JSON)
- Targets: Android, iOS, Desktop (JVM), Web (Wasm)

---

## Task 4.1: Initialize KMP Project Structure

**Files:**
- Create: `frontend/settings.gradle.kts`
- Create: `frontend/build.gradle.kts`
- Create: `frontend/gradle.properties`
- Create: `frontend/gradle/libs.versions.toml`
- Create: `frontend/composeApp/build.gradle.kts`
- Create: `frontend/composeApp/src/commonMain/kotlin/App.kt`

**Step 1: Create frontend directory and Gradle wrapper**

```bash
cd /Users/probe/git/devnogari/claude-code-native
mkdir -p frontend
cd frontend
gradle wrapper --gradle-version 8.11
```

**Step 2: Create version catalog**

```toml
# frontend/gradle/libs.versions.toml
[versions]
kotlin = "2.1.0"
compose-multiplatform = "1.7.1"
ktor = "3.0.2"
koin = "4.0.0"
kotlinx-coroutines = "1.9.0"
kotlinx-serialization = "1.7.3"
kotlinx-datetime = "0.6.1"

[libraries]
# Ktor Client
ktor-client-core = { module = "io.ktor:ktor-client-core", version.ref = "ktor" }
ktor-client-cio = { module = "io.ktor:ktor-client-cio", version.ref = "ktor" }
ktor-client-darwin = { module = "io.ktor:ktor-client-darwin", version.ref = "ktor" }
ktor-client-js = { module = "io.ktor:ktor-client-js", version.ref = "ktor" }
ktor-client-content-negotiation = { module = "io.ktor:ktor-client-content-negotiation", version.ref = "ktor" }
ktor-client-websockets = { module = "io.ktor:ktor-client-websockets", version.ref = "ktor" }
ktor-serialization-kotlinx-json = { module = "io.ktor:ktor-serialization-kotlinx-json", version.ref = "ktor" }

# Koin
koin-core = { module = "io.insert-koin:koin-core", version.ref = "koin" }
koin-compose = { module = "io.insert-koin:koin-compose", version.ref = "koin" }

# Kotlinx
kotlinx-coroutines-core = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", version.ref = "kotlinx-coroutines" }
kotlinx-serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "kotlinx-serialization" }
kotlinx-datetime = { module = "org.jetbrains.kotlinx:kotlinx-datetime", version.ref = "kotlinx-datetime" }

[plugins]
kotlin-multiplatform = { id = "org.jetbrains.kotlin.multiplatform", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
compose-multiplatform = { id = "org.jetbrains.compose", version.ref = "compose-multiplatform" }
compose-compiler = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
```

**Step 3: Create settings.gradle.kts**

```kotlin
// frontend/settings.gradle.kts
rootProject.name = "claude-code-native"

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

include(":composeApp")
```

**Step 4: Create root build.gradle.kts**

```kotlin
// frontend/build.gradle.kts
plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.compose.compiler) apply false
}
```

**Step 5: Create gradle.properties**

```properties
# frontend/gradle.properties
org.gradle.jvmargs=-Xmx2048M -Dfile.encoding=UTF-8
kotlin.code.style=official
kotlin.native.cacheKind.iosX64=none
kotlin.native.cacheKind.iosArm64=none
kotlin.native.cacheKind.iosSimulatorArm64=none
```

**Step 6: Create composeApp build.gradle.kts**

```kotlin
// frontend/composeApp/build.gradle.kts
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.targets.js.webpack.KotlinWebpackConfig

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    jvm("desktop")

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        moduleName = "composeApp"
        browser {
            val rootDirPath = project.rootDir.path
            val projectDirPath = project.projectDir.path
            commonWebpackConfig {
                outputFileName = "composeApp.js"
                devServer = (devServer ?: KotlinWebpackConfig.DevServer()).apply {
                    static = (static ?: mutableListOf()).apply {
                        add(rootDirPath)
                        add(projectDirPath)
                    }
                }
            }
        }
        binaries.executable()
    }

    sourceSets {
        val desktopMain by getting

        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(compose.components.uiToolingPreview)

            // Ktor
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.client.websockets)
            implementation(libs.ktor.serialization.kotlinx.json)

            // Koin
            implementation(libs.koin.core)
            implementation(libs.koin.compose)

            // Kotlinx
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
        }

        desktopMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.ktor.client.cio)
            implementation(libs.kotlinx.coroutines.swing)
        }

        wasmJsMain.dependencies {
            implementation(libs.ktor.client.js)
        }
    }
}

compose.desktop {
    application {
        mainClass = "MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "claude-code-native"
            packageVersion = "1.0.0"
        }
    }
}
```

**Step 7: Create minimal App.kt**

```kotlin
// frontend/composeApp/src/commonMain/kotlin/App.kt
package com.claudecode.native

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun App() {
    MaterialTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "Claude Code Native",
                    style = MaterialTheme.typography.headlineLarge
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Loading...",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}
```

**Step 8: Create Desktop main**

```kotlin
// frontend/composeApp/src/desktopMain/kotlin/main.kt
package com.claudecode.native

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Claude Code Native"
    ) {
        App()
    }
}
```

**Step 9: Create Web main**

```kotlin
// frontend/composeApp/src/wasmJsMain/kotlin/main.kt
package com.claudecode.native

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import kotlinx.browser.document

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ComposeViewport(document.body!!) {
        App()
    }
}
```

**Step 10: Create web resources**

```html
<!-- frontend/composeApp/src/wasmJsMain/resources/index.html -->
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Claude Code Native</title>
    <style>
        html, body {
            margin: 0;
            padding: 0;
            width: 100%;
            height: 100%;
            overflow: hidden;
        }
    </style>
    <script src="skiko.js"></script>
    <script src="composeApp.js"></script>
</head>
<body>
</body>
</html>
```

**Step 11: Verify build**

Run: `cd frontend && ./gradlew composeApp:desktopRun`
Expected: Desktop window opens with "Claude Code Native" text

**Step 12: Commit**

```bash
git add frontend/
git commit -m "feat(frontend): initialize KMP project with Compose Multiplatform"
```

---

## Task 4.2: Create Shared Data Models

**Files:**
- Create: `frontend/composeApp/src/commonMain/kotlin/data/model/User.kt`
- Create: `frontend/composeApp/src/commonMain/kotlin/data/model/Project.kt`
- Create: `frontend/composeApp/src/commonMain/kotlin/data/model/Conversation.kt`
- Create: `frontend/composeApp/src/commonMain/kotlin/data/model/Message.kt`
- Create: `frontend/composeApp/src/commonMain/kotlin/data/model/AuthResponse.kt`

**Step 1: Create User model**

```kotlin
// frontend/composeApp/src/commonMain/kotlin/data/model/User.kt
package com.claudecode.native.data.model

import kotlinx.serialization.Serializable

@Serializable
data class User(
    val id: String,
    val username: String
)
```

**Step 2: Create Project model**

```kotlin
// frontend/composeApp/src/commonMain/kotlin/data/model/Project.kt
package com.claudecode.native.data.model

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Project(
    val id: String,
    @SerialName("user_id") val userId: String,
    val name: String,
    val path: String,
    @SerialName("claude_id") val claudeId: String? = null,
    @SerialName("last_accessed") val lastAccessed: Instant? = null,
    @SerialName("created_at") val createdAt: Instant,
    @SerialName("updated_at") val updatedAt: Instant
)

@Serializable
data class CreateProjectRequest(
    val name: String,
    val path: String
)

@Serializable
data class UpdateProjectRequest(
    val name: String? = null,
    val path: String? = null
)
```

**Step 3: Create Conversation model**

```kotlin
// frontend/composeApp/src/commonMain/kotlin/data/model/Conversation.kt
package com.claudecode.native.data.model

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Conversation(
    val id: String,
    @SerialName("project_id") val projectId: String,
    @SerialName("claude_session") val claudeSession: String? = null,
    val title: String? = null,
    @SerialName("message_count") val messageCount: Int = 0,
    @SerialName("jsonl_path") val jsonlPath: String? = null,
    @SerialName("created_at") val createdAt: Instant,
    @SerialName("updated_at") val updatedAt: Instant
)

@Serializable
data class CreateConversationRequest(
    val title: String? = null
)

@Serializable
data class UpdateConversationRequest(
    val title: String? = null
)
```

**Step 4: Create Message model**

```kotlin
// frontend/composeApp/src/commonMain/kotlin/data/model/Message.kt
package com.claudecode.native.data.model

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Message(
    val id: String,
    @SerialName("conversation_id") val conversationId: String,
    val role: MessageRole,
    val content: String,
    @SerialName("token_count") val tokenCount: Int? = null,
    @SerialName("created_at") val createdAt: Instant
)

@Serializable
enum class MessageRole {
    @SerialName("user") USER,
    @SerialName("assistant") ASSISTANT,
    @SerialName("system") SYSTEM
}
```

**Step 5: Create Auth models**

```kotlin
// frontend/composeApp/src/commonMain/kotlin/data/model/AuthResponse.kt
package com.claudecode.native.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LoginRequest(
    val username: String,
    val password: String
)

@Serializable
data class RegisterRequest(
    val username: String,
    val password: String,
    @SerialName("confirm_password") val confirmPassword: String
)

@Serializable
data class TokenResponse(
    val token: String,
    @SerialName("expires_at") val expiresAt: Long,
    val user: User
)

@Serializable
data class ErrorResponse(
    val error: String,
    val details: String? = null
)
```

**Step 6: Verify build**

Run: `cd frontend && ./gradlew composeApp:compileKotlinDesktop`
Expected: BUILD SUCCESSFUL

**Step 7: Commit**

```bash
git add frontend/composeApp/src/commonMain/kotlin/data/
git commit -m "feat(frontend): add shared data models"
```

---

## Task 4.3: Create API Client

**Files:**
- Create: `frontend/composeApp/src/commonMain/kotlin/data/api/ApiClient.kt`
- Create: `frontend/composeApp/src/commonMain/kotlin/data/api/AuthApi.kt`
- Create: `frontend/composeApp/src/commonMain/kotlin/data/api/ProjectApi.kt`
- Create: `frontend/composeApp/src/commonMain/kotlin/data/api/ConversationApi.kt`

**Step 1: Create base ApiClient**

```kotlin
// frontend/composeApp/src/commonMain/kotlin/data/api/ApiClient.kt
package com.claudecode.native.data.api

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json

class ApiClient(
    private val baseUrl: String = "http://localhost:8080/api/v1"
) {
    private var authToken: String? = null

    val httpClient = HttpClient {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
                prettyPrint = false
            })
        }

        install(DefaultRequest) {
            contentType(ContentType.Application.Json)
        }

        HttpResponseValidator {
            handleResponseExceptionWithRequest { exception, _ ->
                throw ApiException(exception.message ?: "Unknown error")
            }
        }
    }

    fun setAuthToken(token: String?) {
        authToken = token
    }

    fun getAuthToken(): String? = authToken

    suspend inline fun <reified T> get(endpoint: String): T {
        return httpClient.get("$baseUrl$endpoint") {
            authToken?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        }.body()
    }

    suspend inline fun <reified T, reified R> post(endpoint: String, body: T): R {
        return httpClient.post("$baseUrl$endpoint") {
            authToken?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            setBody(body)
        }.body()
    }

    suspend inline fun <reified T, reified R> put(endpoint: String, body: T): R {
        return httpClient.put("$baseUrl$endpoint") {
            authToken?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            setBody(body)
        }.body()
    }

    suspend inline fun delete(endpoint: String) {
        httpClient.delete("$baseUrl$endpoint") {
            authToken?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        }
    }
}

class ApiException(message: String) : Exception(message)
```

**Step 2: Create AuthApi**

```kotlin
// frontend/composeApp/src/commonMain/kotlin/data/api/AuthApi.kt
package com.claudecode.native.data.api

import com.claudecode.native.data.model.*

class AuthApi(private val client: ApiClient) {

    suspend fun login(username: String, password: String): TokenResponse {
        val response = client.post<LoginRequest, TokenResponse>(
            "/auth/login",
            LoginRequest(username, password)
        )
        client.setAuthToken(response.token)
        return response
    }

    suspend fun register(username: String, password: String, confirmPassword: String): TokenResponse {
        val response = client.post<RegisterRequest, TokenResponse>(
            "/auth/register",
            RegisterRequest(username, password, confirmPassword)
        )
        client.setAuthToken(response.token)
        return response
    }

    fun logout() {
        client.setAuthToken(null)
    }

    fun isLoggedIn(): Boolean = client.getAuthToken() != null
}
```

**Step 3: Create ProjectApi**

```kotlin
// frontend/composeApp/src/commonMain/kotlin/data/api/ProjectApi.kt
package com.claudecode.native.data.api

import com.claudecode.native.data.model.*

class ProjectApi(private val client: ApiClient) {

    suspend fun getProjects(): List<Project> {
        return client.get("/projects")
    }

    suspend fun getProject(id: String): Project {
        return client.get("/projects/$id")
    }

    suspend fun createProject(name: String, path: String): Project {
        return client.post("/projects", CreateProjectRequest(name, path))
    }

    suspend fun updateProject(id: String, name: String? = null, path: String? = null): Project {
        return client.put("/projects/$id", UpdateProjectRequest(name, path))
    }

    suspend fun deleteProject(id: String) {
        client.delete("/projects/$id")
    }
}
```

**Step 4: Create ConversationApi**

```kotlin
// frontend/composeApp/src/commonMain/kotlin/data/api/ConversationApi.kt
package com.claudecode.native.data.api

import com.claudecode.native.data.model.*

class ConversationApi(private val client: ApiClient) {

    suspend fun getConversations(projectId: String): List<Conversation> {
        return client.get("/projects/$projectId/conversations")
    }

    suspend fun getConversation(id: String): Conversation {
        return client.get("/conversations/$id")
    }

    suspend fun createConversation(projectId: String, title: String? = null): Conversation {
        return client.post("/projects/$projectId/conversations", CreateConversationRequest(title))
    }

    suspend fun updateConversation(id: String, title: String?): Conversation {
        return client.put("/conversations/$id", UpdateConversationRequest(title))
    }

    suspend fun deleteConversation(id: String) {
        client.delete("/conversations/$id")
    }
}
```

**Step 5: Verify build**

Run: `cd frontend && ./gradlew composeApp:compileKotlinDesktop`
Expected: BUILD SUCCESSFUL

**Step 6: Commit**

```bash
git add frontend/composeApp/src/commonMain/kotlin/data/api/
git commit -m "feat(frontend): add API client layer"
```

---

## Task 4.4: Create WebSocket Client

**Files:**
- Create: `frontend/composeApp/src/commonMain/kotlin/data/websocket/WebSocketClient.kt`
- Create: `frontend/composeApp/src/commonMain/kotlin/data/websocket/WebSocketMessage.kt`

**Step 1: Create WebSocket message types**

```kotlin
// frontend/composeApp/src/commonMain/kotlin/data/websocket/WebSocketMessage.kt
package com.claudecode.native.data.websocket

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class OutgoingMessage(
    val type: String,
    val content: String? = null
) {
    companion object {
        fun chat(content: String) = OutgoingMessage("chat", content)
        fun stop() = OutgoingMessage("stop")
        fun ping() = OutgoingMessage("ping")
    }
}

@Serializable
data class IncomingMessage(
    val type: String,
    @SerialName("conversation_id") val conversationId: String? = null,
    val content: String? = null,
    val error: String? = null,
    val status: String? = null
)

object MessageType {
    const val CHAT = "chat"
    const val STREAM = "stream"
    const val STATUS = "status"
    const val ERROR = "error"
    const val STOP = "stop"
    const val COMPLETE = "complete"
    const val PING = "ping"
    const val PONG = "pong"
}
```

**Step 2: Create WebSocket client**

```kotlin
// frontend/composeApp/src/commonMain/kotlin/data/websocket/WebSocketClient.kt
package com.claudecode.native.data.websocket

import io.ktor.client.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class WebSocketClient(
    private val httpClient: HttpClient,
    private val baseUrl: String = "ws://localhost:8080/api/v1"
) {
    private var session: WebSocketSession? = null
    private var receiveJob: Job? = null

    private val _messages = MutableSharedFlow<IncomingMessage>()
    val messages: SharedFlow<IncomingMessage> = _messages.asSharedFlow()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    suspend fun connect(conversationId: String, token: String) {
        if (_connectionState.value == ConnectionState.Connected) {
            return
        }

        _connectionState.value = ConnectionState.Connecting

        try {
            session = httpClient.webSocketSession("$baseUrl/ws/$conversationId") {
                url {
                    parameters.append("token", token)
                }
            }

            _connectionState.value = ConnectionState.Connected

            receiveJob = CoroutineScope(Dispatchers.Default).launch {
                session?.let { ws ->
                    try {
                        for (frame in ws.incoming) {
                            when (frame) {
                                is Frame.Text -> {
                                    val text = frame.readText()
                                    val message = json.decodeFromString<IncomingMessage>(text)
                                    _messages.emit(message)
                                }
                                is Frame.Close -> {
                                    _connectionState.value = ConnectionState.Disconnected
                                }
                                else -> {}
                            }
                        }
                    } catch (e: Exception) {
                        _connectionState.value = ConnectionState.Error(e.message ?: "Unknown error")
                    }
                }
            }
        } catch (e: Exception) {
            _connectionState.value = ConnectionState.Error(e.message ?: "Connection failed")
        }
    }

    suspend fun send(message: OutgoingMessage) {
        session?.send(Frame.Text(json.encodeToString(message)))
    }

    suspend fun sendChat(content: String) {
        send(OutgoingMessage.chat(content))
    }

    suspend fun sendStop() {
        send(OutgoingMessage.stop())
    }

    suspend fun disconnect() {
        receiveJob?.cancel()
        session?.close()
        session = null
        _connectionState.value = ConnectionState.Disconnected
    }
}

sealed class ConnectionState {
    data object Disconnected : ConnectionState()
    data object Connecting : ConnectionState()
    data object Connected : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}
```

**Step 3: Verify build**

Run: `cd frontend && ./gradlew composeApp:compileKotlinDesktop`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add frontend/composeApp/src/commonMain/kotlin/data/websocket/
git commit -m "feat(frontend): add WebSocket client for real-time chat"
```

---

## Task 4.5: Create Koin Dependency Injection Module

**Files:**
- Create: `frontend/composeApp/src/commonMain/kotlin/di/AppModule.kt`
- Modify: `frontend/composeApp/src/commonMain/kotlin/App.kt`

**Step 1: Create Koin module**

```kotlin
// frontend/composeApp/src/commonMain/kotlin/di/AppModule.kt
package com.claudecode.native.di

import com.claudecode.native.data.api.*
import com.claudecode.native.data.websocket.WebSocketClient
import io.ktor.client.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.websocket.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import org.koin.dsl.module

val appModule = module {
    // HTTP Client for REST API
    single {
        ApiClient(baseUrl = "http://localhost:8080/api/v1")
    }

    // HTTP Client for WebSocket
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

    // WebSocket Client
    single { WebSocketClient(get(), baseUrl = "ws://localhost:8080/api/v1") }
}
```

**Step 2: Update App.kt with Koin**

```kotlin
// frontend/composeApp/src/commonMain/kotlin/App.kt
package com.claudecode.native

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.claudecode.native.di.appModule
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
                MainContent()
            }
        }
    }
}

@Composable
fun MainContent() {
    // TODO: Replace with actual navigation
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Claude Code Native",
            style = MaterialTheme.typography.headlineLarge
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Ready to connect",
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
```

**Step 3: Verify build and run**

Run: `cd frontend && ./gradlew composeApp:desktopRun`
Expected: Desktop window opens with "Claude Code Native" text

**Step 4: Commit**

```bash
git add frontend/composeApp/src/commonMain/kotlin/di/ frontend/composeApp/src/commonMain/kotlin/App.kt
git commit -m "feat(frontend): add Koin dependency injection"
```

---

## Task 4.6: Create Login Screen

**Files:**
- Create: `frontend/composeApp/src/commonMain/kotlin/ui/screen/LoginScreen.kt`
- Create: `frontend/composeApp/src/commonMain/kotlin/ui/viewmodel/LoginViewModel.kt`

**Step 1: Create LoginViewModel**

```kotlin
// frontend/composeApp/src/commonMain/kotlin/ui/viewmodel/LoginViewModel.kt
package com.claudecode.native.ui.viewmodel

import com.claudecode.native.data.api.AuthApi
import com.claudecode.native.data.model.TokenResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class LoginViewModel(private val authApi: AuthApi) {
    private val _uiState = MutableStateFlow<LoginUiState>(LoginUiState.Idle)
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    suspend fun login(username: String, password: String) {
        _uiState.value = LoginUiState.Loading
        try {
            val response = authApi.login(username, password)
            _uiState.value = LoginUiState.Success(response)
        } catch (e: Exception) {
            _uiState.value = LoginUiState.Error(e.message ?: "Login failed")
        }
    }

    suspend fun register(username: String, password: String, confirmPassword: String) {
        if (password != confirmPassword) {
            _uiState.value = LoginUiState.Error("Passwords do not match")
            return
        }

        _uiState.value = LoginUiState.Loading
        try {
            val response = authApi.register(username, password, confirmPassword)
            _uiState.value = LoginUiState.Success(response)
        } catch (e: Exception) {
            _uiState.value = LoginUiState.Error(e.message ?: "Registration failed")
        }
    }

    fun clearError() {
        if (_uiState.value is LoginUiState.Error) {
            _uiState.value = LoginUiState.Idle
        }
    }
}

sealed class LoginUiState {
    data object Idle : LoginUiState()
    data object Loading : LoginUiState()
    data class Success(val response: TokenResponse) : LoginUiState()
    data class Error(val message: String) : LoginUiState()
}
```

**Step 2: Create LoginScreen**

```kotlin
// frontend/composeApp/src/commonMain/kotlin/ui/screen/LoginScreen.kt
package com.claudecode.native.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.claudecode.native.ui.viewmodel.LoginUiState
import com.claudecode.native.ui.viewmodel.LoginViewModel
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@Composable
fun LoginScreen(
    viewModel: LoginViewModel = koinInject(),
    onLoginSuccess: () -> Unit
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var isRegisterMode by remember { mutableStateOf(false) }

    val uiState by viewModel.uiState.collectAsState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(uiState) {
        if (uiState is LoginUiState.Success) {
            onLoginSuccess()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Claude Code Native",
            style = MaterialTheme.typography.headlineLarge
        )

        Spacer(modifier = Modifier.height(32.dp))

        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("Username") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = uiState !is LoginUiState.Loading
        )

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            enabled = uiState !is LoginUiState.Loading
        )

        if (isRegisterMode) {
            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = confirmPassword,
                onValueChange = { confirmPassword = it },
                label = { Text("Confirm Password") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                enabled = uiState !is LoginUiState.Loading
            )
        }

        if (uiState is LoginUiState.Error) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = (uiState as LoginUiState.Error).message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = {
                scope.launch {
                    if (isRegisterMode) {
                        viewModel.register(username, password, confirmPassword)
                    } else {
                        viewModel.login(username, password)
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = uiState !is LoginUiState.Loading &&
                      username.isNotBlank() &&
                      password.isNotBlank() &&
                      (!isRegisterMode || confirmPassword.isNotBlank())
        ) {
            if (uiState is LoginUiState.Loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = MaterialTheme.colorScheme.onPrimary
                )
            } else {
                Text(if (isRegisterMode) "Register" else "Login")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        TextButton(
            onClick = {
                isRegisterMode = !isRegisterMode
                viewModel.clearError()
            }
        ) {
            Text(
                if (isRegisterMode) "Already have an account? Login"
                else "Don't have an account? Register"
            )
        }
    }
}
```

**Step 3: Add LoginViewModel to Koin**

Update `frontend/composeApp/src/commonMain/kotlin/di/AppModule.kt`:

```kotlin
// Add to appModule
factory { LoginViewModel(get()) }
```

**Step 4: Verify build**

Run: `cd frontend && ./gradlew composeApp:compileKotlinDesktop`
Expected: BUILD SUCCESSFUL

**Step 5: Commit**

```bash
git add frontend/composeApp/src/commonMain/kotlin/ui/ frontend/composeApp/src/commonMain/kotlin/di/
git commit -m "feat(frontend): add Login screen with ViewModel"
```

---

## Task 4.7: Create Chat Screen

**Files:**
- Create: `frontend/composeApp/src/commonMain/kotlin/ui/screen/ChatScreen.kt`
- Create: `frontend/composeApp/src/commonMain/kotlin/ui/viewmodel/ChatViewModel.kt`
- Create: `frontend/composeApp/src/commonMain/kotlin/ui/component/MessageBubble.kt`

**Step 1: Create ChatViewModel**

```kotlin
// frontend/composeApp/src/commonMain/kotlin/ui/viewmodel/ChatViewModel.kt
package com.claudecode.native.ui.viewmodel

import com.claudecode.native.data.api.ApiClient
import com.claudecode.native.data.model.Message
import com.claudecode.native.data.model.MessageRole
import com.claudecode.native.data.websocket.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock

class ChatViewModel(
    private val webSocketClient: WebSocketClient,
    private val apiClient: ApiClient
) {
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val _streamingContent = MutableStateFlow("")
    val streamingContent: StateFlow<String> = _streamingContent.asStateFlow()

    val connectionState = webSocketClient.connectionState

    private var currentConversationId: String? = null
    private val scope = CoroutineScope(Dispatchers.Default)

    init {
        scope.launch {
            webSocketClient.messages.collect { message ->
                handleIncomingMessage(message)
            }
        }
    }

    suspend fun connect(conversationId: String) {
        currentConversationId = conversationId
        val token = apiClient.getAuthToken() ?: return
        webSocketClient.connect(conversationId, token)
    }

    suspend fun disconnect() {
        webSocketClient.disconnect()
    }

    suspend fun sendMessage(content: String) {
        if (content.isBlank()) return

        // Add user message to list
        val userMessage = ChatMessage(
            id = Clock.System.now().toEpochMilliseconds().toString(),
            role = MessageRole.USER,
            content = content,
            isStreaming = false
        )
        _messages.value = _messages.value + userMessage

        // Send via WebSocket
        webSocketClient.sendChat(content)
        _isStreaming.value = true
        _streamingContent.value = ""
    }

    suspend fun stopGeneration() {
        webSocketClient.sendStop()
    }

    private fun handleIncomingMessage(message: IncomingMessage) {
        when (message.type) {
            MessageType.STREAM -> {
                _streamingContent.value += message.content ?: ""
            }
            MessageType.COMPLETE -> {
                // Add complete assistant message
                val assistantMessage = ChatMessage(
                    id = Clock.System.now().toEpochMilliseconds().toString(),
                    role = MessageRole.ASSISTANT,
                    content = _streamingContent.value,
                    isStreaming = false
                )
                _messages.value = _messages.value + assistantMessage
                _isStreaming.value = false
                _streamingContent.value = ""
            }
            MessageType.ERROR -> {
                // Handle error
                _isStreaming.value = false
                _streamingContent.value = ""
            }
            MessageType.STATUS -> {
                // Handle status updates
            }
        }
    }

    fun clearMessages() {
        _messages.value = emptyList()
    }
}

data class ChatMessage(
    val id: String,
    val role: MessageRole,
    val content: String,
    val isStreaming: Boolean = false
)
```

**Step 2: Create MessageBubble component**

```kotlin
// frontend/composeApp/src/commonMain/kotlin/ui/component/MessageBubble.kt
package com.claudecode.native.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.claudecode.native.data.model.MessageRole
import com.claudecode.native.ui.viewmodel.ChatMessage

@Composable
fun MessageBubble(
    message: ChatMessage,
    modifier: Modifier = Modifier
) {
    val isUser = message.role == MessageRole.USER
    val alignment = if (isUser) Alignment.End else Alignment.Start
    val backgroundColor = if (isUser) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val textColor = if (isUser) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 600.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(backgroundColor)
                .padding(12.dp)
        ) {
            Text(
                text = message.content,
                color = textColor,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
fun StreamingBubble(
    content: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 600.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = content.ifEmpty { "..." },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                if (content.isEmpty()) {
                    Spacer(modifier = Modifier.width(8.dp))
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                }
            }
        }
    }
}
```

**Step 3: Create ChatScreen**

```kotlin
// frontend/composeApp/src/commonMain/kotlin/ui/screen/ChatScreen.kt
package com.claudecode.native.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.claudecode.native.data.websocket.ConnectionState
import com.claudecode.native.ui.component.MessageBubble
import com.claudecode.native.ui.component.StreamingBubble
import com.claudecode.native.ui.viewmodel.ChatViewModel
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@Composable
fun ChatScreen(
    conversationId: String,
    viewModel: ChatViewModel = koinInject()
) {
    var inputText by remember { mutableStateOf("") }
    val messages by viewModel.messages.collectAsState()
    val isStreaming by viewModel.isStreaming.collectAsState()
    val streamingContent by viewModel.streamingContent.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(conversationId) {
        viewModel.connect(conversationId)
    }

    DisposableEffect(Unit) {
        onDispose {
            scope.launch { viewModel.disconnect() }
        }
    }

    LaunchedEffect(messages.size, streamingContent) {
        if (messages.isNotEmpty() || streamingContent.isNotEmpty()) {
            listState.animateScrollToItem(messages.size)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Connection status
        if (connectionState !is ConnectionState.Connected) {
            Surface(
                color = when (connectionState) {
                    is ConnectionState.Connecting -> MaterialTheme.colorScheme.secondaryContainer
                    is ConnectionState.Error -> MaterialTheme.colorScheme.errorContainer
                    else -> MaterialTheme.colorScheme.surfaceVariant
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = when (connectionState) {
                        is ConnectionState.Connecting -> "Connecting..."
                        is ConnectionState.Error -> "Error: ${(connectionState as ConnectionState.Error).message}"
                        else -> "Disconnected"
                    },
                    modifier = Modifier.padding(8.dp),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        // Messages list
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            items(messages, key = { it.id }) { message ->
                MessageBubble(message = message)
            }

            if (isStreaming) {
                item {
                    StreamingBubble(content = streamingContent)
                }
            }
        }

        // Input area
        Surface(
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.Bottom
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Type a message...") },
                    enabled = connectionState is ConnectionState.Connected && !isStreaming,
                    maxLines = 5
                )

                Spacer(modifier = Modifier.width(8.dp))

                if (isStreaming) {
                    IconButton(
                        onClick = { scope.launch { viewModel.stopGeneration() } }
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = "Stop")
                    }
                } else {
                    IconButton(
                        onClick = {
                            if (inputText.isNotBlank()) {
                                scope.launch {
                                    viewModel.sendMessage(inputText)
                                    inputText = ""
                                }
                            }
                        },
                        enabled = connectionState is ConnectionState.Connected && inputText.isNotBlank()
                    ) {
                        Icon(Icons.Default.Send, contentDescription = "Send")
                    }
                }
            }
        }
    }
}
```

**Step 4: Add ChatViewModel to Koin**

Update `frontend/composeApp/src/commonMain/kotlin/di/AppModule.kt`:

```kotlin
// Add to appModule
factory { ChatViewModel(get(), get()) }
```

**Step 5: Verify build**

Run: `cd frontend && ./gradlew composeApp:compileKotlinDesktop`
Expected: BUILD SUCCESSFUL

**Step 6: Commit**

```bash
git add frontend/composeApp/src/commonMain/kotlin/ui/
git commit -m "feat(frontend): add Chat screen with real-time messaging"
```

---

## Task 4.8: Create Navigation and Main App

**Files:**
- Create: `frontend/composeApp/src/commonMain/kotlin/ui/navigation/Navigation.kt`
- Modify: `frontend/composeApp/src/commonMain/kotlin/App.kt`

**Step 1: Create Navigation**

```kotlin
// frontend/composeApp/src/commonMain/kotlin/ui/navigation/Navigation.kt
package com.claudecode.native.ui.navigation

sealed class Screen(val route: String) {
    data object Login : Screen("login")
    data object ProjectList : Screen("projects")
    data class Chat(val conversationId: String) : Screen("chat/$conversationId")
}
```

**Step 2: Update App.kt with navigation**

```kotlin
// frontend/composeApp/src/commonMain/kotlin/App.kt
package com.claudecode.native

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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

@Composable
fun AppNavigation() {
    var currentScreen by remember { mutableStateOf<Screen>(Screen.Login) }
    var selectedConversationId by remember { mutableStateOf<String?>(null) }

    when (val screen = currentScreen) {
        is Screen.Login -> {
            LoginScreen(
                onLoginSuccess = {
                    // For now, go directly to a demo chat
                    // In production, navigate to project list first
                    currentScreen = Screen.ProjectList
                }
            )
        }
        is Screen.ProjectList -> {
            // Placeholder - will be implemented in future task
            ProjectListPlaceholder(
                onConversationSelected = { convId ->
                    selectedConversationId = convId
                    currentScreen = Screen.Chat(convId)
                }
            )
        }
        is Screen.Chat -> {
            ChatScreen(conversationId = screen.conversationId)
        }
    }
}

@Composable
fun ProjectListPlaceholder(onConversationSelected: (String) -> Unit) {
    // Temporary placeholder
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp)
    ) {
        Text("Projects", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(16.dp))
        Text("Project and conversation list will be implemented here.")
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = {
            // Demo: Use a test conversation ID
            onConversationSelected("test-conversation-id")
        }) {
            Text("Open Demo Chat")
        }
    }
}
```

**Step 3: Update final Koin module**

```kotlin
// frontend/composeApp/src/commonMain/kotlin/di/AppModule.kt
package com.claudecode.native.di

import com.claudecode.native.data.api.*
import com.claudecode.native.data.websocket.WebSocketClient
import com.claudecode.native.ui.viewmodel.ChatViewModel
import com.claudecode.native.ui.viewmodel.LoginViewModel
import io.ktor.client.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.websocket.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import org.koin.dsl.module

val appModule = module {
    // HTTP Client for REST API
    single {
        ApiClient(baseUrl = "http://localhost:8080/api/v1")
    }

    // HTTP Client for WebSocket
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

    // WebSocket Client
    single { WebSocketClient(get(), baseUrl = "ws://localhost:8080/api/v1") }

    // ViewModels
    factory { LoginViewModel(get()) }
    factory { ChatViewModel(get(), get()) }
}
```

**Step 4: Verify build and run**

Run: `cd frontend && ./gradlew composeApp:desktopRun`
Expected: Desktop window opens with Login screen

**Step 5: Commit**

```bash
git add frontend/
git commit -m "feat(frontend): add navigation and integrate screens"
```

---

## Summary

Phase 4 establishes the KMP frontend foundation:

1. **Task 4.1**: KMP project setup with Compose Multiplatform
2. **Task 4.2**: Shared data models (User, Project, Conversation, Message)
3. **Task 4.3**: REST API client (Auth, Project, Conversation APIs)
4. **Task 4.4**: WebSocket client for real-time chat
5. **Task 4.5**: Koin dependency injection
6. **Task 4.6**: Login screen with authentication
7. **Task 4.7**: Chat screen with real-time messaging
8. **Task 4.8**: Navigation and app integration

**Targets Supported:**
- Desktop (JVM) - Primary development target
- Web (Wasm) - Browser deployment
- Android/iOS - Can be added later with minimal changes

**Run Commands:**
- Desktop: `./gradlew composeApp:desktopRun`
- Web: `./gradlew composeApp:wasmJsBrowserRun`

**Next Phase:** Phase 5 will add project/conversation list screens, settings, and polish.
