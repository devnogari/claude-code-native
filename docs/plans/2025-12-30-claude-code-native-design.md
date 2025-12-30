# Claude Code Native - Design Document

**Project**: claude-code-native
**Date**: 2025-01-01
**Status**: Approved
**Author**: AI-assisted design session

---

## Executive Summary

Claude Code Native is a self-hosted, multi-platform interface for Claude Code CLI. It enables remote access, mobile companion usage, and multi-device sync for Claude Code sessions through native applications built with Kotlin Multiplatform (KMP) and a Go backend.

### Key Goals
- **Remote Access**: Access Claude Code on a server from any device
- **Mobile Companion**: Use Claude Code from phone/tablet
- **Multi-device Sync**: Continue conversations across devices
- **Self-hosted**: Privacy-focused, run everything locally

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────┐
│                    claude-code-native                            │
├─────────────────────────────────────────────────────────────────┤
│  ┌───────────────────────────────────────────────────────────┐  │
│  │           KMP Clients (Compose Multiplatform)             │  │
│  │  ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌─────────┐         │  │
│  │  │ Desktop │ │ Android │ │   iOS   │ │   Web   │         │  │
│  │  │  (JVM)  │ │(Native) │ │(Native) │ │ (Wasm)  │         │  │
│  │  └────┬────┘ └────┬────┘ └────┬────┘ └────┬────┘         │  │
│  │       └───────────┴───────────┴───────────┘               │  │
│  │                        │                                   │  │
│  │              Shared Kotlin Code                           │  │
│  │   ┌─────────────┬─────────────┬─────────────┐            │  │
│  │   │  UI Layer   │ Domain Layer│ Data Layer  │            │  │
│  │   │  (Compose)  │  (UseCases) │(Ktor+Repos) │            │  │
│  │   └─────────────┴─────────────┴─────────────┘            │  │
│  └───────────────────────────────────────────────────────────┘  │
│                              │                                   │
│                     WebSocket + REST                            │
│                              │                                   │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │                 Docker Compose Stack                       │  │
│  │  ┌─────────┐    ┌─────────────┐    ┌──────────────┐      │  │
│  │  │ Traefik │───▶│  Go Backend │───▶│  PostgreSQL  │      │  │
│  │  │ :443    │    │  :8080      │    │  :5432       │      │  │
│  │  └─────────┘    └──────┬──────┘    └──────────────┘      │  │
│  │                        │                                   │  │
│  │                        ▼                                   │  │
│  │              ┌─────────────────┐                          │  │
│  │              │ Claude CLI      │                          │  │
│  │              │ (spawned/session)│                          │  │
│  │              └─────────────────┘                          │  │
│  └───────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
```

---

## Technology Stack

### Backend (Go)

| Component | Technology | Rationale |
|-----------|------------|-----------|
| Language | Go 1.22+ | Fast, concurrent, easy deployment |
| HTTP Framework | Fiber or Gin | Performant, WebSocket support |
| ORM | uptrace/bun | SQL-first, explicit, Postgres-native |
| Database | PostgreSQL 18 | UUID v7 native, mature, reliable |
| WebSocket | gorilla/websocket | Industry standard |
| Auth | JWT + bcrypt | Stateless, secure |
| Container | Docker | Portable deployment |

### Frontend (KMP)

| Component | Technology | Rationale |
|-----------|------------|-----------|
| UI Framework | Compose Multiplatform | Single codebase, native performance |
| Navigation | Voyager | Simple, Compose-native |
| Networking | Ktor Client | First-party KMP, WebSocket support |
| DI | Koin | Lightweight, KMP-first |
| JSON | kotlinx.serialization | Compile-time safe |
| Storage | DataStore | Modern preferences API |

### Platform Status (Dec 2025)

| Platform | Status | Notes |
|----------|--------|-------|
| Android | ✅ Stable | Most mature |
| Desktop | ✅ Stable | JVM-based |
| iOS | ✅ Stable | Since CMP 1.8.0 (May 2025) |
| Web | 🟡 Beta | Since CMP 1.9.0 (Sept 2025) |

---

## Features

### v1 (MVP)
- [x] Chat interface with streaming responses
- [x] Conversation history & multi-device sync
- [x] Project switching
- [x] Dark/Light theme
- [x] Username/Password authentication
- [x] Tailscale/VPN network security

### v2 (Planned)
- [ ] File explorer with syntax highlighting
- [ ] Integrated terminal/shell
- [ ] Live file editing

### v3 (Future)
- [ ] Git operations UI
- [ ] Push notifications (mobile)
- [ ] Offline mode / local cache

---

## Backend Structure

```
backend/
├── cmd/
│   └── server/
│       └── main.go              # Entry point
│
├── internal/
│   ├── config/
│   │   └── config.go            # Env vars, settings
│   │
│   ├── auth/
│   │   ├── handler.go           # Login/logout endpoints
│   │   ├── middleware.go        # JWT validation
│   │   └── service.go           # Password hashing, token gen
│   │
│   ├── user/
│   │   ├── model.go             # User struct + Bun tags
│   │   ├── repository.go        # DB operations
│   │   └── service.go           # Business logic
│   │
│   ├── project/
│   │   ├── model.go             # Project struct
│   │   ├── repository.go        # DB + filesystem scan
│   │   ├── service.go           # Project discovery
│   │   └── handler.go           # REST endpoints
│   │
│   ├── chat/
│   │   ├── handler.go           # WebSocket upgrade
│   │   ├── session.go           # Claude CLI process mgmt
│   │   ├── stream.go            # stdout/stdin piping
│   │   └── history.go           # JSONL sync
│   │
│   └── ws/
│       ├── hub.go               # Connection registry
│       ├── client.go            # Per-connection state
│       └── message.go           # Protocol types
│
├── pkg/
│   └── claude/
│       ├── cli.go               # Claude CLI wrapper
│       └── jsonl.go             # JSONL parser
│
├── migrations/
│   └── 001_init.up.sql          # Initial schema
│
├── Dockerfile
├── go.mod
└── go.sum
```

---

## API Design

### REST Endpoints

```
Auth
├── POST   /api/v1/auth/login      → JWT token
├── POST   /api/v1/auth/logout     → Invalidate token
└── GET    /api/v1/auth/me         → Current user

