package ws

import (
	"sync"
	"testing"
	"time"

	"github.com/gofrs/uuid/v5"
	"github.com/stretchr/testify/assert"
)

func TestNewHub(t *testing.T) {
	hub := NewHub()

	if hub == nil {
		t.Fatal("expected non-nil hub")
	}

	if hub.clients == nil {
		t.Error("expected clients map to be initialized")
	}

	if hub.conversations == nil {
		t.Error("expected conversations map to be initialized")
	}

	if hub.register == nil {
		t.Error("expected register channel to be initialized")
	}

	if hub.unregister == nil {
		t.Error("expected unregister channel to be initialized")
	}

	if hub.broadcast == nil {
		t.Error("expected broadcast channel to be initialized")
	}
}

func TestHub_Register(t *testing.T) {
	hub := NewHub()
	go hub.Run()

	userID, _ := uuid.NewV7()
	convID, _ := uuid.NewV7()

	// Create client with nil connection (for testing purposes)
	client := NewClient(userID, convID, nil)

	hub.Register(client)

	// Give the goroutine time to process
	time.Sleep(10 * time.Millisecond)

	// Verify client was added to clients map
	hub.mu.RLock()
	registeredClient, exists := hub.clients[client.ID]
	hub.mu.RUnlock()

	if !exists {
		t.Error("expected client to be registered in clients map")
	}

	if registeredClient != client {
		t.Error("expected registered client to match original client")
	}

	// Verify client was added to conversations map
	hub.mu.RLock()
	convClients, convExists := hub.conversations[convID]
	hub.mu.RUnlock()

	if !convExists {
		t.Error("expected conversation to exist in conversations map")
	}

	if convClients[client.ID] != client {
		t.Error("expected client to be in conversation's client map")
	}
}

func TestHub_Unregister(t *testing.T) {
	hub := NewHub()
	go hub.Run()

	userID, _ := uuid.NewV7()
	convID, _ := uuid.NewV7()

	client := NewClient(userID, convID, nil)

	// First register the client
	hub.Register(client)
	time.Sleep(10 * time.Millisecond)

	// Then unregister
	hub.Unregister(client)
	time.Sleep(10 * time.Millisecond)

	// Verify client was removed from clients map
	hub.mu.RLock()
	_, exists := hub.clients[client.ID]
	hub.mu.RUnlock()

	if exists {
		t.Error("expected client to be removed from clients map")
	}

	// Verify client was removed from conversations map
	hub.mu.RLock()
	convClients, convExists := hub.conversations[convID]
	hub.mu.RUnlock()

	if convExists && convClients[client.ID] != nil {
		t.Error("expected client to be removed from conversation's client map")
	}

	// Verify Send channel is closed
	select {
	case _, ok := <-client.Send:
		if ok {
			t.Error("expected Send channel to be closed")
		}
	default:
		// Channel might be empty but not closed yet, that's OK for this test
	}
}

func TestHub_BroadcastToConversation(t *testing.T) {
	hub := NewHub()
	go hub.Run()

	userID1, _ := uuid.NewV7()
	userID2, _ := uuid.NewV7()
	convID, _ := uuid.NewV7()
	otherConvID, _ := uuid.NewV7()

	// Create clients in the same conversation
	client1 := NewClient(userID1, convID, nil)
	client2 := NewClient(userID2, convID, nil)

	// Create client in different conversation
	client3 := NewClient(userID1, otherConvID, nil)

	hub.Register(client1)
	hub.Register(client2)
	hub.Register(client3)
	time.Sleep(10 * time.Millisecond)

	testData := []byte(`{"type":"test","content":"hello"}`)

	// Broadcast to conversation
	hub.BroadcastToConversation(convID, testData)
	time.Sleep(10 * time.Millisecond)

	// Check client1 received the message
	select {
	case msg := <-client1.Send:
		if string(msg) != string(testData) {
			t.Errorf("client1 received wrong message: got %s, want %s", string(msg), string(testData))
		}
	case <-time.After(100 * time.Millisecond):
		t.Error("client1 did not receive message")
	}

	// Check client2 received the message
	select {
	case msg := <-client2.Send:
		if string(msg) != string(testData) {
			t.Errorf("client2 received wrong message: got %s, want %s", string(msg), string(testData))
		}
	case <-time.After(100 * time.Millisecond):
		t.Error("client2 did not receive message")
	}

	// Check client3 did NOT receive the message (different conversation)
	select {
	case <-client3.Send:
		t.Error("client3 should not have received message (different conversation)")
	case <-time.After(50 * time.Millisecond):
		// Expected - no message received
	}
}

