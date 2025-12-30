# Phase 3: WebSocket & Claude CLI Integration

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Enable real-time bidirectional communication between frontend and Claude CLI via WebSocket with streaming output.

**Architecture:** WebSocket hub manages client connections and maps them to Claude CLI process sessions. Messages from clients are forwarded to CLI stdin, and CLI stdout/stderr streams back through WebSocket. Each conversation gets its own CLI process with isolated working directory.

**Tech Stack:**
- Go Fiber with WebSocket upgrade (`github.com/gofiber/contrib/websocket`)
- Process management via `os/exec`
- JSON message protocol for client-server communication
- UUID v7 for session identifiers

---

## Task 3.1: Create Message Model & Repository

**Files:**
- Create: `backend/internal/message/model.go`
- Create: `backend/internal/message/repository.go`
- Create: `backend/internal/message/repository_test.go`
- Create: `backend/internal/message/module.go`
- Create: `backend/migrations/000004_create_messages.up.sql`
- Create: `backend/migrations/000004_create_messages.down.sql`

**Step 1: Write the failing test for Message model validation**

```go
// backend/internal/message/repository_test.go
package message

import (
	"strings"
	"testing"

	"github.com/gofrs/uuid/v5"
)

func TestMessage_Validate(t *testing.T) {
	validConvID, _ := uuid.NewV7()

	tests := []struct {
		name    string
		msg     Message
		wantErr bool
		errMsg  string
	}{
		{
			name: "valid user message passes validation",
			msg: Message{
				ConversationID: validConvID,
				Role:           RoleUser,
				Content:        "Hello, Claude!",
			},
			wantErr: false,
		},
		{
			name: "valid assistant message passes validation",
			msg: Message{
				ConversationID: validConvID,
				Role:           RoleAssistant,
				Content:        "Hello! How can I help?",
			},
			wantErr: false,
		},
		{
			name: "missing conversation_id fails validation",
			msg: Message{
				ConversationID: uuid.Nil,
				Role:           RoleUser,
				Content:        "Hello",
			},
			wantErr: true,
			errMsg:  "conversation_id",
		},
		{
			name: "empty role fails validation",
			msg: Message{
				ConversationID: validConvID,
				Role:           "",
				Content:        "Hello",
			},
			wantErr: true,
			errMsg:  "role",
		},
		{
			name: "invalid role fails validation",
			msg: Message{
				ConversationID: validConvID,
				Role:           "invalid",
				Content:        "Hello",
			},
			wantErr: true,
			errMsg:  "role",
		},
		{
			name: "empty content fails validation",
			msg: Message{
				ConversationID: validConvID,
				Role:           RoleUser,
				Content:        "",
			},
			wantErr: true,
			errMsg:  "content",
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			err := tt.msg.Validate()
			if tt.wantErr {
				if err == nil {
					t.Errorf("expected error but got nil")
					return
				}
				if tt.errMsg != "" && !strings.Contains(err.Error(), tt.errMsg) {
					t.Errorf("expected error to contain %q, got %q", tt.errMsg, err.Error())
				}
			} else {
				if err != nil {
					t.Errorf("expected no error but got: %v", err)
				}
			}
		})
	}
}

func TestNewRepository(t *testing.T) {
	repo := NewRepository(nil)
	if repo == nil {
		t.Error("expected non-nil repository")
	}
	if repo.db != nil {
		t.Error("expected nil db for nil input")
	}
}
```

**Step 2: Run test to verify it fails**

Run: `cd backend && go test ./internal/message/... -v`
Expected: FAIL - package not found

**Step 3: Write Message model implementation**

```go
// backend/internal/message/model.go
package message

import (
	"fmt"
	"time"

	"github.com/gofrs/uuid/v5"
	"github.com/uptrace/bun"
)

const (
	RoleUser      = "user"
	RoleAssistant = "assistant"
	RoleSystem    = "system"
)

type Message struct {
	bun.BaseModel `bun:"table:messages,alias:m"`

	ID             uuid.UUID `bun:"id,pk,type:uuid,default:uuidv7()"  json:"id"`
	ConversationID uuid.UUID `bun:"conversation_id,notnull,type:uuid" json:"conversation_id"`
	Role           string    `bun:"role,notnull"                      json:"role"`
	Content        string    `bun:"content,notnull"                   json:"content"`
	TokenCount     *int      `bun:"token_count"                       json:"token_count,omitempty"`
	CreatedAt      time.Time `bun:"created_at,default:now()"          json:"created_at"`
}

func (m *Message) Validate() error {
	if m.ConversationID == uuid.Nil {
		return fmt.Errorf("conversation_id is required")
	}
	if m.Role == "" {
		return fmt.Errorf("role is required")
	}
	if m.Role != RoleUser && m.Role != RoleAssistant && m.Role != RoleSystem {
		return fmt.Errorf("role must be one of: user, assistant, system")
	}
	if m.Content == "" {
		return fmt.Errorf("content is required")
	}
	return nil
}
```

