# Development Guidelines

This document provides essential information for advanced developers working on the Claude Code Native project.

## Build/Configuration Instructions

### Prerequisites
- **Go** 1.23+
- **JDK** 17+
- **Docker** (for database)

### Backend Setup
1. **Environment Variables**: Create a `.env` or `.env.local` file in the `backend/` directory.
   - `DATABASE_URL`: PostgreSQL connection string (e.g., `postgres://user:pass@localhost:5432/claude_native?sslmode=disable`)
   - `JWT_SECRET`: A secure random string for JWT signing.
   - `PORT`: Server port (default: `8080`).
2. **Database**: Use the provided `Makefile` to manage the database.
   ```bash
   make db          # Start PostgreSQL in Docker
   make db-stop     # Stop PostgreSQL
   ```
3. **Running**:
   ```bash
   make server      # Build and run the backend
   # OR
   cd backend && go run cmd/server/main.go
   ```

### Frontend Setup
1. **Running Desktop**:
   ```bash
   cd frontend && ./gradlew :composeApp:desktopRun
   ```
2. **Running Web**:
   ```bash
   cd frontend && ./gradlew :composeApp:wasmJsBrowserRun
   ```

---

## Testing Information

### Backend Tests
- **Location**: Test files are co-located with the source code (e.g., `internal/auth/service_test.go`).
- **Running**:
  ```bash
  go test ./...    # Run all backend tests
  make test-backend # Using Makefile
  ```
- **Example**:
  ```go
  func TestExample(t *testing.T) {
      expected := "ping"
      actual := "ping"
      if actual != expected {
          t.Errorf("expected %s, got %s", expected, actual)
      }
  }
  ```

### Frontend Tests
- **Location**: `frontend/composeApp/src/commonTest/kotlin` (shared) or platform-specific directories (`desktopTest`, `androidDebugUnitTest`).
- **Running**:
  ```bash
  cd frontend
  ./gradlew :composeApp:desktopTest # Run desktop tests
  ./gradlew :composeApp:allTests     # Run all platform tests
  ```
- **Example**:
  ```kotlin
  @Test
  fun testExample() {
      val expected = "ping"
      val actual = "ping"
      assertEquals(expected, actual)
  }
  ```

---

## Additional Development Information

### Backend Architecture
- **DI Framework**: [Uber fx](https://github.com/uber-go/fx) is used for dependency injection and lifecycle management.
- **Web Framework**: [Fiber v2](https://gofiber.io/) handles HTTP and WebSocket routing.
- **Database/ORM**: [Bun](https://bun.uptrace.dev/) is used as the SQL-first ORM for PostgreSQL.
- **Module Structure**: Each package in `internal/` should expose a `Module` variable for `fx` integration.

### Frontend Architecture
- **Framework**: Compose Multiplatform with Kotlin Multiplatform (KMP).
- **DI Framework**: [Koin](https://insert-koin.io/) is used for dependency injection.
- **HTTP Client**: [Ktor](https://ktor.io/) is used for both REST and WebSocket communication.
- **State Management**: Multiplatform `ViewModel` with `StateFlow` is used to manage UI state.

### Code Style
- **Backend**: Standard Go formatting (`gofmt`). Avoid global variables; use DI.
- **Frontend**: Standard Kotlin coding conventions. Follow Material Design 3 principles for UI components.
