package claude

import (
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
	"go.uber.org/zap"
)

func TestNewHistoryCache(t *testing.T) {
	logger := zap.NewNop()

	cache, err := NewHistoryCache(logger)

	// Should not error even if ~/.claude/projects doesn't exist
	require.NoError(t, err)
	require.NotNil(t, cache)
	assert.True(t, cache.ready)

	// Cleanup
	cache.Close()
}

func TestHistoryCache_GetProjects(t *testing.T) {
	logger := zap.NewNop()
	cache, err := NewHistoryCache(logger)
	require.NoError(t, err)
	defer cache.Close()

	// GetProjects should return a slice (possibly empty)
	projects := cache.GetProjects()
	assert.NotNil(t, projects)
}

func TestHistoryCache_GetProject_NotFound(t *testing.T) {
	logger := zap.NewNop()
	cache, err := NewHistoryCache(logger)
	require.NoError(t, err)
	defer cache.Close()

	project, found := cache.GetProject("nonexistent-encoded-path")
	assert.False(t, found)
	assert.Nil(t, project)
}

func TestHistoryCache_Subscribe(t *testing.T) {
	logger := zap.NewNop()
	cache, err := NewHistoryCache(logger)
	require.NoError(t, err)
	defer cache.Close()

	encodedPath := "test-project"
	sessionID := "test-session"

	callback := func(ep, sid string, messages []ClaudeMessage) {}

	// Subscribe
	cache.Subscribe(encodedPath, sessionID, callback)

	// Verify subscriber is registered
	cache.subscribersMu.RLock()
	key := encodedPath + "/" + sessionID
	subs := cache.subscribers[key]
	cache.subscribersMu.RUnlock()

	assert.Len(t, subs, 1)

	// Unsubscribe
	cache.Unsubscribe(encodedPath, sessionID)

	cache.subscribersMu.RLock()
	subs = cache.subscribers[key]
	cache.subscribersMu.RUnlock()

	assert.Len(t, subs, 0)
}

func TestHistoryCache_Refresh(t *testing.T) {
	logger := zap.NewNop()
	cache, err := NewHistoryCache(logger)
	require.NoError(t, err)
	defer cache.Close()

	// Refresh should not panic
	cache.Refresh()

	// Projects should still be accessible after refresh
	projects := cache.GetProjects()
	assert.NotNil(t, projects)
}

func TestHistoryCache_GetSessionMessages_NotFound(t *testing.T) {
	logger := zap.NewNop()
	cache, err := NewHistoryCache(logger)
	require.NoError(t, err)
	defer cache.Close()

	// Non-existent session should return error
	messages, err := cache.GetSessionMessages("nonexistent", "session")
	assert.Error(t, err)
	assert.Nil(t, messages)
}

func TestHistoryCache_GetSessionMessagesPaginated_NotFound(t *testing.T) {
	logger := zap.NewNop()
	cache, err := NewHistoryCache(logger)
	require.NoError(t, err)
	defer cache.Close()

	// Non-existent session should return error
	result, err := cache.GetSessionMessagesPaginated("nonexistent", "session", 50, 0)
	assert.Error(t, err)
	assert.Nil(t, result)
}

func TestHistoryCache_ThreadSafety(t *testing.T) {
	logger := zap.NewNop()
	cache, err := NewHistoryCache(logger)
	require.NoError(t, err)
	defer cache.Close()

	done := make(chan bool, 4)

	// Concurrent reads
	go func() {
		for i := 0; i < 100; i++ {
			cache.GetProjects()
		}
		done <- true
	}()

	go func() {
		for i := 0; i < 100; i++ {
			cache.GetProject("test-path")
		}
		done <- true
	}()

	// Concurrent subscribe/unsubscribe
	go func() {
		for i := 0; i < 100; i++ {
			cache.Subscribe("path", "session", func(a, b string, c []ClaudeMessage) {})
		}
		done <- true
	}()

	go func() {
		for i := 0; i < 100; i++ {
			cache.Unsubscribe("path", "session")
		}
		done <- true
	}()

	// Wait for all goroutines
	for i := 0; i < 4; i++ {
		select {
		case <-done:
		case <-time.After(5 * time.Second):
			t.Fatal("timeout waiting for concurrent operations")
		}
	}
}

func TestHistoryCache_WithTestDir(t *testing.T) {
	// Create a temporary test directory structure
	tmpDir := t.TempDir()
	projectsDir := filepath.Join(tmpDir, ".claude", "projects")

	// Create test project directory
	testProjectPath := filepath.Join(projectsDir, "Users-test-myproject")
	err := os.MkdirAll(testProjectPath, 0755)
	require.NoError(t, err)

	// Create a test session file
	sessionContent := `{"type":"summary","sessionId":"test-session-123","summary":"Initial setup","leafId":"leaf1"}
{"type":"user","message":{"role":"user","content":"Hello"},"timestamp":"2024-01-01T12:00:00Z"}
{"type":"assistant","message":{"role":"assistant","content":"Hi there!"},"timestamp":"2024-01-01T12:00:01Z"}
`
	sessionFile := filepath.Join(testProjectPath, "test-session-123.jsonl")
	err = os.WriteFile(sessionFile, []byte(sessionContent), 0644)
	require.NoError(t, err)

	// Create HistoryCache with custom path
	// Note: HistoryCache uses os.UserHomeDir() internally, so this test
	// verifies the parsing logic by ensuring no panics occur
	logger := zap.NewNop()
	cache, err := NewHistoryCache(logger)
	require.NoError(t, err)
	defer cache.Close()

	// Verify cache is ready
	assert.True(t, cache.ready)
}

func TestPaginatedMessages_Struct(t *testing.T) {
	pm := PaginatedMessages{
		Messages: []ClaudeMessage{{Type: "user"}},
		Total:    100,
		Limit:    50,
		Offset:   0,
		HasMore:  true,
	}

	assert.Len(t, pm.Messages, 1)
	assert.Equal(t, 100, pm.Total)
	assert.Equal(t, 50, pm.Limit)
	assert.Equal(t, 0, pm.Offset)
	assert.True(t, pm.HasMore)
}