**Step 4: Write Repository implementation**

```go
// backend/internal/message/repository.go
package message

import (
	"context"

	"github.com/gofrs/uuid/v5"
	"github.com/uptrace/bun"
)

type Repository struct {
	db *bun.DB
}

func NewRepository(db *bun.DB) *Repository {
	return &Repository{db: db}
}

func (r *Repository) Create(ctx context.Context, m *Message) error {
	_, err := r.db.NewInsert().Model(m).Exec(ctx)
	return err
}

func (r *Repository) FindByID(ctx context.Context, id uuid.UUID) (*Message, error) {
	msg := new(Message)
	err := r.db.NewSelect().Model(msg).Where("id = ?", id).Scan(ctx)
	if err != nil {
		return nil, err
	}
	return msg, nil
}

func (r *Repository) FindByConversationID(ctx context.Context, conversationID uuid.UUID) ([]*Message, error) {
	var messages []*Message
	err := r.db.NewSelect().
		Model(&messages).
		Where("conversation_id = ?", conversationID).
		Order("created_at ASC").
		Scan(ctx)
	if err != nil {
		return nil, err
	}
	return messages, nil
}

func (r *Repository) Delete(ctx context.Context, id uuid.UUID) error {
	_, err := r.db.NewDelete().Model((*Message)(nil)).Where("id = ?", id).Exec(ctx)
	return err
}

func (r *Repository) DeleteByConversationID(ctx context.Context, conversationID uuid.UUID) error {
	_, err := r.db.NewDelete().
		Model((*Message)(nil)).
		Where("conversation_id = ?", conversationID).
		Exec(ctx)
	return err
}
```

**Step 5: Write fx module**

```go
// backend/internal/message/module.go
package message

import (
	"github.com/uptrace/bun"
	"go.uber.org/fx"
)

var Module = fx.Module("message",
	fx.Provide(func(db *bun.DB) *Repository {
		return NewRepository(db)
	}),
)
```

**Step 6: Write migration files**

```sql
-- backend/migrations/000004_create_messages.up.sql
CREATE TABLE IF NOT EXISTS messages (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    conversation_id UUID NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    role VARCHAR(20) NOT NULL CHECK (role IN ('user', 'assistant', 'system')),
    content TEXT NOT NULL,
    token_count INTEGER,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_messages_conversation_id ON messages(conversation_id);
CREATE INDEX idx_messages_created_at ON messages(created_at);
```

```sql
-- backend/migrations/000004_create_messages.down.sql
DROP TABLE IF EXISTS messages;
```

**Step 7: Run test to verify it passes**

Run: `cd backend && go test ./internal/message/... -v`
Expected: PASS

**Step 8: Commit**

```bash
git add backend/internal/message/ backend/migrations/000004_*
git commit -m "feat(message): add Message model and repository"
```

---

## Task 3.2: Create WebSocket Hub

**Files:**
- Create: `backend/internal/ws/hub.go`
- Create: `backend/internal/ws/client.go`
- Create: `backend/internal/ws/hub_test.go`
- Create: `backend/internal/ws/module.go`

**Step 1: Write the failing test for Hub**

```go
// backend/internal/ws/hub_test.go
package ws

import (
	"testing"
	"time"

	"github.com/gofrs/uuid/v5"
)

func TestNewHub(t *testing.T) {
	hub := NewHub()
	if hub == nil {
		t.Fatal("expected non-nil hub")
	}
	if hub.clients == nil {
		t.Error("expected initialized clients map")
	}
	if hub.conversations == nil {
		t.Error("expected initialized conversations map")
	}
	if hub.register == nil {
		t.Error("expected initialized register channel")
	}
	if hub.unregister == nil {
		t.Error("expected initialized unregister channel")
	}
	if hub.broadcast == nil {
		t.Error("expected initialized broadcast channel")
	}
}

func TestHub_RegisterUnregister(t *testing.T) {
	hub := NewHub()
	go hub.Run()

	userID, _ := uuid.NewV7()
	convID, _ := uuid.NewV7()

	client := &Client{
		ID:             uuid.Must(uuid.NewV7()),
		UserID:         userID,
		ConversationID: convID,
		Send:           make(chan []byte, 256),
	}

	// Register client
	hub.Register(client)
	time.Sleep(10 * time.Millisecond) // Allow goroutine to process

	if _, ok := hub.clients[client.ID]; !ok {
		t.Error("expected client to be registered")
	}

	// Unregister client
	hub.Unregister(client)
	time.Sleep(10 * time.Millisecond)

	if _, ok := hub.clients[client.ID]; ok {
		t.Error("expected client to be unregistered")
	}
}

func TestHub_BroadcastToConversation(t *testing.T) {
	hub := NewHub()
	go hub.Run()

	userID, _ := uuid.NewV7()
	convID, _ := uuid.NewV7()

	client := &Client{
		ID:             uuid.Must(uuid.NewV7()),
		UserID:         userID,
		ConversationID: convID,
		Send:           make(chan []byte, 256),
	}

	hub.Register(client)
	time.Sleep(10 * time.Millisecond)

	// Broadcast message
	testMsg := []byte(`{"type":"test","content":"hello"}`)
	hub.BroadcastToConversation(convID, testMsg)
	time.Sleep(10 * time.Millisecond)

	select {
	case msg := <-client.Send:
		if string(msg) != string(testMsg) {
			t.Errorf("expected %q, got %q", testMsg, msg)
		}
	default:
		t.Error("expected message in client send channel")
	}
}
```

