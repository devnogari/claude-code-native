package auth_test

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/devnogari/claude-code-native/backend/internal/auth"
	"github.com/devnogari/claude-code-native/backend/internal/config"
	"github.com/devnogari/claude-code-native/backend/internal/user"
	"github.com/gofiber/fiber/v2"
	"github.com/gofrs/uuid/v5"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

// MockUserRepository implements a mock for testing
type MockUserRepository struct {
	users       map[string]*user.User
	createErr   error
	findErr     error
	updateErr   error
	lastCreated *user.User
}

func NewMockUserRepository() *MockUserRepository {
	return &MockUserRepository{
		users: make(map[string]*user.User),
	}
}

func (m *MockUserRepository) Create(ctx context.Context, u *user.User) error {
	if m.createErr != nil {
		return m.createErr
	}
	if u.ID == uuid.Nil {
		id, _ := uuid.NewV7()
		u.ID = id
	}
	m.users[u.Username] = u
	m.lastCreated = u
	return nil
}

func (m *MockUserRepository) FindByUsername(ctx context.Context, username string) (*user.User, error) {
	if m.findErr != nil {
		return nil, m.findErr
	}
	if u, ok := m.users[username]; ok {
		return u, nil
	}
	return nil, nil // User not found returns nil, nil
}

func (m *MockUserRepository) UpdateLastLogin(ctx context.Context, id uuid.UUID) error {
	return m.updateErr
}

func (m *MockUserRepository) AddUser(u *user.User) {
	m.users[u.Username] = u
}

// UserRepository interface for handlers to use
type UserRepository interface {
	Create(ctx context.Context, u *user.User) error
	FindByUsername(ctx context.Context, username string) (*user.User, error)
	UpdateLastLogin(ctx context.Context, id uuid.UUID) error
}

func setupTestApp(mockRepo *MockUserRepository) (*fiber.App, *auth.Service) {
	cfg := &config.Config{
		Auth: config.AuthConfig{
			JWTSecret:     "testsecret12345678901234567890123",
			JWTExpiryDays: 7,
		},
	}
	svc := auth.NewService(cfg, nil)
	handler := auth.NewHandler(svc, mockRepo)

	app := fiber.New()
	app.Post("/api/v1/auth/register", handler.Register)
	app.Post("/api/v1/auth/login", handler.Login)

	return app, svc
}

// --- Register Tests ---

