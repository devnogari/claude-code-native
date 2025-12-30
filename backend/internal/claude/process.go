package claude

import (
	"os/exec"
	"sync"
	"time"

	"github.com/gofrs/uuid/v5"
)

// Process status constants
const (
	ProcessStatusIdle    = "idle"
	ProcessStatusRunning = "running"
	ProcessStatusStopped = "stopped"
	ProcessStatusError   = "error"
)

// OutputMessage represents a message from the Claude CLI process
type OutputMessage struct {
	Type    string `json:"type"`    // "stdout", "stderr", "status"
	Content string `json:"content"`
}

// Process represents a Claude CLI process session
type Process struct {
	ConversationID uuid.UUID
	WorkDir        string
	Env            []string
	Cmd            *exec.Cmd
	Status         string
	Input          chan string
	Output         chan OutputMessage
	Error          chan error
	Done           chan struct{}
	StartedAt      *time.Time
	mu             sync.RWMutex
	closeOnce      sync.Once
}

// NewProcess creates a new Process for a conversation
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

// SetStatus sets the process status in a thread-safe manner
func (p *Process) SetStatus(status string) {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.Status = status
}

// GetStatus returns the current process status in a thread-safe manner
func (p *Process) GetStatus() string {
	p.mu.RLock()
	defer p.mu.RUnlock()
	return p.Status
}

// SendInput sends a message to the process input channel
func (p *Process) SendInput(msg string) {
	select {
	case p.Input <- msg:
	default:
		// Channel full, could log or handle differently
	}
}

// Close closes the process channels safely using sync.Once
// All channels are closed to prevent goroutine leaks
func (p *Process) Close() {
	p.closeOnce.Do(func() {
		close(p.Done)
		close(p.Input)
		close(p.Output)
		close(p.Error)
	})
}