Projects
├── GET    /api/v1/projects        → List all projects
├── GET    /api/v1/projects/:id    → Project details
└── POST   /api/v1/projects/scan   → Rescan ~/.claude/

Conversations
├── GET    /api/v1/projects/:id/conversations
├── GET    /api/v1/conversations/:id
└── DELETE /api/v1/conversations/:id

Settings
├── GET    /api/v1/settings        → User preferences
└── PUT    /api/v1/settings        → Update preferences

Health
└── GET    /health                 → Liveness check
```

### WebSocket Protocol

**Connection**: `wss://host/ws/chat?token=<jwt>`

**Client → Server:**
```json
{
  "type": "chat.send",
  "payload": {
    "project_id": "uuid",
    "message": "explain this code",
    "session_id": "uuid"
  }
}
```

```json
{
  "type": "chat.cancel",
  "payload": { "session_id": "uuid" }
}
```

```json
{
  "type": "session.switch",
  "payload": { "project_id": "uuid" }
}
```

**Server → Client:**
```json
{
  "type": "chat.stream",
  "payload": {
    "session_id": "uuid",
    "content": "Here's the",
    "done": false
  }
}
```

```json
{
  "type": "chat.complete",
  "payload": {
    "session_id": "uuid",
    "message_id": "uuid",
    "usage": { "input": 150, "output": 500 }
  }
}
```

```json
{
  "type": "chat.error",
  "payload": {
    "code": "CLI_ERROR",
    "message": "Claude CLI exited unexpectedly"
  }
}
```

---

## Database Schema

PostgreSQL 18 with native UUID v7 support.

```sql
-- Users
CREATE TABLE users (
    id            UUID PRIMARY KEY DEFAULT uuidv7(),
    username      VARCHAR(50) UNIQUE NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    created_at    TIMESTAMPTZ DEFAULT NOW(),
    updated_at    TIMESTAMPTZ DEFAULT NOW(),
    last_login_at TIMESTAMPTZ
);

-- Projects
CREATE TABLE projects (
    id            UUID PRIMARY KEY DEFAULT uuidv7(),
    user_id       UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name          VARCHAR(255) NOT NULL,
    path          VARCHAR(1024) NOT NULL,
    claude_id     VARCHAR(255),
    last_accessed TIMESTAMPTZ,
    created_at    TIMESTAMPTZ DEFAULT NOW(),
    updated_at    TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE(user_id, path)
);

-- Conversations
CREATE TABLE conversations (
    id              UUID PRIMARY KEY DEFAULT uuidv7(),
    project_id      UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    claude_session  VARCHAR(255),
    title           VARCHAR(255),
    message_count   INTEGER DEFAULT 0,
    jsonl_path      VARCHAR(1024),
    created_at      TIMESTAMPTZ DEFAULT NOW(),
    updated_at      TIMESTAMPTZ DEFAULT NOW()
);

-- Messages
CREATE TABLE messages (
    id              UUID PRIMARY KEY DEFAULT uuidv7(),
    conversation_id UUID NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    role            VARCHAR(20) NOT NULL CHECK (role IN ('user', 'assistant')),
    content         TEXT NOT NULL,
    token_count     INTEGER,
    sequence_num    INTEGER NOT NULL,
    created_at      TIMESTAMPTZ DEFAULT NOW()
);

-- User Settings
CREATE TABLE user_settings (
    user_id         UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    theme           VARCHAR(20) DEFAULT 'system',
    default_project UUID REFERENCES projects(id) ON DELETE SET NULL,
    preferences     JSONB DEFAULT '{}',
    updated_at      TIMESTAMPTZ DEFAULT NOW()
);

-- Active Sessions
CREATE TABLE active_sessions (
    id              UUID PRIMARY KEY DEFAULT uuidv7(),
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    conversation_id UUID REFERENCES conversations(id) ON DELETE SET NULL,
    pid             INTEGER,
    started_at      TIMESTAMPTZ DEFAULT NOW(),
    last_activity   TIMESTAMPTZ DEFAULT NOW()
);
```

