# Claude Code Native

A native desktop and web application for Claude Code, providing a modern cross-platform interface for AI-assisted coding. Built with Go backend and Kotlin Multiplatform frontend.

## Features

- **Multi-Platform Support**: Desktop (macOS, Windows, Linux) and Web (WebAssembly)
- **Real-time Streaming**: WebSocket-based communication for live AI responses
- **Project Management**: Organize conversations by project with persistent history
- **User Authentication**: Secure JWT-based authentication system
- **Claude Integration**: Direct integration with Claude Code CLI
- **Modern UI**: Material Design 3 interface with Compose Multiplatform

## Architecture Overview

```
claude-code-native/
├── backend/                 # Go backend (Fiber + Bun ORM)
│   ├── cmd/server/          # Application entry point
│   ├── internal/
│   │   ├── auth/            # JWT authentication
│   │   ├── claude/          # Claude CLI process management
│   │   ├── config/          # Configuration management
│   │   ├── conversation/    # Conversation CRUD
│   │   ├── database/        # PostgreSQL with Bun ORM
│   │   ├── message/         # Message storage
│   │   ├── middleware/      # Auth middleware
│   │   ├── project/         # Project management
│   │   ├── server/          # HTTP server & routes
│   │   ├── user/            # User management
│   │   └── ws/              # WebSocket hub & handlers
│   └── migrations/          # SQL migrations
│
└── frontend/                # Kotlin Multiplatform (Compose)
    └── composeApp/
        └── src/
            ├── commonMain/  # Shared code (UI, API, WebSocket)
            ├── desktopMain/ # Desktop-specific (JVM)
            └── wasmJsMain/  # Web-specific (WebAssembly)
```

### Technology Stack

| Layer | Technology |
|-------|------------|
| Backend Framework | Go 1.23+ with Fiber v2 |
| Backend DI | Uber fx |
| Database | PostgreSQL 18 |
| ORM | Bun |
| Frontend | Kotlin Multiplatform with Compose |
| Frontend DI | Koin |
| HTTP Client | Ktor |
| Real-time | WebSocket (gorilla/websocket + Ktor) |
| Reverse Proxy | Traefik v3 |

## Prerequisites

- **Go** 1.22+ (or 1.23+ for latest features)
- **JDK** 17+ (for Kotlin/Compose)
- **Docker** & Docker Compose (for containerized deployment)
- **PostgreSQL** 15+ (or use Docker)
- **Node.js** 18+ (for Claude Code CLI)
- **Claude Code CLI**: `npm install -g @anthropic-ai/claude-code`

## Quick Start

### 1. Clone and Configure

```bash
git clone https://github.com/devnogari/claude-code-native.git
cd claude-code-native

# Copy environment configuration
cp .env.example .env
# Edit .env with your values:
# - DB_PASSWORD: Your PostgreSQL password
# - JWT_SECRET: Minimum 32-character secret key
```

### 2. Start with Docker Compose (Recommended)

```bash
docker-compose up -d
```

This starts:
- **Traefik**: Reverse proxy on ports 80, 443, 8080 (dashboard)
- **Backend**: Go server accessible via `ccn.localhost`
- **PostgreSQL**: Database on port 5432

### 3. Access the Application

- Backend API: `http://ccn.localhost/api/v1`
- Traefik Dashboard: `http://localhost:8080`

## Development Setup

### Backend Development

```bash
cd backend

# Install dependencies
go mod download

# Set up local environment
cp .env.example .env
# Edit .env with local PostgreSQL connection

# Run the server
go run ./cmd/server

# Run tests
go test ./...
```

The backend will start on `http://localhost:8080` by default.

### Frontend Desktop

```bash
cd frontend

# Run desktop application
./gradlew composeApp:desktopRun

# Build desktop distribution
./gradlew composeApp:packageDistributionForCurrentOS
```

### Frontend Web (WebAssembly)

```bash
cd frontend

# Run development server
./gradlew composeApp:wasmJsBrowserRun

# Build production bundle
./gradlew composeApp:wasmJsBrowserProductionWebpack
```

## Docker Compose Setup

The `docker-compose.yml` provides a complete production-ready stack:

```yaml
services:
  traefik:    # Reverse proxy with automatic service discovery
  backend:    # Go backend with Claude CLI
  postgres:   # PostgreSQL database
```

### Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `DB_PASSWORD` | PostgreSQL password | Required |
| `JWT_SECRET` | JWT signing secret (32+ chars) | Required |
| `LOG_LEVEL` | Logging level | `info` |
| `PORT` | Backend port | `8080` |
| `DATABASE_URL` | PostgreSQL connection string | Auto-configured |
| `CLAUDE_PROJECTS_PATH` | Claude projects directory | `/claude-projects` |

### Volumes

- `postgres-data`: Persistent database storage
- `claude-sessions`: Claude session data
- `~/.claude`: Mounted read-only for Claude projects

## API Documentation

### Authentication Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/auth/register` | Register new user |
| POST | `/api/v1/auth/login` | Login and get JWT |

### Project Endpoints (Protected)

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/v1/projects` | List user projects |
| POST | `/api/v1/projects` | Create project |
| GET | `/api/v1/projects/:id` | Get project details |
| PUT | `/api/v1/projects/:id` | Update project |
| DELETE | `/api/v1/projects/:id` | Delete project |

### Conversation Endpoints (Protected)

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/v1/projects/:projectId/conversations` | List conversations |
| POST | `/api/v1/projects/:projectId/conversations` | Create conversation |
| GET | `/api/v1/conversations/:id` | Get conversation |
| PUT | `/api/v1/conversations/:id` | Update conversation |
| DELETE | `/api/v1/conversations/:id` | Delete conversation |

### Health Check

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/health` | Service health status |

## WebSocket Protocol

Connect to `/api/v1/ws/:conversationID` with JWT authentication.

### Message Types

#### Client to Server

| Type | Description | Payload |
|------|-------------|---------|
| `chat` | Send message to Claude | `{ "type": "chat", "content": "..." }` |
| `stop` | Stop current Claude process | `{ "type": "stop" }` |
| `ping` | Connection keep-alive | `{ "type": "ping" }` |

#### Server to Client

| Type | Description | Payload |
|------|-------------|---------|
| `stream` | Streaming response chunk | `{ "type": "stream", "conversation_id": "...", "content": "..." }` |
| `complete` | Response complete | `{ "type": "complete", "conversation_id": "..." }` |
| `status` | Processing status | `{ "type": "status", "status": "..." }` |
| `error` | Error occurred | `{ "type": "error", "error": "..." }` |
| `pong` | Response to ping | `{ "type": "pong" }` |

### Example WebSocket Session

```javascript
const ws = new WebSocket('ws://ccn.localhost/api/v1/ws/conv-123?token=jwt-token');

ws.onopen = () => {
  ws.send(JSON.stringify({ type: 'chat', content: 'Hello Claude!' }));
};

ws.onmessage = (event) => {
  const msg = JSON.parse(event.data);
  if (msg.type === 'stream') {
    console.log('Claude:', msg.content);
  }
};
```

## Project Structure

```
claude-code-native/
├── .env.example              # Environment template
├── .gitignore                # Git ignore rules
├── docker-compose.yml        # Docker Compose configuration
├── docs/                     # Documentation
│   └── plans/                # Planning documents
├── backend/                  # Go backend
│   ├── cmd/server/           # Entry point
│   ├── internal/             # Internal packages
│   ├── migrations/           # Database migrations
│   ├── Dockerfile            # Backend container
│   ├── go.mod                # Go modules
│   └── go.sum                # Dependency checksums
└── frontend/                 # Kotlin Multiplatform frontend
    ├── composeApp/           # Compose application
    │   ├── src/              # Source code
    │   └── build.gradle.kts  # Module build config
    ├── gradle/               # Gradle wrapper & versions
    ├── build.gradle.kts      # Root build config
    └── settings.gradle.kts   # Project settings
```

## Database Schema

The application uses PostgreSQL with the following tables:

- **users**: User accounts with password hashes
- **projects**: Claude projects linked to users
- **conversations**: Conversations within projects
- **messages**: Individual messages in conversations
- **user_settings**: User preferences (theme, defaults)
- **active_sessions**: Currently active Claude sessions

## Contributing

1. Fork the repository
2. Create a feature branch: `git checkout -b feature/my-feature`
3. Commit changes: `git commit -m 'Add my feature'`
4. Push to branch: `git push origin feature/my-feature`
5. Open a Pull Request

### Development Guidelines

- Follow Go conventions for backend code
- Use Kotlin coding conventions for frontend
- Write tests for new functionality
- Update documentation as needed

### Running Tests

```bash
# Backend tests
cd backend && go test ./...

# Frontend tests (when available)
cd frontend && ./gradlew check
```

## License

MIT License

Copyright (c) 2024 devnogari

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
