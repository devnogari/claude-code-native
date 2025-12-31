# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Claude Code Native is a cross-platform desktop/web application providing a native UI for Claude Code CLI. It consists of a Go backend with real-time WebSocket streaming and a Kotlin Multiplatform frontend (Compose).

## Build & Run Commands

### Backend (Go)
```bash
cd backend
go run ./cmd/server              # Run server (default :8080)
go test ./...                    # Run all tests
go test ./internal/auth/...      # Run tests for single package
go test -v -run TestLogin ./...  # Run specific test
go mod tidy                      # Clean dependencies
```

### Frontend (Kotlin Multiplatform)
```bash
cd frontend
./gradlew composeApp:desktopRun                    # Run desktop app
./gradlew composeApp:wasmJsBrowserRun              # Run web dev server
./gradlew composeApp:packageDistributionForCurrentOS  # Build desktop distribution
./gradlew composeApp:wasmJsBrowserProductionWebpack   # Build production web bundle
./gradlew check                                    # Run all checks
```

### Docker Compose (Full Stack)
```bash
docker-compose up -d             # Start all services (Traefik, backend, PostgreSQL)
docker-compose logs -f backend   # Follow backend logs
docker-compose down              # Stop services
```

**Required env vars**: `DB_PASSWORD`, `JWT_SECRET` (32+ chars) - see `.env.example`

## Architecture

### Backend Module Pattern (Uber fx DI)
Each domain in `backend/internal/` follows a consistent module structure:
- `module.go` - fx.Module definition with providers
- `model.go` - Domain entities (Bun ORM tags)
- `repository.go` - Database operations (PostgreSQL via Bun)
- `service.go` - Business logic
- `handler.go` - HTTP handlers (Fiber v2)
- `dto.go` - Request/response types with validation
- `*_test.go` - Tests using testify with mock repositories

Main entry point `cmd/server/main.go` composes all modules via fx.New().

### Real-Time Communication Flow
1. Client connects to `/api/v1/ws/:conversationID` with JWT
2. `ws.Hub` manages client connections per conversation (goroutine-safe)
3. `claude.Manager` spawns/manages Claude CLI processes per conversation
4. Claude CLI stdout streams through Hub → broadcast to WebSocket clients

### Frontend Layering (Koin DI)
```
ui/screen/     → Composable screens (LoginScreen, ChatScreen, etc.)
ui/viewmodel/  → ViewModels managing state (StateFlow)
ui/component/  → Reusable UI components
data/api/      → REST API clients (Ktor)
data/websocket/→ WebSocket client with auto-reconnection
data/model/    → Serializable data classes
di/            → Koin module definitions
```

Platform targets: `commonMain` (shared), `desktopMain` (JVM/CIO), `wasmJsMain` (WASM/JS)

### Database Schema
PostgreSQL with UUIDv7 primary keys. Tables: `users`, `projects`, `conversations`, `messages`, `user_settings`, `active_sessions`. Migrations in `backend/migrations/`.

### API Routes
- Auth (public): `POST /api/v1/auth/{login,register}`
- Projects (protected): `GET|POST|PUT|DELETE /api/v1/projects[/:id]`
- Conversations (protected): `GET|POST /api/v1/projects/:projectId/conversations`, `GET|PUT|DELETE /api/v1/conversations/:id`
- WebSocket (protected): `GET /api/v1/ws/:conversationID`

### WebSocket Message Types
Client→Server: `chat` (send message), `stop` (interrupt), `ping`
Server→Client: `stream` (chunk), `complete`, `status`, `error`, `pong`

## Key Dependencies

**Backend**: Fiber v2 (HTTP), gorilla/websocket, Bun ORM, golang-jwt/v5, uber/fx, uber/zap
**Frontend**: Compose Multiplatform, Ktor (HTTP/WS), Koin (DI), kotlinx-serialization

## Testing Patterns

Backend tests use:
- Mock repositories implementing interfaces
- `httptest` with Fiber's `app.Test()`
- `testify/assert` and `testify/require`

Run single test: `go test -v -run TestRegister_Success ./internal/auth/...`