**Step 2: Run test to verify it fails**

Run: `cd backend && go test ./internal/ws/... -v`
Expected: FAIL - package not found

**Step 3: Write Client struct**

```go
// backend/internal/ws/client.go
package ws

import (
	"github.com/gofrs/uuid/v5"
	"github.com/gofiber/contrib/websocket"
)

type Client struct {
	ID             uuid.UUID
	UserID         uuid.UUID
	ConversationID uuid.UUID
	Conn           *websocket.Conn
	Send           chan []byte
}

func NewClient(userID, conversationID uuid.UUID, conn *websocket.Conn) *Client {
	return &Client{
		ID:             uuid.Must(uuid.NewV7()),
		UserID:         userID,
		ConversationID: conversationID,
		Conn:           conn,
		Send:           make(chan []byte, 256),
	}
}
```

**Step 4: Write Hub implementation**

```go
// backend/internal/ws/hub.go
package ws

import (
	"sync"

	"github.com/gofrs/uuid/v5"
)

type Hub struct {
	clients       map[uuid.UUID]*Client
	conversations map[uuid.UUID]map[uuid.UUID]*Client // convID -> clientID -> client
	register      chan *Client
	unregister    chan *Client
	broadcast     chan *BroadcastMessage
	mu            sync.RWMutex
}

type BroadcastMessage struct {
	ConversationID uuid.UUID
	Data           []byte
}

func NewHub() *Hub {
	return &Hub{
		clients:       make(map[uuid.UUID]*Client),
		conversations: make(map[uuid.UUID]map[uuid.UUID]*Client),
		register:      make(chan *Client),
		unregister:    make(chan *Client),
		broadcast:     make(chan *BroadcastMessage),
	}
}

func (h *Hub) Run() {
	for {
		select {
		case client := <-h.register:
			h.mu.Lock()
			h.clients[client.ID] = client
			if h.conversations[client.ConversationID] == nil {
				h.conversations[client.ConversationID] = make(map[uuid.UUID]*Client)
			}
			h.conversations[client.ConversationID][client.ID] = client
			h.mu.Unlock()

		case client := <-h.unregister:
			h.mu.Lock()
			if _, ok := h.clients[client.ID]; ok {
				delete(h.clients, client.ID)
				if convClients, ok := h.conversations[client.ConversationID]; ok {
					delete(convClients, client.ID)
					if len(convClients) == 0 {
						delete(h.conversations, client.ConversationID)
					}
				}
				close(client.Send)
			}
			h.mu.Unlock()

		case msg := <-h.broadcast:
			h.mu.RLock()
			if clients, ok := h.conversations[msg.ConversationID]; ok {
				for _, client := range clients {
					select {
					case client.Send <- msg.Data:
					default:
						// Client buffer full, skip
					}
				}
			}
			h.mu.RUnlock()
		}
	}
}

func (h *Hub) Register(client *Client) {
	h.register <- client
}

func (h *Hub) Unregister(client *Client) {
	h.unregister <- client
}

func (h *Hub) BroadcastToConversation(convID uuid.UUID, data []byte) {
	h.broadcast <- &BroadcastMessage{
		ConversationID: convID,
		Data:           data,
	}
}

func (h *Hub) GetClientsForConversation(convID uuid.UUID) []*Client {
	h.mu.RLock()
	defer h.mu.RUnlock()

	var clients []*Client
	if convClients, ok := h.conversations[convID]; ok {
		for _, client := range convClients {
			clients = append(clients, client)
		}
	}
	return clients
}
```

**Step 5: Write fx module**

```go
// backend/internal/ws/module.go
package ws

import (
	"context"

	"go.uber.org/fx"
)

var Module = fx.Module("ws",
	fx.Provide(NewHub),
	fx.Invoke(func(lc fx.Lifecycle, hub *Hub) {
		lc.Append(fx.Hook{
			OnStart: func(ctx context.Context) error {
				go hub.Run()
				return nil
			},
		})
	}),
)
```

**Step 6: Run test to verify it passes**

Run: `cd backend && go test ./internal/ws/... -v`
Expected: PASS

**Step 7: Commit**

```bash
git add backend/internal/ws/
git commit -m "feat(ws): add WebSocket hub for client management"
```

---

