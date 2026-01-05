package claude

import (
	"encoding/json"
	"os"
	"path/filepath"
	"sync"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
	"go.uber.org/zap"
)

func TestValidateEncodedPath(t *testing.T) {
	tests := []struct {
		name     string
		input    string
		expected bool
	}{
		// Valid paths
		{"valid path with leading dash", "-Users-test-project", true},
		{"valid path simple", "myproject", true},
		{"valid path with dashes", "my-project-name", true},
		{"valid path with underscores", "my_project_name", true},
		{"valid path alphanumeric", "project123", true},
		{"valid path mixed", "-Users-home-project_v2", true},

		// Invalid paths - empty
		{"empty string", "", false},

		// Invalid paths - traversal attacks
		{"path traversal double dot", "..", false},
		{"path traversal with prefix", "test/../etc", false},
		{"path traversal encoded start", "-Users-..-etc-passwd", false},
		{"single dot", ".", false},

		// Invalid paths - null byte injection
		{"null byte injection", "test\x00.txt", false},
		{"null byte in middle", "test\x00/../etc", false},

		// Invalid paths - special characters
		{"contains slash", "path/to/file", false},
		{"contains backslash", "path\\to\\file", false},
		{"contains space", "path to file", false},
		{"contains colon", "C:Users", false},
		{"contains semicolon", "path;injection", false},
		{"contains pipe", "path|cmd", false},
		{"contains ampersand", "path&cmd", false},
		{"contains dollar", "path$var", false},
		{"contains backtick", "path`cmd`", false},
		{"contains quotes", "path\"test\"", false},
		{"contains single quote", "path'test'", false},
		{"contains angle brackets", "path<test>", false},
		{"contains percent", "path%20test", false},
		{"contains at sign", "user@host", false},
		{"contains hash", "path#anchor", false},
		{"contains asterisk", "path*glob", false},
		{"contains question mark", "path?query", false},
		{"contains exclamation", "path!cmd", false},
		{"contains parentheses", "path(test)", false},
		{"contains brackets", "path[0]", false},
		{"contains braces", "path{test}", false},

		// Unicode/special encoding attempts
		{"unicode slash attempt", "test\u002fpasswd", false},
		{"unicode backslash attempt", "test\u005cetc", false},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			result := validateEncodedPath(tt.input)
			assert.Equal(t, tt.expected, result, "validateEncodedPath(%q) = %v, want %v", tt.input, result, tt.expected)
		})
	}
}

func TestValidateSessionID(t *testing.T) {
	tests := []struct {
		name     string
		input    string
		expected bool
	}{
		// Valid UUIDs - must be lowercase (URL convention)
		{"valid UUID lowercase", "550e8400-e29b-41d4-a716-446655440000", true},
		{"valid UUIDv7", "01912e3e-3c4a-7b2f-8c1a-9b3f2e4d5a6c", true},

		// Invalid UUIDs - uppercase not accepted (normalize to lowercase before calling)
		{"uppercase UUID rejected", "550E8400-E29B-41D4-A716-446655440000", false},
		{"mixed case UUID rejected", "550e8400-E29B-41d4-A716-446655440000", false},

		// Invalid UUIDs
		{"empty string", "", false},
		{"too short", "550e8400-e29b-41d4-a716", false},
		{"too long", "550e8400-e29b-41d4-a716-446655440000-extra", false},
		{"missing dashes", "550e8400e29b41d4a716446655440000", false},
		{"wrong dash positions", "550e8400e-29b-41d4-a716-44665544000", false},
		{"invalid characters", "550e8400-e29b-41d4-a716-44665544xxxx", false},
		{"path traversal attempt", "../../../etc/passwd", false},
		{"sql injection attempt", "'; DROP TABLE users; --", false},
		{"command injection", "$(whoami)", false},
		{"null byte", "550e8400-e29b-\x00-a716-446655440000", false},
		{"spaces", "550e8400 e29b 41d4 a716 446655440000", false},
		{"newline injection", "550e8400-e29b-41d4-a716-446655440000\n", false},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			result := validateSessionID(tt.input)
			assert.Equal(t, tt.expected, result, "validateSessionID(%q) = %v, want %v", tt.input, result, tt.expected)
		})
	}
}