---

## KMP Frontend Structure

```
kmp-client/
├── shared/                          # Shared Kotlin code
│   └── src/commonMain/kotlin/com/ccn/
│       ├── di/                      # Koin modules
│       ├── data/
│       │   ├── api/                 # Ktor client, WebSocket
│       │   ├── repository/          # Data repositories
│       │   └── local/               # DataStore
│       ├── domain/
│       │   ├── model/               # Domain entities
│       │   └── usecase/             # Business logic
│       └── util/                    # Utilities
│
├── composeApp/                      # Shared Compose UI
│   └── src/commonMain/kotlin/com/ccn/ui/
│       ├── App.kt
│       ├── theme/                   # Material3 theme
│       ├── navigation/              # Voyager navigator
│       ├── screens/
│       │   ├── login/
│       │   ├── projects/
│       │   ├── chat/
│       │   └── settings/
│       └── components/              # Shared components
│
├── desktopApp/                      # Desktop (JVM)
├── androidApp/                      # Android
├── iosApp/                          # iOS
└── webApp/                          # Web (Wasm)
```

---

## Docker Compose

```yaml
version: "3.9"

services:
  traefik:
    image: traefik:v3.0
    container_name: ccn-traefik
    restart: unless-stopped
    ports:
      - "80:80"
      - "443:443"
      - "8080:8080"
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock:ro
      - ./traefik/certs:/certs:ro
    networks:
      - ccn-network

  backend:
    build:
      context: ./backend
      dockerfile: Dockerfile
    container_name: ccn-backend
    restart: unless-stopped
    environment:
      - DATABASE_URL=postgres://ccn:${DB_PASSWORD}@postgres:5432/claude_code_native?sslmode=disable
      - JWT_SECRET=${JWT_SECRET}
      - CLAUDE_PROJECTS_PATH=/claude-projects
    volumes:
      - ~/.claude:/claude-projects:ro
      - claude-sessions:/var/lib/ccn/sessions
    depends_on:
      postgres:
        condition: service_healthy
    labels:
      - "traefik.enable=true"
      - "traefik.http.routers.backend.rule=Host(`ccn.localhost`)"
      - "traefik.http.services.backend.loadbalancer.server.port=8080"
    networks:
      - ccn-network

  postgres:
    image: postgres:18-alpine
    container_name: ccn-postgres
    restart: unless-stopped
    environment:
      - POSTGRES_USER=ccn
      - POSTGRES_PASSWORD=${DB_PASSWORD}
      - POSTGRES_DB=claude_code_native
    volumes:
      - postgres-data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U ccn -d claude_code_native"]
      interval: 5s
      timeout: 5s
      retries: 5
    networks:
      - ccn-network

volumes:
  postgres-data:
  claude-sessions:

networks:
  ccn-network:
    driver: bridge
```

---

## KMP Dependencies

```kotlin
// shared/build.gradle.kts
kotlin {
    sourceSets {
        commonMain.dependencies {
            // UI
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)

            // Navigation
            implementation("cafe.adriel.voyager:voyager-navigator:1.0.0")
            implementation("cafe.adriel.voyager:voyager-screenmodel:1.0.0")

            // Networking
            implementation("io.ktor:ktor-client-core:2.3.7")
            implementation("io.ktor:ktor-client-websockets:2.3.7")
            implementation("io.ktor:ktor-client-content-negotiation:2.3.7")
            implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.7")
            implementation("io.ktor:ktor-client-auth:2.3.7")

            // DI
            implementation("io.insert-koin:koin-core:3.5.0")
            implementation("io.insert-koin:koin-compose:1.1.0")

            // Async
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

            // Storage
            implementation("androidx.datastore:datastore-preferences-core:1.1.0")

            // Serialization
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")
        }
    }
}
```

---

## Security Model

### Authentication
- **Network Layer**: Tailscale/VPN (primary security)
- **App Layer**: Username/Password + JWT tokens
- **Password Storage**: bcrypt hashing
- **Token Expiry**: Configurable (default 7 days)

