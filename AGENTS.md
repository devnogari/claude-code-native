# AGENTS.md - claude-code-native Project Agents & Coding Standards

Project-specific agents and coding conventions for the Claude Code Native application.

## Project Architecture

```
claude-code-native/
├── backend/          # Go (Fiber + fx + Bun)
├── frontend/         # Kotlin Multiplatform (Compose)
└── docs/             # Documentation & Plans
```

---

## Coding Standards

### Kotlin/Compose Multiplatform

#### ViewModel Pattern
```kotlin
class ExampleViewModel(
    private val repository: ExampleRepository,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) {
    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    fun doAction() {
        scope.launch {
            _uiState.value = UiState.Loading
            try {
                val result = repository.fetch()
                _uiState.value = UiState.Success(result)
            } catch (e: Exception) {
                _uiState.value = UiState.Error(e.message ?: "Unknown error")
            }
        }
    }
}
```

#### UI State Pattern
```kotlin
sealed class UiState {
    object Idle : UiState()
    object Loading : UiState()
    data class Success<T>(val data: T) : UiState()
    data class Error(val message: String) : UiState()
}
```

#### Compose Screen Pattern
```kotlin
@Composable
fun ExampleScreen(
    viewModel: ExampleViewModel = koinInject(),
    onNavigate: (Route) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    ExampleContent(
        uiState = uiState,
        onAction = viewModel::doAction,
        onNavigate = onNavigate
    )
}

@Composable
private fun ExampleContent(
    uiState: UiState,
    onAction: () -> Unit,
    onNavigate: (Route) -> Unit
) {
    // State hoisting - UI is stateless
}
```

#### Koin DI Pattern
```kotlin
val featureModule = module {
    singleOf(::FeatureRepository)
    factoryOf(::FeatureViewModel)
}
```

#### Test Pattern (Turbine + MockK)
```kotlin
@OptIn(ExperimentalCoroutinesApi::class)
class ExampleViewModelTest : ViewModelTestBase() {
    private lateinit var repository: ExampleRepository
    private lateinit var viewModel: ExampleViewModel

    @BeforeTest
    fun setUp() {
        super.setup()
        repository = mockk(relaxed = true)
        viewModel = ExampleViewModel(repository, TestScope(testDispatcher))
    }

    @Test
    fun `action should update state`() = runTest {
        coEvery { repository.fetch() } returns Result.success(data)

        viewModel.uiState.test {
            assertEquals(UiState.Idle, awaitItem())
            viewModel.doAction()
            assertEquals(UiState.Loading, awaitItem())
            testDispatcher.scheduler.advanceUntilIdle()
            val success = awaitItem()
            assertTrue(success is UiState.Success)
        }
    }
}
```

---

### Go Backend

#### Module Pattern (Uber fx)
```go
// module.go
var Module = fx.Module("feature",
    fx.Provide(NewRepository),
    fx.Provide(NewService),
    fx.Provide(NewHandler),
)

// handler.go
type Handler struct {
    service *Service
    logger  *zap.Logger
}

func NewHandler(service *Service, logger *zap.Logger) *Handler {
    return &Handler{service: service, logger: logger}
}
```

#### Repository Pattern (Bun ORM)
```go
type Repository struct {
    db *bun.DB
}

func (r *Repository) FindByID(ctx context.Context, id string) (*Model, error) {
    var model Model
    err := r.db.NewSelect().
        Model(&model).
        Where("id = ?", id).
        Scan(ctx)
    if err != nil {
        return nil, fmt.Errorf("find by id: %w", err)
    }
    return &model, nil
}
```

#### Error Handling
```go
// Always wrap errors with context
if err != nil {
    return fmt.Errorf("operation name: %w", err)
}

// Early return pattern
func (s *Service) DoSomething(ctx context.Context) error {
    if err := s.validate(); err != nil {
        return fmt.Errorf("validate: %w", err)
    }

    if err := s.process(); err != nil {
        return fmt.Errorf("process: %w", err)
    }

    return nil
}
```

#### Handler Pattern (Fiber v2)
```go
func (h *Handler) Create(c *fiber.Ctx) error {
    var req CreateRequest
    if err := c.BodyParser(&req); err != nil {
        return fiber.NewError(fiber.StatusBadRequest, "invalid request body")
    }

    if err := req.Validate(); err != nil {
        return fiber.NewError(fiber.StatusBadRequest, err.Error())
    }

    result, err := h.service.Create(c.Context(), &req)
    if err != nil {
        h.logger.Error("create failed", zap.Error(err))
        return fiber.NewError(fiber.StatusInternalServerError, "internal error")
    }

    return c.Status(fiber.StatusCreated).JSON(result)
}
```

