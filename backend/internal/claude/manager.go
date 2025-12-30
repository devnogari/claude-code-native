package claude

import (
	"fmt"
	"sync"

	"github.com/gofrs/uuid/v5"
)

// Manager manages Claude CLI process sessions for conversations
type Manager struct {
	processes map[uuid.UUID]*Process
	mu        sync.RWMutex
}

// NewManager creates a new Manager with initialized process map
func NewManager() *Manager {
	return &Manager{
		processes: make(map[uuid.UUID]*Process),
	}
}

// CreateProcess creates a new process for a conversation or returns existing one
func (m *Manager) CreateProcess(convID uuid.UUID, workDir string, env []string) (*Process, error) {
	m.mu.Lock()
	defer m.mu.Unlock()

	// Return existing process if one exists
	if existing, exists := m.processes[convID]; exists {
		return existing, nil
	}

	// Create new process
	process := NewProcess(convID, workDir)
	process.Env = env
	m.processes[convID] = process

	return process, nil
}

// GetProcess returns the process for a conversation or nil if not found
func (m *Manager) GetProcess(convID uuid.UUID) *Process {
	m.mu.RLock()
	defer m.mu.RUnlock()

	return m.processes[convID]
}

// StopProcess stops and removes a process for a conversation
func (m *Manager) StopProcess(convID uuid.UUID) error {
	m.mu.Lock()
	defer m.mu.Unlock()

	process, exists := m.processes[convID]
	if !exists {
		return fmt.Errorf("process not found for conversation %s", convID)
	}

	// Kill the underlying command if running
	if process.Cmd != nil && process.Cmd.Process != nil {
		_ = process.Cmd.Process.Kill()
	}

	// Close the process channels
	process.Close()

	// Remove from map
	delete(m.processes, convID)

	return nil
}

// StopAll stops all managed processes
func (m *Manager) StopAll() {
	m.mu.Lock()
	defer m.mu.Unlock()

	for convID, process := range m.processes {
		// Kill the underlying command if running
		if process.Cmd != nil && process.Cmd.Process != nil {
			_ = process.Cmd.Process.Kill()
		}

		// Close the process channels
		process.Close()

		// Remove from map
		delete(m.processes, convID)
	}
}

// ListProcesses returns a slice of all managed processes
func (m *Manager) ListProcesses() []*Process {
	m.mu.RLock()
	defer m.mu.RUnlock()

	processes := make([]*Process, 0, len(m.processes))
	for _, process := range m.processes {
		processes = append(processes, process)
	}

	return processes
}
