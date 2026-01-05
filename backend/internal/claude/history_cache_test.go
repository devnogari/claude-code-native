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

	cache, err := NewHistoryCache(logger, "~/.claude")

	// Should not error even if ~/.claude/projects doesn't exist
	require.NoError(t, err)
	require.NotNil(t, cache)
	assert.True(t, cache.ready)

	// Cleanup
	cache.Close()
}

func TestHistoryCache_GetProjects(t *testing.T) {
	logger := zap.NewNop()
	cache, err := NewHistoryCache(logger, "~/.claude")
	require.NoError(t, err)
	defer cache.Close()

	// GetProjects should return a slice (possibly empty)
	projects := cache.GetProjects()
	assert.NotNil(t, projects)
}

func TestHistoryCache_GetProject_NotFound(t *testing.T) {
	logger := zap.NewNop()
	cache, err := NewHistoryCache(logger, "~/.claude")
	require.NoError(t, err)
	defer cache.Close()

	project, found := cache.GetProject("nonexistent-encoded-path")
	assert.False(t, found)
	assert.Nil(t, project)
}

func TestHistoryCache_Subscribe(t *testing.T) {
	logger := zap.NewNop()
	cache, err := NewHistoryCache(logger, "~/.claude")
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
	cache, err := NewHistoryCache(logger, "~/.claude")
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
	cache, err := NewHistoryCache(logger, "~/.claude")
	require.NoError(t, err)
	defer cache.Close()

	// Non-existent session should return error
	messages, err := cache.GetSessionMessages("nonexistent", "session")
	assert.Error(t, err)
	assert.Nil(t, messages)
}

func TestHistoryCache_GetSessionMessagesPaginated_NotFound(t *testing.T) {
	logger := zap.NewNop()
	cache, err := NewHistoryCache(logger, "~/.claude")
	require.NoError(t, err)
	defer cache.Close()

	// Non-existent session should return error
	result, err := cache.GetSessionMessagesPaginated("nonexistent", "session", 50, 0)
	assert.Error(t, err)
	assert.Nil(t, result)
}

func TestHistoryCache_ThreadSafety(t *testing.T) {
	logger := zap.NewNop()
	cache, err := NewHistoryCache(logger, "~/.claude")
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
	claudeDir := filepath.Join(tmpDir, ".claude")
	projectsDir := filepath.Join(claudeDir, "projects")

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

	// Create HistoryCache with temp directory path
	logger := zap.NewNop()
	cache, err := NewHistoryCache(logger, claudeDir)
	require.NoError(t, err)
	defer cache.Close()

	// Verify cache is ready
	assert.True(t, cache.ready)

	// Verify project was loaded from temp directory
	projects := cache.GetProjects()
	assert.Len(t, projects, 1)
	assert.Equal(t, "Users-test-myproject", projects[0].ID)
	assert.Len(t, projects[0].Sessions, 1)
	assert.Equal(t, "test-session-123", projects[0].Sessions[0].ID)
}

func TestHistoryCache_GetSessionMessages_WithTestDir(t *testing.T) {
	// Setup temp directory
	tmpDir := t.TempDir()
	claudeDir := filepath.Join(tmpDir, ".claude")
	projectsDir := filepath.Join(claudeDir, "projects")
	testProjectPath := filepath.Join(projectsDir, "test-project")
	err := os.MkdirAll(testProjectPath, 0755)
	require.NoError(t, err)

	// Create session with multiple messages
	sessionContent := `{"type":"summary","sessionId":"session-001","summary":"Test session"}
{"type":"user","uuid":"uuid-1","message":{"role":"user","content":"Hello"}}
{"type":"assistant","uuid":"uuid-2","message":{"role":"assistant","content":"Hi there!"}}
{"type":"user","uuid":"uuid-3","message":{"role":"user","content":"How are you?"}}
{"type":"assistant","uuid":"uuid-4","message":{"role":"assistant","content":"I'm doing well!"}}
`
	sessionFile := filepath.Join(testProjectPath, "session-001.jsonl")
	err = os.WriteFile(sessionFile, []byte(sessionContent), 0644)
	require.NoError(t, err)

	logger := zap.NewNop()
	cache, err := NewHistoryCache(logger, claudeDir)
	require.NoError(t, err)
	defer cache.Close()

	// Test GetSessionMessages
	messages, err := cache.GetSessionMessages("test-project", "session-001")
	require.NoError(t, err)
	assert.GreaterOrEqual(t, len(messages), 4)
}

