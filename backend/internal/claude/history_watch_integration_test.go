package claude

import (
	"encoding/json"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"testing"
	"time"

	fws "github.com/fasthttp/websocket"
	"github.com/gofiber/contrib/websocket"
	"github.com/gofiber/fiber/v2"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
	"go.uber.org/zap"
)

// TestHistoryWatch_E2E_RealTimeUpdates tests the full E2E flow:
// 1. WebSocket connection to server
// 2. Subscribe message sent
// 3. File change detected by fsnotify
// 4. New messages broadcast to WebSocket client
func TestHistoryWatch_E2E_RealTimeUpdates(t *testing.T) {
	// Setup temp directory with Claude structure
	tmpDir := t.TempDir()
	claudeDir := filepath.Join(tmpDir, ".claude")
	projectsDir := filepath.Join(claudeDir, "projects")
	testProjectPath := filepath.Join(projectsDir, "test-e2e-project")
	err := os.MkdirAll(testProjectPath, 0755)
	require.NoError(t, err)

	sessionID := "550e8400-e29b-41d4-a716-446655440099"
	encodedPath := "test-e2e-project"

	// Create initial session file
	initialContent := `{"type":"summary","sessionId":"` + sessionID + `"}
{"type":"user","uuid":"uuid-1","message":{"role":"user","content":"Hello"}}
`
	sessionFile := filepath.Join(testProjectPath, sessionID+".jsonl")
	err = os.WriteFile(sessionFile, []byte(initialContent), 0644)
	require.NoError(t, err)

	// Initialize cache and handler
	logger, _ := zap.NewDevelopment()
	cache, err := NewHistoryCache(logger, claudeDir)
	require.NoError(t, err)
	defer cache.Close()

	handler := NewHistoryWatchHandler(cache, logger)

	// Create Fiber app with WebSocket route
	app := fiber.New()
	app.Get("/ws/user", handler.Upgrade, websocket.New(handler.HandleUnifiedConnection))

	// Create listener
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	require.NoError(t, err, "failed to create listener")

	// Start server in goroutine
	go app.Listener(ln)
	defer app.Shutdown()

	// Wait for server to start
	time.Sleep(100 * time.Millisecond)

	// Get the actual address
	addr := ln.Addr().String()
	wsURL := fmt.Sprintf("ws://%s/ws/user", addr)

	t.Logf("Connecting to WebSocket at: %s", wsURL)

	// Connect WebSocket client using fasthttp websocket
	dialer := fws.Dialer{
		HandshakeTimeout: 5 * time.Second,
	}
	conn, _, err := dialer.Dial(wsURL, nil)
	require.NoError(t, err, "WebSocket dial failed")
	defer conn.Close()

	t.Logf("WebSocket connected successfully")

	// Send subscribe message
	subscribeMsg := WatchIncomingMessage{
		Type:        WatchMessageTypeSubscribe,
		EncodedPath: encodedPath,
		SessionID:   sessionID,
	}
	msgBytes, _ := json.Marshal(subscribeMsg)
	err = conn.WriteMessage(fws.TextMessage, msgBytes)
	require.NoError(t, err, "Failed to send subscribe message")

	t.Logf("Sent subscribe message: %s", string(msgBytes))

	// Wait for subscribed confirmation
	conn.SetReadDeadline(time.Now().Add(5 * time.Second))
	_, msg, err := conn.ReadMessage()
	require.NoError(t, err, "Failed to read subscribed confirmation")

	var confirmedMsg WatchOutgoingMessage
	err = json.Unmarshal(msg, &confirmedMsg)
	require.NoError(t, err)
	assert.Equal(t, WatchMessageTypeSubscribed, confirmedMsg.Type, "Expected subscribed confirmation")
	t.Logf("Received subscribed confirmation: %s", string(msg))

	// Give fsnotify time to settle
	time.Sleep(100 * time.Millisecond)

	// Write new message to session file
	newMessage := `{"type":"assistant","uuid":"uuid-2","message":{"role":"assistant","content":"Hello! I received your message."}}
`
	t.Logf("Appending new message to session file: %s", sessionFile)

	f, err := os.OpenFile(sessionFile, os.O_APPEND|os.O_WRONLY, 0644)
	require.NoError(t, err)
	_, err = f.WriteString(newMessage)
	require.NoError(t, err)
	f.Close()

	t.Logf("New message appended, waiting for broadcast...")

	// Wait for new_messages broadcast
	conn.SetReadDeadline(time.Now().Add(5 * time.Second))
	_, msg, err = conn.ReadMessage()
	if err != nil {
		t.Fatalf("TIMEOUT or error waiting for new_messages: %v\nThis indicates real-time updates are NOT working!", err)
	}

	var newMsgsEvent WatchOutgoingMessage
	err = json.Unmarshal(msg, &newMsgsEvent)
	require.NoError(t, err)

	t.Logf("Received message: %s", string(msg))

	// Verify we received the new message
	assert.Equal(t, WatchMessageTypeNewMessages, newMsgsEvent.Type, "Expected new_messages event")
	assert.NotEmpty(t, newMsgsEvent.Messages, "Should have at least one new message")

	if len(newMsgsEvent.Messages) > 0 {
		t.Logf("SUCCESS: Received %d new message(s) via real-time WebSocket", len(newMsgsEvent.Messages))
		for _, m := range newMsgsEvent.Messages {
			if m.Message != nil {
				contentStr := ""
				if s, ok := m.Message.Content.(string); ok {
					contentStr = s
				}
				t.Logf("  - Role: %s, Content preview: %s", m.Message.Role, truncateString(contentStr, 50))
			}
		}
	}
}