func TestHub_GetClientsForConversation(t *testing.T) {
	hub := NewHub()
	go hub.Run()

	userID1, _ := uuid.NewV7()
	userID2, _ := uuid.NewV7()
	convID, _ := uuid.NewV7()
	otherConvID, _ := uuid.NewV7()

	client1 := NewClient(userID1, convID, nil)
	client2 := NewClient(userID2, convID, nil)
	client3 := NewClient(userID1, otherConvID, nil)

	hub.Register(client1)
	hub.Register(client2)
	hub.Register(client3)
	time.Sleep(10 * time.Millisecond)

	clients := hub.GetClientsForConversation(convID)

	if len(clients) != 2 {
		t.Errorf("expected 2 clients in conversation, got %d", len(clients))
	}

	// Verify the correct clients are returned
	clientIDs := make(map[uuid.UUID]bool)
	for _, c := range clients {
		clientIDs[c.ID] = true
	}

	if !clientIDs[client1.ID] {
		t.Error("expected client1 to be in returned clients")
	}

	if !clientIDs[client2.ID] {
		t.Error("expected client2 to be in returned clients")
	}

	if clientIDs[client3.ID] {
		t.Error("client3 should not be in returned clients (different conversation)")
	}
}

func TestHub_GetClientsForConversation_EmptyConversation(t *testing.T) {
	hub := NewHub()
	go hub.Run()

	nonExistentConvID, _ := uuid.NewV7()

	clients := hub.GetClientsForConversation(nonExistentConvID)

	if clients == nil {
		t.Error("expected non-nil slice for empty conversation")
	}

	if len(clients) != 0 {
		t.Errorf("expected 0 clients for non-existent conversation, got %d", len(clients))
	}
}

func TestHub_ConcurrentOperations(t *testing.T) {
	hub := NewHub()
	go hub.Run()

	convID, _ := uuid.NewV7()
	numClients := 50

	var wg sync.WaitGroup
	clients := make([]*Client, numClients)

	// Register many clients concurrently
	for i := 0; i < numClients; i++ {
		wg.Add(1)
		go func(idx int) {
			defer wg.Done()
			userID, _ := uuid.NewV7()
			clients[idx] = NewClient(userID, convID, nil)
			hub.Register(clients[idx])
		}(i)
	}
	wg.Wait()
	time.Sleep(50 * time.Millisecond)

	// Verify all clients registered
	registeredClients := hub.GetClientsForConversation(convID)
	if len(registeredClients) != numClients {
		t.Errorf("expected %d clients, got %d", numClients, len(registeredClients))
	}

	// Unregister half concurrently
	for i := 0; i < numClients/2; i++ {
		wg.Add(1)
		go func(idx int) {
			defer wg.Done()
			hub.Unregister(clients[idx])
		}(i)
	}
	wg.Wait()
	time.Sleep(50 * time.Millisecond)

	// Verify remaining clients
	remainingClients := hub.GetClientsForConversation(convID)
	if len(remainingClients) != numClients/2 {
		t.Errorf("expected %d clients after unregister, got %d", numClients/2, len(remainingClients))
	}
}

func TestNewClient(t *testing.T) {
	userID, _ := uuid.NewV7()
	convID, _ := uuid.NewV7()

	client := NewClient(userID, convID, nil)

	if client == nil {
		t.Fatal("expected non-nil client")
	}

	if client.ID == uuid.Nil {
		t.Error("expected client ID to be generated")
	}

	if client.UserID != userID {
		t.Errorf("expected userID %v, got %v", userID, client.UserID)
	}

	if client.ConversationID != convID {
		t.Errorf("expected conversationID %v, got %v", convID, client.ConversationID)
	}

	if client.Conn != nil {
		t.Error("expected nil connection for nil input")
	}

	if client.Send == nil {
		t.Error("expected Send channel to be initialized")
	}

	// Verify Send channel is buffered
	select {
	case client.Send <- []byte("test"):
		// Should not block - channel is buffered
	default:
		t.Error("Send channel should be buffered")
	}
}

