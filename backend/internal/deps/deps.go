// Package deps ensures core dependencies are retained in go.mod.
// This file imports all required packages to prevent go mod tidy from removing them.
// These imports will be moved to their proper locations as the application develops.
package deps

import (
	// Web framework
	_ "github.com/gofiber/fiber/v2"

	// WebSocket support
	_ "github.com/gofiber/contrib/websocket"

	// ORM and database
	_ "github.com/uptrace/bun"
	_ "github.com/uptrace/bun/dialect/pgdialect"
	_ "github.com/uptrace/bun/driver/pgdriver"

	// UUID support
	_ "github.com/gofrs/uuid/v5"

	// JWT authentication
	_ "github.com/golang-jwt/jwt/v5"

	// Cryptography
	_ "golang.org/x/crypto/bcrypt"

	// Environment configuration
	_ "github.com/joho/godotenv"

	// Structured logging
	_ "go.uber.org/zap"
)
