package claude

import (
	"bufio"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"github.com/gofrs/uuid/v5"
	"go.uber.org/zap"
)

// StreamMessage represents a JSON message from Claude CLI stream-json output
type StreamMessage struct {
	Type    string `json:"type"`    // "system", "assistant", "result", "user"
	Subtype string `json:"subtype"` // for system messages: "init", "hook_response", etc.
	Message *struct {
		Content []ContentBlock `json:"content"`
	} `json:"message,omitempty"`
}

// ContentBlock represents a content block in an assistant message
type ContentBlock struct {
	Type string `json:"type"` // "text", "tool_use", "tool_result"
	Text string `json:"text,omitempty"`
}

// ParseStreamJSON parses a Claude CLI stream-json line and extracts text content
// Returns the extracted text and whether it's displayable content
func ParseStreamJSON(line string) (text string, isDisplayable bool) {
	var msg StreamMessage
	if err := json.Unmarshal([]byte(line), &msg); err != nil {
		fmt.Printf("[DEBUG] ParseStreamJSON: failed to unmarshal: %v, line: %s\n", err, line[:min(len(line), 200)])
		return "", false
	}

	fmt.Printf("[DEBUG] ParseStreamJSON: type=%s, subtype=%s, hasMessage=%v\n", msg.Type, msg.Subtype, msg.Message != nil)

	// Only extract text from assistant messages
	if msg.Type == "assistant" && msg.Message != nil {
		for _, block := range msg.Message.Content {
			fmt.Printf("[DEBUG] ParseStreamJSON: block type=%s, hasText=%v\n", block.Type, block.Text != "")
			if block.Type == "text" && block.Text != "" {
				return block.Text, true
			}
		}
	}

	return "", false
}

func min(a, b int) int {
	if a < b {
		return a
	}
	return b
}

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
	Output         chan OutputMessage
	Error          chan error
	Done           chan struct{}
	StartedAt      *time.Time
	logger         *zap.Logger
	mu             sync.RWMutex
	closeOnce      sync.Once
	hasRun         bool           // Track if process has run before (for --continue flag)
	wg             sync.WaitGroup // Track goroutines for cleanup synchronization
	closed         bool           // Track if channels have been closed
}

// NewProcess creates a new Process for a conversation
func NewProcess(convID uuid.UUID, workDir string, logger *zap.Logger) *Process {
	if logger == nil {
		logger = zap.NewNop()
	}
	return &Process{
		ConversationID: convID,
		WorkDir:        workDir,
		Status:         ProcessStatusIdle,
		Output:         make(chan OutputMessage, 100),
		Error:          make(chan error, 10),
		Done:           make(chan struct{}),
		logger:         logger,
	}
}

// Start launches the Claude CLI subprocess (deprecated - use StartWithPrompt)
func (p *Process) Start() error {
	return fmt.Errorf("use StartWithPrompt instead")
}