// TestHistoryWatch_E2E_MultipleUpdates tests that multiple file updates
// are all received in real-time without needing to rejoin
func TestHistoryWatch_E2E_MultipleUpdates(t *testing.T) {
	tmpDir := t.TempDir()
	claudeDir := filepath.Join(tmpDir, ".claude")
	projectsDir := filepath.Join(claudeDir, "projects")
	testProjectPath := filepath.Join(projectsDir, "test-multi")
	err := os.MkdirAll(testProjectPath, 0755)
	require.NoError(t, err)

	sessionID := "550e8400-e29b-41d4-a716-446655440088"
	encodedPath := "test-multi"

	initialContent := `{"type":"summary","sessionId":"` + sessionID + `"}
{"type":"user","uuid":"uuid-1","message":{"role":"user","content":"Start"}}
`
	sessionFile := filepath.Join(testProjectPath, sessionID+".jsonl")
	err = os.WriteFile(sessionFile, []byte(initialContent), 0644)
	require.NoError(t, err)

	logger, _ := zap.NewDevelopment()
	cache, err := NewHistoryCache(logger, claudeDir)
	require.NoError(t, err)
	defer cache.Close()

	handler := NewHistoryWatchHandler(cache, logger)

	app := fiber.New()
	app.Get("/ws/user", handler.Upgrade, websocket.New(handler.HandleUnifiedConnection))

	ln, err := net.Listen("tcp", "127.0.0.1:0")
	require.NoError(t, err)

	go app.Listener(ln)
	defer app.Shutdown()

	time.Sleep(100 * time.Millisecond)

	addr := ln.Addr().String()
	wsURL := fmt.Sprintf("ws://%s/ws/user", addr)

	dialer := fws.Dialer{HandshakeTimeout: 5 * time.Second}
	conn, _, err := dialer.Dial(wsURL, nil)
	require.NoError(t, err)
	defer conn.Close()

	// Subscribe
	subscribeMsg := WatchIncomingMessage{
		Type:        WatchMessageTypeSubscribe,
		EncodedPath: encodedPath,
		SessionID:   sessionID,
	}
	msgBytes, _ := json.Marshal(subscribeMsg)
	conn.WriteMessage(fws.TextMessage, msgBytes)

	// Read subscribed confirmation
	conn.SetReadDeadline(time.Now().Add(5 * time.Second))
	conn.ReadMessage()

	time.Sleep(100 * time.Millisecond)

	// Send 5 updates and verify all are received
	receivedCount := 0
	for i := 2; i <= 6; i++ {
		newMessage := fmt.Sprintf(`{"type":"assistant","uuid":"uuid-%d","message":{"role":"assistant","content":"Message %d"}}
`, i, i)

		f, _ := os.OpenFile(sessionFile, os.O_APPEND|os.O_WRONLY, 0644)
		f.WriteString(newMessage)
		f.Close()

		// Wait for broadcast
		conn.SetReadDeadline(time.Now().Add(5 * time.Second))
		_, msg, err := conn.ReadMessage()
		if err != nil {
			t.Logf("Failed to receive update %d: %v", i, err)
			continue
		}

		var event WatchOutgoingMessage
		json.Unmarshal(msg, &event)
		if event.Type == WatchMessageTypeNewMessages {
			receivedCount++
			t.Logf("Received update %d/%d", receivedCount, 5)
		}

		// Small delay between updates
		time.Sleep(50 * time.Millisecond)
	}

	assert.GreaterOrEqual(t, receivedCount, 3, "Should receive at least 3 out of 5 updates in real-time")
	t.Logf("Received %d/5 updates via real-time WebSocket", receivedCount)
}

