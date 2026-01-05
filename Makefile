# Claude Code Native - Development Makefile

.PHONY: help db db-stop db-logs server frontend all stop clean test

# Default target
help:
	@echo "Claude Code Native - Development Commands"
	@echo ""
	@echo "Quick Start:"
	@echo "  make all          - Start DB + Server + Frontend"
	@echo "  make dev          - Start DB + Server (no frontend)"
	@echo ""
	@echo "Individual Services:"
	@echo "  make db             - Start PostgreSQL (docker)"
	@echo "  make db-stop        - Stop PostgreSQL"
	@echo "  make db-logs        - View PostgreSQL logs"
	@echo "  make server         - Start backend server (go run)"
	@echo "  make server-watch   - Start backend with hot reload (air)"
	@echo "  make frontend       - Start desktop app (gradle)"
	@echo "  make frontend-watch - Start desktop app with hot reload"
	@echo "  make frontend-web   - Start web app (wasm)"
	@echo "  make frontend-web-watch - Start web app with hot reload"
	@echo ""
	@echo "Docker (Full Stack):"
	@echo "  make docker-up    - Start all services via docker-compose"
	@echo "  make docker-down  - Stop all docker services"
	@echo "  make docker-logs  - View docker logs"
	@echo ""
	@echo "Utilities:"
	@echo "  make test         - Run all tests"
	@echo "  make test-backend - Run backend tests"
	@echo "  make build        - Build all components"
	@echo "  make clean        - Clean build artifacts"
	@echo "  make db-reset     - Reset database (destroy + recreate)"

# =============================================================================
# Local Development (recommended)
# =============================================================================

# Start PostgreSQL only (for local backend development)
db:
	@echo "Starting PostgreSQL..."
	docker compose -f docker-compose.local.yml up -d
	@echo "PostgreSQL running on localhost:5438"

db-stop:
	@echo "Stopping PostgreSQL..."
	docker compose -f docker-compose.local.yml down

db-logs:
	docker compose -f docker-compose.local.yml logs -f postgres

db-reset:
	@echo "Resetting database..."
	docker compose -f docker-compose.local.yml down -v
	docker compose -f docker-compose.local.yml up -d
	@echo "Database reset complete"

# Start backend server (requires db to be running)
server:
	@echo "Starting backend server on :8083..."
	@cd backend && \
	env $$(grep -v '^#' .env.local | xargs) go run ./cmd/server

# Start backend with hot reload (requires air: go install github.com/air-verse/air@latest)
server-watch:
	@echo "Starting backend with hot reload..."
	@cd backend && \
	env $$(grep -v '^#' .env.local | xargs) air

# Start frontend desktop app
frontend:
	@echo "Starting desktop app..."
	cd frontend && ./gradlew composeApp:hotRunDesktop --mainClass=com.claudecode.native.MainKt

# Start frontend with hot reload (watches for changes)
frontend-watch:
	@echo "Starting desktop app with hot reload..."
	@echo "Hot reload: Code changes will be automatically applied"
	cd frontend && ./gradlew composeApp:hotRunDesktop --mainClass=com.claudecode.native.MainKt --auto

# Start frontend web app (wasm)
frontend-web:
	@echo "Starting web app..."
	cd frontend && ./gradlew composeApp:wasmJsBrowserDevelopmentRun --continuous

# Start frontend web app with hot reload
frontend-web-watch:
	@echo "Starting web app with hot reload..."
	cd frontend && ./gradlew composeApp:wasmJsBrowserDevelopmentRun --continuous

# Start DB + Server (no frontend)
dev: db
	@echo "Building backend (cleaning cache)..."
	cd backend && go clean -cache
	cd backend && rm -f bin/server
	cd backend && go build -v -o bin/server ./cmd/server
	@echo "Starting backend server on :8083..."
	cd backend && env $$(grep -v '^#' .env.local | xargs) ./bin/server

# Start everything (DB + Server in background + Frontend)
all: db
	@sleep 2
	@echo "Starting server in background..."
	@cd backend && \
	env $$(grep -v '^#' .env.local | xargs) go run ./cmd/server &
	@sleep 2
	cd frontend && ./gradlew composeApp:hotRunDesktop --mainClass=com.claudecode.native.MainKt

# =============================================================================
# Docker (Full Stack)
# =============================================================================

docker-up:
	@echo "Starting all services via docker-compose..."
	docker compose up -d
	@echo "Services running:"
	@echo "  - Backend: http://ccn.localhost (via Traefik)"
	@echo "  - Traefik Dashboard: http://localhost:8080"

docker-down:
	docker compose down

docker-logs:
	docker compose logs -f

docker-build:
	docker compose build

# =============================================================================
# Testing
# =============================================================================

test: test-all

test-backend:
	@echo "Running backend tests..."
	cd backend && go test ./...

test-frontend:
	@echo "Running frontend tests..."
	cd frontend && ./gradlew :composeApp:allTests

test-all: test-backend test-frontend

report-test:
	@echo "Generating test reports..."
	cd backend && go test -json ./... > test-report-backend.json
	cd frontend && ./gradlew :composeApp:allTests --continue

test-backend-v:
	@echo "Running backend tests (verbose)..."
	cd backend && go test -v ./...

# =============================================================================
# Build
# =============================================================================

build: build-backend build-frontend

build-backend:
	@echo "Building backend..."
	cd backend && go build -o bin/server ./cmd/server

build-frontend:
	@echo "Building frontend..."
	cd frontend && ./gradlew composeApp:packageDistributionForCurrentOS

build-frontend-web:
	@echo "Building web frontend..."
	cd frontend && ./gradlew composeApp:wasmJsBrowserProductionWebpack

# =============================================================================
# Utilities
# =============================================================================

clean:
	@echo "Cleaning build artifacts..."
	cd backend && rm -rf bin/
	cd frontend && ./gradlew clean

deps:
	@echo "Installing dependencies..."
	cd backend && go mod tidy
	cd frontend && ./gradlew dependencies

# Stop all running services
stop: db-stop
	@echo "Stopping any running go processes..."
	-pkill -f "go run ./cmd/server" 2>/dev/null || true
	@echo "All services stopped"