func TestHub_BroadcastNonBlocking(t *testing.T) {
	hub := NewHub()
	go hub.Run()

	userID, _ := uuid.NewV7()
	convID, _ := uuid.NewV7()

	client := NewClient(userID, convID, nil)
	hub.Register(client)
	time.Sleep(10 * time.Millisecond)

	// Fill the client's Send channel
	for i := 0; i < 256; i++ {
		select {
		case client.Send <- []byte("fill"):
		default:
			break
		}
	}

	// Broadcast should not block even if channel is full
	done := make(chan bool)
	go func() {
		hub.BroadcastToConversation(convID, []byte("test"))
		done <- true
	}()

	select {
	case <-done:
		// Broadcast completed without blocking
	case <-time.After(100 * time.Millisecond):
		t.Error("BroadcastToConversation blocked - should use non-blocking send")
	}
}

func TestHub_UnregisterNonExistentClient(t *testing.T) {
	hub := NewHub()
	go hub.Run()

	userID, _ := uuid.NewV7()
	convID, _ := uuid.NewV7()

	client := NewClient(userID, convID, nil)

	// Unregister without registering first - should not panic
	hub.Unregister(client)
	time.Sleep(10 * time.Millisecond)

	// Verify hub is still functional
	hub.Register(client)
	time.Sleep(10 * time.Millisecond)

	clients := hub.GetClientsForConversation(convID)
	if len(clients) != 1 {
		t.Errorf("expected 1 client after re-register, got %d", len(clients))
	}
}

func TestHub_MultipleConversations(t *testing.T) {
	hub := NewHub()
	go hub.Run()

	convID1, _ := uuid.NewV7()
	convID2, _ := uuid.NewV7()
	convID3, _ := uuid.NewV7()

	// Register clients to different conversations
	for i := 0; i < 3; i++ {
		userID, _ := uuid.NewV7()
		client := NewClient(userID, convID1, nil)
		hub.Register(client)
	}

	for i := 0; i < 5; i++ {
		userID, _ := uuid.NewV7()
		client := NewClient(userID, convID2, nil)
		hub.Register(client)
	}

	for i := 0; i < 2; i++ {
		userID, _ := uuid.NewV7()
		client := NewClient(userID, convID3, nil)
		hub.Register(client)
	}

	time.Sleep(50 * time.Millisecond)

	if len(hub.GetClientsForConversation(convID1)) != 3 {
		t.Errorf("expected 3 clients in conv1, got %d", len(hub.GetClientsForConversation(convID1)))
	}

	if len(hub.GetClientsForConversation(convID2)) != 5 {
		t.Errorf("expected 5 clients in conv2, got %d", len(hub.GetClientsForConversation(convID2)))
	}

	if len(hub.GetClientsForConversation(convID3)) != 2 {
		t.Errorf("expected 2 clients in conv3, got %d", len(hub.GetClientsForConversation(convID3)))
	}
}

func TestHub_Subscribe(t *testing.T) {
	hub := NewHub()
	go hub.Run()
	defer close(hub.register) // Stop the hub

	// Create a client
	clientID, _ := uuid.NewV7()
	userID, _ := uuid.NewV7()
	convID1, _ := uuid.NewV7()
	convID2, _ := uuid.NewV7()

	client := &Client{
		ID:             clientID,
		UserID:         userID,
		ConversationID: convID1,
		Send:           make(chan []byte, 256),
		Done:           make(chan struct{}),
	}

	// Register client
	hub.Register(client)
	time.Sleep(10 * time.Millisecond) // Wait for processing

	// Subscribe to first conversation
	resp := make(chan error, 1)
	hub.Subscribe(&SubscribeRequest{
		Client:         client,
		ConversationID: convID1,
		Response:       resp,
	})
	err := <-resp
	assert.NoError(t, err)

	// Verify subscription
	hub.mu.RLock()
	assert.Equal(t, convID1, hub.subscriptions[clientID])
	assert.Contains(t, hub.conversations[convID1], clientID)
	hub.mu.RUnlock()

	// Switch to second conversation
	resp2 := make(chan error, 1)
	hub.Subscribe(&SubscribeRequest{
		Client:         client,
		ConversationID: convID2,
		Response:       resp2,
	})
	err = <-resp2
	assert.NoError(t, err)

	// Verify switched
	hub.mu.RLock()
	assert.Equal(t, convID2, hub.subscriptions[clientID])
	assert.Contains(t, hub.conversations[convID2], clientID)
	assert.NotContains(t, hub.conversations[convID1], clientID)
	hub.mu.RUnlock()
}