// StartWithPrompt launches Claude CLI with the given prompt
// Uses --print for non-interactive mode and --continue for conversation context
func (p *Process) StartWithPrompt(prompt string) error {
	p.mu.Lock()
	if p.Status == ProcessStatusRunning {
		p.mu.Unlock()
		return fmt.Errorf("process already running")
	}

	// Reset channels for reuse if this is a subsequent run
	if p.hasRun {
		// Release lock while waiting for goroutines to avoid deadlock
		p.mu.Unlock()
		// Wait for previous goroutines (readOutput, waitForExit) to complete
		// This prevents race condition where old goroutines send on closed channels
		p.wg.Wait()
		p.mu.Lock()
		// Now safe to recreate channels
		p.Done = make(chan struct{})
		p.Output = make(chan OutputMessage, 100)
		p.Error = make(chan error, 10)
		p.closeOnce = sync.Once{}
		p.closed = false
	}
	p.mu.Unlock()

	// Build command arguments
	// --print: non-interactive mode, outputs response and exits
	// --output-format stream-json: stream JSON chunks for real-time updates
	// --verbose: required when using --print with stream-json
	// --dangerously-skip-permissions: skip permission prompts for automated usage
	// --resume: continue existing session, or --session-id for new session
	args := []string{
		"--print",
		"--output-format", "stream-json",
		"--verbose",
		"--dangerously-skip-permissions",
	}

	// Check if a Claude session file already exists for this conversation
	sessionExists := p.checkSessionExists()
	if sessionExists {
		// Resume existing session
		args = append(args, "--resume", p.ConversationID.String())
		p.logger.Debug("resuming existing claude session",
			zap.String("conversationID", p.ConversationID.String()))
	} else {
		// Create new session with specific ID
		args = append(args, "--session-id", p.ConversationID.String())
		p.logger.Debug("creating new claude session",
			zap.String("conversationID", p.ConversationID.String()))
	}

	// Add the prompt as the final argument
	args = append(args, prompt)

	p.logger.Debug("executing claude command",
		zap.Strings("args", args),
		zap.String("workDir", p.WorkDir))

	cmd := exec.Command("claude", args...)
	cmd.Dir = p.WorkDir
	if len(p.Env) > 0 {
		cmd.Env = append(cmd.Environ(), p.Env...)
	}

	// Setup pipes (no stdin needed - prompt is passed as argument)
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		return fmt.Errorf("failed to create stdout pipe: %w", err)
	}

	stderr, err := cmd.StderrPipe()
	if err != nil {
		return fmt.Errorf("failed to create stderr pipe: %w", err)
	}

	// Start the process
	if err := cmd.Start(); err != nil {
		return fmt.Errorf("failed to start claude: %w", err)
	}

	p.mu.Lock()
	p.Cmd = cmd
	now := time.Now()
	p.StartedAt = &now
	p.Status = ProcessStatusRunning
	p.hasRun = true
	p.mu.Unlock()

	p.logger.Info("claude process started",
		zap.String("conversationID", p.ConversationID.String()),
		zap.Int("pid", cmd.Process.Pid),
		zap.String("sessionID", p.ConversationID.String()))

	// Start goroutines for I/O handling with WaitGroup tracking
	p.wg.Add(3)
	go func() {
		defer p.wg.Done()
		p.readOutput(stdout, "stdout")
	}()
	go func() {
		defer p.wg.Done()
		p.readOutput(stderr, "stderr")
	}()
	go func() {
		defer p.wg.Done()
		p.waitForExit()
	}()

	return nil
}

// readOutput reads from a pipe and sends to output channel
func (p *Process) readOutput(pipe io.Reader, outputType string) {
	scanner := bufio.NewScanner(pipe)
	// Increase buffer size for large outputs
	buf := make([]byte, 0, 64*1024)
	scanner.Buffer(buf, 1024*1024)

	for scanner.Scan() {
		line := scanner.Text()
		// Log stderr for debugging
		if outputType == "stderr" {
			p.logger.Warn("claude stderr",
				zap.String("conversationID", p.ConversationID.String()),
				zap.String("content", line))
		}
		select {
		case p.Output <- OutputMessage{Type: outputType, Content: line}:
		case <-p.Done:
			return
		}
	}

	if err := scanner.Err(); err != nil {
		p.logger.Error("error reading output",
			zap.String("type", outputType),
			zap.Error(err))
	}
}


