# Claude Code Native - Frontend

Kotlin Multiplatform frontend for Claude Code Native, supporting Desktop (JVM) and Web (WebAssembly) platforms with a shared codebase.

## Technology Stack

- **UI Framework**: [Compose Multiplatform](https://www.jetbrains.com/lp/compose-multiplatform/) 1.7.1
- **Language**: Kotlin 2.1.0
- **DI**: [Koin](https://insert-koin.io/) 4.0
- **HTTP Client**: [Ktor](https://ktor.io/) 3.0.2
- **Serialization**: Kotlinx Serialization 1.7.3
- **Coroutines**: Kotlinx Coroutines 1.9.0
- **Design**: Material Design 3

## Supported Platforms

| Platform | Target | Status |
|----------|--------|--------|
| Desktop (macOS) | JVM | Supported |
| Desktop (Windows) | JVM | Supported |
| Desktop (Linux) | JVM | Supported |
| Web | WebAssembly (Wasm) | Supported |

## Project Structure

```
frontend/
├── composeApp/
│   ├── src/
│   │   ├── commonMain/          # Shared code (all platforms)
│   │   │   └── kotlin/
│   │   │       └── com/claudecode/native/
│   │   │           ├── App.kt                # Main app & navigation
│   │   │           ├── di/
│   │   │           │   └── AppModule.kt      # Koin DI module
│   │   │           ├── data/
│   │   │           │   ├── api/              # HTTP API clients
│   │   │           │   │   ├── ApiClient.kt
│   │   │           │   │   ├── AuthApi.kt
│   │   │           │   │   ├── ProjectApi.kt
│   │   │           │   │   └── ConversationApi.kt
│   │   │           │   ├── model/            # Data models
│   │   │           │   │   ├── User.kt
│   │   │           │   │   ├── AuthResponse.kt
│   │   │           │   │   ├── Project.kt
│   │   │           │   │   ├── Conversation.kt
│   │   │           │   │   └── Message.kt
│   │   │           │   └── websocket/        # WebSocket client
│   │   │           │       ├── WebSocketClient.kt
│   │   │           │       └── WebSocketMessage.kt
│   │   │           └── ui/
│   │   │               ├── component/        # Reusable components
│   │   │               │   └── MessageBubble.kt
│   │   │               ├── screen/           # Screen composables
│   │   │               │   ├── LoginScreen.kt
│   │   │               │   ├── ProjectListScreen.kt
│   │   │               │   └── ChatScreen.kt
│   │   │               ├── viewmodel/        # ViewModels
│   │   │               │   ├── LoginViewModel.kt
│   │   │               │   ├── ProjectListViewModel.kt
│   │   │               │   └── ChatViewModel.kt
│   │   │               └── navigation/
│   │   │                   └── Navigation.kt # Navigation sealed class
│   │   │
│   │   ├── desktopMain/         # Desktop-specific (JVM)
│   │   │   └── kotlin/
│   │   │       └── com/claudecode/native/
│   │   │           └── main.kt  # Desktop entry point
│   │   │
│   │   └── wasmJsMain/          # Web-specific (WebAssembly)
│   │       └── kotlin/
│   │           └── com/claudecode/native/
│   │               └── main.kt  # Web entry point
│   │
│   └── build.gradle.kts         # Module build configuration
│
├── gradle/
│   └── libs.versions.toml       # Version catalog
├── build.gradle.kts             # Root build configuration
├── settings.gradle.kts          # Project settings
├── gradlew                      # Gradle wrapper (Unix)
└── gradlew.bat                  # Gradle wrapper (Windows)
```

## Getting Started

### Prerequisites

- **JDK 17+** (Temurin/Corretto recommended)
- **Gradle 8.0+** (wrapper included)

### Running Desktop Application

```bash
# Development run
./gradlew composeApp:desktopRun

# With specific JVM args
./gradlew composeApp:desktopRun -PjvmArgs="-Xmx2g"
```

### Running Web Application

```bash
# Development server with hot reload
./gradlew composeApp:wasmJsBrowserRun

# The app will be available at http://localhost:8080
```

### Building Distributions

#### Desktop

```bash
# Build for current OS
./gradlew composeApp:packageDistributionForCurrentOS

# Specific formats
./gradlew composeApp:packageDmg      # macOS DMG
./gradlew composeApp:packageMsi      # Windows MSI
./gradlew composeApp:packageDeb      # Linux DEB

# Output: composeApp/build/compose/binaries/
```

#### Web

```bash
# Production build
./gradlew composeApp:wasmJsBrowserProductionWebpack

# Output: composeApp/build/dist/wasmJs/productionExecutable/
```

## Architecture

### Navigation

State-based navigation using sealed class:

```kotlin
sealed class Screen {
    object Login : Screen()
    object ProjectList : Screen()
    data class Chat(val conversationId: String) : Screen()
}
```

### Dependency Injection (Koin)

```kotlin
val appModule = module {
    single { ApiClient() }
    single { AuthApi(get()) }
    single { ProjectApi(get()) }
    single { ConversationApi(get()) }
    single { WebSocketClient(get()) }

    viewModel { LoginViewModel(get()) }
    viewModel { ProjectListViewModel(get(), get()) }
    viewModel { params -> ChatViewModel(params.get(), get(), get()) }
}
```

### Data Flow

```
UI (Composables)
    ↓ User Actions
ViewModel (State Management)
    ↓ API Calls
API Client (Ktor HTTP)
    ↓ HTTP/WebSocket
Backend Server
```

## API Integration

### HTTP Client

```kotlin
class ApiClient {
    private val client = HttpClient {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        install(WebSockets)
    }

    suspend fun <T> get(path: String): T = client.get(baseUrl + path)
    suspend fun <T, R> post(path: String, body: T): R = client.post(baseUrl + path) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }
}
```

### WebSocket Client

```kotlin
class WebSocketClient(private val apiClient: ApiClient) {
    suspend fun connect(conversationId: String, token: String) {
        apiClient.webSocket("/api/v1/ws/$conversationId?token=$token") {
            // Handle incoming messages
            for (frame in incoming) {
                when (frame) {
                    is Frame.Text -> handleMessage(frame.readText())
                    else -> {}
                }
            }
        }
    }

    suspend fun send(message: WebSocketMessage) {
        session?.send(Json.encodeToString(message))
    }
}
```

## UI Components

### Material Design 3

Using Compose Material 3 with dynamic theming:

```kotlin
@Composable
fun App() {
    MaterialTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            AppNavigation()
        }
    }
}
```

### Custom Components

- **MessageBubble**: Chat message display with user/assistant styling
- **ProjectCard**: Project list item with actions
- **ConversationItem**: Conversation list item

## Platform-Specific Code

### Desktop (JVM)

```kotlin
// desktopMain/kotlin/.../main.kt
fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Claude Code Native"
    ) {
        App()
    }
}
```

Uses:
- Ktor CIO engine for HTTP
- Swing coroutine dispatcher
- Native window decorations

### Web (WebAssembly)

```kotlin
// wasmJsMain/kotlin/.../main.kt
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    CanvasBasedWindow(canvasElementId = "ComposeTarget") {
        App()
    }
}
```

Uses:
- Ktor JS engine for HTTP
- Canvas-based rendering
- Browser APIs via Kotlin/JS interop

## Development

### IDE Setup

1. **IntelliJ IDEA** (recommended) or Android Studio
2. Install Kotlin Multiplatform plugin
3. Open project from `frontend/` directory

### Running Tests

```bash
# All tests
./gradlew check

# Desktop tests only
./gradlew desktopTest

# With coverage
./gradlew koverReport
```

### Code Style

Following Kotlin coding conventions:
- Use meaningful names
- Prefer immutability
- Use extension functions appropriately
- Document public APIs

### Adding New Screens

1. Create screen composable in `ui/screen/`
2. Create ViewModel in `ui/viewmodel/`
3. Add to `Screen` sealed class
4. Update `AppNavigation()` with new route
5. Register ViewModel in Koin module

## Configuration

### Build Configuration

`gradle.properties`:
```properties
kotlin.code.style=official
kotlin.native.cacheKind.linuxX64=none
```

### Dependencies

All versions managed in `gradle/libs.versions.toml`:

```toml
[versions]
kotlin = "2.1.0"
compose-multiplatform = "1.7.1"
ktor = "3.0.2"
koin = "4.0.0"
kotlinx-coroutines = "1.9.0"
kotlinx-serialization = "1.7.3"
kotlinx-datetime = "0.6.1"
```

## Troubleshooting

### Common Issues

1. **Gradle sync fails**
   - Ensure JDK 17+ is configured
   - Run `./gradlew --refresh-dependencies`

2. **Desktop app won't start**
   - Check for conflicting ports
   - Verify backend is running

3. **WASM build errors**
   - Clear build cache: `./gradlew clean`
   - Update Kotlin version if needed

4. **WebSocket connection fails**
   - Verify CORS settings on backend
   - Check authentication token

### Debug Mode

```bash
# Desktop with debug logging
./gradlew composeApp:desktopRun --info

# Web with source maps
./gradlew composeApp:wasmJsBrowserDevelopmentRun
```

## License

MIT License - See root README for details.
