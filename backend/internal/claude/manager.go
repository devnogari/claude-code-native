package claude

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"syscall"
	"time"

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
// permissionMode can be "default", "plan", or "bypassPermissions"
func (m *Manager) CreateProcess(convID uuid.UUID, workDir string, env []string, permissionMode string) (*Process, error) {
	m.mu.Lock()
	defer m.mu.Unlock()

	// Return existing process if one exists
	if existing, exists := m.processes[convID]; exists {
		// Update permission mode if changed (will apply on next StartInteractive)
		if existing.PermissionMode != permissionMode {
			m.logger.Info("permission mode changed for existing process",
				zap.String("convID", convID.String()),
				zap.String("oldMode", existing.PermissionMode),
				zap.String("newMode", permissionMode))
			existing.PermissionMode = permissionMode
		}
		return existing, nil
	}

	// Create new process with permission mode
	process := NewProcess(convID, workDir, m.logger)
	process.Env = env
	process.PermissionMode = permissionMode
	m.processes[convID] = process

	return process, nil
}

// GetProcess returns the process for a conversation or nil if not found
func (m *Manager) GetProcess(convID uuid.UUID) *Process {
	m.mu.RLock()
	defer m.mu.RUnlock()

	return m.processes[convID]
}

// StopProcess stops and removes a process for a conversation.
// It first sends SIGINT for graceful shutdown, then SIGKILL if the process
// doesn't terminate within the timeout period.
func (m *Manager) StopProcess(convID uuid.UUID) error {
	m.mu.Lock()
	process, exists := m.processes[convID]
	if !exists {
		m.mu.Unlock()
		return fmt.Errorf("process not found for conversation %s", convID)
	}

	// Remove from map immediately to prevent duplicate stop attempts
	delete(m.processes, convID)
	m.mu.Unlock()

	// Interrupt the process gracefully
	if process.Cmd != nil && process.Cmd.Process != nil {
		m.logger.Info("sending SIGINT to claude process",
			zap.String("convID", convID.String()),
			zap.Int("pid", process.Cmd.Process.Pid))

		// First, try graceful shutdown with SIGINT
		if err := process.Cmd.Process.Signal(syscall.SIGINT); err != nil {
			m.logger.Warn("failed to send SIGINT, trying SIGKILL",
				zap.String("convID", convID.String()),
				zap.Error(err))
			_ = process.Cmd.Process.Kill()
		} else {
			// Wait for process to exit gracefully (up to 2 seconds)
			// Note: If waitForExit() goroutine (in process.go) already reaped the process,
			// this Wait() will return an error, which is harmless and expected.
			done := make(chan struct{})
			go func() {
				_, _ = process.Cmd.Process.Wait()
				close(done)
			}()

			select {
			case <-done:
				m.logger.Info("claude process terminated gracefully",
					zap.String("convID", convID.String()))
			case <-time.After(2 * time.Second):
				m.logger.Warn("graceful shutdown timeout, sending SIGKILL",
					zap.String("convID", convID.String()))
				_ = process.Cmd.Process.Kill()
			}
		}
	}

	// Close the process channels
	process.Close()

	return nil
}

// StopAll stops all managed processes with graceful shutdown.
// Used during server shutdown to cleanly terminate all Claude processes.
func (m *Manager) StopAll() {
	m.mu.Lock()
	// Copy the map to avoid holding lock during shutdown
	processesCopy := make(map[uuid.UUID]*Process, len(m.processes))
	for k, v := range m.processes {
		processesCopy[k] = v
	}
	// Clear the original map
	m.processes = make(map[uuid.UUID]*Process)
	m.mu.Unlock()

	// Send SIGINT to all processes first
	for convID, process := range processesCopy {
		if process.Cmd != nil && process.Cmd.Process != nil {
			m.logger.Info("sending SIGINT to claude process during shutdown",
				zap.String("convID", convID.String()))
			_ = process.Cmd.Process.Signal(syscall.SIGINT)
		}
	}

	// Wait up to 2 seconds for all to terminate gracefully
	time.Sleep(2 * time.Second)

	// Force kill any remaining processes and close channels
	for convID, process := range processesCopy {
		if process.Cmd != nil && process.Cmd.Process != nil {
			// Check if still running by sending signal 0
			if err := process.Cmd.Process.Signal(syscall.Signal(0)); err == nil {
				m.logger.Warn("force killing claude process",
					zap.String("convID", convID.String()))
				_ = process.Cmd.Process.Kill()
			}
		}
		process.Close()
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
