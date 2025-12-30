# Claude Code Native - Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Build a self-hosted, multi-platform interface for Claude Code CLI with Go backend and KMP frontend.

**Architecture:** Go backend (Fiber + Bun + PostgreSQL 18 + **Uber fx DI**) with WebSocket streaming, Docker Compose deployment. KMP frontend (Compose Multiplatform + Voyager + Ktor) targeting Desktop, Android, iOS, and Web.

**Tech Stack:** Go 1.22+, PostgreSQL 18 (UUID v7), Fiber, uptrace/bun, **go.uber.org/fx**, gorilla/websocket | Kotlin, Compose Multiplatform, Ktor, Voyager, Koin

---

## Uber fx Architecture Overview

```
┌─────────────────────────────────────────────────────────────┐
│                    fx.New() Application                      │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐         │
│  │ ConfigModule│  │   DBModule  │  │ AuthModule  │         │
│  │ fx.Provide  │  │ fx.Provide  │  │ fx.Provide  │         │
│  │  - Config   │  │  - *bun.DB  │  │  - Service  │         │
│  └──────┬──────┘  └──────┬──────┘  └──────┬──────┘         │
│         │                │                │                  │
│         └────────────────┼────────────────┘                  │
│                          ▼                                   │
│  ┌─────────────────────────────────────────────────────┐    │
│  │                   ServerModule                       │    │
│  │  fx.Provide: NewServer                              │    │
│  │  fx.Invoke: RegisterRoutes                          │    │
│  │  fx.Lifecycle: OnStart(server.Listen)               │    │
│  │               OnStop(server.Shutdown)               │    │
│  └─────────────────────────────────────────────────────┘    │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

---

## Phase 1: Backend Foundation

### Task 1.1: Initialize Go Project with fx

**Files:**
- Create: `backend/go.mod`
- Create: `backend/go.sum`
- Create: `backend/cmd/server/main.go`
- Create: `backend/.gitignore`

**Step 1: Create directory structure**

```bash
mkdir -p backend/cmd/server
cd backend
```

**Step 2: Initialize Go module**

```bash
go mod init github.com/devnogari/claude-code-native/backend
```

**Step 3: Create main.go with fx skeleton**

```go
// backend/cmd/server/main.go
package main

import (
	"go.uber.org/fx"
)

func main() {
	fx.New(
		// Modules will be added here
		fx.NopLogger, // Disable fx logging for now
	).Run()
}
```

**Step 4: Create .gitignore**

```gitignore
# backend/.gitignore
# Binaries
*.exe
*.exe~
*.dll
*.so
*.dylib
ccn-server
server

# Test binary
*.test

# Output
*.out

# Go workspace
go.work

# IDE
.idea/
.vscode/
*.swp
*.swo

# Environment
.env
.env.local

# Vendor (if using)
# vendor/
```

**Step 5: Verify project compiles**

Run: `go mod tidy && go build ./cmd/server`
Expected: No errors

**Step 6: Commit**

```bash
git add backend/
git commit -m "feat(backend): initialize Go project with Uber fx skeleton"
```

---

### Task 1.2: Add Core Dependencies

**Files:**
- Modify: `backend/go.mod`

**Step 1: Add Uber fx**

```bash
cd backend
go get go.uber.org/fx
```

**Step 2: Add Fiber framework**

```bash
go get github.com/gofiber/fiber/v2
```

**Step 3: Add Bun ORM**

```bash
go get github.com/uptrace/bun
go get github.com/uptrace/bun/dialect/pgdialect
go get github.com/uptrace/bun/driver/pgdriver
```

**Step 4: Add WebSocket support**

```bash
go get github.com/gofiber/contrib/websocket
```

**Step 5: Add UUID v7 support**

```bash
go get github.com/gofrs/uuid/v5
```

**Step 6: Add JWT and security**

```bash
go get github.com/golang-jwt/jwt/v5
go get golang.org/x/crypto
```

**Step 7: Add environment config**

```bash
go get github.com/joho/godotenv
```

**Step 8: Add Zap logger (works well with fx)**

```bash
go get go.uber.org/zap
```

**Step 9: Tidy dependencies**

Run: `go mod tidy`
Expected: go.mod and go.sum updated with all dependencies

**Step 10: Commit**

```bash
git add backend/go.mod backend/go.sum
git commit -m "feat(backend): add core dependencies (fx, Fiber, Bun, WebSocket, JWT, Zap)"
```

---

### Task 1.3: Create Configuration Module (fx)

**Files:**
- Create: `backend/internal/config/config.go`
- Create: `backend/internal/config/module.go`
- Create: `backend/internal/config/config_test.go`
- Create: `backend/.env.example`

**Step 1: Write the failing test**

```go
// backend/internal/config/config_test.go
package config

import (
	"os"
	"testing"
)