## Task 3.3: Create Claude CLI Process Manager

**Files:**
- Create: `backend/internal/claude/process.go`
- Create: `backend/internal/claude/manager.go`
- Create: `backend/internal/claude/manager_test.go`
- Create: `backend/internal/claude/module.go`

**Step 1: Write the failing test for ProcessManager**

```go
// backend/internal/claude/manager_test.go
package claude

import (
	"context"
	"testing"
	"time"

	"github.com/gofrs/uuid/v5"
)

func TestNewManager(t *testing.T) {
	mgr := NewManager()
	if mgr == nil {
		t.Fatal("expected non-nil manager")
	}
	if mgr.processes == nil {
		t.Error("expected initialized processes map")
	}
}

func TestManager_CreateProcess(t *testing.T) {
	mgr := NewManager()
	convID, _ := uuid.NewV7()

	proc, err := mgr.CreateProcess(convID, "/tmp", nil)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if proc == nil {
		t.Fatal("expected non-nil process")
	}
	if proc.ConversationID != convID {
		t.Errorf("expected conversation ID %s, got %s", convID, proc.ConversationID)
	}
	if proc.WorkDir != "/tmp" {
		t.Errorf("expected work dir /tmp, got %s", proc.WorkDir)
	}
	if proc.Status != ProcessStatusIdle {
		t.Errorf("expected status idle, got %s", proc.Status)
	}

	// Cleanup
	mgr.StopProcess(convID)
}

func TestManager_GetProcess(t *testing.T) {
	mgr := NewManager()
	convID, _ := uuid.NewV7()

	// Should return nil for non-existent process
	proc := mgr.GetProcess(convID)
	if proc != nil {
		t.Error("expected nil for non-existent process")
	}

	// Create process
	_, err := mgr.CreateProcess(convID, "/tmp", nil)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	// Should return process now
	proc = mgr.GetProcess(convID)
	if proc == nil {
		t.Error("expected non-nil process")
	}

	// Cleanup
	mgr.StopProcess(convID)
}

func TestManager_StopProcess(t *testing.T) {
	mgr := NewManager()
	convID, _ := uuid.NewV7()

	_, err := mgr.CreateProcess(convID, "/tmp", nil)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	err = mgr.StopProcess(convID)
	if err != nil {
		t.Fatalf("unexpected error stopping process: %v", err)
	}

	// Process should be removed
	proc := mgr.GetProcess(convID)
	if proc != nil {
		t.Error("expected nil after stopping process")
	}
}

func TestProcess_SendInput(t *testing.T) {
	mgr := NewManager()
	convID, _ := uuid.NewV7()

	proc, err := mgr.CreateProcess(convID, "/tmp", nil)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	defer mgr.StopProcess(convID)

	// Test input channel
	ctx, cancel := context.WithTimeout(context.Background(), 100*time.Millisecond)
	defer cancel()

	go func() {
		proc.SendInput("test message")
	}()

	select {
	case msg := <-proc.Input:
		if msg != "test message" {
			t.Errorf("expected 'test message', got %q", msg)
		}
	case <-ctx.Done():
		t.Error("timeout waiting for input message")
	}
}
```

**Step 2: Run test to verify it fails**

Run: `cd backend && go test ./internal/claude/... -v`
Expected: FAIL - package not found

**Step 3: Write Process struct**

```go
// backend/internal/claude/process.go
package claude

import (
	"os/exec"
	"sync"
	"time"

	"github.com/gofrs/uuid/v5"
)

const (
	ProcessStatusIdle    = "idle"
	ProcessStatusRunning = "running"
	ProcessStatusStopped = "stopped"
	ProcessStatusError   = "error"
)

type Process struct {
	ConversationID uuid.UUID
	WorkDir        string
	Cmd            *exec.Cmd
	Status         string
	Input          chan string
	Output         chan OutputMessage
	Error          chan error
	Done           chan struct{}
	StartedAt      *time.Time
	mu             sync.RWMutex
}

type OutputMessage struct {
	Type    string `json:"type"`    // "stdout", "stderr", "status"
	Content string `json:"content"`
}

func NewProcess(convID uuid.UUID, workDir string) *Process {
	return &Process{
		ConversationID: convID,
		WorkDir:        workDir,
		Status:         ProcessStatusIdle,
		Input:          make(chan string, 10),
		Output:         make(chan OutputMessage, 100),
		Error:          make(chan error, 10),
		Done:           make(chan struct{}),
	}
}

func (p *Process) SetStatus(status string) {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.Status = status
}

func (p *Process) GetStatus() string {
	p.mu.RLock()
	defer p.mu.RUnlock()
	return p.Status
}

func (p *Process) SendInput(msg string) {
	select {
	case p.Input <- msg:
	default:
		// Input buffer full
	}
}

func (p *Process) Close() {
	close(p.Done)
	close(p.Input)
}
```

**Step 4: Write Manager implementation**

