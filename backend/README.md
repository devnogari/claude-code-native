# Claude Code Native - Backend

Go backend server for Claude Code Native, providing REST API, WebSocket communication, and Claude CLI integration.

## Technology Stack

- **Framework**: [Fiber v2](https://gofiber.io/) - Fast HTTP framework
- **DI**: [Uber fx](https://github.com/uber-go/fx) - Dependency injection
- **ORM**: [Bun](https://bun.uptrace.dev/) - SQL-first ORM
- **Database**: PostgreSQL 15+
- **Auth**: JWT (golang-jwt/jwt)
- **WebSocket**: gofiber/contrib/websocket
- **Logging**: Uber Zap

## Project Structure

```
backend/
├── cmd/
│   └── server/
│       └── main.go          # Application entry point with fx modules
├── internal/
│   ├── auth/                # JWT authentication
│   │   ├── dto.go           # Request/response DTOs
│   │   ├── handler.go       # HTTP handlers
│   │   ├── jwt.go           # JWT utilities
│   │   ├── module.go        # fx module
│   │   └── service.go       # Business logic
│   ├── claude/              # Claude CLI integration
│   │   ├── manager.go       # Process lifecycle
│   │   ├── module.go        # fx module
│   │   └── process.go       # Process execution
│   ├── config/              # Configuration
│   │   ├── config.go        # Config struct & loading
│   │   └── module.go        # fx module
│   ├── conversation/        # Conversation management
│   │   ├── dto.go           # DTOs
│   │   ├── handler.go       # HTTP handlers
│   │   ├── model.go         # Database model
│   │   ├── module.go        # fx module
│   │   └── repository.go    # Data access
│   ├── database/            # Database connection
│   │   ├── database.go      # Connection setup
│   │   └── module.go        # fx module
│   ├── deps/                # Shared dependencies
│   │   └── deps.go          # Common types
│   ├── logger/              # Logging
│   │   ├── logger.go        # Zap setup
│   │   └── module.go        # fx module
│   ├── message/             # Message storage
│   │   ├── model.go         # Database model
│   │   ├── module.go        # fx module
│   │   └── repository.go    # Data access
│   ├── middleware/          # HTTP middleware
│   │   ├── auth.go          # JWT authentication
│   │   └── module.go        # fx module
│   ├── project/             # Project management
│   │   ├── dto.go           # DTOs
│   │   ├── handler.go       # HTTP handlers
│   │   ├── model.go         # Database model
│   │   ├── module.go        # fx module
│   │   └── repository.go    # Data access
│   ├── server/              # HTTP server
│   │   ├── module.go        # fx module with lifecycle
│   │   ├── routes.go        # Route definitions
│   │   └── server.go        # Server setup
│   ├── user/                # User management
│   │   ├── model.go         # Database model
│   │   ├── module.go        # fx module
│   │   └── repository.go    # Data access
│   └── ws/                  # WebSocket
│       ├── client.go        # Client connection
│       ├── dto.go           # Message types
│       ├── handler.go       # Connection handler
│       ├── hub.go           # Connection hub
│       └── module.go        # fx module
├── migrations/              # SQL migrations
│   ├── 001_init.up.sql      # Initial schema
│   └── 001_init.down.sql    # Rollback
├── Dockerfile               # Container build
├── go.mod                   # Go modules
├── go.sum                   # Dependency checksums
└── .env.example             # Environment template
```

## Getting Started

### Prerequisites

- Go 1.22+ (1.23+ recommended)
- PostgreSQL 15+
- Node.js 18+ (for Claude CLI)
- Claude Code CLI: `npm install -g @anthropic-ai/claude-code`

### Local Development

1. **Set up PostgreSQL**

```bash
# Using Docker
docker run -d \
  --name ccn-postgres \
  -e POSTGRES_USER=ccn \
  -e POSTGRES_PASSWORD=your-password \
  -e POSTGRES_DB=claude_code_native \
  -p 5432:5432 \
  postgres:18-alpine
```

2. **Configure Environment**

```bash
cp .env.example .env
# Edit .env with your values
```

Environment variables:

| Variable | Description | Default |
|----------|-------------|---------|
| `PORT` | Server port | `8080` |
| `DATABASE_URL` | PostgreSQL connection | Required |
| `JWT_SECRET` | JWT signing key (32+ chars) | Required |
| `CLAUDE_PROJECTS_PATH` | Claude projects directory | `~/.claude` |
| `LOG_LEVEL` | Log level (debug/info/warn/error) | `info` |

3. **Run Migrations**

Migrations run automatically on startup via the `docker-entrypoint-initdb.d` mount, or you can run them manually:

```bash
psql $DATABASE_URL -f migrations/001_init.up.sql
```

4. **Start the Server**

```bash
go run ./cmd/server
```

### Running Tests

```bash
# All tests
go test ./...

# With coverage
go test -cover ./...

# Specific package
go test ./internal/auth/...

# Verbose output
go test -v ./internal/auth/...
```

## API Reference

### Authentication

#### Register

```http
POST /api/v1/auth/register
Content-Type: application/json

{
  "username": "string",
  "password": "string"
}
```

Response: `201 Created`
```json
{
  "token": "jwt-token",
  "user": {
    "id": "uuid",
    "username": "string"
  }
}
```

#### Login

```http
POST /api/v1/auth/login
Content-Type: application/json

{
  "username": "string",
  "password": "string"
}
```

Response: `200 OK`
```json
{
  "token": "jwt-token",
  "user": {
    "id": "uuid",
    "username": "string"
  }
}
```

### Projects

All project endpoints require `Authorization: Bearer <token>` header.

#### List Projects

```http
GET /api/v1/projects
```

#### Create Project

```http
POST /api/v1/projects
Content-Type: application/json

{
  "name": "string",
  "path": "string"
}
```

#### Get Project

```http
GET /api/v1/projects/:id
```

#### Update Project

```http
PUT /api/v1/projects/:id
Content-Type: application/json

{
  "name": "string"
}
```

#### Delete Project

```http
DELETE /api/v1/projects/:id
```

### Conversations

#### List Conversations

```http
GET /api/v1/projects/:projectId/conversations
```

#### Create Conversation

```http
POST /api/v1/projects/:projectId/conversations
Content-Type: application/json

{
  "title": "string"
}
```

#### Get Conversation

```http
GET /api/v1/conversations/:id
```

#### Update Conversation

```http
PUT /api/v1/conversations/:id
Content-Type: application/json

{
  "title": "string"
}
```

#### Delete Conversation

```http
DELETE /api/v1/conversations/:id
```

## WebSocket Protocol

### Connection

```
GET /api/v1/ws/:conversationID
Authorization: Bearer <token>
Upgrade: websocket
```

### Message Types

#### Client Messages

```typescript
// Send chat message
{ "type": "chat", "content": "Hello Claude" }

// Stop current process
{ "type": "stop" }

// Keep-alive ping
{ "type": "ping" }
```

#### Server Messages

```typescript
// Streaming content
{ "type": "stream", "conversation_id": "uuid", "content": "..." }

// Response complete
{ "type": "complete", "conversation_id": "uuid" }

// Status update
{ "type": "status", "status": "processing" }

// Error
{ "type": "error", "error": "error message" }

// Pong response
{ "type": "pong" }
```

## Module System (fx)

The application uses Uber's fx for dependency injection:

```go
fx.New(
    // Core modules
    config.Module,      // Configuration loading
    logger.Module,      // Zap logger
    database.Module,    // PostgreSQL connection

    // Domain modules
    user.Module,        // User repository
    auth.Module,        // Auth service & handler
    middleware.Module,  // Auth middleware
    project.Module,     // Project CRUD
    conversation.Module,// Conversation CRUD
    message.Module,     // Message storage

    // Real-time modules
    claude.Module,      // Claude CLI manager
    ws.Module,          // WebSocket hub

    // Server (lifecycle managed)
    server.Module,
).Run()
```

## Database Schema

### Tables

- **users**: User accounts with bcrypt password hashes
- **projects**: Projects linked to users with path to Claude project
- **conversations**: Conversations within projects
- **messages**: Messages with role (user/assistant/system)
- **user_settings**: User preferences (theme, defaults)
- **active_sessions**: Currently active Claude CLI sessions

### UUID Generation

Uses UUIDv7 for time-ordered unique identifiers:
```sql
id UUID PRIMARY KEY DEFAULT uuidv7()
```

## Docker

### Build Image

```bash
docker build -t ccn-backend .
```

### Run Container

```bash
docker run -d \
  --name ccn-backend \
  -p 8080:8080 \
  -e DATABASE_URL=postgres://... \
  -e JWT_SECRET=your-secret \
  -v ~/.claude:/claude-projects:ro \
  ccn-backend
```

## Development Guidelines

### Code Organization

- Each domain module has its own package under `internal/`
- Each package typically contains:
  - `model.go`: Database models
  - `repository.go`: Data access layer
  - `service.go`: Business logic (if needed)
  - `handler.go`: HTTP handlers
  - `dto.go`: Request/response types
  - `module.go`: fx module definition
  - `*_test.go`: Tests

### Error Handling

Use Fiber's error handling with appropriate HTTP status codes:

```go
return c.Status(fiber.StatusBadRequest).JSON(fiber.Map{
    "error": "validation failed",
})
```

### Logging

Use the injected Zap logger:

```go
logger.Info("processing request",
    zap.String("user_id", userID),
    zap.String("action", "create_project"),
)
```

## License

MIT License - See root README for details.