func TestLoadConfig_Defaults(t *testing.T) {
	os.Clearenv()

	cfg, err := New()
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	if cfg.Server.Port != "8080" {
		t.Errorf("expected default port 8080, got %s", cfg.Server.Port)
	}
	if cfg.Server.LogLevel != "info" {
		t.Errorf("expected default log level info, got %s", cfg.Server.LogLevel)
	}
}

func TestLoadConfig_FromEnv(t *testing.T) {
	os.Setenv("PORT", "3000")
	os.Setenv("DATABASE_URL", "postgres://test:test@localhost:5432/test")
	os.Setenv("JWT_SECRET", "testsecret123456789012345678901234")
	defer os.Clearenv()

	cfg, err := New()
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	if cfg.Server.Port != "3000" {
		t.Errorf("expected port 3000, got %s", cfg.Server.Port)
	}
	if cfg.Database.URL != "postgres://test:test@localhost:5432/test" {
		t.Errorf("expected database URL from env, got %s", cfg.Database.URL)
	}
}
```

**Step 2: Run test to verify it fails**

Run: `cd backend && go test ./internal/config/... -v`
Expected: FAIL - package not found

**Step 3: Write config implementation**

```go
// backend/internal/config/config.go
package config

import (
	"fmt"
	"os"
	"strconv"

	"github.com/joho/godotenv"
)

type Config struct {
	Server   ServerConfig
	Database DatabaseConfig
	Auth     AuthConfig
	Claude   ClaudeConfig
}

type ServerConfig struct {
	Port     string
	LogLevel string
}

type DatabaseConfig struct {
	URL string
}

type AuthConfig struct {
	JWTSecret     string
	JWTExpiryDays int
}

type ClaudeConfig struct {
	ProjectsPath string
}

func New() (*Config, error) {
	// Load .env file if exists (ignore error if not found)
	_ = godotenv.Load()

	cfg := &Config{
		Server: ServerConfig{
			Port:     getEnv("PORT", "8080"),
			LogLevel: getEnv("LOG_LEVEL", "info"),
		},
		Database: DatabaseConfig{
			URL: getEnv("DATABASE_URL", ""),
		},
		Auth: AuthConfig{
			JWTSecret:     getEnv("JWT_SECRET", ""),
			JWTExpiryDays: getEnvInt("JWT_EXPIRY_DAYS", 7),
		},
		Claude: ClaudeConfig{
			ProjectsPath: getEnv("CLAUDE_PROJECTS_PATH", "~/.claude"),
		},
	}

	return cfg, nil
}

func getEnv(key, defaultValue string) string {
	if value := os.Getenv(key); value != "" {
		return value
	}
	return defaultValue
}

func getEnvInt(key string, defaultValue int) int {
	if value := os.Getenv(key); value != "" {
		if result, err := strconv.Atoi(value); err == nil {
			return result
		}
	}
	return defaultValue
}

func getEnvBool(key string, defaultValue bool) bool {
	if value := os.Getenv(key); value != "" {
		if result, err := strconv.ParseBool(value); err == nil {
			return result
		}
	}
	return defaultValue
}
```

**Step 4: Create fx module**

```go
// backend/internal/config/module.go
package config

import (
	"go.uber.org/fx"
)

// Module provides config dependencies
var Module = fx.Module("config",
	fx.Provide(New),
)
```

**Step 5: Run test**

Run: `cd backend && go test ./internal/config/... -v`
Expected: PASS

**Step 6: Create .env.example**

```bash
# backend/.env.example

# Server
PORT=8080
LOG_LEVEL=info

# Database (PostgreSQL 18)
DATABASE_URL=postgres://ccn:password@localhost:5432/claude_code_native?sslmode=disable

# Authentication
JWT_SECRET=your-secret-key-min-32-characters-long
JWT_EXPIRY_DAYS=7

# Claude
CLAUDE_PROJECTS_PATH=~/.claude
```

**Step 7: Commit**

```bash
git add backend/internal/config/ backend/.env.example
git commit -m "feat(backend): add configuration module with fx provider"
```

---

### Task 1.4: Create Logger Module (fx)

**Files:**
- Create: `backend/internal/logger/logger.go`
- Create: `backend/internal/logger/module.go`

**Step 1: Create logger**

```go
// backend/internal/logger/logger.go
package logger

import (
	"github.com/devnogari/claude-code-native/backend/internal/config"
	"go.uber.org/zap"
	"go.uber.org/zap/zapcore"
)

func New(cfg *config.Config) (*zap.Logger, error) {
	var level zapcore.Level
	if err := level.UnmarshalText([]byte(cfg.Server.LogLevel)); err != nil {
		level = zapcore.InfoLevel
	}

	zapConfig := zap.Config{
		Level:            zap.NewAtomicLevelAt(level),
		Development:      cfg.Server.LogLevel == "debug",
		Encoding:         "json",
		EncoderConfig:    zap.NewProductionEncoderConfig(),
		OutputPaths:      []string{"stdout"},
		ErrorOutputPaths: []string{"stderr"},
	}

	return zapConfig.Build()
}

