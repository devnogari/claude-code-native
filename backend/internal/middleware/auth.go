package middleware

import (
	"strings"

	"github.com/devnogari/claude-code-native/backend/internal/config"
	"github.com/gofiber/fiber/v2"
	"github.com/golang-jwt/jwt/v5"
)

// AuthMiddleware provides JWT authentication middleware for Fiber
type AuthMiddleware struct {
	config *config.Config
}

// NewAuthMiddleware creates a new AuthMiddleware instance
func NewAuthMiddleware(cfg *config.Config) *AuthMiddleware {
	return &AuthMiddleware{
		config: cfg,
	}
}

// Authenticate validates JWT tokens and extracts user information
func (m *AuthMiddleware) Authenticate(c *fiber.Ctx) error {
	var tokenString string

	// First, try to extract token from Authorization header
	authHeader := c.Get("Authorization")
	if authHeader != "" {
		// Validate "Bearer " prefix
		if !strings.HasPrefix(authHeader, "Bearer ") {
			return c.Status(fiber.StatusUnauthorized).JSON(fiber.Map{
				"error": "invalid authorization format",
			})
		}
		tokenString = strings.TrimPrefix(authHeader, "Bearer ")
	}

	// If no header, try query parameter (for WebSocket connections - backward compatibility)
	// Note: Query params are less secure as they appear in logs. Prefer auth message for WebSocket.
	if tokenString == "" {
		tokenString = c.Query("token")
	}

	// For WebSocket upgrade requests, if no token provided, allow connection
	// and let handler authenticate via first message (more secure approach)
	if tokenString == "" {
		// Use case-insensitive comparison since HTTP headers are case-insensitive
		if strings.EqualFold(c.Get("Upgrade"), "websocket") {
			// Let the WebSocket handler handle authentication via first message
			return c.Next()
		}
		return c.Status(fiber.StatusUnauthorized).JSON(fiber.Map{
			"error": "missing authorization token",
		})
	}

	// Parse and validate JWT token
	token, err := jwt.Parse(tokenString, func(token *jwt.Token) (interface{}, error) {
		// Validate signing method
		if _, ok := token.Method.(*jwt.SigningMethodHMAC); !ok {
			return nil, fiber.NewError(fiber.StatusUnauthorized, "invalid signing method")
		}
		return []byte(m.config.Auth.JWTSecret), nil
	})

	if err != nil || !token.Valid {
		return c.Status(fiber.StatusUnauthorized).JSON(fiber.Map{
			"error": "invalid or expired token",
		})
	}

	// Extract claims
	claims, ok := token.Claims.(jwt.MapClaims)
	if !ok {
		return c.Status(fiber.StatusUnauthorized).JSON(fiber.Map{
			"error": "invalid token claims",
		})
	}

	// Extract userID (sub claim)
	userID, ok := claims["sub"].(string)
	if !ok {
		return c.Status(fiber.StatusUnauthorized).JSON(fiber.Map{
			"error": "missing user ID in token",
		})
	}

	// Extract username claim
	username, ok := claims["username"].(string)
	if !ok {
		return c.Status(fiber.StatusUnauthorized).JSON(fiber.Map{
			"error": "missing username in token",
		})
	}

	// Store claims in fiber.Ctx.Locals
	c.Locals("userID", userID)
	c.Locals("username", username)

	// Continue to next handler
	return c.Next()
}
