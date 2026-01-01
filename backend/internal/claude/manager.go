package claude

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"

	"github.com/gofrs/uuid/v5"
	"go.uber.org/zap"
)

// Manager manages Claude CLI process sessions for conversations
type Manager struct {
	processes map[uuid.UUID]*Process
	logger    *zap.Logger
	mu        sync.RWMutex
}

// NewManager creates a new Manager with initialized process map
func NewManager(logger *zap.Logger) *Manager {
	if logger == nil {
		logger = zap.NewNop()
	}
	return &Manager{
		processes: make(map[uuid.UUID]*Process),
		logger:    logger,
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
	process := NewProcess(convID, workDir, m.logger)
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

// DeleteSession deletes the Claude CLI session files for a conversation
// This allows starting fresh without previous conversation context
func (m *Manager) DeleteSession(convID uuid.UUID, workDir string) error {
	// First stop the process if running
	_ = m.StopProcess(convID)

	// Create a temporary process just for deletion
	process := NewProcess(convID, workDir, m.logger)
	return process.DeleteSession()
}

// DeleteSessionByEncodedPath deletes the Claude CLI session files using the encoded path directly
// This is used when the session was inherited from a parent project and we know the exact location
func (m *Manager) DeleteSessionByEncodedPath(convID uuid.UUID, encodedPath string) error {
	// Validate encodedPath doesn't contain path traversal sequences
	// Encoded paths should be like "-Users-probe-git-project" with no slashes or ".."
	if strings.Contains(encodedPath, "..") || strings.Contains(encodedPath, "/") || strings.Contains(encodedPath, "\\") {
		return fmt.Errorf("invalid encoded path: contains path traversal characters")
	}

	// First stop the process if running
	_ = m.StopProcess(convID)

	homeDir, err := os.UserHomeDir()
	if err != nil {
		return fmt.Errorf("failed to get home directory: %w", err)
	}

	sessionFile := filepath.Join(
		homeDir,
		".claude",
		"projects",
		encodedPath,
		convID.String()+".jsonl",
	)

	// Also check for related files (session-env, todos, debug)
	relatedPaths := []string{
		sessionFile,
		filepath.Join(homeDir, ".claude", "session-env", convID.String()),
		filepath.Join(homeDir, ".claude", "debug", convID.String()+".txt"),
	}

	// Find and delete todo files
	todosDir := filepath.Join(homeDir, ".claude", "todos")
	if files, err := filepath.Glob(filepath.Join(todosDir, convID.String()+"*.json")); err == nil {
		relatedPaths = append(relatedPaths, files...)
	}

	var lastErr error
	for _, path := range relatedPaths {
		if err := os.Remove(path); err != nil && !os.IsNotExist(err) {
			m.logger.Warn("failed to delete session file",
				zap.String("path", path),
				zap.Error(err))
			lastErr = err
		} else if err == nil {
			m.logger.Info("deleted session file",
				zap.String("path", path))
		}
	}

	return lastErr
}