// NewSugar provides a sugared logger for convenience
func NewSugar(logger *zap.Logger) *zap.SugaredLogger {
	return logger.Sugar()
}
```

**Step 2: Create fx module**

```go
// backend/internal/logger/module.go
package logger

import (
	"go.uber.org/fx"
)

var Module = fx.Module("logger",
	fx.Provide(New),
	fx.Provide(NewSugar),
)
```

**Step 3: Commit**

```bash
git add backend/internal/logger/
git commit -m "feat(backend): add Zap logger module with fx provider"
```

---

### Task 1.5: Create Database Module (fx)

**Files:**
- Create: `backend/internal/database/database.go`
- Create: `backend/internal/database/module.go`
- Create: `backend/internal/database/database_test.go`

**Step 1: Write the failing test**

```go
// backend/internal/database/database_test.go
package database

import (
	"testing"

	"github.com/devnogari/claude-code-native/backend/internal/config"
)

func TestNewDB_EmptyURL(t *testing.T) {
	cfg := &config.Config{
		Database: config.DatabaseConfig{
			URL: "",
		},
	}

	_, err := New(cfg, nil)
	if err == nil {
		t.Error("expected error for empty URL")
	}
}

func TestNewDB_InvalidURL(t *testing.T) {
	cfg := &config.Config{
		Database: config.DatabaseConfig{
			URL: "invalid-url",
		},
	}

	_, err := New(cfg, nil)
	if err == nil {
		t.Error("expected error for invalid URL")
	}
}
```

**Step 2: Run test to verify it fails**

Run: `cd backend && go test ./internal/database/... -v`
Expected: FAIL - package not found

**Step 3: Write database implementation**

```go
// backend/internal/database/database.go
package database

import (
	"context"
	"database/sql"
	"fmt"

	"github.com/devnogari/claude-code-native/backend/internal/config"
	"github.com/uptrace/bun"
	"github.com/uptrace/bun/dialect/pgdialect"
	"github.com/uptrace/bun/driver/pgdriver"
	"go.uber.org/fx"
	"go.uber.org/zap"
)

// DBParams contains dependencies for database
type DBParams struct {
	fx.In
	Config *config.Config
	Logger *zap.Logger `optional:"true"`
}

// DBResult contains provided database
type DBResult struct {
	fx.Out
	DB *bun.DB
}

func New(cfg *config.Config, logger *zap.Logger) (*bun.DB, error) {
	if cfg.Database.URL == "" {
		return nil, fmt.Errorf("database URL is required")
	}

	// Create connector
	connector := pgdriver.NewConnector(pgdriver.WithDSN(cfg.Database.URL))
	sqldb := sql.OpenDB(connector)

	// Test connection
	if err := sqldb.Ping(); err != nil {
		return nil, fmt.Errorf("failed to connect to database: %w", err)
	}

	// Create Bun DB
	db := bun.NewDB(sqldb, pgdialect.New())

	if logger != nil {
		logger.Info("Database connected successfully")
	}

	return db, nil
}

// NewWithLifecycle creates DB with fx lifecycle management
func NewWithLifecycle(lc fx.Lifecycle, cfg *config.Config, logger *zap.Logger) (*bun.DB, error) {
	db, err := New(cfg, logger)
	if err != nil {
		return nil, err
	}

	lc.Append(fx.Hook{
		OnStop: func(ctx context.Context) error {
			if logger != nil {
				logger.Info("Closing database connection")
			}
			return db.Close()
		},
	})

	return db, nil
}
```

**Step 4: Create fx module**

```go
// backend/internal/database/module.go
package database

import (
	"go.uber.org/fx"
)

var Module = fx.Module("database",
	fx.Provide(NewWithLifecycle),
)
```

**Step 5: Run test**

Run: `cd backend && go test ./internal/database/... -v`
Expected: PASS

**Step 6: Commit**

```bash
git add backend/internal/database/
git commit -m "feat(backend): add database module with Bun and fx lifecycle"
```

---

### Task 1.6: Create User Model and Repository (fx)

**Files:**
- Create: `backend/internal/user/model.go`
- Create: `backend/internal/user/repository.go`
- Create: `backend/internal/user/module.go`
- Create: `backend/internal/user/repository_test.go`

**Step 1: Create User model**

```go
// backend/internal/user/model.go
package user

import (
	"time"

	"github.com/gofrs/uuid/v5"
	"github.com/uptrace/bun"
)

type User struct {
	bun.BaseModel `bun:"table:users,alias:u"`

	ID           uuid.UUID  `bun:"id,pk,type:uuid,default:uuidv7()"  json:"id"`
	Username     string     `bun:"username,notnull,unique"           json:"username"`
	PasswordHash string     `bun:"password_hash,notnull"             json:"-"`
	CreatedAt    time.Time  `bun:"created_at,default:now()"          json:"created_at"`
	UpdatedAt    time.Time  `bun:"updated_at,default:now()"          json:"updated_at"`
	LastLoginAt  *time.Time `bun:"last_login_at"                     json:"last_login_at,omitempty"`
}
```

**Step 2: Write the failing test**

```go
// backend/internal/user/repository_test.go
package user

