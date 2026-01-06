# Claude Code Native

[![CI](https://github.com/devnogari/claude-code-native/actions/workflows/ci.yml/badge.svg)](https://github.com/devnogari/claude-code-native/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Go](https://img.shields.io/badge/Go-1.24+-00ADD8?logo=go&logoColor=white)](https://go.dev/)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.1+-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org/)

A native desktop and web application for Claude Code, providing a modern cross-platform interface for AI-assisted coding. Built with a Go backend and Kotlin Multiplatform (Compose) frontend.

## Features

- **Multi-Platform Support**: Desktop (macOS, Windows, Linux) and Web (WebAssembly).
- **Real-time Streaming**: WebSocket-based communication for live AI responses.
- **Project Management**: Organize conversations by project with persistent history.
- **User Authentication**: Secure JWT-based authentication system.
- **Claude Integration**: Direct integration with Claude Code CLI.
- **Modern UI**: Material Design 3 interface with Compose Multiplatform.
- **Unified WebSocket**: Multiplexed connections for efficient updates across sessions.

## Architecture Overview

```text
claude-code-native/
├── backend/                 # Go backend (Fiber + Bun ORM)
│   ├── cmd/server/          # Application entry point
│   ├── internal/            # Core business logic
│   │   ├── auth/            # JWT authentication
│   │   ├── claude/          # Claude CLI process management & History
│   │   ├── conversation/    # Conversation management
│   │   ├── project/         # Project management
│   │   ├── ws/              # WebSocket hub & handlers
│   │   └── ...
│   └── migrations/          # SQL migrations
├── frontend/                # Kotlin Multiplatform (Compose)
│   └── composeApp/
│       └── src/
│           ├── commonMain/  # Shared code (UI, API, WebSocket)
│           ├── desktopMain/ # Desktop-specific (JVM)
│           └── wasmJsMain/  # Web-specific (WebAssembly)
└── docs/                    # Design and implementation plans
```

### Technology Stack

| Layer | Technology |
|-------|------------|
| Backend Framework | Go 1.24+ with Fiber v2 |
| Backend DI | Uber fx |
| Database | PostgreSQL 18 (Alpine) |
| ORM | Bun |
| Frontend | Kotlin Multiplatform with Compose |
| Frontend DI | Koin |
| HTTP Client | Ktor |
| Real-time | WebSocket (gorilla/websocket + Ktor) |

## Prerequisites

- **Go** 1.24+
- **JDK** 17+ (for Kotlin/Compose)
- **Docker** & Docker Compose (for PostgreSQL)
- **Node.js** 18+ (for Claude Code CLI)
- **Claude Code CLI**: `npm install -g @anthropic-ai/claude-code`

## Quick Start

The project uses a `Makefile` to simplify development tasks.

### 1. Clone and Configure

```bash
git clone https://github.com/devnogari/claude-code-native.git
cd claude-code-native

# Setup backend environment
cp backend/.env.example backend/.env.local
# Edit backend/.env.local with your values (JWT_SECRET, etc.)

# Setup root environment (if using docker-compose)
cp .env.example .env
```

### 2. Start Services

Using the `Makefile` (recommended for development):

```bash
# Start DB, Server, and Frontend Desktop
make all

# Or start individually:
make db             # Start PostgreSQL via Docker
make server         # Start backend server
make frontend       # Start desktop app
```

### 3. Access the Application

- **Desktop App**: Launched via `make frontend`
- **Web App**: `make frontend-web` (accessible at http://localhost:8080 by default)
- **Backend API**: `http://localhost:8083` (when using `make server`)

## Development

### Backend Development

The backend is a Go application using the Fiber framework.

```bash
cd backend
go mod download
# Running with hot reload (requires 'air')
make server-watch
```

### Frontend Development

The frontend is built with Compose Multiplatform.

```bash
cd frontend
# Run Desktop
./gradlew composeApp:run
# Run Web (Wasm)
./gradlew composeApp:wasmJsBrowserRun
```

## Environment Variables

### Backend (`backend/.env.local`)

| Variable | Description | Default |
|----------|-------------|---------|
| `PORT` | Backend port | `8083` |
| `DATABASE_URL` | PostgreSQL connection string | `postgres://ccn:localdev@localhost:5438/...` |
| `JWT_SECRET` | JWT signing secret (32+ chars) | Required |
| `CLAUDE_PROJECTS_PATH` | Claude projects directory | `~/.claude` |

## Scripts

| Command | Description |
|---------|-------------|
| `make db` | Start PostgreSQL container |
| `make server` | Start backend server |
| `make frontend` | Start desktop application |
| `make test` | Run all tests |
| `make build` | Build backend and frontend |
| `make clean` | Remove build artifacts |

## Testing

### Backend Tests
```bash
make test-backend
```

### Frontend Tests
```bash
cd frontend
./gradlew check
```

## API Documentation (v1)

### Authentication
- `POST /api/v1/auth/register` - Register new user
- `POST /api/v1/auth/login` - Login and get JWT

### Projects
- `GET /api/v1/projects` - List projects
- `POST /api/v1/projects` - Create project
- `GET /api/v1/projects/:id` - Get project
- `PUT /api/v1/projects/:id` - Update project
- `DELETE /api/v1/projects/:id` - Delete project

### Conversations
- `GET /api/v1/projects/:projectId/conversations` - List conversations
- `POST /api/v1/projects/:projectId/conversations` - Create conversation
- `GET /api/v1/conversations/:id` - Get conversation

### WebSocket
- `/api/v1/ws/user` - Unified user WebSocket (Multiplexed)
- `/api/v1/ws/:conversationID` - Legacy per-conversation WebSocket

## TODOs / Roadmap

- [ ] Fix `docker-compose.local.yml` discrepancy in Makefile.
- [ ] Add `Dockerfile` for backend and frontend.
- [ ] Implement full production-ready `docker-compose.yml` including Traefik.
- [ ] Add Android and iOS support to the frontend.
- [ ] Improve documentation for WebSocket protocol messages.

## License

MIT License

Copyright (c) 2024-2026 devnogari

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