### Authorization
- Single-user system (no role-based access)
- JWT validation on all protected endpoints
- WebSocket auth via query parameter token

---

## Implementation Roadmap

### Phase 1: Foundation (v0.1)
- Go project setup with Fiber/Gin
- Bun + PostgreSQL 18 + migrations
- User auth (register/login/JWT)
- Basic REST endpoints
- Docker Compose working

### Phase 2: Core Features (v0.2)
- WebSocket hub implementation
- Claude CLI process spawning
- Stdout/stdin streaming
- Session management
- JSONL parsing

### Phase 3: KMP Client (v0.3)
- Project structure setup
- Koin DI + Ktor client
- Navigation (Voyager)
- Theme system (Material3)
- Login + Projects + Chat screens

### Phase 4: Multi-platform (v0.4)
- Desktop app (JVM)
- Android app
- iOS app
- Adaptive layouts

### Phase 5: Polish (v1.0)
- Web app (Wasm) - Beta
- Error handling
- Reconnection logic
- Testing
- Documentation

---

## Risk Mitigation

| Risk | Impact | Mitigation |
|------|--------|------------|
| iOS performance | Medium | Profile early, optimize render cycles |
| Web Wasm size | Low | Code splitting, lazy loading |
| Android WebSocket drops | Medium | Ping interval (20s) + reconnection |
| Compose Web stability | Low | Lower priority, desktop/mobile first |

---

## References

- [JetBrains KMP Roadmap 2025](https://blog.jetbrains.com/kotlin/2025/08/kmp-roadmap-aug-2025/)
- [Compose Multiplatform 1.9.0](https://blog.jetbrains.com/kotlin/2025/09/compose-multiplatform-1-9-0-compose-for-web-beta/)
- [Go ORM Comparison 2025](https://www.glukhov.org/post/2025/09/comparing-go-orms-gorm-ent-bun-sqlc/)
- [PostgreSQL 18 UUID v7](https://www.thenile.dev/blog/uuidv7)
- [Ktor Roadmap 2025](https://blog.jetbrains.com/kotlin/2025/09/ktor-roadmap-2025/)
- [Voyager Navigation](https://voyager.adriel.cafe/)

---

## Appendix: Go Models

```go
// internal/user/model.go
type User struct {
    bun.BaseModel `bun:"table:users,alias:u"`
    ID           uuid.UUID  `bun:"id,pk,type:uuid,default:uuidv7()"`
    Username     string     `bun:"username,notnull,unique"`
    PasswordHash string     `bun:"password_hash,notnull"`
    CreatedAt    time.Time  `bun:"created_at,default:now()"`
    UpdatedAt    time.Time  `bun:"updated_at,default:now()"`
    LastLoginAt  *time.Time `bun:"last_login_at"`
}

// internal/project/model.go
type Project struct {
    bun.BaseModel `bun:"table:projects,alias:p"`
    ID           uuid.UUID  `bun:"id,pk,type:uuid,default:uuidv7()"`
    UserID       uuid.UUID  `bun:"user_id,notnull,type:uuid"`
    Name         string     `bun:"name,notnull"`
    Path         string     `bun:"path,notnull"`
    ClaudeID     *string    `bun:"claude_id"`
    LastAccessed *time.Time `bun:"last_accessed"`
    CreatedAt    time.Time  `bun:"created_at,default:now()"`
    UpdatedAt    time.Time  `bun:"updated_at,default:now()"`
}

// internal/chat/model.go
type Conversation struct {
    bun.BaseModel `bun:"table:conversations,alias:c"`
    ID            uuid.UUID `bun:"id,pk,type:uuid,default:uuidv7()"`
    ProjectID     uuid.UUID `bun:"project_id,notnull,type:uuid"`
    ClaudeSession *string   `bun:"claude_session"`
    Title         *string   `bun:"title"`
    MessageCount  int       `bun:"message_count,default:0"`
    JSONLPath     *string   `bun:"jsonl_path"`
    CreatedAt     time.Time `bun:"created_at,default:now()"`
    UpdatedAt     time.Time `bun:"updated_at,default:now()"`
}

type Message struct {
    bun.BaseModel  `bun:"table:messages,alias:m"`
    ID             uuid.UUID `bun:"id,pk,type:uuid,default:uuidv7()"`
    ConversationID uuid.UUID `bun:"conversation_id,notnull,type:uuid"`
    Role           string    `bun:"role,notnull"`
    Content        string    `bun:"content,notnull"`
    TokenCount     *int      `bun:"token_count"`
    SequenceNum    int       `bun:"sequence_num,notnull"`
    CreatedAt      time.Time `bun:"created_at,default:now()"`
}
```