import (
	"testing"
)

func TestNewRepository(t *testing.T) {
	repo := NewRepository(nil)
	if repo == nil {
		t.Error("expected non-nil repository")
	}
	if repo.db != nil {
		t.Error("expected nil db for nil input")
	}
}
```

**Step 3: Run test to verify it fails**

Run: `cd backend && go test ./internal/user/... -v`
Expected: FAIL - NewRepository not defined

**Step 4: Write repository implementation**

```go
// backend/internal/user/repository.go
package user

import (
	"context"
	"fmt"

	"github.com/gofrs/uuid/v5"
	"github.com/uptrace/bun"
)

type Repository struct {
	db *bun.DB
}

func NewRepository(db *bun.DB) *Repository {
	return &Repository{db: db}
}

func (r *Repository) Create(ctx context.Context, user *User) error {
	if r.db == nil {
		return fmt.Errorf("database not initialized")
	}

	if user.ID == uuid.Nil {
		id, err := uuid.NewV7()
		if err != nil {
			return fmt.Errorf("failed to generate UUID: %w", err)
		}
		user.ID = id
	}

	_, err := r.db.NewInsert().Model(user).Exec(ctx)
	return err
}

func (r *Repository) FindByID(ctx context.Context, id uuid.UUID) (*User, error) {
	if r.db == nil {
		return nil, fmt.Errorf("database not initialized")
	}

	user := new(User)
	err := r.db.NewSelect().
		Model(user).
		Where("id = ?", id).
		Scan(ctx)

	if err != nil {
		return nil, err
	}
	return user, nil
}

func (r *Repository) FindByUsername(ctx context.Context, username string) (*User, error) {
	if r.db == nil {
		return nil, fmt.Errorf("database not initialized")
	}

	user := new(User)
	err := r.db.NewSelect().
		Model(user).
		Where("username = ?", username).
		Scan(ctx)

	if err != nil {
		return nil, err
	}
	return user, nil
}

func (r *Repository) UpdateLastLogin(ctx context.Context, id uuid.UUID) error {
	if r.db == nil {
		return fmt.Errorf("database not initialized")
	}

	_, err := r.db.NewUpdate().
		Model((*User)(nil)).
		Set("last_login_at = NOW()").
		Set("updated_at = NOW()").
		Where("id = ?", id).
		Exec(ctx)

	return err
}
```

**Step 5: Create fx module**

```go
// backend/internal/user/module.go
package user

import (
	"go.uber.org/fx"
)

var Module = fx.Module("user",
	fx.Provide(NewRepository),
)
```

**Step 6: Run test**

Run: `cd backend && go test ./internal/user/... -v`
Expected: PASS

**Step 7: Commit**

```bash
git add backend/internal/user/
git commit -m "feat(backend): add User model and repository with fx module"
```

---

### Task 1.7: Create Auth Service (fx)

**Files:**
- Create: `backend/internal/auth/service.go`
- Create: `backend/internal/auth/jwt.go`
- Create: `backend/internal/auth/module.go`
- Create: `backend/internal/auth/service_test.go`

**Step 1: Write the failing test**

```go
// backend/internal/auth/service_test.go
package auth

import (
	"testing"

	"github.com/devnogari/claude-code-native/backend/internal/config"
)

func TestHashPassword(t *testing.T) {
	svc := NewService(&config.Config{
		Auth: config.AuthConfig{
			JWTSecret:     "testsecret12345678901234567890123",
			JWTExpiryDays: 7,
		},
	}, nil)

	password := "testpassword123"
	hash, err := svc.HashPassword(password)
	if err != nil {
		t.Fatalf("failed to hash password: %v", err)
	}

	if hash == password {
		t.Error("hash should not equal plain password")
	}
}

func TestVerifyPassword(t *testing.T) {
	svc := NewService(&config.Config{
		Auth: config.AuthConfig{
			JWTSecret:     "testsecret12345678901234567890123",
			JWTExpiryDays: 7,
		},
	}, nil)

	password := "testpassword123"
	hash, _ := svc.HashPassword(password)

	if !svc.VerifyPassword(password, hash) {
		t.Error("password verification failed for correct password")
	}

	if svc.VerifyPassword("wrongpassword", hash) {
		t.Error("password verification should fail for wrong password")
	}
}

