package project_test

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/devnogari/claude-code-native/backend/internal/project"
	"github.com/gofiber/fiber/v2"
	"github.com/gofrs/uuid/v5"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
	"go.uber.org/zap"
)

// MockProjectRepository implements ProjectRepository for testing
type MockProjectRepository struct {
	projects        map[uuid.UUID]*project.Project
	createErr       error
	findByIDErr     error
	findByUserIDErr error
	updateErr       error
	deleteErr       error
}

func NewMockProjectRepository() *MockProjectRepository {
	return &MockProjectRepository{
		projects: make(map[uuid.UUID]*project.Project),
	}
}

func (m *MockProjectRepository) Create(ctx context.Context, p *project.Project) error {
	if m.createErr != nil {
		return m.createErr
	}
	if p.ID == uuid.Nil {
		id, _ := uuid.NewV7()
		p.ID = id
	}
	p.CreatedAt = time.Now()
	p.UpdatedAt = time.Now()
	m.projects[p.ID] = p
	return nil
}

func (m *MockProjectRepository) FindByID(ctx context.Context, id uuid.UUID) (*project.Project, error) {
	if m.findByIDErr != nil {
		return nil, m.findByIDErr
	}
	if p, ok := m.projects[id]; ok {
		return p, nil
	}
	return nil, errors.New("sql: no rows in result set")
}

func (m *MockProjectRepository) FindByUserID(ctx context.Context, userID uuid.UUID) ([]*project.Project, error) {
	if m.findByUserIDErr != nil {
		return nil, m.findByUserIDErr
	}
	var result []*project.Project
	for _, p := range m.projects {
		if p.UserID == userID {
			result = append(result, p)
		}
	}
	return result, nil
}

func (m *MockProjectRepository) Update(ctx context.Context, p *project.Project) error {
	if m.updateErr != nil {
		return m.updateErr
	}
	if _, ok := m.projects[p.ID]; !ok {
		return errors.New("project not found")
	}
	p.UpdatedAt = time.Now()
	m.projects[p.ID] = p
	return nil
}

func (m *MockProjectRepository) Delete(ctx context.Context, id uuid.UUID) error {
	if m.deleteErr != nil {
		return m.deleteErr
	}
	delete(m.projects, id)
	return nil
}

func (m *MockProjectRepository) UpdateLastAccessed(ctx context.Context, id uuid.UUID) error {
	if p, ok := m.projects[id]; ok {
		now := time.Now()
		p.LastAccessed = &now
	}
	return nil
}

func (m *MockProjectRepository) AddProject(p *project.Project) {
	m.projects[p.ID] = p
}

// Helper to create test app with injected user context
func setupTestAppWithUser(mockRepo *MockProjectRepository, userID, username string) *fiber.App {
	handler := project.NewHandler(mockRepo, zap.NewNop())

	app := fiber.New()

	// Inject user context
	app.Use(func(c *fiber.Ctx) error {
		c.Locals("userID", userID)
		c.Locals("username", username)
		return c.Next()
	})

	// Register routes
	projects := app.Group("/api/v1/projects")
	projects.Post("/", handler.Create)
	projects.Get("/", handler.List)
	projects.Get("/:id", handler.Get)
	projects.Put("/:id", handler.Update)
	projects.Delete("/:id", handler.Delete)

	return app
}

// --- Create Project Tests ---

