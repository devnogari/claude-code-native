package middleware

import (
	"io"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/devnogari/claude-code-native/backend/internal/auth"
	"github.com/devnogari/claude-code-native/backend/internal/config"
	"github.com/gofiber/fiber/v2"
	"github.com/golang-jwt/jwt/v5"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func setupTestMiddleware() (*AuthMiddleware, *fiber.App, *auth.Service) {
	cfg := &config.Config{
		Auth: config.AuthConfig{
			JWTSecret:     "testsecret12345678901234567890123",
			JWTExpiryDays: 7,
		},
	}
	authSvc := auth.NewService(cfg, nil)
	mw := NewAuthMiddleware(cfg)

	app := fiber.New()
	app.Use("/protected", mw.Authenticate)
	app.Get("/protected/resource", func(c *fiber.Ctx) error {
		userID := c.Locals("userID")
		username := c.Locals("username")
		return c.JSON(fiber.Map{"user_id": userID, "username": username})
	})

	return mw, app, authSvc
}

func TestAuthMiddleware_ValidToken(t *testing.T) {
	mw, app, authSvc := setupTestMiddleware()
	require.NotNil(t, mw)

	// Generate a valid token using the auth service
	token, _, err := authSvc.GenerateToken("user123", "testuser")
	require.NoError(t, err)

	// Make request with valid token
	req := httptest.NewRequest(http.MethodGet, "/protected/resource", nil)
	req.Header.Set("Authorization", "Bearer "+token)

	resp, err := app.Test(req)
	require.NoError(t, err)
	defer resp.Body.Close()

	assert.Equal(t, http.StatusOK, resp.StatusCode)

	body, err := io.ReadAll(resp.Body)
	require.NoError(t, err)
	assert.Contains(t, string(body), "user123")
	assert.Contains(t, string(body), "testuser")
}

func TestAuthMiddleware_MissingAuthorizationHeader(t *testing.T) {
	mw, app, _ := setupTestMiddleware()
	require.NotNil(t, mw)

	// Make request without Authorization header
	req := httptest.NewRequest(http.MethodGet, "/protected/resource", nil)

	resp, err := app.Test(req)
	require.NoError(t, err)
	defer resp.Body.Close()

	assert.Equal(t, http.StatusUnauthorized, resp.StatusCode)
}

func TestAuthMiddleware_InvalidFormat_NoBearerPrefix(t *testing.T) {
	mw, app, authSvc := setupTestMiddleware()
	require.NotNil(t, mw)

	// Generate a valid token
	token, _, err := authSvc.GenerateToken("user123", "testuser")
	require.NoError(t, err)

	// Make request without "Bearer " prefix
	req := httptest.NewRequest(http.MethodGet, "/protected/resource", nil)
	req.Header.Set("Authorization", token) // Missing "Bearer " prefix

	resp, err := app.Test(req)
	require.NoError(t, err)
	defer resp.Body.Close()

	assert.Equal(t, http.StatusUnauthorized, resp.StatusCode)
}

func TestAuthMiddleware_InvalidToken(t *testing.T) {
	mw, app, _ := setupTestMiddleware()
	require.NotNil(t, mw)

	// Make request with invalid token
	req := httptest.NewRequest(http.MethodGet, "/protected/resource", nil)
	req.Header.Set("Authorization", "Bearer invalid.token.here")

	resp, err := app.Test(req)
	require.NoError(t, err)
	defer resp.Body.Close()

	assert.Equal(t, http.StatusUnauthorized, resp.StatusCode)
}

func TestAuthMiddleware_ExpiredToken(t *testing.T) {
	mw, app, _ := setupTestMiddleware()
	require.NotNil(t, mw)

	cfg := &config.Config{
		Auth: config.AuthConfig{
			JWTSecret:     "testsecret12345678901234567890123",
			JWTExpiryDays: 7,
		},
	}

	// Create an expired token manually
	claims := jwt.MapClaims{
		"sub":      "user123",
		"username": "testuser",
		"exp":      time.Now().Add(-1 * time.Hour).Unix(), // Expired 1 hour ago
		"iat":      time.Now().Add(-2 * time.Hour).Unix(),
	}
	token := jwt.NewWithClaims(jwt.SigningMethodHS256, claims)
	expiredToken, err := token.SignedString([]byte(cfg.Auth.JWTSecret))
	require.NoError(t, err)

	// Make request with expired token
	req := httptest.NewRequest(http.MethodGet, "/protected/resource", nil)
	req.Header.Set("Authorization", "Bearer "+expiredToken)

	resp, err := app.Test(req)
	require.NoError(t, err)
	defer resp.Body.Close()

	assert.Equal(t, http.StatusUnauthorized, resp.StatusCode)
}

func TestAuthMiddleware_MalformedToken(t *testing.T) {
	mw, app, _ := setupTestMiddleware()
	require.NotNil(t, mw)

	testCases := []struct {
		name  string
		token string
	}{
		{"empty token", "Bearer "},
		{"single part", "Bearer abc"},
		{"two parts", "Bearer abc.def"},
		{"random string", "Bearer notavalidjwt"},
		{"just Bearer", "Bearer"},
	}

	for _, tc := range testCases {
		t.Run(tc.name, func(t *testing.T) {
			req := httptest.NewRequest(http.MethodGet, "/protected/resource", nil)
			req.Header.Set("Authorization", tc.token)

			resp, err := app.Test(req)
			require.NoError(t, err)
			defer resp.Body.Close()

			assert.Equal(t, http.StatusUnauthorized, resp.StatusCode, "token: %s", tc.token)
		})
	}
}

func TestAuthMiddleware_WrongSigningKey(t *testing.T) {
	mw, app, _ := setupTestMiddleware()
	require.NotNil(t, mw)

	// Create a token signed with a different secret
	claims := jwt.MapClaims{
		"sub":      "user123",
		"username": "testuser",
		"exp":      time.Now().Add(24 * time.Hour).Unix(),
		"iat":      time.Now().Unix(),
	}
	token := jwt.NewWithClaims(jwt.SigningMethodHS256, claims)
	wrongSecretToken, err := token.SignedString([]byte("different_secret_key_1234567890"))
	require.NoError(t, err)

	// Make request with token signed by wrong key
	req := httptest.NewRequest(http.MethodGet, "/protected/resource", nil)
	req.Header.Set("Authorization", "Bearer "+wrongSecretToken)

	resp, err := app.Test(req)
	require.NoError(t, err)
	defer resp.Body.Close()

	assert.Equal(t, http.StatusUnauthorized, resp.StatusCode)
}

func TestAuthMiddleware_EmptyBearerPrefix(t *testing.T) {
	mw, app, _ := setupTestMiddleware()
	require.NotNil(t, mw)

	// Make request with empty Authorization header
	req := httptest.NewRequest(http.MethodGet, "/protected/resource", nil)
	req.Header.Set("Authorization", "")

	resp, err := app.Test(req)
	require.NoError(t, err)
	defer resp.Body.Close()

	assert.Equal(t, http.StatusUnauthorized, resp.StatusCode)
}

func TestAuthMiddleware_OnlyBearerWord(t *testing.T) {
	mw, app, _ := setupTestMiddleware()
	require.NotNil(t, mw)

	// Make request with just "Bearer" (no space and no token)
	req := httptest.NewRequest(http.MethodGet, "/protected/resource", nil)
	req.Header.Set("Authorization", "Bearer")

	resp, err := app.Test(req)
	require.NoError(t, err)
	defer resp.Body.Close()

	assert.Equal(t, http.StatusUnauthorized, resp.StatusCode)
}