```go
// backend/internal/claude/manager.go
package claude

import (
	"sync"

	"github.com/gofrs/uuid/v5"
)

type Manager struct {
	processes map[uuid.UUID]*Process
	mu        sync.RWMutex
}

func NewManager() *Manager {
	return &Manager{
		processes: make(map[uuid.UUID]*Process),
	}
}

func (m *Manager) CreateProcess(convID uuid.UUID, workDir string, env []string) (*Process, error) {
	m.mu.Lock()
	defer m.mu.Unlock()

	// Check if process already exists
	if existing, ok := m.processes[convID]; ok {
		return existing, nil
	}

	proc := NewProcess(convID, workDir)
	m.processes[convID] = proc

	return proc, nil
}

func (m *Manager) GetProcess(convID uuid.UUID) *Process {
	m.mu.RLock()
	defer m.mu.RUnlock()
	return m.processes[convID]
}

func (m *Manager) StopProcess(convID uuid.UUID) error {
	m.mu.Lock()
	defer m.mu.Unlock()

	proc, ok := m.processes[convID]
	if !ok {
		return nil
	}

	// Stop the process if running
	if proc.Cmd != nil && proc.Cmd.Process != nil {
		_ = proc.Cmd.Process.Kill()
	}

	proc.SetStatus(ProcessStatusStopped)
	proc.Close()
	delete(m.processes, convID)

	return nil
}

func (m *Manager) StopAll() {
	m.mu.Lock()
	defer m.mu.Unlock()

	for convID, proc := range m.processes {
		if proc.Cmd != nil && proc.Cmd.Process != nil {
			_ = proc.Cmd.Process.Kill()
		}
		proc.SetStatus(ProcessStatusStopped)
		proc.Close()
		delete(m.processes, convID)
	}
}

func (m *Manager) ListProcesses() []*Process {
	m.mu.RLock()
	defer m.mu.RUnlock()

	procs := make([]*Process, 0, len(m.processes))
	for _, proc := range m.processes {
		procs = append(procs, proc)
	}
	return procs
}
```

**Step 5: Write fx module**

```go
// backend/internal/claude/module.go
package claude

import (
	"context"

	"go.uber.org/fx"
	"go.uber.org/zap"
)

var Module = fx.Module("claude",
	fx.Provide(NewManager),
	fx.Invoke(func(lc fx.Lifecycle, mgr *Manager, logger *zap.Logger) {
		lc.Append(fx.Hook{
			OnStop: func(ctx context.Context) error {
				logger.Info("Stopping all Claude CLI processes")
				mgr.StopAll()
				return nil
			},
		})
	}),
)
```

**Step 6: Run test to verify it passes**

Run: `cd backend && go test ./internal/claude/... -v`
Expected: PASS

**Step 7: Commit**

```bash
git add backend/internal/claude/
git commit -m "feat(claude): add CLI process manager"
```

---

## Task 3.4: Create WebSocket Handler

**Files:**
- Create: `backend/internal/ws/handler.go`
- Create: `backend/internal/ws/dto.go`
- Create: `backend/internal/ws/handler_test.go`
- Modify: `backend/internal/server/server.go`
- Modify: `backend/internal/server/routes.go`

**Step 1: Write the failing test for WebSocket DTOs**

```go
// backend/internal/ws/handler_test.go
package ws

import (
	"encoding/json"
	"testing"

	"github.com/gofrs/uuid/v5"
)

func TestWSMessage_ParseIncoming(t *testing.T) {
	tests := []struct {
		name    string
		input   string
		want    IncomingMessage
		wantErr bool
	}{
		{
			name:  "valid chat message",
			input: `{"type":"chat","content":"Hello, Claude!"}`,
			want: IncomingMessage{
				Type:    MessageTypeChat,
				Content: "Hello, Claude!",
			},
			wantErr: false,
		},
		{
			name:  "valid stop message",
			input: `{"type":"stop"}`,
			want: IncomingMessage{
				Type: MessageTypeStop,
			},
			wantErr: false,
		},
		{
			name:    "invalid json",
			input:   `{invalid}`,
			wantErr: true,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			var msg IncomingMessage
			err := json.Unmarshal([]byte(tt.input), &msg)
			if tt.wantErr {
				if err == nil {
					t.Error("expected error but got nil")
				}
				return
			}
			if err != nil {
				t.Errorf("unexpected error: %v", err)
				return
			}
			if msg.Type != tt.want.Type {
				t.Errorf("expected type %q, got %q", tt.want.Type, msg.Type)
			}
			if msg.Content != tt.want.Content {
				t.Errorf("expected content %q, got %q", tt.want.Content, msg.Content)
			}
		})
	}
}

func TestOutgoingMessage_Marshal(t *testing.T) {
	convID, _ := uuid.NewV7()
	msg := OutgoingMessage{
		Type:           MessageTypeStream,
		ConversationID: convID.String(),
		Content:        "Hello from Claude!",
	}

	data, err := json.Marshal(msg)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	var parsed OutgoingMessage
	if err := json.Unmarshal(data, &parsed); err != nil {
		t.Fatalf("failed to parse: %v", err)
	}

	if parsed.Type != msg.Type {
		t.Errorf("expected type %q, got %q", msg.Type, parsed.Type)
	}
	if parsed.Content != msg.Content {
		t.Errorf("expected content %q, got %q", msg.Content, parsed.Content)
	}
}

func TestHandler_NewHandler(t *testing.T) {
	hub := NewHub()
	handler := NewHandler(hub, nil, nil, nil, nil, nil, nil)
	if handler == nil {
		t.Fatal("expected non-nil handler")
	}
	if handler.hub != hub {
		t.Error("expected hub to be set")
	}
}
```