func TestRegister_Success(t *testing.T) {
	mockRepo := NewMockUserRepository()
	app, _ := setupTestApp(mockRepo)

	reqBody := auth.RegisterRequest{
		Username:        "testuser",
		Password:        "Password123",
		ConfirmPassword: "Password123",
	}
	body, _ := json.Marshal(reqBody)

	req := httptest.NewRequest(http.MethodPost, "/api/v1/auth/register", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")

	resp, err := app.Test(req)
	require.NoError(t, err)
	assert.Equal(t, http.StatusCreated, resp.StatusCode)

	var tokenResp auth.TokenResponse
	err = json.NewDecoder(resp.Body).Decode(&tokenResp)
	require.NoError(t, err)

	assert.NotEmpty(t, tokenResp.Token)
	assert.Greater(t, tokenResp.ExpiresAt, int64(0))
	assert.Equal(t, "testuser", tokenResp.User.Username)
	assert.NotEmpty(t, tokenResp.User.ID)
}

func TestRegister_ValidationError_MissingUsername(t *testing.T) {
	mockRepo := NewMockUserRepository()
	app, _ := setupTestApp(mockRepo)

	reqBody := auth.RegisterRequest{
		Username:        "",
		Password:        "Password123",
		ConfirmPassword: "Password123",
	}
	body, _ := json.Marshal(reqBody)

	req := httptest.NewRequest(http.MethodPost, "/api/v1/auth/register", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")

	resp, err := app.Test(req)
	require.NoError(t, err)
	assert.Equal(t, http.StatusBadRequest, resp.StatusCode)

	var errResp auth.ErrorResponse
	err = json.NewDecoder(resp.Body).Decode(&errResp)
	require.NoError(t, err)
	assert.Contains(t, errResp.Error, "username")
}

func TestRegister_ValidationError_PasswordMismatch(t *testing.T) {
	mockRepo := NewMockUserRepository()
	app, _ := setupTestApp(mockRepo)

	reqBody := auth.RegisterRequest{
		Username:        "testuser",
		Password:        "Password123",
		ConfirmPassword: "Different123",
	}
	body, _ := json.Marshal(reqBody)

	req := httptest.NewRequest(http.MethodPost, "/api/v1/auth/register", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")

	resp, err := app.Test(req)
	require.NoError(t, err)
	assert.Equal(t, http.StatusBadRequest, resp.StatusCode)

	var errResp auth.ErrorResponse
	err = json.NewDecoder(resp.Body).Decode(&errResp)
	require.NoError(t, err)
	assert.Contains(t, errResp.Error, "passwords do not match")
}

func TestRegister_ValidationError_ShortPassword(t *testing.T) {
	mockRepo := NewMockUserRepository()
	app, _ := setupTestApp(mockRepo)

	reqBody := auth.RegisterRequest{
		Username:        "testuser",
		Password:        "short",
		ConfirmPassword: "short",
	}
	body, _ := json.Marshal(reqBody)

	req := httptest.NewRequest(http.MethodPost, "/api/v1/auth/register", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")

	resp, err := app.Test(req)
	require.NoError(t, err)
	assert.Equal(t, http.StatusBadRequest, resp.StatusCode)

	var errResp auth.ErrorResponse
	err = json.NewDecoder(resp.Body).Decode(&errResp)
	require.NoError(t, err)
	assert.Contains(t, errResp.Error, "password must be at least 8 characters")
}

func TestRegister_DuplicateUsername(t *testing.T) {
	mockRepo := NewMockUserRepository()
	app, _ := setupTestApp(mockRepo)

	// Add existing user
	existingUser := &user.User{
		Username:     "existinguser",
		PasswordHash: "somehash",
	}
	mockRepo.AddUser(existingUser)

	reqBody := auth.RegisterRequest{
		Username:        "existinguser",
		Password:        "Password123",
		ConfirmPassword: "Password123",
	}
	body, _ := json.Marshal(reqBody)

	req := httptest.NewRequest(http.MethodPost, "/api/v1/auth/register", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")

	resp, err := app.Test(req)
	require.NoError(t, err)
	assert.Equal(t, http.StatusConflict, resp.StatusCode)

	var errResp auth.ErrorResponse
	err = json.NewDecoder(resp.Body).Decode(&errResp)
	require.NoError(t, err)
	assert.Contains(t, errResp.Error, "username already exists")
}

func TestRegister_InvalidJSON(t *testing.T) {
	mockRepo := NewMockUserRepository()
	app, _ := setupTestApp(mockRepo)

	req := httptest.NewRequest(http.MethodPost, "/api/v1/auth/register", bytes.NewReader([]byte("invalid json")))
	req.Header.Set("Content-Type", "application/json")

	resp, err := app.Test(req)
	require.NoError(t, err)
	assert.Equal(t, http.StatusBadRequest, resp.StatusCode)
}

// --- Login Tests ---

func TestLogin_Success(t *testing.T) {
	mockRepo := NewMockUserRepository()
	app, svc := setupTestApp(mockRepo)

	// Create a user with hashed password
	hashedPassword, _ := svc.HashPassword("password123")
	id, _ := uuid.NewV7()
	existingUser := &user.User{
		ID:           id,
		Username:     "testuser",
		PasswordHash: hashedPassword,
	}
	mockRepo.AddUser(existingUser)

	reqBody := auth.LoginRequest{
		Username: "testuser",
		Password: "password123",
	}
	body, _ := json.Marshal(reqBody)

	req := httptest.NewRequest(http.MethodPost, "/api/v1/auth/login", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")

	resp, err := app.Test(req)
	require.NoError(t, err)
	assert.Equal(t, http.StatusOK, resp.StatusCode)

	var tokenResp auth.TokenResponse
	err = json.NewDecoder(resp.Body).Decode(&tokenResp)
	require.NoError(t, err)

	assert.NotEmpty(t, tokenResp.Token)
	assert.Greater(t, tokenResp.ExpiresAt, int64(0))
	assert.Equal(t, "testuser", tokenResp.User.Username)
	assert.Equal(t, id.String(), tokenResp.User.ID)
}

func TestLogin_ValidationError_MissingUsername(t *testing.T) {
	mockRepo := NewMockUserRepository()
	app, _ := setupTestApp(mockRepo)

	reqBody := auth.LoginRequest{
		Username: "",
		Password: "password123",
	}
	body, _ := json.Marshal(reqBody)

	req := httptest.NewRequest(http.MethodPost, "/api/v1/auth/login", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")

	resp, err := app.Test(req)
	require.NoError(t, err)
	assert.Equal(t, http.StatusBadRequest, resp.StatusCode)

	var errResp auth.ErrorResponse
	err = json.NewDecoder(resp.Body).Decode(&errResp)
	require.NoError(t, err)
	assert.Contains(t, errResp.Error, "username")
}

func TestLogin_ValidationError_MissingPassword(t *testing.T) {
	mockRepo := NewMockUserRepository()
	app, _ := setupTestApp(mockRepo)

	reqBody := auth.LoginRequest{
		Username: "testuser",
		Password: "",
	}
	body, _ := json.Marshal(reqBody)

	req := httptest.NewRequest(http.MethodPost, "/api/v1/auth/login", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")

	resp, err := app.Test(req)
	require.NoError(t, err)
	assert.Equal(t, http.StatusBadRequest, resp.StatusCode)

	var errResp auth.ErrorResponse
	err = json.NewDecoder(resp.Body).Decode(&errResp)
	require.NoError(t, err)
	assert.Contains(t, errResp.Error, "password")
}

func TestLogin_UserNotFound(t *testing.T) {
	mockRepo := NewMockUserRepository()
	app, _ := setupTestApp(mockRepo)

	reqBody := auth.LoginRequest{
		Username: "nonexistent",
		Password: "password123",
	}
	body, _ := json.Marshal(reqBody)

	req := httptest.NewRequest(http.MethodPost, "/api/v1/auth/login", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")

	resp, err := app.Test(req)
	require.NoError(t, err)
	assert.Equal(t, http.StatusUnauthorized, resp.StatusCode)

	var errResp auth.ErrorResponse
	err = json.NewDecoder(resp.Body).Decode(&errResp)
	require.NoError(t, err)
	assert.Contains(t, errResp.Error, "invalid credentials")
}

func TestLogin_WrongPassword(t *testing.T) {
	mockRepo := NewMockUserRepository()
	app, svc := setupTestApp(mockRepo)

	// Create a user with hashed password
	hashedPassword, _ := svc.HashPassword("correctpassword")
	id, _ := uuid.NewV7()
	existingUser := &user.User{
		ID:           id,
		Username:     "testuser",
		PasswordHash: hashedPassword,
	}
	mockRepo.AddUser(existingUser)

	reqBody := auth.LoginRequest{
		Username: "testuser",
		Password: "wrongpassword",
	}
	body, _ := json.Marshal(reqBody)

	req := httptest.NewRequest(http.MethodPost, "/api/v1/auth/login", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")

	resp, err := app.Test(req)
	require.NoError(t, err)
	assert.Equal(t, http.StatusUnauthorized, resp.StatusCode)

	var errResp auth.ErrorResponse
	err = json.NewDecoder(resp.Body).Decode(&errResp)
	require.NoError(t, err)
	assert.Contains(t, errResp.Error, "invalid credentials")
}

func TestLogin_InvalidJSON(t *testing.T) {
	mockRepo := NewMockUserRepository()
	app, _ := setupTestApp(mockRepo)

	req := httptest.NewRequest(http.MethodPost, "/api/v1/auth/login", bytes.NewReader([]byte("invalid json")))
	req.Header.Set("Content-Type", "application/json")

	resp, err := app.Test(req)
	require.NoError(t, err)
	assert.Equal(t, http.StatusBadRequest, resp.StatusCode)
}

// --- JWT Token Tests ---

func TestGenerateToken(t *testing.T) {
	cfg := &config.Config{
		Auth: config.AuthConfig{
			JWTSecret:     "testsecret12345678901234567890123",
			JWTExpiryDays: 7,
		},
	}
	svc := auth.NewService(cfg, nil)

	token, expiresAt, err := svc.GenerateToken("user-123", "testuser")
	require.NoError(t, err)
	assert.NotEmpty(t, token)
	assert.Greater(t, expiresAt, int64(0))
}