// waitForExit waits for the process to exit and updates status
func (p *Process) waitForExit() {
	if p.Cmd == nil {
		return
	}

	err := p.Cmd.Wait()

	p.mu.Lock()
	// Check if already closed (process was stopped externally via manager.StopProcess)
	if p.closed {
		p.mu.Unlock()
		return
	}

	if err != nil {
		// "wait: no child processes" is expected when manager.StopProcess already reaped the process
		// This happens because both manager.StopProcess and waitForExit call Wait()
		errStr := err.Error()
		if strings.Contains(errStr, "no child processes") || strings.Contains(errStr, "wait: ") {
			p.Status = ProcessStatusStopped
			p.logger.Debug("claude process already reaped by manager",
				zap.String("conversationID", p.ConversationID.String()))
			p.mu.Unlock()
			p.Close()
			return
		}

		// Check if this is an exit status (normal termination with non-zero code)
		// Exit status errors are not necessarily errors - Claude CLI may exit with status on user interrupt
		if strings.Contains(errStr, "exit status") {
			p.Status = ProcessStatusStopped
			p.logger.Info("claude process exited",
				zap.String("conversationID", p.ConversationID.String()),
				zap.String("exitStatus", errStr))
		} else {
			p.Status = ProcessStatusError
			p.logger.Error("claude process exited with error",
				zap.String("conversationID", p.ConversationID.String()),
				zap.Error(err))
		}
	} else {
		p.Status = ProcessStatusStopped
		p.logger.Info("claude process exited normally",
			zap.String("conversationID", p.ConversationID.String()))
	}
	p.mu.Unlock()

	// Signal completion by sending status - use trySend to avoid panic on closed channel
	p.trySendOutput(OutputMessage{Type: "status", Content: "completed"})

	// Close channels to signal completion to all listeners
	p.Close()
}

// trySendOutput attempts to send a message to the Output channel safely
// Returns true if sent successfully, false if channel is closed or full
func (p *Process) trySendOutput(msg OutputMessage) bool {
	p.mu.RLock()
	if p.closed {
		p.mu.RUnlock()
		return false
	}
	p.mu.RUnlock()

	// Use select with default to avoid blocking
	select {
	case p.Output <- msg:
		return true
	default:
		return false
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

// Close closes the process channels safely using sync.Once
// All channels are closed to prevent goroutine leaks
func (p *Process) Close() {
	p.closeOnce.Do(func() {
		p.mu.Lock()
		p.closed = true
		p.mu.Unlock()
		close(p.Done)
		close(p.Output)
		close(p.Error)
	})
}

// checkSessionExists checks if a Claude session file exists for this conversation
// Claude stores sessions in ~/.claude/projects/<project-path>/<session-id>.jsonl
func (p *Process) checkSessionExists() bool {
	homeDir, err := os.UserHomeDir()
	if err != nil {
		return false
	}

	// Convert work directory to Claude's project path format
	// e.g., /Users/probe/git/project -> -Users-probe-git-project
	projectPath := strings.ReplaceAll(p.WorkDir, "/", "-")

	sessionFile := filepath.Join(
		homeDir,
		".claude",
		"projects",
		projectPath,
		p.ConversationID.String()+".jsonl",
	)

	p.logger.Debug("checking session file",
		zap.String("sessionFile", sessionFile))

	_, err = os.Stat(sessionFile)
	return err == nil
}

// DeleteSession deletes the Claude session file for this conversation
// Returns nil if session was deleted or didn't exist
func (p *Process) DeleteSession() error {
	homeDir, err := os.UserHomeDir()
	if err != nil {
		return fmt.Errorf("failed to get home directory: %w", err)
	}

	projectPath := strings.ReplaceAll(p.WorkDir, "/", "-")
	sessionFile := filepath.Join(
		homeDir,
		".claude",
		"projects",
		projectPath,
		p.ConversationID.String()+".jsonl",
	)

	// Also check for related files (session-env, todos, debug)
	relatedPaths := []string{
		sessionFile,
		filepath.Join(homeDir, ".claude", "session-env", p.ConversationID.String()),
		filepath.Join(homeDir, ".claude", "debug", p.ConversationID.String()+".txt"),
	}

	// Find and delete todo files
	todosDir := filepath.Join(homeDir, ".claude", "todos")
	if files, err := filepath.Glob(filepath.Join(todosDir, p.ConversationID.String()+"*.json")); err == nil {
		relatedPaths = append(relatedPaths, files...)
	}

	var lastErr error
	for _, path := range relatedPaths {
		if err := os.Remove(path); err != nil && !os.IsNotExist(err) {
			p.logger.Warn("failed to delete session file",
				zap.String("path", path),
				zap.Error(err))
			lastErr = err
		} else if err == nil {
			p.logger.Info("deleted session file",
				zap.String("path", path))
		}
	}

	return lastErr
}
