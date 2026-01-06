# Claude Code Native

[![CI](https://github.com/devnogari/claude-code-native/actions/workflows/ci.yml/badge.svg)](https://github.com/devnogari/claude-code-native/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Go](https://img.shields.io/badge/Go-1.24+-00ADD8?logo=go&logoColor=white)](https://go.dev/)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.1+-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org/)

A native desktop and web application for Claude Code, providing a modern cross-platform interface for AI-assisted coding. Built with a Go backend and Kotlin Multiplatform (Compose) frontend.

## Table of Contents

- [Features](#features)
- [Architecture](#architecture)
- [Prerequisites](#prerequisites)
- [Quick Start](#quick-start)
- [Backend Daemon](#backend-daemon)
- [Development](#development)
- [API Reference](#api-reference)
- [Contributing](#contributing)
- [License](#license)

## Features

- **Multi-Platform Support**: Desktop (macOS, Windows, Linux) and Web (WebAssembly)
- **Real-time Streaming**: WebSocket-based communication for live AI responses
- **Project Management**: Organize conversations by project with persistent history
- **User Authentication**: Secure JWT-based authentication system
- **Claude Integration**: Direct integration with Claude Code CLI
- **Modern UI**: Material Design 3 interface with Compose Multiplatform
- **Unified WebSocket**: Multiplexed connections for efficient updates across sessions
- **Background Service**: Run backend as a system daemon (launchd/systemd/Windows Service)

## Architecture

```
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
│   ├── migrations/          # SQL migrations
│   └── scripts/service/     # Daemon control scripts
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
| Database | PostgreSQL 18 |
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

### 1. Clone and Configure

```bash
git clone https://github.com/devnogari/claude-code-native.git
cd claude-code-native

# Setup environment
cp .env.example .env
# Edit .env with your values (DB_PASSWORD, JWT_SECRET)
```

### 2. Start Services

Using the `Makefile` (recommended):

```bash
# Start everything (DB + Server + Desktop)
make all

# Or start individually:
make db             # Start PostgreSQL via Docker
make server         # Start backend server
make frontend       # Start desktop app
make frontend-web   # Start web app
```

### 3. Access the Application

| Service | URL |
|---------|-----|
| Desktop App | Launched via `make frontend` |
| Web App | http://localhost:8080 |
| Backend API | http://localhost:8083 |

## Backend Daemon

Run the backend as a system service for persistent operation.

### Service Control (ctl.sh)

```bash
cd backend/scripts/service

# Install and start
./ctl.sh install      # Install as system service
./ctl.sh start        # Start backend

# Management
./ctl.sh status       # Show service status
./ctl.sh restart      # Restart backend
./ctl.sh stop         # Stop backend
./ctl.sh update       # Build and restart (after code changes)
./ctl.sh logs         # View recent logs
./ctl.sh logs -f      # Follow logs (tail)
./ctl.sh uninstall    # Remove service

# Full stack control
./ctl.sh up           # Start PostgreSQL + Backend
./ctl.sh down         # Stop all services

# Database management
./ctl.sh db start     # Start PostgreSQL
./ctl.sh db stop      # Stop PostgreSQL
./ctl.sh db status    # Check PostgreSQL status
./ctl.sh db logs      # View PostgreSQL logs
./ctl.sh db shell     # Open psql shell
```

### Platform-Specific Notes

**macOS (launchd)**
```bash
./ctl.sh install && ./ctl.sh start
# Logs: ~/Library/Logs/ccn-backend.log
```

**Linux (systemd)**
```bash
sudo ./ctl.sh install && sudo ./ctl.sh start
# Logs: journalctl -u ccn-backend
```

**Windows (NSSM)**
```powershell
# Requires NSSM: choco install nssm
.\ctl.ps1 install
.\ctl.ps1 start
# Logs: C:\Program Files\ccn-backend\logs\
```

### Environment Variables

Set these before running `./ctl.sh install`:

| Variable | Description | Required |
|----------|-------------|----------|
| `DATABASE_URL` | PostgreSQL connection string | Yes |
| `JWT_SECRET` | JWT signing secret (32+ chars) | Yes |
| `PORT` | Server port (default: 8083) | No |
| `LOG_LEVEL` | debug/info/warn/error | No |
| `CLAUDE_PROJECTS_PATH` | Claude projects directory | No |

```bash
export DATABASE_URL="postgres://ccn:password@localhost:5438/claude_code_native?sslmode=disable"
export JWT_SECRET="your-secure-32-character-secret-key"
./ctl.sh install
```

## Development

### Backend

```bash
cd backend
go mod download
go run ./cmd/server              # Run directly
make server-watch                # Run with hot reload (requires 'air')
go test ./...                    # Run tests
```

### Frontend

```bash
cd frontend
./gradlew composeApp:desktopRun           # Desktop app
./gradlew composeApp:wasmJsBrowserRun     # Web app (dev server)
./gradlew check                            # Run tests + linting
```

### Build for Production

```bash
# Backend
cd backend && go build -o ccn-backend ./cmd/server

# Frontend Desktop
cd frontend && ./gradlew composeApp:packageDistributionForCurrentOS

# Frontend Web
cd frontend && ./gradlew composeApp:wasmJsBrowserProductionWebpack
```

## API Reference

### Authentication

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/auth/register` | Register new user |
| POST | `/api/v1/auth/login` | Login and get JWT |

### Projects

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/v1/projects` | List all projects |
| POST | `/api/v1/projects` | Create project |
| GET | `/api/v1/projects/:id` | Get project details |
| PUT | `/api/v1/projects/:id` | Update project |
| DELETE | `/api/v1/projects/:id` | Delete project |

### Conversations

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/v1/projects/:projectId/conversations` | List conversations |
| POST | `/api/v1/projects/:projectId/conversations` | Create conversation |
| GET | `/api/v1/conversations/:id` | Get conversation |

### WebSocket

| Endpoint | Description |
|----------|-------------|
| `/api/v1/ws/user` | Unified user WebSocket (multiplexed) |
| `/api/v1/ws/:conversationID` | Per-conversation WebSocket (legacy) |

**WebSocket Message Types:**

| Direction | Type | Description |
|-----------|------|-------------|
| Client→Server | `chat` | Send message to Claude |
| Client→Server | `stop` | Interrupt current response |
| Client→Server | `ping` | Keep-alive |
| Server→Client | `stream` | Response chunk |
| Server→Client | `complete` | Response complete |
| Server→Client | `error` | Error message |
| Server→Client | `pong` | Keep-alive response |

## Contributing

We welcome contributions! Please see our [Contributing Guide](CONTRIBUTING.md) for details.

- [Code of Conduct](CODE_OF_CONDUCT.md)
- [Security Policy](SECURITY.md)

## License

[MIT License](LICENSE) - Copyright (c) 2024-2026 Claude Code Native Contributors
