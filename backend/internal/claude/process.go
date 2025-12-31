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
	hasRun         bool // Track if process has run before (for --continue flag)
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
		p.Done = make(chan struct{})
		p.Output = make(chan OutputMessage, 100)
		p.Error = make(chan error, 10)
		p.closeOnce = sync.Once{}
	}
	p.mu.Unlock()

	// Build command arguments
	// --print: non-interactive mode, outputs response and exits
	// --output-format stream-json: stream JSON chunks for real-time updates
	// --verbose: required when using --print with stream-json
	// --resume: continue existing session, or --session-id for new session
	args := []string{
		"--print",
		"--output-format", "stream-json",
		"--verbose",
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

	// Start goroutines for I/O handling
	go p.readOutput(stdout, "stdout")
	go p.readOutput(stderr, "stderr")
	go p.waitForExit()

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
	if err != nil {
		p.Status = ProcessStatusError
		p.logger.Error("claude process exited with error",
			zap.String("conversationID", p.ConversationID.String()),
			zap.Error(err))
	} else {
		p.Status = ProcessStatusStopped
		p.logger.Info("claude process exited normally",
			zap.String("conversationID", p.ConversationID.String()))
	}
	p.mu.Unlock()

	// Signal completion
	p.Output <- OutputMessage{Type: "status", Content: "completed"}
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
