package conversation_test

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/devnogari/claude-code-native/backend/internal/conversation"
	"github.com/devnogari/claude-code-native/backend/internal/project"
	"github.com/gofiber/fiber/v2"
	"github.com/gofrs/uuid/v5"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
	"go.uber.org/zap"
)

// MockConversationRepository implements ConversationRepository for testing
type MockConversationRepository struct {
	conversations map[uuid.UUID]*conversation.Conversation
	createErr     error
	findByIDErr   error
	findByProjErr error
	updateErr     error
	deleteErr     error
}

func NewMockConversationRepository() *MockConversationRepository {
	return &MockConversationRepository{
		conversations: make(map[uuid.UUID]*conversation.Conversation),
	}
}

func (m *MockConversationRepository) Create(ctx context.Context, c *conversation.Conversation) error {
	if m.createErr != nil {
		return m.createErr
	}
	if c.ID == uuid.Nil {
		id, _ := uuid.NewV7()
		c.ID = id
	}
	c.CreatedAt = time.Now()
	c.UpdatedAt = time.Now()
	m.conversations[c.ID] = c
	return nil
}

func (m *MockConversationRepository) FindByID(ctx context.Context, id uuid.UUID) (*conversation.Conversation, error) {
	if m.findByIDErr != nil {
		return nil, m.findByIDErr
	}
	if c, ok := m.conversations[id]; ok {
		return c, nil
	}
	return nil, errors.New("sql: no rows in result set")
}

func (m *MockConversationRepository) FindByProjectID(ctx context.Context, projectID uuid.UUID) ([]*conversation.Conversation, error) {
	if m.findByProjErr != nil {
		return nil, m.findByProjErr
	}
	var result []*conversation.Conversation
	for _, c := range m.conversations {
		if c.ProjectID == projectID {
			result = append(result, c)
		}
	}
	return result, nil
}

func (m *MockConversationRepository) Update(ctx context.Context, c *conversation.Conversation) error {
	if m.updateErr != nil {
		return m.updateErr
	}
	if _, ok := m.conversations[c.ID]; !ok {
		return errors.New("conversation not found")
	}
	c.UpdatedAt = time.Now()
	m.conversations[c.ID] = c
	return nil
}

func (m *MockConversationRepository) Delete(ctx context.Context, id uuid.UUID) error {
	if m.deleteErr != nil {
		return m.deleteErr
	}
	delete(m.conversations, id)
	return nil
}

func (m *MockConversationRepository) ToggleFavorite(ctx context.Context, id uuid.UUID) (*conversation.Conversation, error) {
	if c, ok := m.conversations[id]; ok {
		c.IsFavorite = !c.IsFavorite
		c.UpdatedAt = time.Now()
		m.conversations[id] = c
		return c, nil
	}
	return nil, errors.New("sql: no rows in result set")
}

func (m *MockConversationRepository) AddConversation(c *conversation.Conversation) {
	m.conversations[c.ID] = c
}

// MockProjectRepository implements ProjectRepository for testing
type MockProjectRepository struct {
	projects    map[uuid.UUID]*project.Project
	findByIDErr error
}