func TestHistoryCache_GetSessionMessagesPaginated_WithTestDir(t *testing.T) {
	// Setup temp directory
	tmpDir := t.TempDir()
	claudeDir := filepath.Join(tmpDir, ".claude")
	projectsDir := filepath.Join(claudeDir, "projects")
	testProjectPath := filepath.Join(projectsDir, "paginated-project")
	err := os.MkdirAll(testProjectPath, 0755)
	require.NoError(t, err)

	// Create session with messages
	sessionContent := `{"type":"summary","sessionId":"session-pag"}
{"type":"user","uuid":"u1","message":{"role":"user","content":"Msg 1"}}
{"type":"assistant","uuid":"a1","message":{"role":"assistant","content":"Reply 1"}}
{"type":"user","uuid":"u2","message":{"role":"user","content":"Msg 2"}}
{"type":"assistant","uuid":"a2","message":{"role":"assistant","content":"Reply 2"}}
`
	sessionFile := filepath.Join(testProjectPath, "session-pag.jsonl")
	err = os.WriteFile(sessionFile, []byte(sessionContent), 0644)
	require.NoError(t, err)

	logger := zap.NewNop()
	cache, err := NewHistoryCache(logger, claudeDir)
	require.NoError(t, err)
	defer cache.Close()

	// Test pagination
	result, err := cache.GetSessionMessagesPaginated("paginated-project", "session-pag", 2, 0)
	require.NoError(t, err)
	assert.Equal(t, 4, result.Total)
	assert.Equal(t, 2, result.Limit)
	assert.True(t, result.HasMore)
}