**Step 2: Run test to verify it fails**

Run: `cd backend && go test ./internal/ws/... -v`
Expected: FAIL - types not defined

**Step 3: Write WebSocket DTOs**

```go
// backend/internal/ws/dto.go
package ws

const (
	MessageTypeChat     = "chat"
	MessageTypeStream   = "stream"
	MessageTypeStatus   = "status"
	MessageTypeError    = "error"
	MessageTypeStop     = "stop"
	MessageTypeComplete = "complete"
	MessageTypePing     = "ping"
	MessageTypePong     = "pong"
)

// IncomingMessage represents a message from the client
type IncomingMessage struct {
	Type    string `json:"type"`
	Content string `json:"content,omitempty"`
}

// OutgoingMessage represents a message to the client
type OutgoingMessage struct {
	Type           string `json:"type"`
	ConversationID string `json:"conversation_id,omitempty"`
	Content        string `json:"content,omitempty"`
	Error          string `json:"error,omitempty"`
	Status         string `json:"status,omitempty"`
}
```

**Step 4: Write WebSocket Handler**

```go
// backend/internal/ws/handler.go
package ws

import (
	"context"
	"encoding/json"
	"strings"

	"github.com/devnogari/claude-code-native/backend/internal/claude"
	"github.com/devnogari/claude-code-native/backend/internal/config"
	"github.com/devnogari/claude-code-native/backend/internal/conversation"
	"github.com/devnogari/claude-code-native/backend/internal/message"
	"github.com/devnogari/claude-code-native/backend/internal/project"
	"github.com/gofiber/contrib/websocket"
	"github.com/gofiber/fiber/v2"
	"github.com/gofrs/uuid/v5"
	"go.uber.org/zap"
)

type Handler struct {
	hub        *Hub
	config     *config.Config
	logger     *zap.Logger
	claudeMgr  *claude.Manager
	convRepo   ConversationRepository
	projRepo   ProjectRepository
	msgRepo    MessageRepository
}

type ConversationRepository interface {
	FindByID(ctx context.Context, id uuid.UUID) (*conversation.Conversation, error)
	Update(ctx context.Context, c *conversation.Conversation) error
}

type ProjectRepository interface {
	FindByID(ctx context.Context, id uuid.UUID) (*project.Project, error)
}

type MessageRepository interface {
	Create(ctx context.Context, m *message.Message) error
	FindByConversationID(ctx context.Context, conversationID uuid.UUID) ([]*message.Message, error)
}

func NewHandler(
	hub *Hub,
	config *config.Config,
	logger *zap.Logger,
	claudeMgr *claude.Manager,
	convRepo ConversationRepository,
	projRepo ProjectRepository,
	msgRepo MessageRepository,
) *Handler {
	return &Handler{
		hub:       hub,
		config:    config,
		logger:    logger,
		claudeMgr: claudeMgr,
		convRepo:  convRepo,
		projRepo:  projRepo,
		msgRepo:   msgRepo,
	}
}

// Upgrade handles WebSocket upgrade request
func (h *Handler) Upgrade(c *fiber.Ctx) error {
	if websocket.IsWebSocketUpgrade(c) {
		return c.Next()
	}
	return fiber.ErrUpgradeRequired
}

// HandleConnection handles a WebSocket connection
func (h *Handler) HandleConnection(c *websocket.Conn) {
	// Extract user ID and conversation ID from query params
	userIDStr := c.Query("user_id")
	convIDStr := c.Query("conversation_id")

	userID, err := uuid.FromString(userIDStr)
	if err != nil {
		h.sendError(c, "invalid user_id")
		return
	}

	convID, err := uuid.FromString(convIDStr)
	if err != nil {
		h.sendError(c, "invalid conversation_id")
		return
	}

	// Verify conversation exists and user has access
	ctx := context.Background()
	conv, err := h.convRepo.FindByID(ctx, convID)
	if err != nil {
		h.sendError(c, "conversation not found")
		return
	}

	// Verify project ownership
	proj, err := h.projRepo.FindByID(ctx, conv.ProjectID)
	if err != nil {
		h.sendError(c, "project not found")
		return
	}
	if proj.UserID != userID {
		h.sendError(c, "unauthorized")
		return
	}

	// Create client and register with hub
	client := NewClient(userID, convID, c)
	h.hub.Register(client)

	defer func() {
		h.hub.Unregister(client)
		c.Close()
	}()

	// Send connection status
	h.sendStatus(c, convID, "connected")

	// Start goroutine to send messages to client
	go h.writePump(client)

	// Read messages from client
	h.readPump(client)
}

func (h *Handler) readPump(client *Client) {
	for {
		_, msgBytes, err := client.Conn.ReadMessage()
		if err != nil {
			if websocket.IsUnexpectedCloseError(err, websocket.CloseGoingAway, websocket.CloseAbnormalClosure) {
				h.logger.Error("WebSocket read error", zap.Error(err))
			}
			break
		}

		var incoming IncomingMessage
		if err := json.Unmarshal(msgBytes, &incoming); err != nil {
			h.sendError(client.Conn, "invalid message format")
			continue
		}

		h.handleMessage(client, incoming)
	}
}

func (h *Handler) writePump(client *Client) {
	for msg := range client.Send {
		if err := client.Conn.WriteMessage(websocket.TextMessage, msg); err != nil {
			h.logger.Error("WebSocket write error", zap.Error(err))
			break
		}
	}
}

func (h *Handler) handleMessage(client *Client, msg IncomingMessage) {
	switch msg.Type {
	case MessageTypeChat:
		h.handleChatMessage(client, msg.Content)
	case MessageTypeStop:
		h.handleStopMessage(client)
	case MessageTypePing:
		h.sendPong(client.Conn)
	default:
		h.sendError(client.Conn, "unknown message type")
	}
}

func (h *Handler) handleChatMessage(client *Client, content string) {
	ctx := context.Background()

	// Save user message to database
	userMsg := &message.Message{
		ConversationID: client.ConversationID,
		Role:           message.RoleUser,
		Content:        content,
	}
	if err := h.msgRepo.Create(ctx, userMsg); err != nil {
		h.logger.Error("Failed to save user message", zap.Error(err))
	}

	// Get or create Claude process for this conversation
	proc := h.claudeMgr.GetProcess(client.ConversationID)
	if proc == nil {
		// Get project path
		conv, err := h.convRepo.FindByID(ctx, client.ConversationID)
		if err != nil {
			h.sendError(client.Conn, "conversation not found")
			return
		}
		proj, err := h.projRepo.FindByID(ctx, conv.ProjectID)
		if err != nil {
			h.sendError(client.Conn, "project not found")
			return
		}

		proc, err = h.claudeMgr.CreateProcess(client.ConversationID, proj.Path, nil)
		if err != nil {
			h.sendError(client.Conn, "failed to create Claude process")
			return
		}

		// Start listening to process output
		go h.streamProcessOutput(client.ConversationID, proc)
	}

	// Send input to Claude process
	proc.SendInput(content)
}

func (h *Handler) handleStopMessage(client *Client) {
	if err := h.claudeMgr.StopProcess(client.ConversationID); err != nil {
		h.logger.Error("Failed to stop process", zap.Error(err))
	}
	h.sendStatus(client.Conn, client.ConversationID, "stopped")
}

func (h *Handler) streamProcessOutput(convID uuid.UUID, proc *claude.Process) {
	ctx := context.Background()

	var contentBuilder strings.Builder
	for {
		select {
		case output, ok := <-proc.Output:
			if !ok {
				return
			}

			// Broadcast output to all clients in this conversation
			msg := OutgoingMessage{
				Type:           MessageTypeStream,
				ConversationID: convID.String(),
				Content:        output.Content,
			}
			data, _ := json.Marshal(msg)
			h.hub.BroadcastToConversation(convID, data)

			contentBuilder.WriteString(output.Content)

		case <-proc.Done:
			// Save assistant message when complete
			if contentBuilder.Len() > 0 {
				assistantMsg := &message.Message{
					ConversationID: convID,
					Role:           message.RoleAssistant,
					Content:        contentBuilder.String(),
				}
				if err := h.msgRepo.Create(ctx, assistantMsg); err != nil {
					h.logger.Error("Failed to save assistant message", zap.Error(err))
				}
			}

			// Send completion message
			completeMsg := OutgoingMessage{
				Type:           MessageTypeComplete,
				ConversationID: convID.String(),
			}
			data, _ := json.Marshal(completeMsg)
			h.hub.BroadcastToConversation(convID, data)
			return
		}
	}
}

func (h *Handler) sendError(c *websocket.Conn, errMsg string) {
	msg := OutgoingMessage{
		Type:  MessageTypeError,
		Error: errMsg,
	}
	data, _ := json.Marshal(msg)
	c.WriteMessage(websocket.TextMessage, data)
}

func (h *Handler) sendStatus(c *websocket.Conn, convID uuid.UUID, status string) {
	msg := OutgoingMessage{
		Type:           MessageTypeStatus,
		ConversationID: convID.String(),
		Status:         status,
	}
	data, _ := json.Marshal(msg)
	c.WriteMessage(websocket.TextMessage, data)
}

func (h *Handler) sendPong(c *websocket.Conn) {
	msg := OutgoingMessage{Type: MessageTypePong}
	data, _ := json.Marshal(msg)
	c.WriteMessage(websocket.TextMessage, data)
}
```

