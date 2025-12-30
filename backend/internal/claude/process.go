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
	Cmd            *exec.Cmd
	Status         string
	Input          chan string
	Output         chan OutputMessage
	Error          chan error
	Done           chan struct{}
	StartedAt      *time.Time
	mu             sync.RWMutex
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

// Close closes the process channels
// Done channel is closed first, then Input
func (p *Process) Close() {
	// Close Done channel first to signal shutdown
	select {
	case <-p.Done:
		// Already closed
	default:
		close(p.Done)
	}

	// Close Input channel
	select {
	case <-p.Input:
		// Drain and check if closed
	default:
	}
	// Use a sync.Once pattern or check to prevent double close
	p.mu.Lock()
	defer p.mu.Unlock()
	// Safe close of Input channel
	select {
	case _, ok := <-p.Input:
		if ok {
			// Channel not closed, drain it first
			for len(p.Input) > 0 {
				<-p.Input
			}
		}
	default:
		// Channel empty, close it
	}

	// Close input channel using a helper to avoid double close
	defer func() {
		recover() // Recover from panic if channel already closed
	}()
	close(p.Input)
}