// TestHistoryWatchHandler_handleSessionChange tests the callback from cache to handler
func TestHistoryWatchHandler_handleSessionChange(t *testing.T) {
	tmpDir := t.TempDir()
	claudeDir := filepath.Join(tmpDir, ".claude")
	projectsDir := filepath.Join(claudeDir, "projects")
	testProjectPath := filepath.Join(projectsDir, "test-handler")
	err := os.MkdirAll(testProjectPath, 0755)
	require.NoError(t, err)

	sessionID := "550e8400-e29b-41d4-a716-446655440010"
	encodedPath := "test-handler"
	content := `{"type":"summary","sessionId":"` + sessionID + `"}
{"type":"user","uuid":"uuid-1","message":{"role":"user","content":"Hello"}}
`
	sessionFile := filepath.Join(testProjectPath, sessionID+".jsonl")
	err = os.WriteFile(sessionFile, []byte(content), 0644)
	require.NoError(t, err)

	logger, _ := zap.NewDevelopment()
	cache, err := NewHistoryCache(logger, claudeDir)
	require.NoError(t, err)
	defer cache.Close()

	handler := NewHistoryWatchHandler(cache, logger)

	// Create a mock client
	mockClient := &WatchClient{
		send:        make(chan []byte, 256),
		done:        make(chan struct{}),
		encodedPath: encodedPath,
		sessionID:   sessionID,
	}

	// Register the client (this subscribes to cache)
	handler.registerClient(mockClient)
	defer handler.unregisterClient(mockClient)

	// Give time for subscription
	time.Sleep(50 * time.Millisecond)

	// Append new message
	newMsg := `{"type":"assistant","uuid":"uuid-2","message":{"role":"assistant","content":"Reply"}}
`
	f, err := os.OpenFile(sessionFile, os.O_APPEND|os.O_WRONLY, 0644)
	require.NoError(t, err)
	f.WriteString(newMsg)
	f.Close()

	// Wait for message on client.send channel
	select {
	case data := <-mockClient.send:
		t.Logf("Received data on client.send: %s", string(data))
		var msg WatchOutgoingMessage
		err := json.Unmarshal(data, &msg)
		require.NoError(t, err)
		assert.Equal(t, WatchMessageTypeNewMessages, msg.Type)
		assert.NotEmpty(t, msg.Messages, "should have new messages")
	case <-time.After(3 * time.Second):
		t.Fatal("TIMEOUT: no message received on client.send - handleSessionChange not working!")
	}
}

// TestHistoryWatchHandler_RegisterClient_SubscribesToCache tests that registering
// a client properly subscribes to cache changes
func TestHistoryWatchHandler_RegisterClient_SubscribesToCache(t *testing.T) {
	tmpDir := t.TempDir()
	claudeDir := filepath.Join(tmpDir, ".claude")
	projectsDir := filepath.Join(claudeDir, "projects")
	testProjectPath := filepath.Join(projectsDir, "test-register")
	err := os.MkdirAll(testProjectPath, 0755)
	require.NoError(t, err)

	sessionID := "550e8400-e29b-41d4-a716-446655440011"
	encodedPath := "test-register"
	content := `{"type":"summary","sessionId":"` + sessionID + `"}
{"type":"user","uuid":"uuid-1","message":{"role":"user","content":"Hello"}}
`
	sessionFile := filepath.Join(testProjectPath, sessionID+".jsonl")
	err = os.WriteFile(sessionFile, []byte(content), 0644)
	require.NoError(t, err)

	logger, _ := zap.NewDevelopment()
	cache, err := NewHistoryCache(logger, claudeDir)
	require.NoError(t, err)
	defer cache.Close()

	handler := NewHistoryWatchHandler(cache, logger)

	// Verify no subscribers initially
	cache.subscribersMu.RLock()
	key := encodedPath + "/" + sessionID
	initialSubs := len(cache.subscribers[key])
	cache.subscribersMu.RUnlock()
	assert.Equal(t, 0, initialSubs, "should have no subscribers initially")

	// Create and register client
	mockClient := &WatchClient{
		send:        make(chan []byte, 256),
		done:        make(chan struct{}),
		encodedPath: encodedPath,
		sessionID:   sessionID,
	}
	handler.registerClient(mockClient)

	// Verify subscriber was added
	cache.subscribersMu.RLock()
	afterSubs := len(cache.subscribers[key])
	cache.subscribersMu.RUnlock()
	assert.Equal(t, 1, afterSubs, "should have 1 subscriber after register")

	// Unregister
	handler.unregisterClient(mockClient)

	// Verify subscriber was removed
	cache.subscribersMu.RLock()
	finalSubs := len(cache.subscribers[key])
	cache.subscribersMu.RUnlock()
	assert.Equal(t, 0, finalSubs, "should have 0 subscribers after unregister")
}