func TestCreateProject_Success(t *testing.T) {
	mockRepo := NewMockProjectRepository()
	userID, _ := uuid.NewV7()
	app := setupTestAppWithUser(mockRepo, userID.String(), "testuser")

	reqBody := project.CreateProjectRequest{
		Name: "My Project",
		Path: "/home/user/projects/my-project",
	}
	body, _ := json.Marshal(reqBody)

	req := httptest.NewRequest(http.MethodPost, "/api/v1/projects", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")

	resp, err := app.Test(req)
	require.NoError(t, err)
	assert.Equal(t, http.StatusCreated, resp.StatusCode)

	var projectResp project.ProjectResponse
	err = json.NewDecoder(resp.Body).Decode(&projectResp)
	require.NoError(t, err)

	assert.NotEmpty(t, projectResp.ID)
	assert.Equal(t, "My Project", projectResp.Name)
	assert.Equal(t, "/home/user/projects/my-project", projectResp.Path)
	assert.NotEmpty(t, projectResp.CreatedAt)
	assert.NotEmpty(t, projectResp.UpdatedAt)
}

func TestCreateProject_ValidationError_MissingName(t *testing.T) {
	mockRepo := NewMockProjectRepository()
	userID, _ := uuid.NewV7()
	app := setupTestAppWithUser(mockRepo, userID.String(), "testuser")

	reqBody := project.CreateProjectRequest{
		Name: "",
		Path: "/home/user/projects/my-project",
	}
	body, _ := json.Marshal(reqBody)

	req := httptest.NewRequest(http.MethodPost, "/api/v1/projects", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")

	resp, err := app.Test(req)
	require.NoError(t, err)
	assert.Equal(t, http.StatusBadRequest, resp.StatusCode)

	var errResp project.ErrorResponse
	err = json.NewDecoder(resp.Body).Decode(&errResp)
	require.NoError(t, err)
	assert.Contains(t, errResp.Error, "name")
}

func TestCreateProject_ValidationError_MissingPath(t *testing.T) {
	mockRepo := NewMockProjectRepository()
	userID, _ := uuid.NewV7()
	app := setupTestAppWithUser(mockRepo, userID.String(), "testuser")

	reqBody := project.CreateProjectRequest{
		Name: "My Project",
		Path: "",
	}
	body, _ := json.Marshal(reqBody)

	req := httptest.NewRequest(http.MethodPost, "/api/v1/projects", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")

	resp, err := app.Test(req)
	require.NoError(t, err)
	assert.Equal(t, http.StatusBadRequest, resp.StatusCode)

	var errResp project.ErrorResponse
	err = json.NewDecoder(resp.Body).Decode(&errResp)
	require.NoError(t, err)
	assert.Contains(t, errResp.Error, "path")
}

func TestCreateProject_InvalidJSON(t *testing.T) {
	mockRepo := NewMockProjectRepository()
	userID, _ := uuid.NewV7()
	app := setupTestAppWithUser(mockRepo, userID.String(), "testuser")

	req := httptest.NewRequest(http.MethodPost, "/api/v1/projects", bytes.NewReader([]byte("invalid json")))
	req.Header.Set("Content-Type", "application/json")

	resp, err := app.Test(req)
	require.NoError(t, err)
	assert.Equal(t, http.StatusBadRequest, resp.StatusCode)
}

func TestCreateProject_MissingUserID(t *testing.T) {
	mockRepo := NewMockProjectRepository()
	app := setupTestAppWithUser(mockRepo, "", "testuser") // Empty userID

	reqBody := project.CreateProjectRequest{
		Name: "My Project",
		Path: "/home/user/projects/my-project",
	}
	body, _ := json.Marshal(reqBody)

	req := httptest.NewRequest(http.MethodPost, "/api/v1/projects", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")

	resp, err := app.Test(req)
	require.NoError(t, err)
	assert.Equal(t, http.StatusUnauthorized, resp.StatusCode)
}

// --- List Projects Tests ---

func TestListProjects_Success(t *testing.T) {
	mockRepo := NewMockProjectRepository()
	userID, _ := uuid.NewV7()
	app := setupTestAppWithUser(mockRepo, userID.String(), "testuser")

	// Add some projects for the user
	proj1ID, _ := uuid.NewV7()
	proj2ID, _ := uuid.NewV7()
	now := time.Now()

	mockRepo.AddProject(&project.Project{
		ID:        proj1ID,
		UserID:    userID,
		Name:      "Project 1",
		Path:      "/path/to/project1",
		CreatedAt: now,
		UpdatedAt: now,
	})
	mockRepo.AddProject(&project.Project{
		ID:        proj2ID,
		UserID:    userID,
		Name:      "Project 2",
		Path:      "/path/to/project2",
		CreatedAt: now,
		UpdatedAt: now,
	})

	req := httptest.NewRequest(http.MethodGet, "/api/v1/projects", nil)
	req.Header.Set("Content-Type", "application/json")

	resp, err := app.Test(req)
	require.NoError(t, err)
	assert.Equal(t, http.StatusOK, resp.StatusCode)

	var projectsResp []project.ProjectResponse
	err = json.NewDecoder(resp.Body).Decode(&projectsResp)
	require.NoError(t, err)

	assert.Len(t, projectsResp, 2)
}

func TestListProjects_EmptyList(t *testing.T) {
	mockRepo := NewMockProjectRepository()
	userID, _ := uuid.NewV7()
	app := setupTestAppWithUser(mockRepo, userID.String(), "testuser")

	req := httptest.NewRequest(http.MethodGet, "/api/v1/projects", nil)
	req.Header.Set("Content-Type", "application/json")

	resp, err := app.Test(req)
	require.NoError(t, err)
	assert.Equal(t, http.StatusOK, resp.StatusCode)

	var projectsResp []project.ProjectResponse
	err = json.NewDecoder(resp.Body).Decode(&projectsResp)
	require.NoError(t, err)

	assert.Empty(t, projectsResp)
}

func TestListProjects_OnlyReturnsUserProjects(t *testing.T) {
	mockRepo := NewMockProjectRepository()
	userID, _ := uuid.NewV7()
	otherUserID, _ := uuid.NewV7()
	app := setupTestAppWithUser(mockRepo, userID.String(), "testuser")

	// Add a project for the current user
	proj1ID, _ := uuid.NewV7()
	now := time.Now()
	mockRepo.AddProject(&project.Project{
		ID:        proj1ID,
		UserID:    userID,
		Name:      "My Project",
		Path:      "/path/to/my-project",
		CreatedAt: now,
		UpdatedAt: now,
	})

	// Add a project for another user
	proj2ID, _ := uuid.NewV7()
	mockRepo.AddProject(&project.Project{
		ID:        proj2ID,
		UserID:    otherUserID,
		Name:      "Other Project",
		Path:      "/path/to/other-project",
		CreatedAt: now,
		UpdatedAt: now,
	})

	req := httptest.NewRequest(http.MethodGet, "/api/v1/projects", nil)
	req.Header.Set("Content-Type", "application/json")

	resp, err := app.Test(req)
	require.NoError(t, err)
	assert.Equal(t, http.StatusOK, resp.StatusCode)

	var projectsResp []project.ProjectResponse
	err = json.NewDecoder(resp.Body).Decode(&projectsResp)
	require.NoError(t, err)

	// Should only return the current user's project
	assert.Len(t, projectsResp, 1)
	assert.Equal(t, "My Project", projectsResp[0].Name)
}

// --- Get Project Tests ---

func TestGetProject_Success(t *testing.T) {
	mockRepo := NewMockProjectRepository()
	userID, _ := uuid.NewV7()
	app := setupTestAppWithUser(mockRepo, userID.String(), "testuser")

	projID, _ := uuid.NewV7()
	now := time.Now()
	claudeID := "claude-123"

	mockRepo.AddProject(&project.Project{
		ID:        projID,
		UserID:    userID,
		Name:      "My Project",
		Path:      "/path/to/project",
		ClaudeID:  &claudeID,
		CreatedAt: now,
		UpdatedAt: now,
	})

	req := httptest.NewRequest(http.MethodGet, "/api/v1/projects/"+projID.String(), nil)
	req.Header.Set("Content-Type", "application/json")

	resp, err := app.Test(req)
	require.NoError(t, err)
	assert.Equal(t, http.StatusOK, resp.StatusCode)

	var projectResp project.ProjectResponse
	err = json.NewDecoder(resp.Body).Decode(&projectResp)
	require.NoError(t, err)

	assert.Equal(t, projID.String(), projectResp.ID)
	assert.Equal(t, "My Project", projectResp.Name)
	assert.Equal(t, "/path/to/project", projectResp.Path)
	assert.NotNil(t, projectResp.ClaudeID)
	assert.Equal(t, "claude-123", *projectResp.ClaudeID)
}

func TestGetProject_NotFound(t *testing.T) {
	mockRepo := NewMockProjectRepository()
	userID, _ := uuid.NewV7()
	app := setupTestAppWithUser(mockRepo, userID.String(), "testuser")

	nonExistentID, _ := uuid.NewV7()

	req := httptest.NewRequest(http.MethodGet, "/api/v1/projects/"+nonExistentID.String(), nil)
	req.Header.Set("Content-Type", "application/json")

	resp, err := app.Test(req)
	require.NoError(t, err)
	assert.Equal(t, http.StatusNotFound, resp.StatusCode)

	var errResp project.ErrorResponse
	err = json.NewDecoder(resp.Body).Decode(&errResp)
	require.NoError(t, err)
	assert.Contains(t, errResp.Error, "not found")
}

func TestGetProject_Unauthorized(t *testing.T) {
	mockRepo := NewMockProjectRepository()
	userID, _ := uuid.NewV7()
	otherUserID, _ := uuid.NewV7()
	app := setupTestAppWithUser(mockRepo, userID.String(), "testuser")

	// Create a project owned by another user
	projID, _ := uuid.NewV7()
	now := time.Now()

	mockRepo.AddProject(&project.Project{
		ID:        projID,
		UserID:    otherUserID, // Different user
		Name:      "Other User Project",
		Path:      "/path/to/project",
		CreatedAt: now,
		UpdatedAt: now,
	})

	req := httptest.NewRequest(http.MethodGet, "/api/v1/projects/"+projID.String(), nil)
	req.Header.Set("Content-Type", "application/json")

	resp, err := app.Test(req)
	require.NoError(t, err)
	assert.Equal(t, http.StatusForbidden, resp.StatusCode)

	var errResp project.ErrorResponse
	err = json.NewDecoder(resp.Body).Decode(&errResp)
	require.NoError(t, err)
	assert.Contains(t, errResp.Error, "forbidden")
}

func TestGetProject_InvalidID(t *testing.T) {
	mockRepo := NewMockProjectRepository()
	userID, _ := uuid.NewV7()
	app := setupTestAppWithUser(mockRepo, userID.String(), "testuser")

	req := httptest.NewRequest(http.MethodGet, "/api/v1/projects/invalid-uuid", nil)
	req.Header.Set("Content-Type", "application/json")

	resp, err := app.Test(req)
	require.NoError(t, err)
	assert.Equal(t, http.StatusBadRequest, resp.StatusCode)
}

// --- Update Project Tests ---

func TestUpdateProject_Success(t *testing.T) {
	mockRepo := NewMockProjectRepository()
	userID, _ := uuid.NewV7()
	app := setupTestAppWithUser(mockRepo, userID.String(), "testuser")

	projID, _ := uuid.NewV7()
	now := time.Now()

	mockRepo.AddProject(&project.Project{
		ID:        projID,
		UserID:    userID,
		Name:      "Old Name",
		Path:      "/path/to/project",
		CreatedAt: now,
		UpdatedAt: now,
	})

	reqBody := project.UpdateProjectRequest{
		Name: "New Name",
	}
	body, _ := json.Marshal(reqBody)

	req := httptest.NewRequest(http.MethodPut, "/api/v1/projects/"+projID.String(), bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")

	resp, err := app.Test(req)
	require.NoError(t, err)
	assert.Equal(t, http.StatusOK, resp.StatusCode)

	var projectResp project.ProjectResponse
	err = json.NewDecoder(resp.Body).Decode(&projectResp)
	require.NoError(t, err)

	assert.Equal(t, "New Name", projectResp.Name)
	assert.Equal(t, "/path/to/project", projectResp.Path) // Path should not change
}

func TestUpdateProject_ValidationError_EmptyName(t *testing.T) {
	mockRepo := NewMockProjectRepository()
	userID, _ := uuid.NewV7()
	app := setupTestAppWithUser(mockRepo, userID.String(), "testuser")

	projID, _ := uuid.NewV7()
	now := time.Now()

	mockRepo.AddProject(&project.Project{
		ID:        projID,
		UserID:    userID,
		Name:      "Old Name",
		Path:      "/path/to/project",
		CreatedAt: now,
		UpdatedAt: now,
	})

	reqBody := project.UpdateProjectRequest{
		Name: "",
	}
	body, _ := json.Marshal(reqBody)

	req := httptest.NewRequest(http.MethodPut, "/api/v1/projects/"+projID.String(), bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")

	resp, err := app.Test(req)
	require.NoError(t, err)
	assert.Equal(t, http.StatusBadRequest, resp.StatusCode)
}

func TestUpdateProject_NotFound(t *testing.T) {
	mockRepo := NewMockProjectRepository()
	userID, _ := uuid.NewV7()
	app := setupTestAppWithUser(mockRepo, userID.String(), "testuser")

	nonExistentID, _ := uuid.NewV7()

	reqBody := project.UpdateProjectRequest{
		Name: "New Name",
	}
	body, _ := json.Marshal(reqBody)

	req := httptest.NewRequest(http.MethodPut, "/api/v1/projects/"+nonExistentID.String(), bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")

	resp, err := app.Test(req)
	require.NoError(t, err)
	assert.Equal(t, http.StatusNotFound, resp.StatusCode)
}

func TestUpdateProject_Unauthorized(t *testing.T) {
	mockRepo := NewMockProjectRepository()
	userID, _ := uuid.NewV7()
	otherUserID, _ := uuid.NewV7()
	app := setupTestAppWithUser(mockRepo, userID.String(), "testuser")

	projID, _ := uuid.NewV7()
	now := time.Now()

	mockRepo.AddProject(&project.Project{
		ID:        projID,
		UserID:    otherUserID, // Different user
		Name:      "Other User Project",
		Path:      "/path/to/project",
		CreatedAt: now,
		UpdatedAt: now,
	})

	reqBody := project.UpdateProjectRequest{
		Name: "New Name",
	}
	body, _ := json.Marshal(reqBody)

	req := httptest.NewRequest(http.MethodPut, "/api/v1/projects/"+projID.String(), bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")

	resp, err := app.Test(req)
	require.NoError(t, err)
	assert.Equal(t, http.StatusForbidden, resp.StatusCode)
}

// --- Delete Project Tests ---

func TestDeleteProject_Success(t *testing.T) {
	mockRepo := NewMockProjectRepository()
	userID, _ := uuid.NewV7()
	app := setupTestAppWithUser(mockRepo, userID.String(), "testuser")

	projID, _ := uuid.NewV7()
	now := time.Now()

	mockRepo.AddProject(&project.Project{
		ID:        projID,
		UserID:    userID,
		Name:      "My Project",
		Path:      "/path/to/project",
		CreatedAt: now,
		UpdatedAt: now,
	})

	req := httptest.NewRequest(http.MethodDelete, "/api/v1/projects/"+projID.String(), nil)
	req.Header.Set("Content-Type", "application/json")

	resp, err := app.Test(req)
	require.NoError(t, err)
	assert.Equal(t, http.StatusNoContent, resp.StatusCode)

	// Verify project is deleted
	_, exists := mockRepo.projects[projID]
	assert.False(t, exists)
}

func TestDeleteProject_NotFound(t *testing.T) {
	mockRepo := NewMockProjectRepository()
	userID, _ := uuid.NewV7()
	app := setupTestAppWithUser(mockRepo, userID.String(), "testuser")

	nonExistentID, _ := uuid.NewV7()

	req := httptest.NewRequest(http.MethodDelete, "/api/v1/projects/"+nonExistentID.String(), nil)
	req.Header.Set("Content-Type", "application/json")

	resp, err := app.Test(req)
	require.NoError(t, err)
	assert.Equal(t, http.StatusNotFound, resp.StatusCode)
}

func TestDeleteProject_Unauthorized(t *testing.T) {
	mockRepo := NewMockProjectRepository()
	userID, _ := uuid.NewV7()
	otherUserID, _ := uuid.NewV7()
	app := setupTestAppWithUser(mockRepo, userID.String(), "testuser")

	projID, _ := uuid.NewV7()
	now := time.Now()

	mockRepo.AddProject(&project.Project{
		ID:        projID,
		UserID:    otherUserID, // Different user
		Name:      "Other User Project",
		Path:      "/path/to/project",
		CreatedAt: now,
		UpdatedAt: now,
	})

	req := httptest.NewRequest(http.MethodDelete, "/api/v1/projects/"+projID.String(), nil)
	req.Header.Set("Content-Type", "application/json")

	resp, err := app.Test(req)
	require.NoError(t, err)
	assert.Equal(t, http.StatusForbidden, resp.StatusCode)

	// Verify project still exists
	_, exists := mockRepo.projects[projID]
	assert.True(t, exists)
}

func TestDeleteProject_InvalidID(t *testing.T) {
	mockRepo := NewMockProjectRepository()
	userID, _ := uuid.NewV7()
	app := setupTestAppWithUser(mockRepo, userID.String(), "testuser")

	req := httptest.NewRequest(http.MethodDelete, "/api/v1/projects/invalid-uuid", nil)
	req.Header.Set("Content-Type", "application/json")

	resp, err := app.Test(req)
	require.NoError(t, err)
	assert.Equal(t, http.StatusBadRequest, resp.StatusCode)
}