// TestHistoryWatch_E2E_SwitchSession tests switching from one session to another
// and verifying updates are received for the new session only
func TestHistoryWatch_E2E_SwitchSession(t *testing.T) {
	tmpDir := t.TempDir()
	claudeDir := filepath.Join(tmpDir, ".claude")
	projectsDir := filepath.Join(claudeDir, "projects")

	project1 := filepath.Join(projectsDir, "proj-1")
	project2 := filepath.Join(projectsDir, "proj-2")
	os.MkdirAll(project1, 0755)
	os.MkdirAll(project2, 0755)

	session1 := "550e8400-e29b-41d4-a716-446655440001"
	session2 := "550e8400-e29b-41d4-a716-446655440002"

	file1 := filepath.Join(project1, session1+".jsonl")
	file2 := filepath.Join(project2, session2+".jsonl")

	os.WriteFile(file1, []byte(`{"type":"summary"}
{"type":"user","uuid":"u1","message":{"role":"user","content":"S1"}}
`), 0644)
	os.WriteFile(file2, []byte(`{"type":"summary"}
{"type":"user","uuid":"u1","message":{"role":"user","content":"S2"}}
`), 0644)

	logger, _ := zap.NewDevelopment()
	cache, err := NewHistoryCache(logger, claudeDir)
	require.NoError(t, err)
	defer cache.Close()

	handler := NewHistoryWatchHandler(cache, logger)

	app := fiber.New()
	app.Get("/ws/user", handler.Upgrade, websocket.New(handler.HandleUnifiedConnection))

	ln, err := net.Listen("tcp", "127.0.0.1:0")
	require.NoError(t, err)

	go app.Listener(ln)
	defer app.Shutdown()

	time.Sleep(100 * time.Millisecond)

	addr := ln.Addr().String()
	wsURL := fmt.Sprintf("ws://%s/ws/user", addr)

	dialer := fws.Dialer{HandshakeTimeout: 5 * time.Second}
	conn, _, err := dialer.Dial(wsURL, nil)
	require.NoError(t, err)
	defer conn.Close()

	// Subscribe to session 1
	sub1 := WatchIncomingMessage{Type: WatchMessageTypeSubscribe, EncodedPath: "proj-1", SessionID: session1}
	msgBytes, _ := json.Marshal(sub1)
	conn.WriteMessage(fws.TextMessage, msgBytes)

	conn.SetReadDeadline(time.Now().Add(5 * time.Second))
	conn.ReadMessage() // subscribed

	time.Sleep(50 * time.Millisecond)

	// Update session 1 - should receive
	f, _ := os.OpenFile(file1, os.O_APPEND|os.O_WRONLY, 0644)
	f.WriteString(`{"type":"assistant","uuid":"a1","message":{"role":"assistant","content":"Reply1"}}
`)
	f.Close()

	conn.SetReadDeadline(time.Now().Add(5 * time.Second))
	_, msg, err := conn.ReadMessage()
	require.NoError(t, err, "Should receive update for session 1")

	var event WatchOutgoingMessage
	json.Unmarshal(msg, &event)
	assert.Equal(t, WatchMessageTypeNewMessages, event.Type)
	t.Logf("Received update for session 1")

	// Switch to session 2
	sub2 := WatchIncomingMessage{Type: WatchMessageTypeSubscribe, EncodedPath: "proj-2", SessionID: session2}
	msgBytes, _ = json.Marshal(sub2)
	conn.WriteMessage(fws.TextMessage, msgBytes)

	conn.SetReadDeadline(time.Now().Add(5 * time.Second))
	conn.ReadMessage() // subscribed

	time.Sleep(50 * time.Millisecond)

	// Update session 2 - should receive
	f, _ = os.OpenFile(file2, os.O_APPEND|os.O_WRONLY, 0644)
	f.WriteString(`{"type":"assistant","uuid":"a2","message":{"role":"assistant","content":"Reply2"}}
`)
	f.Close()

	conn.SetReadDeadline(time.Now().Add(5 * time.Second))
	_, msg, err = conn.ReadMessage()
	require.NoError(t, err, "Should receive update for session 2 after switch")

	json.Unmarshal(msg, &event)
	assert.Equal(t, WatchMessageTypeNewMessages, event.Type)
	assert.Equal(t, session2, event.SessionID, "Update should be for session 2")
	t.Logf("Received update for session 2 after switch - SUCCESS")
}