// TestHistoryWatchHandler_BroadcastToMultipleClients tests that messages are
// broadcast to all clients watching the same session
func TestHistoryWatchHandler_BroadcastToMultipleClients(t *testing.T) {
	tmpDir := t.TempDir()
	claudeDir := filepath.Join(tmpDir, ".claude")
	projectsDir := filepath.Join(claudeDir, "projects")
	testProjectPath := filepath.Join(projectsDir, "test-broadcast")
	err := os.MkdirAll(testProjectPath, 0755)
	require.NoError(t, err)

	sessionID := "550e8400-e29b-41d4-a716-446655440012"
	encodedPath := "test-broadcast"
	content := `{"type":"summary","sessionId":"` + sessionID + `"}
{"type":"user","uuid":"uuid-1","message":{"role":"user","content":"Hello"}}
`
	sessionFile := filepath.Join(testProjectPath, sessionID+".jsonl")
	err = os.WriteFile(sessionFile, []byte(content), 0644)
	require.NoError(t, err)

	logger, _ := zap.NewDevelopment()
	cache, err := NewHistoryCache(logger, claudeDir)
	require.NoError(t, err)
	defer cache.Close()

	handler := NewHistoryWatchHandler(cache, logger)

	// Create multiple clients
	client1 := &WatchClient{
		send:        make(chan []byte, 256),
		done:        make(chan struct{}),
		encodedPath: encodedPath,
		sessionID:   sessionID,
	}
	client2 := &WatchClient{
		send:        make(chan []byte, 256),
		done:        make(chan struct{}),
		encodedPath: encodedPath,
		sessionID:   sessionID,
	}
	client3 := &WatchClient{
		send:        make(chan []byte, 256),
		done:        make(chan struct{}),
		encodedPath: encodedPath,
		sessionID:   sessionID,
	}

	handler.registerClient(client1)
	handler.registerClient(client2)
	handler.registerClient(client3)
	defer func() {
		handler.unregisterClient(client1)
		handler.unregisterClient(client2)
		handler.unregisterClient(client3)
	}()

	time.Sleep(50 * time.Millisecond)

	// Append new message
	newMsg := `{"type":"assistant","uuid":"uuid-2","message":{"role":"assistant","content":"Broadcast test"}}
`
	f, err := os.OpenFile(sessionFile, os.O_APPEND|os.O_WRONLY, 0644)
	require.NoError(t, err)
	f.WriteString(newMsg)
	f.Close()

	// Wait for all 3 clients to receive the message
	var wg sync.WaitGroup
	receivedCount := 0
	var mu sync.Mutex

	for i, client := range []*WatchClient{client1, client2, client3} {
		wg.Add(1)
		go func(idx int, c *WatchClient) {
			defer wg.Done()
			select {
			case data := <-c.send:
				t.Logf("Client %d received: %s", idx, string(data))
				mu.Lock()
				receivedCount++
				mu.Unlock()
			case <-time.After(3 * time.Second):
				t.Logf("Client %d TIMEOUT", idx)
			}
		}(i, client)
	}

	wg.Wait()
	assert.Equal(t, 3, receivedCount, "all 3 clients should receive the broadcast")
}