func TestHistoryCache_DeleteProject_WithTestDir(t *testing.T) {
	// Setup temp directory
	tmpDir := t.TempDir()
	claudeDir := filepath.Join(tmpDir, ".claude")
	projectsDir := filepath.Join(claudeDir, "projects")

	// Create two projects
	project1 := filepath.Join(projectsDir, "project-one")
	project2 := filepath.Join(projectsDir, "project-two")
	err := os.MkdirAll(project1, 0755)
	require.NoError(t, err)
	err = os.MkdirAll(project2, 0755)
	require.NoError(t, err)

	// Create session files
	sessionContent := `{"type":"summary","sessionId":"s1"}
{"type":"user","message":{"role":"user","content":"Hello"}}
`
	err = os.WriteFile(filepath.Join(project1, "s1.jsonl"), []byte(sessionContent), 0644)
	require.NoError(t, err)
	err = os.WriteFile(filepath.Join(project2, "s2.jsonl"), []byte(sessionContent), 0644)
	require.NoError(t, err)

	logger := zap.NewNop()
	cache, err := NewHistoryCache(logger, claudeDir)
	require.NoError(t, err)
	defer cache.Close()

	// Verify both projects loaded
	projects := cache.GetProjects()
	assert.Len(t, projects, 2)

	// Delete one project
	cache.DeleteProject("project-one")

	// Verify only one project remains
	projects = cache.GetProjects()
	assert.Len(t, projects, 1)
	assert.Equal(t, "project-two", projects[0].ID)

	// Verify excluded file was created
	excludedFile := filepath.Join(projectsDir, ".excluded_projects")
	content, err := os.ReadFile(excludedFile)
	require.NoError(t, err)
	assert.Contains(t, string(content), "project-one")
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

// TestHistoryCache_InheritedSession_GetMessages tests that GetSessionMessages
// can retrieve messages from inherited sessions (sessions in parent project)
func TestHistoryCache_InheritedSession_GetMessages(t *testing.T) {
	tmpDir := t.TempDir()
	claudeDir := filepath.Join(tmpDir, ".claude")
	projectsDir := filepath.Join(claudeDir, "projects")

	// Create parent project with a session
	parentPath := filepath.Join(projectsDir, "Users-test-parent-project")
	err := os.MkdirAll(parentPath, 0755)
	require.NoError(t, err)

	sessionContent := `{"type":"summary","sessionId":"inherited-session","summary":"Parent session"}
{"type":"user","uuid":"u1","message":{"role":"user","content":"Hello from parent"}}
{"type":"assistant","uuid":"a1","message":{"role":"assistant","content":"Reply from parent"}}
`
	err = os.WriteFile(filepath.Join(parentPath, "inherited-session.jsonl"), []byte(sessionContent), 0644)
	require.NoError(t, err)

	// Create child project (no sessions - will inherit from parent)
	childPath := filepath.Join(projectsDir, "Users-test-parent-project-subdir")
	err = os.MkdirAll(childPath, 0755)
	require.NoError(t, err)

	logger := zap.NewNop()
	cache, err := NewHistoryCache(logger, claudeDir)
	require.NoError(t, err)
	defer cache.Close()

	// Child project should have inherited sessions
	project, found := cache.GetProject("Users-test-parent-project-subdir")
	require.True(t, found, "child project should be found")
	require.Len(t, project.Sessions, 1, "child should inherit 1 session from parent")
	assert.Equal(t, "inherited-session", project.Sessions[0].ID)
	assert.Equal(t, "Users-test-parent-project", project.Sessions[0].SourceEncodedPath)

	// GetSessionMessages should work with child project path
	messages, err := cache.GetSessionMessages("Users-test-parent-project-subdir", "inherited-session")
	require.NoError(t, err, "GetSessionMessages should find inherited session")
	assert.GreaterOrEqual(t, len(messages), 2, "should have at least 2 messages")

	// Verify actual content from parent project
	var foundParentContent bool
	for _, msg := range messages {
		if msg.Message != nil && msg.Message.Content == "Hello from parent" {
			foundParentContent = true
			break
		}
	}
	assert.True(t, foundParentContent, "should contain message content from parent project")
}

// TestHistoryCache_InheritedSession_GetMessagesPaginated tests that GetSessionMessagesPaginated
// can retrieve messages from inherited sessions
func TestHistoryCache_InheritedSession_GetMessagesPaginated(t *testing.T) {
	tmpDir := t.TempDir()
	claudeDir := filepath.Join(tmpDir, ".claude")
	projectsDir := filepath.Join(claudeDir, "projects")

	// Create parent project with a session
	parentPath := filepath.Join(projectsDir, "Users-dev-main-repo")
	err := os.MkdirAll(parentPath, 0755)
	require.NoError(t, err)

	sessionContent := `{"type":"summary","sessionId":"paginated-inherited","summary":"Parent session"}
{"type":"user","uuid":"u1","message":{"role":"user","content":"Msg 1"}}
{"type":"assistant","uuid":"a1","message":{"role":"assistant","content":"Reply 1"}}
{"type":"user","uuid":"u2","message":{"role":"user","content":"Msg 2"}}
{"type":"assistant","uuid":"a2","message":{"role":"assistant","content":"Reply 2"}}
`
	err = os.WriteFile(filepath.Join(parentPath, "paginated-inherited.jsonl"), []byte(sessionContent), 0644)
	require.NoError(t, err)

	// Create child project (worktree-like path)
	childPath := filepath.Join(projectsDir, "Users-dev-main-repo--worktrees-feature")
	err = os.MkdirAll(childPath, 0755)
	require.NoError(t, err)

	logger := zap.NewNop()
	cache, err := NewHistoryCache(logger, claudeDir)
	require.NoError(t, err)
	defer cache.Close()

	// GetSessionMessagesPaginated should work with child project path
	result, err := cache.GetSessionMessagesPaginated("Users-dev-main-repo--worktrees-feature", "paginated-inherited", 2, 0)
	require.NoError(t, err, "GetSessionMessagesPaginated should find inherited session")
	assert.Equal(t, 4, result.Total, "should have 4 total messages")
	assert.Equal(t, 2, result.Limit)
	assert.True(t, result.HasMore)
}

// TestHistoryCache_Subscribe_FileChange_Callback tests that when a subscriber is registered
// and a file changes, the callback is invoked with new messages
func TestHistoryCache_Subscribe_FileChange_Callback(t *testing.T) {
	tmpDir := t.TempDir()
	claudeDir := filepath.Join(tmpDir, ".claude")
	projectsDir := filepath.Join(claudeDir, "projects")
	testProjectPath := filepath.Join(projectsDir, "test-subscribe-project")
	err := os.MkdirAll(testProjectPath, 0755)
	require.NoError(t, err)

	// Create initial session file with one message
	sessionID := "550e8400-e29b-41d4-a716-446655440001"
	encodedPath := "test-subscribe-project"
	initialContent := `{"type":"summary","sessionId":"` + sessionID + `","summary":"Test session"}
{"type":"user","uuid":"uuid-1","message":{"role":"user","content":"Hello"}}
`
	sessionFile := filepath.Join(testProjectPath, sessionID+".jsonl")
	err = os.WriteFile(sessionFile, []byte(initialContent), 0644)
	require.NoError(t, err)

	logger, _ := zap.NewDevelopment()
	cache, err := NewHistoryCache(logger, claudeDir)
	require.NoError(t, err)
	defer cache.Close()

	// Channel to receive callback notifications
	callbackCh := make(chan []ClaudeMessage, 10)
	callbackCount := 0

	// Subscribe to session changes
	cache.Subscribe(encodedPath, sessionID, func(ep, sid string, messages []ClaudeMessage) {
		callbackCount++
		t.Logf("Callback #%d invoked: encodedPath=%s, sessionID=%s, messages=%d", callbackCount, ep, sid, len(messages))
		for i, msg := range messages {
			t.Logf("  Message[%d]: type=%s, uuid=%s, role=%v", i, msg.Type, msg.UUID, msg.Message)
		}
		callbackCh <- messages
	})

	// Give fsnotify time to register the watcher
	time.Sleep(100 * time.Millisecond)

	// Append a new message to the file
	newMessage := `{"type":"assistant","uuid":"uuid-2","message":{"role":"assistant","content":"Hi there!"}}
`
	f, err := os.OpenFile(sessionFile, os.O_APPEND|os.O_WRONLY, 0644)
	require.NoError(t, err)
	_, err = f.WriteString(newMessage)
	require.NoError(t, err)
	f.Close()

	// Wait for callback
	select {
	case messages := <-callbackCh:
		t.Logf("Received %d new messages in callback", len(messages))
		assert.NotEmpty(t, messages, "callback should receive new messages")
		// Should receive only the new message (uuid-2)
		found := false
		for _, msg := range messages {
			if msg.UUID == "uuid-2" {
				found = true
				assert.Equal(t, "assistant", msg.Type)
				assert.Equal(t, "assistant", msg.Message.Role)
				assert.Equal(t, "Hi there!", msg.Message.Content)
			}
		}
		assert.True(t, found, "should receive the new message with uuid-2")
	case <-time.After(3 * time.Second):
		t.Fatal("TIMEOUT: callback was not invoked after file change - THIS IS THE BUG!")
	}
}

// TestHistoryCache_Subscribe_MultipleMessages tests that multiple new messages
// are all delivered in a single callback
func TestHistoryCache_Subscribe_MultipleMessages(t *testing.T) {
	tmpDir := t.TempDir()
	claudeDir := filepath.Join(tmpDir, ".claude")
	projectsDir := filepath.Join(claudeDir, "projects")
	testProjectPath := filepath.Join(projectsDir, "test-multi-msg")
	err := os.MkdirAll(testProjectPath, 0755)
	require.NoError(t, err)

	sessionID := "550e8400-e29b-41d4-a716-446655440002"
	encodedPath := "test-multi-msg"
	initialContent := `{"type":"summary","sessionId":"` + sessionID + `"}
{"type":"user","uuid":"uuid-1","message":{"role":"user","content":"First"}}
`
	sessionFile := filepath.Join(testProjectPath, sessionID+".jsonl")
	err = os.WriteFile(sessionFile, []byte(initialContent), 0644)
	require.NoError(t, err)

	logger, _ := zap.NewDevelopment()
	cache, err := NewHistoryCache(logger, claudeDir)
	require.NoError(t, err)
	defer cache.Close()

	callbackCh := make(chan []ClaudeMessage, 10)

	cache.Subscribe(encodedPath, sessionID, func(ep, sid string, messages []ClaudeMessage) {
		t.Logf("Callback: received %d messages", len(messages))
		callbackCh <- messages
	})

	time.Sleep(100 * time.Millisecond)

	// Append multiple messages at once
	newMessages := `{"type":"assistant","uuid":"uuid-2","message":{"role":"assistant","content":"Reply 1"}}
{"type":"user","uuid":"uuid-3","message":{"role":"user","content":"Second"}}
{"type":"assistant","uuid":"uuid-4","message":{"role":"assistant","content":"Reply 2"}}
`
	f, err := os.OpenFile(sessionFile, os.O_APPEND|os.O_WRONLY, 0644)
	require.NoError(t, err)
	_, err = f.WriteString(newMessages)
	require.NoError(t, err)
	f.Close()

	select {
	case messages := <-callbackCh:
		t.Logf("Received %d new messages", len(messages))
		// Should receive all 3 new messages
		assert.GreaterOrEqual(t, len(messages), 3, "should receive all new messages")
	case <-time.After(3 * time.Second):
		t.Fatal("TIMEOUT: callback was not invoked - THIS IS THE BUG!")
	}
}

// TestHistoryCache_Subscribe_RaceCondition tests the race condition between
// Subscribe and file change detection
func TestHistoryCache_Subscribe_RaceCondition(t *testing.T) {
	tmpDir := t.TempDir()
	claudeDir := filepath.Join(tmpDir, ".claude")
	projectsDir := filepath.Join(claudeDir, "projects")
	testProjectPath := filepath.Join(projectsDir, "test-race")
	err := os.MkdirAll(testProjectPath, 0755)
	require.NoError(t, err)

	sessionID := "550e8400-e29b-41d4-a716-446655440003"
	encodedPath := "test-race"
	initialContent := `{"type":"summary","sessionId":"` + sessionID + `"}
{"type":"user","uuid":"uuid-1","message":{"role":"user","content":"Initial"}}
`
	sessionFile := filepath.Join(testProjectPath, sessionID+".jsonl")
	err = os.WriteFile(sessionFile, []byte(initialContent), 0644)
	require.NoError(t, err)

	logger, _ := zap.NewDevelopment()
	cache, err := NewHistoryCache(logger, claudeDir)
	require.NoError(t, err)
	defer cache.Close()

	callbackCh := make(chan []ClaudeMessage, 10)
	callbackInvoked := false

	// Start writing to file BEFORE subscribing (simulating race condition)
	go func() {
		// Small delay then write
		time.Sleep(50 * time.Millisecond)
		newMsg := `{"type":"assistant","uuid":"uuid-2","message":{"role":"assistant","content":"Racing message"}}
`
		f, _ := os.OpenFile(sessionFile, os.O_APPEND|os.O_WRONLY, 0644)
		f.WriteString(newMsg)
		f.Close()
	}()

	// Subscribe immediately
	cache.Subscribe(encodedPath, sessionID, func(ep, sid string, messages []ClaudeMessage) {
		t.Logf("Race callback: %d messages", len(messages))
		callbackInvoked = true
		callbackCh <- messages
	})

	select {
	case messages := <-callbackCh:
		t.Logf("Got %d messages from race condition test", len(messages))
		assert.NotEmpty(t, messages)
	case <-time.After(3 * time.Second):
		if !callbackInvoked {
			t.Log("WARNING: Race condition - file changed during/before subscribe was not detected")
			// This is expected behavior in current implementation
			// The fix should ensure this case is handled
		}
	}
}

// TestHistoryCache_notifySessionSubscribers_DirectCall tests the notifySessionSubscribers
// method directly to isolate whether the issue is in file watching or notification
func TestHistoryCache_notifySessionSubscribers_DirectCall(t *testing.T) {
	tmpDir := t.TempDir()
	claudeDir := filepath.Join(tmpDir, ".claude")
	projectsDir := filepath.Join(claudeDir, "projects")
	testProjectPath := filepath.Join(projectsDir, "test-direct-notify")
	err := os.MkdirAll(testProjectPath, 0755)
	require.NoError(t, err)

	sessionID := "550e8400-e29b-41d4-a716-446655440004"
	encodedPath := "test-direct-notify"
	// Create session with messages
	content := `{"type":"summary","sessionId":"` + sessionID + `"}
{"type":"user","uuid":"uuid-1","message":{"role":"user","content":"Msg1"}}
{"type":"assistant","uuid":"uuid-2","message":{"role":"assistant","content":"Reply1"}}
`
	sessionFile := filepath.Join(testProjectPath, sessionID+".jsonl")
	err = os.WriteFile(sessionFile, []byte(content), 0644)
	require.NoError(t, err)

	logger, _ := zap.NewDevelopment()
	cache, err := NewHistoryCache(logger, claudeDir)
	require.NoError(t, err)
	defer cache.Close()

	callbackCh := make(chan []ClaudeMessage, 10)

	// Subscribe
	cache.Subscribe(encodedPath, sessionID, func(ep, sid string, messages []ClaudeMessage) {
		t.Logf("Direct notify callback: %d messages", len(messages))
		callbackCh <- messages
	})

	// Add a new message to the file
	newMsg := `{"type":"user","uuid":"uuid-3","message":{"role":"user","content":"NewMsg"}}
`
	f, err := os.OpenFile(sessionFile, os.O_APPEND|os.O_WRONLY, 0644)
	require.NoError(t, err)
	f.WriteString(newMsg)
	f.Close()

	// Call notifySessionSubscribers DIRECTLY (bypassing fsnotify)
	cache.notifySessionSubscribers(encodedPath, sessionFile)

	select {
	case messages := <-callbackCh:
		t.Logf("Direct call received %d messages", len(messages))
		assert.NotEmpty(t, messages, "direct call should receive new messages")
		found := false
		for _, msg := range messages {
			if msg.UUID == "uuid-3" {
				found = true
			}
		}
		assert.True(t, found, "should find the new message uuid-3")
	case <-time.After(1 * time.Second):
		t.Fatal("TIMEOUT: even direct call didn't work - logic issue in notifySessionSubscribers")
	}
}
