package claude

import (
	"bufio"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"time"
)

// uuidRegex matches UUID format (session IDs)
var uuidRegex = regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`)

// ClaudeProject represents a discovered Claude Code project
type ClaudeProject struct {
	ID           string           `json:"id"`
	Name         string           `json:"name"`
	Path         string           `json:"path"`
	EncodedPath  string           `json:"encoded_path"`
	Sessions     []ClaudeSession  `json:"sessions"`
	LastAccessed time.Time        `json:"last_accessed"`
}

// ClaudeSession represents a conversation session in Claude Code
type ClaudeSession struct {
	ID                string          `json:"id"`
	Filename          string          `json:"filename"`
	Messages          []ClaudeMessage `json:"messages,omitempty"`
	MessageCount      int             `json:"message_count"`
	FirstMessage      string          `json:"first_message"`
	IsFavorite        bool            `json:"is_favorite"`
	CreatedAt         time.Time       `json:"created_at"`
	UpdatedAt         time.Time       `json:"updated_at"`
	SourceEncodedPath string          `json:"source_encoded_path,omitempty"` // Actual directory where session file resides (for inherited sessions)
}

// ClaudeMessage represents a single message in a Claude Code conversation
type ClaudeMessage struct {
	// Common fields
	UUID       string    `json:"uuid,omitempty"`
	ParentUUID string    `json:"parentUuid,omitempty"`
	Type       string    `json:"type"`
	SessionID  string    `json:"sessionId,omitempty"`
	Timestamp  time.Time `json:"timestamp,omitempty"`
	Cwd        string    `json:"cwd,omitempty"`
	GitBranch  string    `json:"gitBranch,omitempty"`
	IsSidechain bool     `json:"isSidechain,omitempty"`
	UserType   string    `json:"userType,omitempty"`
	Version    string    `json:"version,omitempty"`
	Slug       string    `json:"slug,omitempty"`
	AgentID    string    `json:"agentId,omitempty"` // ID for subagent messages

	// Message content (for user/assistant types)
	Message   *MessageContent `json:"message,omitempty"`
	RequestID string          `json:"requestId,omitempty"`

	// Tool use result (for user type with tool results)
	ToolUseResult *ToolUseResult `json:"toolUseResult,omitempty"`

	// Queue operation fields (type="queue-operation")
	Operation string `json:"operation,omitempty"`
	Content   string `json:"content,omitempty"`
}

// MessageContent represents the message content structure
type MessageContent struct {
	Role         string `json:"role"`
	Content      any    `json:"content"`
	Model        string `json:"model,omitempty"`
	ID           string `json:"id,omitempty"`
	Type         string `json:"type,omitempty"`
	StopReason   string `json:"stop_reason,omitempty"`
	StopSequence string `json:"stop_sequence,omitempty"`
	Usage        *Usage `json:"usage,omitempty"`
}

// Usage represents token usage information
type Usage struct {
	InputTokens              int                 `json:"input_tokens,omitempty"`
	CacheCreationInputTokens int                 `json:"cache_creation_input_tokens,omitempty"`
	CacheReadInputTokens     int                 `json:"cache_read_input_tokens,omitempty"`
	OutputTokens             int                 `json:"output_tokens,omitempty"`
	ServiceTier              string              `json:"service_tier,omitempty"`
	CacheCreation            *CacheCreationUsage `json:"cache_creation,omitempty"`
}

// CacheCreationUsage represents cache creation token breakdown
type CacheCreationUsage struct {
	Ephemeral5mInputTokens int `json:"ephemeral_5m_input_tokens,omitempty"`
	Ephemeral1hInputTokens int `json:"ephemeral_1h_input_tokens,omitempty"`
}

// ToolUseResult represents the result of a tool invocation
type ToolUseResult struct {
	Stdout      string `json:"stdout,omitempty"`
	Stderr      string `json:"stderr,omitempty"`
	Interrupted bool   `json:"interrupted,omitempty"`
	IsImage     bool   `json:"isImage,omitempty"`
}

// SessionState represents the current state of a Claude session
type SessionState string

const (
	SessionStateIdle      SessionState = "idle"      // Ready for new input
	SessionStateQueued    SessionState = "queued"    // Message queued, waiting to process
	SessionStateStreaming SessionState = "streaming" // Currently generating response
)

// GetSessionState determines the current state of a session from its messages
func GetSessionState(messages []ClaudeMessage) SessionState {
	var lastQueueOp string
	var lastRelevantMsg *ClaudeMessage

	for i := range messages {
		msg := &messages[i]
		// Track queue operations
		if msg.Type == "queue-operation" {
			lastQueueOp = msg.Operation
		}
		// Track last user/assistant message
		if msg.Message != nil && (msg.Message.Role == "user" || msg.Message.Role == "assistant") {
			lastRelevantMsg = msg
		}
	}

	// If last queue op is enqueue, message is queued
	if lastQueueOp == "enqueue" {
		return SessionStateQueued
	}
	// After dequeue/remove, fall through to check message role to determine
	// if we're streaming (last msg is user) or idle (last msg is assistant)

	// If last message is from user, assistant is streaming
	if lastRelevantMsg != nil && lastRelevantMsg.Message.Role == "user" {
		return SessionStateStreaming
	}

	return SessionStateIdle
}

// HistoryReader reads Claude Code history from ~/.claude/projects/
type HistoryReader struct {
	basePath string
}

// NewHistoryReader creates a new history reader
func NewHistoryReader(basePath string) *HistoryReader {
	if basePath == "" {
		home, _ := os.UserHomeDir()
		basePath = filepath.Join(home, ".claude", "projects")
	}
	return &HistoryReader{basePath: basePath}
}

// GetProjects returns all discovered Claude Code projects
func (h *HistoryReader) GetProjects() ([]ClaudeProject, error) {
	entries, err := os.ReadDir(h.basePath)
	if err != nil {
		if os.IsNotExist(err) {
			return []ClaudeProject{}, nil
		}
		return nil, err
	}

	var projects []ClaudeProject
	for _, entry := range entries {
		if !entry.IsDir() {
			continue
		}

		encodedPath := entry.Name()
		projectPath := DecodeProjectPath(encodedPath)
		projectDir := filepath.Join(h.basePath, encodedPath)

		// Get sessions for this project
		sessions, lastAccessed, err := h.getProjectSessions(projectDir)
		if err != nil {
			continue // Skip projects we can't read
		}

		// Extract project name from path
		name := filepath.Base(projectPath)
		if name == "" || name == "." {
			name = encodedPath
		}

		// Ensure sessions is never nil (JSON serializes nil slice as null)
		if sessions == nil {
			sessions = []ClaudeSession{}
		}
		projects = append(projects, ClaudeProject{
			ID:           encodedPath,
			Name:         name,
			Path:         projectPath,
			EncodedPath:  encodedPath,
			Sessions:     sessions,
			LastAccessed: lastAccessed,
		})
	}

	// Sort by last accessed (most recent first)
	sort.Slice(projects, func(i, j int) bool {
		return projects[i].LastAccessed.After(projects[j].LastAccessed)
	})

	// Debug: print project order
	fmt.Println("[DEBUG] Projects sorted by LastAccessed:")
	for i, p := range projects {
		fmt.Printf("[DEBUG] Project %d: %s - LastAccessed: %s\n",
			i, p.Name, p.LastAccessed.Format(time.RFC3339))
	}

	return projects, nil
}

// GetProject returns a specific project by its encoded path
func (h *HistoryReader) GetProject(encodedPath string) (*ClaudeProject, error) {
	projectDir := filepath.Join(h.basePath, encodedPath)
	info, err := os.Stat(projectDir)
	if err != nil {
		return nil, err
	}
	if !info.IsDir() {
		return nil, os.ErrNotExist
	}

	projectPath := DecodeProjectPath(encodedPath)
	sessions, lastAccessed, err := h.getProjectSessions(projectDir)
	if err != nil {
		return nil, err
	}

	name := filepath.Base(projectPath)
	if name == "" || name == "." {
		name = encodedPath
	}

	// Ensure sessions is never nil (JSON serializes nil slice as null)
	if sessions == nil {
		sessions = []ClaudeSession{}
	}

	return &ClaudeProject{
		ID:           encodedPath,
		Name:         name,
		Path:         projectPath,
		EncodedPath:  encodedPath,
		Sessions:     sessions,
		LastAccessed: lastAccessed,
	}, nil
}

// GetSessionMessages returns all messages for a specific session
func (h *HistoryReader) GetSessionMessages(encodedPath, sessionID string) ([]ClaudeMessage, error) {
	sessionFile := filepath.Join(h.basePath, encodedPath, sessionID+".jsonl")
	return parseJsonlFile(sessionFile)
}

// getProjectSessions returns all sessions for a project directory
func (h *HistoryReader) getProjectSessions(projectDir string) ([]ClaudeSession, time.Time, error) {
	entries, err := os.ReadDir(projectDir)
	if err != nil {
		return nil, time.Time{}, err
	}

	var sessions []ClaudeSession
	var lastAccessed time.Time
	sessionIDsWithJsonl := make(map[string]bool)

	// First pass: collect sessions from .jsonl files
	for _, entry := range entries {
		if entry.IsDir() || !strings.HasSuffix(entry.Name(), ".jsonl") {
			continue
		}

		// Skip agent files
		if strings.HasPrefix(entry.Name(), "agent-") {
			continue
		}

		filePath := filepath.Join(projectDir, entry.Name())
		info, err := entry.Info()
		if err != nil {
			continue
		}

		// Parse session file for summary
		messages, err := parseJsonlFile(filePath)
		if err != nil {
			continue
		}

		sessionID := strings.TrimSuffix(entry.Name(), ".jsonl")
		sessionIDsWithJsonl[sessionID] = true
		firstMsg := extractFirstUserMessage(messages)
		createdAt := extractCreatedAt(messages)
		modTime := info.ModTime()

		if modTime.After(lastAccessed) {
			lastAccessed = modTime
		}

		sessions = append(sessions, ClaudeSession{
			ID:           sessionID,
			Filename:     entry.Name(),
			MessageCount: countUserAssistantMessages(messages),
			FirstMessage: truncateString(firstMsg, 100),
			CreatedAt:    createdAt,
			UpdatedAt:    modTime,
		})
	}

	// Second pass: collect sessions from directories (UUID format without .jsonl file)
	for _, entry := range entries {
		if !entry.IsDir() {
			continue
		}

		dirName := entry.Name()
		// Skip if this session already has a .jsonl file
		if sessionIDsWithJsonl[dirName] {
			continue
		}

		// Check if directory name is a UUID (session ID format)
		if !uuidRegex.MatchString(dirName) {
			continue
		}

		info, err := entry.Info()
		if err != nil {
			continue
		}

		modTime := info.ModTime()
		if modTime.After(lastAccessed) {
			lastAccessed = modTime
		}

		// Add as empty session (no messages yet, but session exists)
		sessions = append(sessions, ClaudeSession{
			ID:           dirName,
			Filename:     "",
			MessageCount: 0,
			FirstMessage: "(Session in progress...)",
			CreatedAt:    modTime,
			UpdatedAt:    modTime,
		})
	}

	// Sort sessions by updated time (most recent first)
	sort.Slice(sessions, func(i, j int) bool {
		return sessions[i].UpdatedAt.After(sessions[j].UpdatedAt)
	})

	// Debug: print session order
	for i, s := range sessions {
		fmt.Printf("[DEBUG] Session %d: %s - UpdatedAt: %s - FirstMsg: %s\n",
			i, s.ID[:8], s.UpdatedAt.Format(time.RFC3339), truncateString(s.FirstMessage, 30))
	}

	return sessions, lastAccessed, nil
}

// DecodeProjectPath converts encoded project name back to actual path
// Claude CLI encodes paths by replacing / with -
// Problem: can't distinguish original dashes from path separators
// Solution: try decoding and verify path exists using recursive search
func DecodeProjectPath(encoded string) string {
	// Replace leading dash with /
	if strings.HasPrefix(encoded, "-") {
		encoded = "/" + encoded[1:]
	}

	// Simple case: replace all dashes with /
	simplePath := strings.ReplaceAll(encoded, "-", "/")

	// Check if simple decoded path exists
	if _, err := os.Stat(simplePath); err == nil {
		return simplePath
	}

	// Path doesn't exist - try smart decoding
	// Split by - and try to find valid path by checking filesystem
	parts := strings.Split(encoded, "-")
	if result := findValidPath("", parts); result != "" {
		return result
	}

	// Fallback to simple replacement
	return simplePath
}

// findValidPath recursively tries to find a valid path by combining segments
func findValidPath(base string, remaining []string) string {
	if len(remaining) == 0 {
		if base != "" && dirExists(base) {
			return base
		}
		return ""
	}

	// Try combining different numbers of segments with dashes
	for numSegments := 1; numSegments <= len(remaining); numSegments++ {
		// Join numSegments parts with dashes (preserving original dashes in names)
		segment := strings.Join(remaining[:numSegments], "-")

		var nextPath string
		if base == "" {
			nextPath = segment
		} else {
			nextPath = base + "/" + segment
		}

		// If this is the last segment, check if path exists
		if numSegments == len(remaining) {
			if dirExists(nextPath) {
				return nextPath
			}
		} else {
			// Check if current path exists as directory
			if dirExists(nextPath) {
				// Recursively try remaining segments
				if result := findValidPath(nextPath, remaining[numSegments:]); result != "" {
					return result
				}
			}
		}
	}

	return ""
}

// dirExists checks if a directory exists
func dirExists(path string) bool {
	info, err := os.Stat(path)
	return err == nil && info.IsDir()
}

// encodeProjectPath converts a path to encoded project name
func EncodeProjectPath(path string) string {
	// Ensure absolute path
	if !filepath.IsAbs(path) {
		if abs, err := filepath.Abs(path); err == nil {
			path = abs
		}
	}
	// Replace / with -
	encoded := strings.ReplaceAll(path, "/", "-")
	// Remove leading dash if present (from root /)
	if strings.HasPrefix(encoded, "-") {
		encoded = encoded[1:]
	}
	return encoded
}

// parseJsonlFile reads and parses a JSONL file
func parseJsonlFile(filePath string) ([]ClaudeMessage, error) {
	file, err := os.Open(filePath)
	if err != nil {
		return nil, err
	}
	defer file.Close()

	var messages []ClaudeMessage
	scanner := bufio.NewScanner(file)
	// Increase buffer size for large lines
	buf := make([]byte, 0, 64*1024)
	scanner.Buffer(buf, 10*1024*1024) // 10MB max line size

	for scanner.Scan() {
		line := scanner.Text()
		if line == "" {
			continue
		}

		var msg ClaudeMessage
		if err := json.Unmarshal([]byte(line), &msg); err != nil {
			continue // Skip malformed lines
		}
		messages = append(messages, msg)
	}

	return messages, scanner.Err()
}

// extractFirstUserMessage finds the first user message in a session
func extractFirstUserMessage(messages []ClaudeMessage) string {
	for _, msg := range messages {
		if msg.Message != nil && msg.Message.Role == "user" {
			switch content := msg.Message.Content.(type) {
			case string:
				return content
			case []interface{}:
				// Handle array content (like images + text)
				for _, item := range content {
					if m, ok := item.(map[string]interface{}); ok {
						if text, ok := m["text"].(string); ok {
							return text
						}
					}
				}
			}
		}
	}
	return ""
}

// extractCreatedAt finds the earliest timestamp in messages
func extractCreatedAt(messages []ClaudeMessage) time.Time {
	var earliest time.Time
	for _, msg := range messages {
		if !msg.Timestamp.IsZero() {
			if earliest.IsZero() || msg.Timestamp.Before(earliest) {
				earliest = msg.Timestamp
			}
		}
	}
	return earliest
}

// countUserAssistantMessages counts user and assistant messages
func countUserAssistantMessages(messages []ClaudeMessage) int {
	count := 0
	for _, msg := range messages {
		if msg.Message != nil && (msg.Message.Role == "user" || msg.Message.Role == "assistant") {
			count++
		}
	}
	return count
}

// truncateString truncates a string to maxLen characters
func truncateString(s string, maxLen int) string {
	if len(s) <= maxLen {
		return s
	}
	return s[:maxLen-3] + "..."
}

// truncateMessageContent truncates the content of a message for summary mode
func truncateMessageContent(msg *ClaudeMessage, maxLen int) {
	if msg.Message == nil || msg.Message.Content == nil {
		return
	}

	switch content := msg.Message.Content.(type) {
	case string:
		if len(content) > maxLen {
			msg.Message.Content = content[:maxLen-3] + "..."
		}
	case []interface{}:
		// Handle array content (like images + text blocks)
		for i, item := range content {
			if m, ok := item.(map[string]interface{}); ok {
				if text, ok := m["text"].(string); ok && len(text) > maxLen {
					m["text"] = text[:maxLen-3] + "..."
					content[i] = m
				}
				// For tool_use, truncate input if too large
				if input, ok := m["input"]; ok {
					if inputStr, ok := input.(string); ok && len(inputStr) > maxLen {
						m["input"] = inputStr[:maxLen-3] + "..."
						content[i] = m
					} else if inputMap, ok := input.(map[string]interface{}); ok {
						// Just mark as truncated for complex inputs
						if len(inputMap) > 0 {
							truncateMapValues(inputMap, maxLen)
						}
					}
				}
			}
		}
		msg.Message.Content = content
	}
}

// truncateMapValues truncates string values in a map recursively
func truncateMapValues(m map[string]interface{}, maxLen int) {
	for k, v := range m {
		switch val := v.(type) {
		case string:
			if len(val) > maxLen {
				m[k] = val[:maxLen-3] + "..."
			}
		case map[string]interface{}:
			truncateMapValues(val, maxLen)
		case []interface{}:
			for i, item := range val {
				if s, ok := item.(string); ok && len(s) > maxLen {
					val[i] = s[:maxLen-3] + "..."
				} else if itemMap, ok := item.(map[string]interface{}); ok {
					truncateMapValues(itemMap, maxLen)
				}
			}
		}
	}
}