**Step 5: Run test to verify it passes**

Run: `cd backend && go test ./internal/ws/... -v`
Expected: PASS

**Step 6: Commit**

```bash
git add backend/internal/ws/
git commit -m "feat(ws): add WebSocket handler with chat message support"
```

---

## Task 3.5: Wire WebSocket Routes & Module Integration

**Files:**
- Modify: `backend/internal/ws/module.go`
- Modify: `backend/internal/server/server.go`
- Modify: `backend/internal/server/routes.go`
- Modify: `backend/cmd/server/main.go`
- Update: `backend/go.mod` (add websocket dependency)

**Step 1: Add WebSocket dependency**

Run: `cd backend && go get github.com/gofiber/contrib/websocket`
Expected: Module added to go.mod

**Step 2: Update ws module to provide Handler**

```go
// backend/internal/ws/module.go
package ws

import (
	"context"

	"github.com/devnogari/claude-code-native/backend/internal/claude"
	"github.com/devnogari/claude-code-native/backend/internal/config"
	"github.com/devnogari/claude-code-native/backend/internal/conversation"
	"github.com/devnogari/claude-code-native/backend/internal/message"
	"github.com/devnogari/claude-code-native/backend/internal/project"
	"go.uber.org/fx"
	"go.uber.org/zap"
)

var Module = fx.Module("ws",
	fx.Provide(NewHub),
	fx.Provide(func(
		hub *Hub,
		config *config.Config,
		logger *zap.Logger,
		claudeMgr *claude.Manager,
		convRepo *conversation.Repository,
		projRepo *project.Repository,
		msgRepo *message.Repository,
	) *Handler {
		return NewHandler(hub, config, logger, claudeMgr, convRepo, projRepo, msgRepo)
	}),
	fx.Invoke(func(lc fx.Lifecycle, hub *Hub) {
		lc.Append(fx.Hook{
			OnStart: func(ctx context.Context) error {
				go hub.Run()
				return nil
			},
		})
	}),
)
```