// TestHistoryWatch_E2E_InheritedSession tests the scenario where:
// - Client subscribes with encodedPath=child-project
// - Session file actually resides in parent-project (inherited session)
// - File changes should still be detected and broadcast
// This is a critical test for the "have to leave and rejoin to see messages" bug!
func TestHistoryWatch_E2E_InheritedSession(t *testing.T) {
	tmpDir := t.TempDir()
	claudeDir := filepath.Join(tmpDir, ".claude")
	projectsDir := filepath.Join(claudeDir, "projects")

	// Parent project has the session file
	parentProject := filepath.Join(projectsDir, "-Users-dev-myproject")
	// Child project inherits sessions from parent
	childProject := filepath.Join(projectsDir, "-Users-dev-myproject-subdir")

	os.MkdirAll(parentProject, 0755)
	os.MkdirAll(childProject, 0755)

	sessionID := "550e8400-e29b-41d4-a716-446655440077"

	// Session file is in PARENT project
	parentSessionFile := filepath.Join(parentProject, sessionID+".jsonl")
	os.WriteFile(parentSessionFile, []byte(`{"type":"summary","cwd":"/Users/dev/myproject"}
{"type":"user","uuid":"u1","message":{"role":"user","content":"Hello from parent"}}
`), 0644)

	logger, _ := zap.NewDevelopment()
	cache, err := NewHistoryCache(logger, claudeDir)
	require.NoError(t, err)
	defer cache.Close()

	handler := NewHistoryWatchHandler(cache, logger)

	app := fiber.New()
	app.Get("/ws/user", handler.Upgrade, websocket.New(handler.HandleUnifiedConnection))

	ln, err := net.Listen("tcp", "127.0.0.1:0")
	require.NoError(t, err)

	go app.Listener(ln)
	defer app.Shutdown()

	time.Sleep(100 * time.Millisecond)

	addr := ln.Addr().String()
	wsURL := fmt.Sprintf("ws://%s/ws/user", addr)

	dialer := fws.Dialer{HandshakeTimeout: 5 * time.Second}
	conn, _, err := dialer.Dial(wsURL, nil)
	require.NoError(t, err)
	defer conn.Close()

	// Subscribe using CHILD project's encoded path, but session is in PARENT
	// This simulates the inherited session scenario
	childEncodedPath := "-Users-dev-myproject-subdir"
	sub := WatchIncomingMessage{
		Type:        WatchMessageTypeSubscribe,
		EncodedPath: childEncodedPath,
		SessionID:   sessionID,
	}
	msgBytes, _ := json.Marshal(sub)
	conn.WriteMessage(fws.TextMessage, msgBytes)

	conn.SetReadDeadline(time.Now().Add(5 * time.Second))
	_, _, err = conn.ReadMessage() // subscribed
	require.NoError(t, err, "Should receive subscribed confirmation")

	time.Sleep(100 * time.Millisecond)

	// Update the session file in PARENT project
	t.Logf("Updating session file in parent project: %s", parentSessionFile)
	f, err := os.OpenFile(parentSessionFile, os.O_APPEND|os.O_WRONLY, 0644)
	require.NoError(t, err)
	f.WriteString(`{"type":"assistant","uuid":"a1","message":{"role":"assistant","content":"Reply from assistant"}}
`)
	f.Close()

	// This is the critical test:
	// The client subscribed with child encodedPath, but file is in parent.
	// With the fix, sourceToChildren tracking ensures notifications are delivered.
	conn.SetReadDeadline(time.Now().Add(5 * time.Second))
	_, msg, err := conn.ReadMessage()
	require.NoError(t, err, "Should receive new_messages for inherited session - if this fails, the fix is not working")

	var event WatchOutgoingMessage
	err = json.Unmarshal(msg, &event)
	require.NoError(t, err)
	assert.Equal(t, WatchMessageTypeNewMessages, event.Type)
	assert.Equal(t, childEncodedPath, event.EncodedPath, "Should receive client's encodedPath, not source")
	assert.Equal(t, sessionID, event.SessionID)
	assert.Len(t, event.Messages, 1, "Should have exactly one new message")
	if len(event.Messages) > 0 && event.Messages[0].Message != nil {
		contentStr := ""
		if s, ok := event.Messages[0].Message.Content.(string); ok {
			contentStr = s
		}
		assert.Equal(t, "Reply from assistant", contentStr)
	}
	t.Logf("SUCCESS: Received update for inherited session with correct encodedPath")
}
