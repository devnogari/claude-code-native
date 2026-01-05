package claude

import (
	"bufio"
	"encoding/json"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"time"
)

// uuidRegex matches UUID format (session IDs)
var uuidRegex = regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`)

// maxMetadataScanLines is the number of lines to parse for metadata in parseSessionMetadataFast.
// Metadata (cwd, timestamp, first message) typically appears in the first few lines.
const maxMetadataScanLines = 5

// ClaudeProject represents a discovered Claude Code project
type ClaudeProject struct {
	ID           string          `json:"id"`
	Name         string          `json:"name"`
	Path         string          `json:"path"`
	EncodedPath  string          `json:"encoded_path"`
	Sessions     []ClaudeSession `json:"sessions"`
	LastAccessed time.Time       `json:"last_accessed"`
	IsCompleted  bool            `json:"is_completed"` // Completion status from database
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

// TodoItem represents a single todo item from Claude Code's TodoWrite tool
type TodoItem struct {
	Content    string `json:"content"`              // Task description (imperative form)
	Status     string `json:"status"`               // pending, in_progress, completed
	ActiveForm string `json:"activeForm,omitempty"` // Present continuous form shown during execution
	Priority   string `json:"priority,omitempty"`   // Optional: high, medium, low
	ID         string `json:"id,omitempty"`         // Optional: task ID
}

// SessionState represents the current state of a Claude session
type SessionState string

const (
	SessionStateIdle      SessionState = "idle"      // Ready for new input
	SessionStateQueued    SessionState = "queued"    // Message queued, waiting to process
	SessionStateStreaming SessionState = "streaming" // Currently generating response
)

// StreamingTimeout is the duration after which a message without stop_reason
// is considered complete (handles interrupted/crashed sessions)
const StreamingTimeout = 30 * time.Second

// GetSessionState determines the current state of a session from its messages
func GetSessionState(messages []ClaudeMessage) SessionState {
	var lastQueueOp string
	var lastRelevantMsg *ClaudeMessage
	var lastAssistantMsgID string

	// Track stop_reason by message ID (Claude API message ID, not UUID)
	// Claude CLI appends multiple JSONL lines for streaming chunks with the same message.ID
	// Only the final chunk has stop_reason set
	stopReasonByMsgID := make(map[string]string)

	for i := range messages {
		msg := &messages[i]
		// Track queue operations
		if msg.Type == "queue-operation" {
			lastQueueOp = msg.Operation
		}
		// Track last user/assistant message
		if msg.Message != nil && (msg.Message.Role == "user" || msg.Message.Role == "assistant") {
			lastRelevantMsg = msg

			// For assistant messages, track stop_reason by message ID
			// The message.ID is the Claude API message ID (same across streaming chunks)
			if msg.Message.Role == "assistant" && msg.Message.ID != "" {
				lastAssistantMsgID = msg.Message.ID
				// Keep the stop_reason if it's set (later chunks may have it)
				if msg.Message.StopReason != "" {
					stopReasonByMsgID[msg.Message.ID] = msg.Message.StopReason
				}
			}
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

	// If last message is from assistant
	if lastRelevantMsg != nil && lastRelevantMsg.Message.Role == "assistant" {
		// If we have a message ID, check if ANY chunk of that message has stop_reason
		// This handles Claude CLI's streaming behavior where multiple JSONL lines share
		// the same message ID but only the final line has stop_reason set
		if lastAssistantMsgID != "" {
			if stopReasonByMsgID[lastAssistantMsgID] != "" {
				return SessionStateIdle
			}
		} else {
			// Fallback: if no message ID, check stop_reason on the last message directly
			if lastRelevantMsg.Message.StopReason != "" {
				return SessionStateIdle
			}
		}

		// No stop_reason found - check if message is old enough to be considered complete
		// This handles interrupted/crashed sessions where stop_reason was never written
		if !lastRelevantMsg.Timestamp.IsZero() && time.Since(lastRelevantMsg.Timestamp) > StreamingTimeout {
			return SessionStateIdle
		}

		// Recent message without stop_reason means streaming is still in progress
		return SessionStateStreaming
	}

	return SessionStateIdle
}

// HistoryReader reads Claude Code history from ~/.claude/projects/
type HistoryReader struct {
	basePath string
	logger   Logger
}

// Logger interface for HistoryReader (minimal interface to avoid zap dependency)
type Logger interface {
	Debug(msg string, fields ...interface{})
	Warn(msg string, fields ...interface{})
}

// noopLogger is a no-op logger for when no logger is provided
type noopLogger struct{}

func (n noopLogger) Debug(msg string, fields ...interface{}) {}
func (n noopLogger) Warn(msg string, fields ...interface{})  {}

// NewHistoryReader creates a new history reader
func NewHistoryReader(basePath string) *HistoryReader {
	if basePath == "" {
		home, _ := os.UserHomeDir()
		basePath = filepath.Join(home, ".claude", "projects")
	}
	return &HistoryReader{basePath: basePath, logger: noopLogger{}}
}

// NewHistoryReaderWithLogger creates a new history reader with a logger
func NewHistoryReaderWithLogger(basePath string, logger Logger) *HistoryReader {
	reader := NewHistoryReader(basePath)
	if logger != nil {
		reader.logger = logger
	}
	return reader
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

		// Skip git worktree directories (they reference the same git repo as parent)
		if isWorktreePath(projectPath) {
			h.logger.Debug("skipping worktree project", "path", projectPath)
			continue
		}

		// Get sessions for this project
		sessions, lastAccessed, err := h.getProjectSessions(projectDir)
		if err != nil {
			h.logger.Debug("skipping project due to error", "dir", projectDir, "error", err)
			continue
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

	return projects, nil
}

// GetProject returns a specific project by its encoded path
func (h *HistoryReader) GetProject(encodedPath string) (*ClaudeProject, error) {
	projectPath := DecodeProjectPath(encodedPath)

	// Skip git worktree directories (they reference the same git repo as parent)
	if isWorktreePath(projectPath) {
		return nil, os.ErrNotExist
	}

	projectDir := filepath.Join(h.basePath, encodedPath)
	info, err := os.Stat(projectDir)
	if err != nil {
		return nil, err
	}
	if !info.IsDir() {
		return nil, os.ErrNotExist
	}

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

	// Pre-process: replace -- with hidden dir marker (/.hidden)
	// Claude CLI encodes /.dir as --dir (both / and leading . become -)
	const hiddenMarker = "\x00HIDDEN\x00"
	processedEncoded := strings.ReplaceAll(encoded, "--", hiddenMarker)

	// Simple case: replace all dashes with /
	simplePath := strings.ReplaceAll(encoded, "-", "/")

	// Check if simple decoded path exists
	if _, err := os.Stat(simplePath); err == nil {
		return simplePath
	}

	// Smart decoding: split by - and try to find valid path
	// This preserves hyphens in directory names like "claude-code-native"
	parts := strings.Split(processedEncoded, "-")
	if result := findValidPathWithHidden(parts, hiddenMarker); result != "" {
		return result
	}

	// Path doesn't exist - try to decode with best effort
	// Use hidden dir decoding for non-existent paths
	hiddenPath := decodeWithHiddenDirs(encoded)
	if hiddenPath != simplePath {
		return hiddenPath
	}

	// Fallback to simple replacement
	return simplePath
}

// decodeWithHiddenDirs decodes path where -- represents /. (hidden directory)
// Claude CLI encodes both / and leading . as -, so /.hidden becomes --hidden
func decodeWithHiddenDirs(encoded string) string {
	// -- means /. (slash followed by dot for hidden directory)
	// Replace -- with /. first
	result := strings.ReplaceAll(encoded, "--", "/.")

	// Replace remaining - with /
	result = strings.ReplaceAll(result, "-", "/")

	return result
}

// findValidPathWithHidden handles path decoding with hidden directory markers
// The hiddenMarker represents /. (hidden directory) from the original path
func findValidPathWithHidden(parts []string, hiddenMarker string) string {
	// Try to find the longest existing prefix using smart decoding
	// Then decode remaining parts with hidden dir rules
	longestPrefix, remainingParts := findLongestExistingPath("", parts, hiddenMarker)

	if longestPrefix != "" {
		if len(remainingParts) > 0 {
			// Decode remaining with simple rules (join with /, marker -> /.)
			remaining := decodeRemainingParts(remainingParts, hiddenMarker)
			return longestPrefix + "/" + remaining
		}
		return longestPrefix
	}

	// No existing prefix found - decode everything with hidden dir rules
	return decodeRemainingParts(parts, hiddenMarker)
}

// findLongestExistingPath finds the longest path that exists on filesystem
// using smart decoding (trying different segment combinations)
// Returns the longest existing path and remaining parts
func findLongestExistingPath(base string, remaining []string, hiddenMarker string) (string, []string) {
	if len(remaining) == 0 {
		return base, nil
	}

	var longestPath string
	var longestRemainingIdx int

	// Try combining different numbers of segments
	for numSegments := 1; numSegments <= len(remaining); numSegments++ {
		segment := strings.Join(remaining[:numSegments], "-")

		// Handle hidden directory marker in segment
		if strings.Contains(segment, hiddenMarker) {
			segment = strings.ReplaceAll(segment, hiddenMarker, "/.")
		}

		var nextPath string
		if base == "" {
			nextPath = segment
		} else {
			nextPath = base + "/" + segment
		}

		if dirExists(nextPath) {
			// Found a valid path, try to extend further
			extendedPath, extendedRemaining := findLongestExistingPath(nextPath, remaining[numSegments:], hiddenMarker)
			if len(extendedPath) > len(longestPath) {
				longestPath = extendedPath
				longestRemainingIdx = len(remaining) - len(extendedRemaining)
			}
		}
	}

	if longestPath != "" {
		return longestPath, remaining[longestRemainingIdx:]
	}

	// No valid path found from this base, return base as is
	return base, remaining
}

// decodeRemainingParts decodes remaining path parts using simple rules
func decodeRemainingParts(parts []string, hiddenMarker string) string {
	var result strings.Builder

	for i, part := range parts {
		if part == "" {
			continue
		}

		// Handle hidden directory marker in part
		if strings.Contains(part, hiddenMarker) {
			subParts := strings.Split(part, hiddenMarker)
			for j, subPart := range subParts {
				if j > 0 {
					result.WriteString("/.")
				} else if result.Len() > 0 {
					result.WriteString("/")
				}
				result.WriteString(subPart)
			}
		} else {
			if i > 0 || result.Len() > 0 {
				result.WriteString("/")
			}
			result.WriteString(part)
		}
	}

	return result.String()
}

// findValidPath recursively tries to find a valid path by combining segments
// hiddenMarker is used to identify hidden directories (/.dir)
func findValidPath(base string, remaining []string) string {
	const hiddenMarker = "\x00HIDDEN\x00"

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

		// Handle hidden directory marker in segment
		// e.g., "native\x00HIDDEN\x00worktrees" -> "native/.worktrees"
		if strings.Contains(segment, hiddenMarker) {
			segment = strings.ReplaceAll(segment, hiddenMarker, "/.")
		}

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

// SessionMetadata contains only the essential info needed for listing
type SessionMetadata struct {
	Cwd          string
	FirstMessage string
	CreatedAt    time.Time
	MessageCount int
}

// parseSessionMetadataFast extracts session metadata without parsing entire file
// This is much faster than parseJsonlFile for large session files
func parseSessionMetadataFast(filePath string) (*SessionMetadata, error) {
	file, err := os.Open(filePath)
	if err != nil {
		return nil, err
	}
	defer file.Close()

	meta := &SessionMetadata{}
	scanner := bufio.NewScanner(file)
	buf := make([]byte, 0, 64*1024)
	scanner.Buffer(buf, 10*1024*1024)

	lineCount := 0
	for scanner.Scan() {
		line := scanner.Text()
		if line == "" {
			continue
		}
		lineCount++

		// Fast message counting using string search (avoid full JSON parse)
		// Note: This may have rare false positives if role strings appear in content,
		// but the trade-off for performance is acceptable since counts are approximate.
		// Handle common JSON spacing variants.
		if strings.Contains(line, `"role":"user"`) || strings.Contains(line, `"role": "user"`) ||
			strings.Contains(line, `"role":"assistant"`) || strings.Contains(line, `"role": "assistant"`) {
			meta.MessageCount++
		}

		// Only parse JSON for first few lines to get metadata
		if lineCount <= maxMetadataScanLines && (meta.Cwd == "" || meta.FirstMessage == "" || meta.CreatedAt.IsZero()) {
			var msg ClaudeMessage
			if err := json.Unmarshal([]byte(line), &msg); err == nil {
				if meta.Cwd == "" && msg.Cwd != "" {
					meta.Cwd = msg.Cwd
				}
				if meta.CreatedAt.IsZero() && !msg.Timestamp.IsZero() {
					meta.CreatedAt = msg.Timestamp
				}
				if meta.FirstMessage == "" && msg.Message != nil && msg.Message.Role == "user" {
					switch content := msg.Message.Content.(type) {
					case string:
						meta.FirstMessage = content
					case []interface{}:
						for _, item := range content {
							if m, ok := item.(map[string]interface{}); ok {
								if text, ok := m["text"].(string); ok {
									meta.FirstMessage = text
									break
								}
							}
						}
					}
				}
			}
		}
	}

	return meta, scanner.Err()
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

// GetSessionTodos reads todo items for a session from ~/.claude/todos/{sessionId}*.json
// Returns nil if no todos file exists (not an error condition)
func GetSessionTodos(sessionID string) ([]TodoItem, error) {
	home, err := os.UserHomeDir()
	if err != nil {
		return nil, nil
	}

	todosDir := filepath.Join(home, ".claude", "todos")

	// Find todo files matching the session ID pattern
	pattern := filepath.Join(todosDir, sessionID+"*.json")
	files, err := filepath.Glob(pattern)
	if err != nil || len(files) == 0 {
		return nil, nil
	}

	// Use the most recently modified todo file
	var latestFile string
	var latestTime time.Time
	for _, file := range files {
		info, err := os.Stat(file)
		if err != nil {
			continue
		}
		if info.ModTime().After(latestTime) {
			latestTime = info.ModTime()
			latestFile = file
		}
	}

	if latestFile == "" {
		return nil, nil
	}

	// Read and parse the todo file
	data, err := os.ReadFile(latestFile)
	if err != nil {
		return nil, nil
	}

	var todos []TodoItem
	if err := json.Unmarshal(data, &todos); err != nil {
		return nil, nil
	}

	return todos, nil
}

// isWorktreePath checks if a path is inside a git worktree directory.
// Worktrees share the same git repository as their parent, so syncing them
// would create duplicate entries for the same conversations.
// Used in GetProjects() and GetProject() to filter out worktree directories.
func isWorktreePath(path string) bool {
	// Normalize backslashes to forward slashes for consistent cross-platform matching
	// Note: filepath.ToSlash only converts the current OS separator, so we handle
	// backslashes explicitly for Windows paths processed on any OS
	normalized := strings.ReplaceAll(path, "\\", "/")

	// Common worktree directory patterns
	worktreePatterns := []string{
		"/.worktrees/",
		"/worktrees/",
	}

	for _, pattern := range worktreePatterns {
		if strings.Contains(normalized, pattern) {
			return true
		}
	}
	return false
}