#### Test Pattern (testify)
```go
func TestService_Create(t *testing.T) {
    // Arrange
    repo := NewMockRepository(t)
    service := NewService(repo, zap.NewNop())

    repo.EXPECT().
        Create(mock.Anything, mock.AnythingOfType("*Model")).
        Return(nil)

    // Act
    err := service.Create(context.Background(), &CreateRequest{})

    // Assert
    require.NoError(t, err)
}
```

---

## Project-Specific Agents

### `kotlin-compose-developer`
**Purpose**: Kotlin Multiplatform & Compose UI implementation
**Specialization**:
- Compose MP components (Desktop, WASM, iOS, Android)
- StateFlow/MutableStateFlow patterns
- Koin dependency injection
- Ktor HTTP/WebSocket clients

**Triggers**:
- `*.kt` files in `frontend/`
- Compose UI requests
- ViewModel implementation
- Multiplatform configuration

**Patterns to Follow**:
- State hoisting in Composables
- Private MutableStateFlow, public StateFlow
- Sealed class for UI states
- Platform-specific implementations in `*Main` source sets

---

### `go-backend-developer`
**Purpose**: Go backend API & WebSocket implementation
**Specialization**:
- Fiber v2 HTTP handlers
- Uber fx dependency injection
- Bun ORM database operations
- gorilla/websocket real-time communication

**Triggers**:
- `*.go` files in `backend/`
- API endpoint requests
- WebSocket handler implementation
- Database migration needs

**Patterns to Follow**:
- fx.Module for each domain
- Repository interface pattern
- Error wrapping with context
- Structured logging with zap

---

### `fullstack-integrator`
**Purpose**: Frontend-Backend integration & API contracts
**Specialization**:
- REST API alignment (Ktor ↔ Fiber)
- WebSocket message protocol
- DTO/Model mapping
- Cross-platform testing

**Triggers**:
- API contract changes
- WebSocket protocol updates
- Integration issues
- E2E testing needs

**Responsibilities**:
- Ensure API request/response models match
- Validate WebSocket message types align
- Test full request flow

---

### `multiplatform-tester`
**Purpose**: Cross-platform test coverage
**Specialization**:
- Kotlin: Turbine + MockK + runTest
- Go: testify + mockery
- Platform-specific test configurations
- CI test orchestration

**Triggers**:
- New feature implementation
- Bug fixes requiring tests
- Test coverage gaps
- CI/CD test failures

**Test Locations**:
```
frontend/composeApp/src/desktopTest/   # Desktop tests
frontend/composeApp/src/commonTest/    # Shared tests
backend/internal/*/                    # Go package tests
```

---

### `websocket-specialist`
**Purpose**: Real-time communication implementation
**Specialization**:
- WebSocket client (Ktor)
- WebSocket server (gorilla/websocket)
- Message protocol design
- Connection lifecycle management

**Triggers**:
- Real-time feature requests
- WebSocket debugging
- Connection issues
- Message protocol changes

**Message Types**:
```
Client→Server: chat, stop, ping
Server→Client: stream, complete, status, error, pong
```

---

## Auto-Trigger Rules

```yaml
file_patterns:
  "frontend/**/*.kt":
    agent: kotlin-compose-developer

  "backend/**/*.go":
    agent: go-backend-developer

  "**/websocket/**":
    agent: websocket-specialist

  "**/*Test*.kt":
    agent: multiplatform-tester

  "**/*_test.go":
    agent: multiplatform-tester

task_keywords:
  ["compose", "viewmodel", "screen", "ui"]:
    agent: kotlin-compose-developer

  ["api", "handler", "endpoint", "fiber"]:
    agent: go-backend-developer

  ["websocket", "realtime", "streaming"]:
    agent: websocket-specialist

  ["test", "coverage", "mock"]:
    agent: multiplatform-tester

  ["integration", "contract", "dto"]:
    agent: fullstack-integrator
```

---

## Quality Gates

### Before Commit
- [ ] All tests pass (`go test ./...` + `./gradlew check`)
- [ ] No lint errors
- [ ] Code follows patterns above
- [ ] New public functions have tests

### Before PR
- [ ] Code review completed (`/superpowers:requesting-code-review`)
- [ ] All platforms build (Desktop, WASM, iOS, Android)
- [ ] No TODO comments in new code
- [ ] Documentation updated if needed

---

## Quick Reference

| Task | Agent | Command |
|------|-------|---------|
| New Compose screen | kotlin-compose-developer | `/sc:implement` |
| New API endpoint | go-backend-developer | `/sc:implement` |
| WebSocket feature | websocket-specialist | `/sc:implement` |
| Write tests | multiplatform-tester | `/superpowers:test-driven-development` |
| Integration work | fullstack-integrator | `/sc:implement` |
| Code review | - | `/superpowers:requesting-code-review` |

---

## Version History

| Version | Date | Changes |
|---------|------|---------|
| 1.0.0 | 2026-01-16 | Initial creation |