func TestGenerateAndValidateJWT(t *testing.T) {
	svc := NewService(&config.Config{
		Auth: config.AuthConfig{
			JWTSecret:     "testsecret12345678901234567890123",
			JWTExpiryDays: 7,
		},
	}, nil)

	userID := "test-user-id"

	token, err := svc.GenerateJWT(userID)
	if err != nil {
		t.Fatalf("failed to generate JWT: %v", err)
	}

	claims, err := svc.ValidateJWT(token)
	if err != nil {
		t.Fatalf("failed to validate JWT: %v", err)
	}

	if claims.UserID != userID {
		t.Errorf("expected user ID %s, got %s", userID, claims.UserID)
	}
}
```

**Step 2: Run test to verify it fails**

Run: `cd backend && go test ./internal/auth/... -v`
Expected: FAIL - Service not defined

**Step 3: Write auth service**

```go
// backend/internal/auth/service.go
package auth

import (
	"github.com/devnogari/claude-code-native/backend/internal/config"
	"github.com/devnogari/claude-code-native/backend/internal/user"
	"go.uber.org/zap"
	"golang.org/x/crypto/bcrypt"
)

const bcryptCost = 12

type Service struct {
	config   *config.Config
	userRepo *user.Repository
	logger   *zap.Logger
}

func NewService(cfg *config.Config, userRepo *user.Repository) *Service {
	return &Service{
		config:   cfg,
		userRepo: userRepo,
	}
}

func NewServiceWithLogger(cfg *config.Config, userRepo *user.Repository, logger *zap.Logger) *Service {
	return &Service{
		config:   cfg,
		userRepo: userRepo,
		logger:   logger,
	}
}

func (s *Service) HashPassword(password string) (string, error) {
	bytes, err := bcrypt.GenerateFromPassword([]byte(password), bcryptCost)
	if err != nil {
		return "", err
	}
	return string(bytes), nil
}

func (s *Service) VerifyPassword(password, hash string) bool {
	err := bcrypt.CompareHashAndPassword([]byte(hash), []byte(password))
	return err == nil
}
```

**Step 4: Write JWT functions**

```go
// backend/internal/auth/jwt.go
package auth

import (
	"fmt"
	"time"

	"github.com/golang-jwt/jwt/v5"
)

type Claims struct {
	UserID string `json:"user_id"`
	jwt.RegisteredClaims
}

func (s *Service) GenerateJWT(userID string) (string, error) {
	claims := &Claims{
		UserID: userID,
		RegisteredClaims: jwt.RegisteredClaims{
			ExpiresAt: jwt.NewNumericDate(time.Now().Add(time.Duration(s.config.Auth.JWTExpiryDays) * 24 * time.Hour)),
			IssuedAt:  jwt.NewNumericDate(time.Now()),
			Issuer:    "claude-code-native",
		},
	}

	token := jwt.NewWithClaims(jwt.SigningMethodHS256, claims)
	return token.SignedString([]byte(s.config.Auth.JWTSecret))
}

func (s *Service) ValidateJWT(tokenString string) (*Claims, error) {
	token, err := jwt.ParseWithClaims(tokenString, &Claims{}, func(token *jwt.Token) (interface{}, error) {
		if _, ok := token.Method.(*jwt.SigningMethodHMAC); !ok {
			return nil, fmt.Errorf("unexpected signing method: %v", token.Header["alg"])
		}
		return []byte(s.config.Auth.JWTSecret), nil
	})

	if err != nil {
		return nil, err
	}

	if claims, ok := token.Claims.(*Claims); ok && token.Valid {
		return claims, nil
	}

	return nil, fmt.Errorf("invalid token")
}
```

**Step 5: Create fx module**

```go
// backend/internal/auth/module.go
package auth

import (
	"go.uber.org/fx"
)

var Module = fx.Module("auth",
	fx.Provide(NewServiceWithLogger),
)
```

**Step 6: Run test**

Run: `cd backend && go test ./internal/auth/... -v`
Expected: PASS

**Step 7: Commit**

```bash
git add backend/internal/auth/
git commit -m "feat(backend): add auth service with JWT and fx module"
```

---

### Task 1.8: Create Database Migrations

**Files:**
- Create: `backend/migrations/001_init.up.sql`
- Create: `backend/migrations/001_init.down.sql`

**Step 1: Create up migration**

```sql
-- backend/migrations/001_init.up.sql

