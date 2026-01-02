package hook

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"net/http/httptest"
	"testing"

	"github.com/gofiber/fiber/v2"
	"github.com/gofrs/uuid/v5"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

// mockProjectRepo implements ProjectRepository for testing
type mockProjectRepo struct {
	findByPathAnyUserFn func(ctx context.Context, path string) (*Project, error)
	markCompletedFn     func(ctx context.Context, id uuid.UUID, completed bool) error
}

func (m *mockProjectRepo) FindByPathAnyUser(ctx context.Context, path string) (*Project, error) {
	if m.findByPathAnyUserFn != nil {
		return m.findByPathAnyUserFn(ctx, path)
	}
	return nil, errors.New("not implemented")
}

func (m *mockProjectRepo) MarkCompleted(ctx context.Context, id uuid.UUID, completed bool) error {
	if m.markCompletedFn != nil {
		return m.markCompletedFn(ctx, id, completed)
	}
	return errors.New("not implemented")
}

func setupTestApp(handler *Handler) *fiber.App {
	app := fiber.New()
	api := app.Group("/api/v1/hooks")
	api.Post("/session-complete", handler.SessionComplete)
	api.Post("/session-reopen", handler.SessionReopen)
	return app
}

func TestSessionComplete_Success(t *testing.T) {
	projectID, _ := uuid.NewV7()
	project := &Project{
		ID:          projectID,
		Name:        "Test Project",
		Path:        "/Users/test/project",
		IsCompleted: false,
	}

	repo := &mockProjectRepo{
		findByPathAnyUserFn: func(ctx context.Context, path string) (*Project, error) {
			if path == "/Users/test/project" {
				return project, nil
			}
			return nil, errors.New("sql: no rows in result set")
		},
		markCompletedFn: func(ctx context.Context, id uuid.UUID, completed bool) error {
			assert.Equal(t, projectID, id)
			assert.True(t, completed)
			return nil
		},
	}

	handler := NewHandler(repo, "")
	app := setupTestApp(handler)

	reqBody := SessionCompleteRequest{
		SessionID:      "abc123",
		TranscriptPath: "/path/to/transcript.txt",
		Cwd:            "/Users/test/project",
		PermissionMode: "ask",
		HookEventName:  "Stop",
	}
	body, _ := json.Marshal(reqBody)

	req := httptest.NewRequest("POST", "/api/v1/hooks/session-complete", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")

	resp, err := app.Test(req)
	require.NoError(t, err)
	assert.Equal(t, fiber.StatusOK, resp.StatusCode)

	var response SessionCompleteResponse
	err = json.NewDecoder(resp.Body).Decode(&response)
	require.NoError(t, err)

	assert.True(t, response.Success)
	assert.Equal(t, projectID.String(), response.ProjectID)
	assert.Equal(t, "Test Project", response.ProjectName)
}

func TestSessionComplete_WithAPIKey_Success(t *testing.T) {
	projectID, _ := uuid.NewV7()
	project := &Project{
		ID:   projectID,
		Name: "Test Project",
		Path: "/Users/test/project",
	}

	repo := &mockProjectRepo{
		findByPathAnyUserFn: func(ctx context.Context, path string) (*Project, error) {
			return project, nil
		},
		markCompletedFn: func(ctx context.Context, id uuid.UUID, completed bool) error {
			return nil
		},
	}

	handler := NewHandler(repo, "secret-api-key")
	app := setupTestApp(handler)

	reqBody := SessionCompleteRequest{
		SessionID: "abc123",
		Cwd:       "/Users/test/project",
	}
	body, _ := json.Marshal(reqBody)

	req := httptest.NewRequest("POST", "/api/v1/hooks/session-complete", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-API-Key", "secret-api-key")

	resp, err := app.Test(req)
	require.NoError(t, err)
	assert.Equal(t, fiber.StatusOK, resp.StatusCode)
}

func TestSessionComplete_WithBearerToken_Success(t *testing.T) {
	projectID, _ := uuid.NewV7()
	project := &Project{
		ID:   projectID,
		Name: "Test Project",
		Path: "/Users/test/project",
	}

	repo := &mockProjectRepo{
		findByPathAnyUserFn: func(ctx context.Context, path string) (*Project, error) {
			return project, nil
		},
		markCompletedFn: func(ctx context.Context, id uuid.UUID, completed bool) error {
			return nil
		},
	}

	handler := NewHandler(repo, "secret-api-key")
	app := setupTestApp(handler)

	reqBody := SessionCompleteRequest{
		SessionID: "abc123",
		Cwd:       "/Users/test/project",
	}
	body, _ := json.Marshal(reqBody)

	req := httptest.NewRequest("POST", "/api/v1/hooks/session-complete", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer secret-api-key")

	resp, err := app.Test(req)
	require.NoError(t, err)
	assert.Equal(t, fiber.StatusOK, resp.StatusCode)
}

func TestSessionComplete_InvalidAPIKey(t *testing.T) {
	repo := &mockProjectRepo{}
	handler := NewHandler(repo, "secret-api-key")
	app := setupTestApp(handler)

	reqBody := SessionCompleteRequest{
		SessionID: "abc123",
		Cwd:       "/Users/test/project",
	}
	body, _ := json.Marshal(reqBody)

	req := httptest.NewRequest("POST", "/api/v1/hooks/session-complete", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-API-Key", "wrong-key")

	resp, err := app.Test(req)
	require.NoError(t, err)
	assert.Equal(t, fiber.StatusUnauthorized, resp.StatusCode)
}

func TestSessionComplete_MissingAPIKey(t *testing.T) {
	repo := &mockProjectRepo{}
	handler := NewHandler(repo, "secret-api-key")
	app := setupTestApp(handler)

	reqBody := SessionCompleteRequest{
		SessionID: "abc123",
		Cwd:       "/Users/test/project",
	}
	body, _ := json.Marshal(reqBody)

	req := httptest.NewRequest("POST", "/api/v1/hooks/session-complete", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")

	resp, err := app.Test(req)
	require.NoError(t, err)
	assert.Equal(t, fiber.StatusUnauthorized, resp.StatusCode)
}

func TestSessionComplete_MissingSessionID(t *testing.T) {
	repo := &mockProjectRepo{}
	handler := NewHandler(repo, "")
	app := setupTestApp(handler)

	reqBody := SessionCompleteRequest{
		Cwd: "/Users/test/project",
	}
	body, _ := json.Marshal(reqBody)

	req := httptest.NewRequest("POST", "/api/v1/hooks/session-complete", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")

	resp, err := app.Test(req)
	require.NoError(t, err)
	assert.Equal(t, fiber.StatusBadRequest, resp.StatusCode)

	var errResp ErrorResponse
	err = json.NewDecoder(resp.Body).Decode(&errResp)
	require.NoError(t, err)
	assert.Equal(t, "session_id is required", errResp.Error)
}

func TestSessionComplete_MissingCwd(t *testing.T) {
	repo := &mockProjectRepo{}
	handler := NewHandler(repo, "")
	app := setupTestApp(handler)

	reqBody := SessionCompleteRequest{
		SessionID: "abc123",
	}
	body, _ := json.Marshal(reqBody)

	req := httptest.NewRequest("POST", "/api/v1/hooks/session-complete", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")

	resp, err := app.Test(req)
	require.NoError(t, err)
	assert.Equal(t, fiber.StatusBadRequest, resp.StatusCode)

	var errResp ErrorResponse
	err = json.NewDecoder(resp.Body).Decode(&errResp)
	require.NoError(t, err)
	assert.Equal(t, "cwd is required", errResp.Error)
}

func TestSessionComplete_ProjectNotFound(t *testing.T) {
	repo := &mockProjectRepo{
		findByPathAnyUserFn: func(ctx context.Context, path string) (*Project, error) {
			return nil, errors.New("sql: no rows in result set")
		},
	}

	handler := NewHandler(repo, "")
	app := setupTestApp(handler)

	reqBody := SessionCompleteRequest{
		SessionID: "abc123",
		Cwd:       "/Users/test/unknown-project",
	}
	body, _ := json.Marshal(reqBody)

	req := httptest.NewRequest("POST", "/api/v1/hooks/session-complete", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")

	resp, err := app.Test(req)
	require.NoError(t, err)
	assert.Equal(t, fiber.StatusNotFound, resp.StatusCode)
}

func TestSessionReopen_Success(t *testing.T) {
	projectID, _ := uuid.NewV7()
	project := &Project{
		ID:          projectID,
		Name:        "Test Project",
		Path:        "/Users/test/project",
		IsCompleted: true,
	}

	repo := &mockProjectRepo{
		findByPathAnyUserFn: func(ctx context.Context, path string) (*Project, error) {
			return project, nil
		},
		markCompletedFn: func(ctx context.Context, id uuid.UUID, completed bool) error {
			assert.Equal(t, projectID, id)
			assert.False(t, completed) // Should be false for reopen
			return nil
		},
	}

	handler := NewHandler(repo, "")
	app := setupTestApp(handler)

	reqBody := SessionCompleteRequest{
		SessionID: "abc123",
		Cwd:       "/Users/test/project",
	}
	body, _ := json.Marshal(reqBody)

	req := httptest.NewRequest("POST", "/api/v1/hooks/session-reopen", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")

	resp, err := app.Test(req)
	require.NoError(t, err)
	assert.Equal(t, fiber.StatusOK, resp.StatusCode)

	var response SessionCompleteResponse
	err = json.NewDecoder(resp.Body).Decode(&response)
	require.NoError(t, err)

	assert.True(t, response.Success)
	assert.Equal(t, "project reopened", response.Message)
}

func TestSessionComplete_InvalidJSON(t *testing.T) {
	repo := &mockProjectRepo{}
	handler := NewHandler(repo, "")
	app := setupTestApp(handler)

	req := httptest.NewRequest("POST", "/api/v1/hooks/session-complete", bytes.NewReader([]byte("invalid json")))
	req.Header.Set("Content-Type", "application/json")

	resp, err := app.Test(req)
	require.NoError(t, err)
	assert.Equal(t, fiber.StatusBadRequest, resp.StatusCode)
}