**Step 3: Update Server struct to include WebSocket handler**

Add to `backend/internal/server/server.go`:

```go
// In imports, add:
// "github.com/devnogari/claude-code-native/backend/internal/ws"

// In Server struct, add:
// wsHandler *ws.Handler

// In ServerParams, add:
// WSHandler *ws.Handler

// In New function, add to Server initialization:
// wsHandler: p.WSHandler,
```

**Step 4: Update routes to include WebSocket endpoint**

Add to `backend/internal/server/routes.go`:

```go
// In imports, add:
// "github.com/gofiber/contrib/websocket"

// In setupRoutes function, add after protected routes:

// WebSocket route (requires JWT token in query params)
s.app.Get("/ws", s.wsHandler.Upgrade, websocket.New(s.wsHandler.HandleConnection))
```

**Step 5: Update main.go to include new modules**

Add to `backend/cmd/server/main.go`:

```go
// In imports, add:
// "github.com/devnogari/claude-code-native/backend/internal/claude"
// "github.com/devnogari/claude-code-native/backend/internal/message"
// "github.com/devnogari/claude-code-native/backend/internal/ws"

// In fx.New, add modules:
// message.Module,
// claude.Module,
// ws.Module,
```

**Step 6: Verify build succeeds**

Run: `cd backend && go build ./...`
Expected: Build successful

**Step 7: Run all tests**

Run: `cd backend && go test ./... -v`
Expected: All tests pass

**Step 8: Commit**

```bash
git add backend/
git commit -m "feat: wire WebSocket and Claude CLI integration"
```

---

## Summary

Phase 3 adds real-time communication capabilities:

1. **Message Model** (Task 3.1): Stores chat messages with conversation association
2. **WebSocket Hub** (Task 3.2): Manages client connections and broadcasts
3. **Claude CLI Manager** (Task 3.3): Creates and manages CLI process lifecycle
4. **WebSocket Handler** (Task 3.4): Handles client messages and routes to Claude
5. **Module Integration** (Task 3.5): Wires everything together with fx DI

**API Endpoints Added:**
- `GET /ws?user_id=<uuid>&conversation_id=<uuid>` - WebSocket connection

**WebSocket Message Types:**
- Client → Server: `chat`, `stop`, `ping`
- Server → Client: `stream`, `status`, `error`, `complete`, `pong`

**Next Phase:** Phase 4 will implement the actual Claude CLI execution with `os/exec` and pipe handling for stdin/stdout streaming.