// TestHistoryWatchHandler_SwitchSubscription tests the unified mode subscription switching
func TestHistoryWatchHandler_SwitchSubscription(t *testing.T) {
	tmpDir := t.TempDir()
	claudeDir := filepath.Join(tmpDir, ".claude")
	projectsDir := filepath.Join(claudeDir, "projects")

	// Create two projects
	project1 := filepath.Join(projectsDir, "project-a")
	project2 := filepath.Join(projectsDir, "project-b")
	err := os.MkdirAll(project1, 0755)
	require.NoError(t, err)
	err = os.MkdirAll(project2, 0755)
	require.NoError(t, err)

	sessionID1 := "550e8400-e29b-41d4-a716-446655440020"
	sessionID2 := "550e8400-e29b-41d4-a716-446655440021"

	// Create session files
	content1 := `{"type":"summary","sessionId":"` + sessionID1 + `"}
{"type":"user","uuid":"uuid-1","message":{"role":"user","content":"Session 1"}}
`
	content2 := `{"type":"summary","sessionId":"` + sessionID2 + `"}
{"type":"user","uuid":"uuid-1","message":{"role":"user","content":"Session 2"}}
`
	err = os.WriteFile(filepath.Join(project1, sessionID1+".jsonl"), []byte(content1), 0644)
	require.NoError(t, err)
	err = os.WriteFile(filepath.Join(project2, sessionID2+".jsonl"), []byte(content2), 0644)
	require.NoError(t, err)

	logger, _ := zap.NewDevelopment()
	cache, err := NewHistoryCache(logger, claudeDir)
	require.NoError(t, err)
	defer cache.Close()

	handler := NewHistoryWatchHandler(cache, logger)

	// Create unified client (no initial subscription)
	client := &WatchClient{
		send:    make(chan []byte, 256),
		done:    make(chan struct{}),
		handler: handler,
	}

	// Switch to first session
	err = handler.switchSubscription(client, "project-a", sessionID1)
	require.NoError(t, err)

	// Verify client is subscribed to session 1
	client.mu.RLock()
	assert.Equal(t, "project-a", client.encodedPath)
	assert.Equal(t, sessionID1, client.sessionID)
	client.mu.RUnlock()

	// Drain the "subscribed" message
	select {
	case <-client.send:
	case <-time.After(100 * time.Millisecond):
	}

	time.Sleep(50 * time.Millisecond)

	// Add message to session 1 - should receive it
	newMsg1 := `{"type":"assistant","uuid":"uuid-2","message":{"role":"assistant","content":"Reply to session 1"}}
`
	f, _ := os.OpenFile(filepath.Join(project1, sessionID1+".jsonl"), os.O_APPEND|os.O_WRONLY, 0644)
	f.WriteString(newMsg1)
	f.Close()

	select {
	case data := <-client.send:
		t.Logf("Received from session 1: %s", string(data))
		var msg WatchOutgoingMessage
		json.Unmarshal(data, &msg)
		assert.Equal(t, WatchMessageTypeNewMessages, msg.Type)
	case <-time.After(3 * time.Second):
		t.Fatal("TIMEOUT: didn't receive message from session 1")
	}

	// Switch to session 2
	err = handler.switchSubscription(client, "project-b", sessionID2)
	require.NoError(t, err)

	// Drain the "subscribed" message
	select {
	case <-client.send:
	case <-time.After(100 * time.Millisecond):
	}

	time.Sleep(50 * time.Millisecond)

	// Add message to session 2 - should receive it
	newMsg2 := `{"type":"assistant","uuid":"uuid-2","message":{"role":"assistant","content":"Reply to session 2"}}
`
	f, _ = os.OpenFile(filepath.Join(project2, sessionID2+".jsonl"), os.O_APPEND|os.O_WRONLY, 0644)
	f.WriteString(newMsg2)
	f.Close()

	select {
	case data := <-client.send:
		t.Logf("Received from session 2: %s", string(data))
		var msg WatchOutgoingMessage
		json.Unmarshal(data, &msg)
		assert.Equal(t, WatchMessageTypeNewMessages, msg.Type)
		assert.Equal(t, sessionID2, msg.SessionID)
	case <-time.After(3 * time.Second):
		t.Fatal("TIMEOUT: didn't receive message from session 2 after switch")
	}

	// Clean up
	handler.unregisterClient(client)
}