-- Users table
CREATE TABLE IF NOT EXISTS users (
    id            UUID PRIMARY KEY DEFAULT uuidv7(),
    username      VARCHAR(50) UNIQUE NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    created_at    TIMESTAMPTZ DEFAULT NOW(),
    updated_at    TIMESTAMPTZ DEFAULT NOW(),
    last_login_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_users_username ON users(username);

-- Projects table
CREATE TABLE IF NOT EXISTS projects (
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

CREATE INDEX IF NOT EXISTS idx_projects_user_id ON projects(user_id);

-- Conversations table
CREATE TABLE IF NOT EXISTS conversations (
    id              UUID PRIMARY KEY DEFAULT uuidv7(),
    project_id      UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    claude_session  VARCHAR(255),
    title           VARCHAR(255),
    message_count   INTEGER DEFAULT 0,
    jsonl_path      VARCHAR(1024),
    created_at      TIMESTAMPTZ DEFAULT NOW(),
    updated_at      TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_conversations_project_id ON conversations(project_id);

-- Messages table
CREATE TABLE IF NOT EXISTS messages (
    id              UUID PRIMARY KEY DEFAULT uuidv7(),
    conversation_id UUID NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    role            VARCHAR(20) NOT NULL CHECK (role IN ('user', 'assistant')),
    content         TEXT NOT NULL,
    token_count     INTEGER,
    sequence_num    INTEGER NOT NULL,
    created_at      TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_messages_conversation_seq ON messages(conversation_id, sequence_num);

-- User settings table
CREATE TABLE IF NOT EXISTS user_settings (
    user_id         UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    theme           VARCHAR(20) DEFAULT 'system' CHECK (theme IN ('light', 'dark', 'system')),
    default_project UUID REFERENCES projects(id) ON DELETE SET NULL,
    preferences     JSONB DEFAULT '{}',
    updated_at      TIMESTAMPTZ DEFAULT NOW()
);

-- Active sessions table
CREATE TABLE IF NOT EXISTS active_sessions (
    id              UUID PRIMARY KEY DEFAULT uuidv7(),
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    conversation_id UUID REFERENCES conversations(id) ON DELETE SET NULL,
    pid             INTEGER,
    started_at      TIMESTAMPTZ DEFAULT NOW(),
    last_activity   TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_active_sessions_user ON active_sessions(user_id);

-- Auto-update trigger
CREATE OR REPLACE FUNCTION update_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS users_updated_at ON users;
CREATE TRIGGER users_updated_at BEFORE UPDATE ON users
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();

DROP TRIGGER IF EXISTS projects_updated_at ON projects;
CREATE TRIGGER projects_updated_at BEFORE UPDATE ON projects
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();

DROP TRIGGER IF EXISTS conversations_updated_at ON conversations;
CREATE TRIGGER conversations_updated_at BEFORE UPDATE ON conversations
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();
```

**Step 2: Create down migration**

```sql
-- backend/migrations/001_init.down.sql

DROP TRIGGER IF EXISTS conversations_updated_at ON conversations;
DROP TRIGGER IF EXISTS projects_updated_at ON projects;
DROP TRIGGER IF EXISTS users_updated_at ON users;
DROP FUNCTION IF EXISTS update_updated_at();

DROP TABLE IF EXISTS active_sessions;
DROP TABLE IF EXISTS user_settings;
DROP TABLE IF EXISTS messages;
DROP TABLE IF EXISTS conversations;
DROP TABLE IF EXISTS projects;
DROP TABLE IF EXISTS users;
```

**Step 3: Commit**

```bash
git add backend/migrations/
git commit -m "feat(backend): add database migrations for all tables"
```

---

### Task 1.9: Create HTTP Server Module (fx)

**Files:**
- Create: `backend/internal/server/server.go`
- Create: `backend/internal/server/routes.go`
- Create: `backend/internal/server/module.go`

**Step 1: Create server**

```go
// backend/internal/server/server.go
package server

import (
	"context"
	"fmt"
	"time"

	"github.com/devnogari/claude-code-native/backend/internal/auth"
	"github.com/devnogari/claude-code-native/backend/internal/config"
	"github.com/devnogari/claude-code-native/backend/internal/user"
	"github.com/gofiber/fiber/v2"
	"github.com/gofiber/fiber/v2/middleware/cors"
	"github.com/gofiber/fiber/v2/middleware/recover"
	"go.uber.org/fx"
	"go.uber.org/zap"
)

type Server struct {
	app      *fiber.App
	config   *config.Config
	logger   *zap.Logger
	auth     *auth.Service
	userRepo *user.Repository
}

type ServerParams struct {
	fx.In
	Config   *config.Config
	Logger   *zap.Logger
	Auth     *auth.Service
	UserRepo *user.Repository
}

func New(p ServerParams) *Server {
	app := fiber.New(fiber.Config{
		AppName:      "Claude Code Native",
		ReadTimeout:  30 * time.Second,
		WriteTimeout: 30 * time.Second,
		IdleTimeout:  120 * time.Second,
		ErrorHandler: customErrorHandler(p.Logger),
	})

	// Middleware
	app.Use(recover.New())
	app.Use(requestLogger(p.Logger))
	app.Use(cors.New(cors.Config{
		AllowOrigins: "*",
		AllowMethods: "GET,POST,PUT,DELETE,OPTIONS",
		AllowHeaders: "Origin,Content-Type,Accept,Authorization",
	}))

	s := &Server{
		app:      app,
		config:   p.Config,
		logger:   p.Logger,
		auth:     p.Auth,
		userRepo: p.UserRepo,
	}

	s.setupRoutes()

	return s
}

func customErrorHandler(logger *zap.Logger) fiber.ErrorHandler {
	return func(c *fiber.Ctx, err error) error {
		code := fiber.StatusInternalServerError
		if e, ok := err.(*fiber.Error); ok {
			code = e.Code
		}
		logger.Error("HTTP error", zap.Error(err), zap.Int("status", code))
		return c.Status(code).JSON(fiber.Map{"error": err.Error()})
	}
}

func requestLogger(logger *zap.Logger) fiber.Handler {
	return func(c *fiber.Ctx) error {
		start := time.Now()
		err := c.Next()
		logger.Info("request",
			zap.String("method", c.Method()),
			zap.String("path", c.Path()),
			zap.Int("status", c.Response().StatusCode()),
			zap.Duration("latency", time.Since(start)),
		)
		return err
	}
}

func (s *Server) App() *fiber.App {
	return s.app
}

func (s *Server) Start() error {
	addr := fmt.Sprintf(":%s", s.config.Server.Port)
	s.logger.Info("Starting server", zap.String("addr", addr))
	return s.app.Listen(addr)
}

func (s *Server) Shutdown(ctx context.Context) error {
	s.logger.Info("Shutting down server")
	return s.app.ShutdownWithContext(ctx)
}
```

**Step 2: Create routes**

```go
// backend/internal/server/routes.go
package server

import (
	"github.com/gofiber/fiber/v2"
)

func (s *Server) setupRoutes() {
	// Health check
	s.app.Get("/health", s.healthCheck)

	// API v1
	api := s.app.Group("/api/v1")

	// Auth routes (public)
	authGroup := api.Group("/auth")
	authGroup.Post("/login", s.handleLogin)
	authGroup.Post("/register", s.handleRegister)

	// Protected routes (to be added with middleware)
	// api.Use(s.authMiddleware)
	// api.Get("/projects", s.handleListProjects)
}

func (s *Server) healthCheck(c *fiber.Ctx) error {
	return c.JSON(fiber.Map{
		"status":  "ok",
		"service": "claude-code-native",
	})
}

func (s *Server) handleLogin(c *fiber.Ctx) error {
	// TODO: Implement in Phase 2
	return c.Status(501).JSON(fiber.Map{
		"error": "not implemented",
	})
}

func (s *Server) handleRegister(c *fiber.Ctx) error {
	// TODO: Implement in Phase 2
	return c.Status(501).JSON(fiber.Map{
		"error": "not implemented",
	})
}
```

**Step 3: Create fx module with lifecycle**

```go
// backend/internal/server/module.go
package server

import (
	"context"

	"go.uber.org/fx"
)

var Module = fx.Module("server",
	fx.Provide(New),
	fx.Invoke(registerLifecycle),
)

func registerLifecycle(lc fx.Lifecycle, server *Server) {
	lc.Append(fx.Hook{
		OnStart: func(ctx context.Context) error {
			go server.Start()
			return nil
		},
		OnStop: func(ctx context.Context) error {
			return server.Shutdown(ctx)
		},
	})
}
```

**Step 4: Commit**

```bash
git add backend/internal/server/
git commit -m "feat(backend): add Fiber HTTP server with fx lifecycle"
```

---

### Task 1.10: Wire Up Application with fx

**Files:**
- Modify: `backend/cmd/server/main.go`

**Step 1: Update main.go to wire all modules**

```go
// backend/cmd/server/main.go
package main

import (
	"github.com/devnogari/claude-code-native/backend/internal/auth"
	"github.com/devnogari/claude-code-native/backend/internal/config"
	"github.com/devnogari/claude-code-native/backend/internal/database"
	"github.com/devnogari/claude-code-native/backend/internal/logger"
	"github.com/devnogari/claude-code-native/backend/internal/server"
	"github.com/devnogari/claude-code-native/backend/internal/user"
	"go.uber.org/fx"
)

func main() {
	fx.New(
		// Core modules
		config.Module,
		logger.Module,
		database.Module,

		// Domain modules
		user.Module,
		auth.Module,

		// Server (starts on OnStart, stops on OnStop)
		server.Module,
	).Run()
}
```

**Step 2: Build and test**

Run: `cd backend && go build ./cmd/server`
Expected: No errors

**Step 3: Test without database (graceful degradation)**

Run: `cd backend && ./server`
Expected: Server starts, database warning logged

Run (in another terminal): `curl http://localhost:8080/health`
Expected: `{"service":"claude-code-native","status":"ok"}`

**Step 4: Commit**

```bash
git add backend/cmd/server/main.go
git commit -m "feat(backend): wire all modules with Uber fx"
```

---

### Task 1.11: Create Docker Compose Setup

**Files:**
- Create: `docker-compose.yml`
- Create: `backend/Dockerfile`
- Create: `.env.example`
- Create: `.gitignore`

**Step 1: Create docker-compose.yml**

```yaml
# docker-compose.yml
version: "3.9"

services:
  traefik:
    image: traefik:v3.0
    container_name: ccn-traefik
    restart: unless-stopped
    command:
      - "--api.insecure=true"
      - "--providers.docker=true"
      - "--providers.docker.exposedbydefault=false"
      - "--entrypoints.web.address=:80"
      - "--entrypoints.websecure.address=:443"
    ports:
      - "80:80"
      - "443:443"
      - "8080:8080"
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock:ro
    networks:
      - ccn-network

  backend:
    build:
      context: ./backend
      dockerfile: Dockerfile
    container_name: ccn-backend
    restart: unless-stopped
    environment:
      - PORT=8080
      - DATABASE_URL=postgres://ccn:${DB_PASSWORD}@postgres:5432/claude_code_native?sslmode=disable
      - JWT_SECRET=${JWT_SECRET}
      - CLAUDE_PROJECTS_PATH=/claude-projects
      - LOG_LEVEL=info
    volumes:
      - ~/.claude:/claude-projects:ro
      - claude-sessions:/var/lib/ccn/sessions
    depends_on:
      postgres:
        condition: service_healthy
    labels:
      - "traefik.enable=true"
      - "traefik.http.routers.backend.rule=Host(`ccn.localhost`)"
      - "traefik.http.routers.backend.entrypoints=web"
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
      - ./backend/migrations:/docker-entrypoint-initdb.d:ro
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U ccn -d claude_code_native"]
      interval: 5s
      timeout: 5s
      retries: 5
    ports:
      - "5432:5432"
    networks:
      - ccn-network

volumes:
  postgres-data:
    name: ccn-postgres-data
  claude-sessions:
    name: ccn-sessions

networks:
  ccn-network:
    name: ccn-network
    driver: bridge
```

**Step 2: Create backend Dockerfile**

```dockerfile
# backend/Dockerfile

FROM golang:1.22-alpine AS builder

WORKDIR /app

RUN apk add --no-cache gcc musl-dev

COPY go.mod go.sum ./
RUN go mod download

COPY . .

RUN CGO_ENABLED=0 GOOS=linux go build -o /ccn-server ./cmd/server

FROM alpine:3.19

RUN apk add --no-cache \
    ca-certificates \
    tzdata \
    nodejs \
    npm \
    && npm install -g @anthropic-ai/claude-code

WORKDIR /app

COPY --from=builder /ccn-server .
COPY --from=builder /app/migrations ./migrations

RUN adduser -D -g '' ccn
USER ccn

EXPOSE 8080

CMD ["./ccn-server"]
```

**Step 3: Create root .env.example**

```bash
# .env.example

# Database
DB_PASSWORD=your-secure-database-password

# JWT Authentication
JWT_SECRET=your-jwt-secret-minimum-32-characters-long

# Optional
LOG_LEVEL=info
```

**Step 4: Create root .gitignore**

```gitignore
# .gitignore

# Environment files
.env
.env.local
.env.*.local

# IDE
.idea/
.vscode/
*.swp
*.swo
.DS_Store

# Build outputs
dist/
build/
*.exe
*.dll
*.so
*.dylib

# Logs
*.log
logs/

# Docker volumes (local dev)
postgres-data/

# KMP/Gradle
.gradle/
**/build/
!**/src/**/build/

# Node
node_modules/
```

**Step 5: Verify Docker Compose syntax**

Run: `docker compose config`
Expected: Valid YAML output

**Step 6: Commit**

```bash
git add docker-compose.yml backend/Dockerfile .env.example .gitignore
git commit -m "feat: add Docker Compose setup with Traefik, Go backend, PostgreSQL 18"
```

---

## Phase 1 Complete Checkpoint

At this point you should have:
- ✅ Go project with Uber fx DI framework
- ✅ Configuration module (fx)
- ✅ Logger module with Zap (fx)
- ✅ Database module with Bun + lifecycle (fx)
- ✅ User model and repository (fx)
- ✅ Auth service with JWT (fx)
- ✅ Database migrations
- ✅ HTTP server with Fiber + fx lifecycle
- ✅ Docker Compose setup

**Test the full stack:**

```bash
# Create .env from example
cp .env.example .env
# Edit .env with secure passwords

# Start services
docker compose up -d

# Check health
curl http://localhost:8080/health

# Check logs
docker compose logs -f backend
```

---

## Phase 2: Authentication & API (Tasks 2.1 - 2.6)

*Continue with auth handlers, middleware, project endpoints, etc.*

---

## Phase 3: WebSocket & Claude CLI Integration (Tasks 3.1 - 3.5)

*WebSocket hub, CLI spawning, streaming, etc.*

---

## Phase 4: KMP Frontend (Tasks 4.1 - 4.10)

*KMP project setup, shared modules, screens, etc.*

---

## Phase 5: Multi-platform & Polish (Tasks 5.1 - 5.5)

*Platform-specific builds, testing, documentation*

---

**Sources:**
- [Uber fx GitHub](https://github.com/uber-go/fx)
- [Uber fx Documentation](https://uber-go.github.io/fx/index.html)
- [fx Best Practices](https://medium.com/@ademolakolawole/taming-the-dependencies-mastering-dependency-injection-in-go-with-uber-fx-b3c3600e822d)