func NewMockProjectRepository() *MockProjectRepository {
	return &MockProjectRepository{
		projects: make(map[uuid.UUID]*project.Project),
	}
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

func (m *MockProjectRepository) AddProject(p *project.Project) {
	m.projects[p.ID] = p
}

// setupTestApp creates a test fiber app with conversation routes
func setupTestAppWithUser(convRepo *MockConversationRepository, projRepo *MockProjectRepository, userID, username string) *fiber.App {
	handler := conversation.NewHandler(convRepo, projRepo, zap.NewNop())

	app := fiber.New()

	// Inject user context
	app.Use(func(c *fiber.Ctx) error {
		c.Locals("userID", userID)
		c.Locals("username", username)
		return c.Next()
	})

	// Register routes
	// Project-scoped conversation routes
	projectConversations := app.Group("/api/v1/projects/:projectId/conversations")
	projectConversations.Post("/", handler.Create)
	projectConversations.Get("/", handler.List)

	// Conversation routes
	conversations := app.Group("/api/v1/conversations")
	conversations.Get("/:id", handler.Get)
	conversations.Put("/:id", handler.Update)
	conversations.Delete("/:id", handler.Delete)

	return app
}

// --- Create Conversation Tests ---

func TestCreateConversation_Success(t *testing.T) {
	convRepo := NewMockConversationRepository()
	projRepo := NewMockProjectRepository()
	userID, _ := uuid.NewV7()
	projectID, _ := uuid.NewV7()

	// Add a project owned by the user
	projRepo.AddProject(&project.Project{
		ID:        projectID,
		UserID:    userID,
		Name:      "Test Project",
		Path:      "/path/to/project",
		CreatedAt: time.Now(),
		UpdatedAt: time.Now(),
	})

	app := setupTestAppWithUser(convRepo, projRepo, userID.String(), "testuser")

	title := "New Conversation"
	reqBody := conversation.CreateConversationRequest{
		ProjectID: projectID.String(),
		Title:     &title,
	}
	body, _ := json.Marshal(reqBody)

	req := httptest.NewRequest(http.MethodPost, "/api/v1/projects/"+projectID.String()+"/conversations", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")

	resp, err := app.Test(req)
	require.NoError(t, err)
	assert.Equal(t, http.StatusCreated, resp.StatusCode)

	var convResp conversation.ConversationResponse
	err = json.NewDecoder(resp.Body).Decode(&convResp)
	require.NoError(t, err)

	assert.NotEmpty(t, convResp.ID)
	assert.Equal(t, projectID.String(), convResp.ProjectID)
	assert.NotNil(t, convResp.Title)
	assert.Equal(t, "New Conversation", *convResp.Title)
	assert.Equal(t, 0, convResp.MessageCount)
	assert.NotEmpty(t, convResp.CreatedAt)
	assert.NotEmpty(t, convResp.UpdatedAt)
}

func TestCreateConversation_ProjectNotFound(t *testing.T) {
	convRepo := NewMockConversationRepository()
	projRepo := NewMockProjectRepository()
	userID, _ := uuid.NewV7()
	nonExistentProjectID, _ := uuid.NewV7()

	app := setupTestAppWithUser(convRepo, projRepo, userID.String(), "testuser")

	reqBody := conversation.CreateConversationRequest{
		ProjectID: nonExistentProjectID.String(),
	}
	body, _ := json.Marshal(reqBody)

	req := httptest.NewRequest(http.MethodPost, "/api/v1/projects/"+nonExistentProjectID.String()+"/conversations", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")

	resp, err := app.Test(req)
	require.NoError(t, err)
	assert.Equal(t, http.StatusNotFound, resp.StatusCode)

	var errResp conversation.ErrorResponse
	err = json.NewDecoder(resp.Body).Decode(&errResp)
	require.NoError(t, err)
	assert.Contains(t, errResp.Error, "not found")
}

func TestCreateConversation_Unauthorized(t *testing.T) {
	convRepo := NewMockConversationRepository()
	projRepo := NewMockProjectRepository()
	userID, _ := uuid.NewV7()
	otherUserID, _ := uuid.NewV7()
	projectID, _ := uuid.NewV7()

	// Add a project owned by another user
	projRepo.AddProject(&project.Project{
		ID:        projectID,
		UserID:    otherUserID, // Different user
		Name:      "Other User Project",
		Path:      "/path/to/project",
		CreatedAt: time.Now(),
		UpdatedAt: time.Now(),
	})

	app := setupTestAppWithUser(convRepo, projRepo, userID.String(), "testuser")

	reqBody := conversation.CreateConversationRequest{
		ProjectID: projectID.String(),
	}
	body, _ := json.Marshal(reqBody)

	req := httptest.NewRequest(http.MethodPost, "/api/v1/projects/"+projectID.String()+"/conversations", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")

	resp, err := app.Test(req)
	require.NoError(t, err)
	assert.Equal(t, http.StatusForbidden, resp.StatusCode)

	var errResp conversation.ErrorResponse
	err = json.NewDecoder(resp.Body).Decode(&errResp)
	require.NoError(t, err)
	assert.Contains(t, errResp.Error, "forbidden")
}

func TestCreateConversation_InvalidProjectID(t *testing.T) {
	convRepo := NewMockConversationRepository()
	projRepo := NewMockProjectRepository()
	userID, _ := uuid.NewV7()

	app := setupTestAppWithUser(convRepo, projRepo, userID.String(), "testuser")

	req := httptest.NewRequest(http.MethodPost, "/api/v1/projects/invalid-uuid/conversations", bytes.NewReader([]byte("{}")))
	req.Header.Set("Content-Type", "application/json")

	resp, err := app.Test(req)
	require.NoError(t, err)
	assert.Equal(t, http.StatusBadRequest, resp.StatusCode)
}

func TestCreateConversation_MissingUserID(t *testing.T) {
	convRepo := NewMockConversationRepository()
	projRepo := NewMockProjectRepository()
	projectID, _ := uuid.NewV7()

	app := setupTestAppWithUser(convRepo, projRepo, "", "testuser") // Empty userID

	reqBody := conversation.CreateConversationRequest{
		ProjectID: projectID.String(),
	}
	body, _ := json.Marshal(reqBody)

	req := httptest.NewRequest(http.MethodPost, "/api/v1/projects/"+projectID.String()+"/conversations", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")

	resp, err := app.Test(req)
	require.NoError(t, err)
	assert.Equal(t, http.StatusUnauthorized, resp.StatusCode)
}

// --- List Conversations Tests ---

func TestListConversations_Success(t *testing.T) {
	convRepo := NewMockConversationRepository()
	projRepo := NewMockProjectRepository()
	userID, _ := uuid.NewV7()
	projectID, _ := uuid.NewV7()

	// Add a project owned by the user
	projRepo.AddProject(&project.Project{
		ID:        projectID,
		UserID:    userID,
		Name:      "Test Project",
		Path:      "/path/to/project",
		CreatedAt: time.Now(),
		UpdatedAt: time.Now(),
	})

	// Add some conversations for the project
	conv1ID, _ := uuid.NewV7()
	conv2ID, _ := uuid.NewV7()
	title1 := "Conversation 1"
	title2 := "Conversation 2"
	now := time.Now()

	convRepo.AddConversation(&conversation.Conversation{
		ID:        conv1ID,
		ProjectID: projectID,
		Title:     &title1,
		CreatedAt: now,
		UpdatedAt: now,
	})
	convRepo.AddConversation(&conversation.Conversation{
		ID:        conv2ID,
		ProjectID: projectID,
		Title:     &title2,
		CreatedAt: now,
		UpdatedAt: now,
	})

	app := setupTestAppWithUser(convRepo, projRepo, userID.String(), "testuser")

	req := httptest.NewRequest(http.MethodGet, "/api/v1/projects/"+projectID.String()+"/conversations", nil)
	req.Header.Set("Content-Type", "application/json")

	resp, err := app.Test(req)
	require.NoError(t, err)
	assert.Equal(t, http.StatusOK, resp.StatusCode)

	var convsResp []conversation.ConversationResponse
	err = json.NewDecoder(resp.Body).Decode(&convsResp)
	require.NoError(t, err)

	assert.Len(t, convsResp, 2)
}

func TestListConversations_EmptyList(t *testing.T) {
	convRepo := NewMockConversationRepository()
	projRepo := NewMockProjectRepository()
	userID, _ := uuid.NewV7()
	projectID, _ := uuid.NewV7()

	// Add a project owned by the user
	projRepo.AddProject(&project.Project{
		ID:        projectID,
		UserID:    userID,
		Name:      "Test Project",
		Path:      "/path/to/project",
		CreatedAt: time.Now(),
		UpdatedAt: time.Now(),
	})

	app := setupTestAppWithUser(convRepo, projRepo, userID.String(), "testuser")

	req := httptest.NewRequest(http.MethodGet, "/api/v1/projects/"+projectID.String()+"/conversations", nil)
	req.Header.Set("Content-Type", "application/json")

	resp, err := app.Test(req)
	require.NoError(t, err)
	assert.Equal(t, http.StatusOK, resp.StatusCode)

	var convsResp []conversation.ConversationResponse
	err = json.NewDecoder(resp.Body).Decode(&convsResp)
	require.NoError(t, err)

	assert.Empty(t, convsResp)
}

func TestListConversations_ProjectNotFound(t *testing.T) {
	convRepo := NewMockConversationRepository()
	projRepo := NewMockProjectRepository()
	userID, _ := uuid.NewV7()
	nonExistentProjectID, _ := uuid.NewV7()

	app := setupTestAppWithUser(convRepo, projRepo, userID.String(), "testuser")

	req := httptest.NewRequest(http.MethodGet, "/api/v1/projects/"+nonExistentProjectID.String()+"/conversations", nil)
	req.Header.Set("Content-Type", "application/json")

	resp, err := app.Test(req)
	require.NoError(t, err)
	assert.Equal(t, http.StatusNotFound, resp.StatusCode)
}

func TestListConversations_Unauthorized(t *testing.T) {
	convRepo := NewMockConversationRepository()
	projRepo := NewMockProjectRepository()
	userID, _ := uuid.NewV7()
	otherUserID, _ := uuid.NewV7()
	projectID, _ := uuid.NewV7()

	// Add a project owned by another user
	projRepo.AddProject(&project.Project{
		ID:        projectID,
		UserID:    otherUserID, // Different user
		Name:      "Other User Project",
		Path:      "/path/to/project",
		CreatedAt: time.Now(),
		UpdatedAt: time.Now(),
	})

	app := setupTestAppWithUser(convRepo, projRepo, userID.String(), "testuser")

	req := httptest.NewRequest(http.MethodGet, "/api/v1/projects/"+projectID.String()+"/conversations", nil)
	req.Header.Set("Content-Type", "application/json")

	resp, err := app.Test(req)
	require.NoError(t, err)
	assert.Equal(t, http.StatusForbidden, resp.StatusCode)
}

// --- Get Conversation Tests ---

func TestGetConversation_Success(t *testing.T) {
	convRepo := NewMockConversationRepository()
	projRepo := NewMockProjectRepository()
	userID, _ := uuid.NewV7()
	projectID, _ := uuid.NewV7()
	convID, _ := uuid.NewV7()

	// Add a project owned by the user
	projRepo.AddProject(&project.Project{
		ID:        projectID,
		UserID:    userID,
		Name:      "Test Project",
		Path:      "/path/to/project",
		CreatedAt: time.Now(),
		UpdatedAt: time.Now(),
	})

	// Add a conversation
	title := "Test Conversation"
	claudeSession := "session-123"
	now := time.Now()
	convRepo.AddConversation(&conversation.Conversation{
		ID:            convID,
		ProjectID:     projectID,
		Title:         &title,
		ClaudeSession: &claudeSession,
		MessageCount:  5,
		CreatedAt:     now,
		UpdatedAt:     now,
	})

	app := setupTestAppWithUser(convRepo, projRepo, userID.String(), "testuser")

	req := httptest.NewRequest(http.MethodGet, "/api/v1/conversations/"+convID.String(), nil)
	req.Header.Set("Content-Type", "application/json")

	resp, err := app.Test(req)
	require.NoError(t, err)
	assert.Equal(t, http.StatusOK, resp.StatusCode)

	var convResp conversation.ConversationResponse
	err = json.NewDecoder(resp.Body).Decode(&convResp)
	require.NoError(t, err)

	assert.Equal(t, convID.String(), convResp.ID)
	assert.Equal(t, projectID.String(), convResp.ProjectID)
	assert.NotNil(t, convResp.Title)
	assert.Equal(t, "Test Conversation", *convResp.Title)
	assert.NotNil(t, convResp.ClaudeSession)
	assert.Equal(t, "session-123", *convResp.ClaudeSession)
	assert.Equal(t, 5, convResp.MessageCount)
}

func TestGetConversation_NotFound(t *testing.T) {
	convRepo := NewMockConversationRepository()
	projRepo := NewMockProjectRepository()
	userID, _ := uuid.NewV7()
	nonExistentID, _ := uuid.NewV7()

	app := setupTestAppWithUser(convRepo, projRepo, userID.String(), "testuser")

	req := httptest.NewRequest(http.MethodGet, "/api/v1/conversations/"+nonExistentID.String(), nil)
	req.Header.Set("Content-Type", "application/json")

	resp, err := app.Test(req)
	require.NoError(t, err)
	assert.Equal(t, http.StatusNotFound, resp.StatusCode)

	var errResp conversation.ErrorResponse
	err = json.NewDecoder(resp.Body).Decode(&errResp)
	require.NoError(t, err)
	assert.Contains(t, errResp.Error, "not found")
}

func TestGetConversation_Unauthorized(t *testing.T) {
	convRepo := NewMockConversationRepository()
	projRepo := NewMockProjectRepository()
	userID, _ := uuid.NewV7()
	otherUserID, _ := uuid.NewV7()
	projectID, _ := uuid.NewV7()
	convID, _ := uuid.NewV7()

	// Add a project owned by another user
	projRepo.AddProject(&project.Project{
		ID:        projectID,
		UserID:    otherUserID, // Different user
		Name:      "Other User Project",
		Path:      "/path/to/project",
		CreatedAt: time.Now(),
		UpdatedAt: time.Now(),
	})

	// Add a conversation for that project
	title := "Other User Conversation"
	now := time.Now()
	convRepo.AddConversation(&conversation.Conversation{
		ID:        convID,
		ProjectID: projectID,
		Title:     &title,
		CreatedAt: now,
		UpdatedAt: now,
	})

	app := setupTestAppWithUser(convRepo, projRepo, userID.String(), "testuser")

	req := httptest.NewRequest(http.MethodGet, "/api/v1/conversations/"+convID.String(), nil)
	req.Header.Set("Content-Type", "application/json")

	resp, err := app.Test(req)
	require.NoError(t, err)
	assert.Equal(t, http.StatusForbidden, resp.StatusCode)

	var errResp conversation.ErrorResponse
	err = json.NewDecoder(resp.Body).Decode(&errResp)
	require.NoError(t, err)
	assert.Contains(t, errResp.Error, "forbidden")
}

func TestGetConversation_InvalidID(t *testing.T) {
	convRepo := NewMockConversationRepository()
	projRepo := NewMockProjectRepository()
	userID, _ := uuid.NewV7()

	app := setupTestAppWithUser(convRepo, projRepo, userID.String(), "testuser")

	req := httptest.NewRequest(http.MethodGet, "/api/v1/conversations/invalid-uuid", nil)
	req.Header.Set("Content-Type", "application/json")

	resp, err := app.Test(req)
	require.NoError(t, err)
	assert.Equal(t, http.StatusBadRequest, resp.StatusCode)
}

// --- Update Conversation Tests ---

func TestUpdateConversation_Success(t *testing.T) {
	convRepo := NewMockConversationRepository()
	projRepo := NewMockProjectRepository()
	userID, _ := uuid.NewV7()
	projectID, _ := uuid.NewV7()
	convID, _ := uuid.NewV7()

	// Add a project owned by the user
	projRepo.AddProject(&project.Project{
		ID:        projectID,
		UserID:    userID,
		Name:      "Test Project",
		Path:      "/path/to/project",
		CreatedAt: time.Now(),
		UpdatedAt: time.Now(),
	})

	// Add a conversation
	oldTitle := "Old Title"
	now := time.Now()
	convRepo.AddConversation(&conversation.Conversation{
		ID:        convID,
		ProjectID: projectID,
		Title:     &oldTitle,
		CreatedAt: now,
		UpdatedAt: now,
	})

	app := setupTestAppWithUser(convRepo, projRepo, userID.String(), "testuser")

	newTitle := "New Title"
	reqBody := conversation.UpdateConversationRequest{
		Title: &newTitle,
	}
	body, _ := json.Marshal(reqBody)

	req := httptest.NewRequest(http.MethodPut, "/api/v1/conversations/"+convID.String(), bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")

	resp, err := app.Test(req)
	require.NoError(t, err)
	assert.Equal(t, http.StatusOK, resp.StatusCode)

	var convResp conversation.ConversationResponse
	err = json.NewDecoder(resp.Body).Decode(&convResp)
	require.NoError(t, err)

	assert.NotNil(t, convResp.Title)
	assert.Equal(t, "New Title", *convResp.Title)
}

func TestUpdateConversation_NotFound(t *testing.T) {
	convRepo := NewMockConversationRepository()
	projRepo := NewMockProjectRepository()
	userID, _ := uuid.NewV7()
	nonExistentID, _ := uuid.NewV7()

	app := setupTestAppWithUser(convRepo, projRepo, userID.String(), "testuser")

	newTitle := "New Title"
	reqBody := conversation.UpdateConversationRequest{
		Title: &newTitle,
	}
	body, _ := json.Marshal(reqBody)

	req := httptest.NewRequest(http.MethodPut, "/api/v1/conversations/"+nonExistentID.String(), bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")

	resp, err := app.Test(req)
	require.NoError(t, err)
	assert.Equal(t, http.StatusNotFound, resp.StatusCode)
}

func TestUpdateConversation_Unauthorized(t *testing.T) {
	convRepo := NewMockConversationRepository()
	projRepo := NewMockProjectRepository()
	userID, _ := uuid.NewV7()
	otherUserID, _ := uuid.NewV7()
	projectID, _ := uuid.NewV7()
	convID, _ := uuid.NewV7()

	// Add a project owned by another user
	projRepo.AddProject(&project.Project{
		ID:        projectID,
		UserID:    otherUserID, // Different user
		Name:      "Other User Project",
		Path:      "/path/to/project",
		CreatedAt: time.Now(),
		UpdatedAt: time.Now(),
	})

	// Add a conversation for that project
	oldTitle := "Old Title"
	now := time.Now()
	convRepo.AddConversation(&conversation.Conversation{
		ID:        convID,
		ProjectID: projectID,
		Title:     &oldTitle,
		CreatedAt: now,
		UpdatedAt: now,
	})

	app := setupTestAppWithUser(convRepo, projRepo, userID.String(), "testuser")

	newTitle := "New Title"
	reqBody := conversation.UpdateConversationRequest{
		Title: &newTitle,
	}
	body, _ := json.Marshal(reqBody)

	req := httptest.NewRequest(http.MethodPut, "/api/v1/conversations/"+convID.String(), bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")

	resp, err := app.Test(req)
	require.NoError(t, err)
	assert.Equal(t, http.StatusForbidden, resp.StatusCode)
}

func TestUpdateConversation_InvalidID(t *testing.T) {
	convRepo := NewMockConversationRepository()
	projRepo := NewMockProjectRepository()
	userID, _ := uuid.NewV7()

	app := setupTestAppWithUser(convRepo, projRepo, userID.String(), "testuser")

	newTitle := "New Title"
	reqBody := conversation.UpdateConversationRequest{
		Title: &newTitle,
	}
	body, _ := json.Marshal(reqBody)

	req := httptest.NewRequest(http.MethodPut, "/api/v1/conversations/invalid-uuid", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")

	resp, err := app.Test(req)
	require.NoError(t, err)
	assert.Equal(t, http.StatusBadRequest, resp.StatusCode)
}

// --- Delete Conversation Tests ---

func TestDeleteConversation_Success(t *testing.T) {
	convRepo := NewMockConversationRepository()
	projRepo := NewMockProjectRepository()
	userID, _ := uuid.NewV7()
	projectID, _ := uuid.NewV7()
	convID, _ := uuid.NewV7()

	// Add a project owned by the user
	projRepo.AddProject(&project.Project{
		ID:        projectID,
		UserID:    userID,
		Name:      "Test Project",
		Path:      "/path/to/project",
		CreatedAt: time.Now(),
		UpdatedAt: time.Now(),
	})

	// Add a conversation
	title := "Test Conversation"
	now := time.Now()
	convRepo.AddConversation(&conversation.Conversation{
		ID:        convID,
		ProjectID: projectID,
		Title:     &title,
		CreatedAt: now,
		UpdatedAt: now,
	})

	app := setupTestAppWithUser(convRepo, projRepo, userID.String(), "testuser")

	req := httptest.NewRequest(http.MethodDelete, "/api/v1/conversations/"+convID.String(), nil)
	req.Header.Set("Content-Type", "application/json")

	resp, err := app.Test(req)
	require.NoError(t, err)
	assert.Equal(t, http.StatusNoContent, resp.StatusCode)

	// Verify conversation is deleted
	_, exists := convRepo.conversations[convID]
	assert.False(t, exists)
}

func TestDeleteConversation_NotFound(t *testing.T) {
	convRepo := NewMockConversationRepository()
	projRepo := NewMockProjectRepository()
	userID, _ := uuid.NewV7()
	nonExistentID, _ := uuid.NewV7()

	app := setupTestAppWithUser(convRepo, projRepo, userID.String(), "testuser")

	req := httptest.NewRequest(http.MethodDelete, "/api/v1/conversations/"+nonExistentID.String(), nil)
	req.Header.Set("Content-Type", "application/json")

	resp, err := app.Test(req)
	require.NoError(t, err)
	assert.Equal(t, http.StatusNotFound, resp.StatusCode)
}

func TestDeleteConversation_Unauthorized(t *testing.T) {
	convRepo := NewMockConversationRepository()
	projRepo := NewMockProjectRepository()
	userID, _ := uuid.NewV7()
	otherUserID, _ := uuid.NewV7()
	projectID, _ := uuid.NewV7()
	convID, _ := uuid.NewV7()

	// Add a project owned by another user
	projRepo.AddProject(&project.Project{
		ID:        projectID,
		UserID:    otherUserID, // Different user
		Name:      "Other User Project",
		Path:      "/path/to/project",
		CreatedAt: time.Now(),
		UpdatedAt: time.Now(),
	})

	// Add a conversation for that project
	title := "Other User Conversation"
	now := time.Now()
	convRepo.AddConversation(&conversation.Conversation{
		ID:        convID,
		ProjectID: projectID,
		Title:     &title,
		CreatedAt: now,
		UpdatedAt: now,
	})

	app := setupTestAppWithUser(convRepo, projRepo, userID.String(), "testuser")

	req := httptest.NewRequest(http.MethodDelete, "/api/v1/conversations/"+convID.String(), nil)
	req.Header.Set("Content-Type", "application/json")

	resp, err := app.Test(req)
	require.NoError(t, err)
	assert.Equal(t, http.StatusForbidden, resp.StatusCode)

	// Verify conversation still exists
	_, exists := convRepo.conversations[convID]
	assert.True(t, exists)
}

func TestDeleteConversation_InvalidID(t *testing.T) {
	convRepo := NewMockConversationRepository()
	projRepo := NewMockProjectRepository()
	userID, _ := uuid.NewV7()

	app := setupTestAppWithUser(convRepo, projRepo, userID.String(), "testuser")

	req := httptest.NewRequest(http.MethodDelete, "/api/v1/conversations/invalid-uuid", nil)
	req.Header.Set("Content-Type", "application/json")

	resp, err := app.Test(req)
	require.NoError(t, err)
	assert.Equal(t, http.StatusBadRequest, resp.StatusCode)
}
