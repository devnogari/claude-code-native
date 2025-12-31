package claude

import (
	"bufio"
	"encoding/json"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"
)

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
	ID           string          `json:"id"`
	Filename     string          `json:"filename"`
	Messages     []ClaudeMessage `json:"messages,omitempty"`
	MessageCount int             `json:"message_count"`
	FirstMessage string          `json:"first_message"`
	CreatedAt    time.Time       `json:"created_at"`
	UpdatedAt    time.Time       `json:"updated_at"`
}

// ClaudeMessage represents a single message in a Claude Code conversation
type ClaudeMessage struct {
	Type      string    `json:"type"`
	SessionID string    `json:"sessionId,omitempty"`
	Timestamp time.Time `json:"timestamp,omitempty"`
	Message   *struct {
		Role    string `json:"role"`
		Content any    `json:"content"`
	} `json:"message,omitempty"`
	Cwd         string `json:"cwd,omitempty"`
	ParentMsgID string `json:"parentMsgId,omitempty"`
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
		projectPath := decodeProjectPath(encodedPath)
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

	projectPath := decodeProjectPath(encodedPath)
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

	// Sort sessions by updated time (most recent first)
	sort.Slice(sessions, func(i, j int) bool {
		return sessions[i].UpdatedAt.After(sessions[j].UpdatedAt)
	})

	return sessions, lastAccessed, nil
}

// decodeProjectPath converts encoded project name back to actual path
func decodeProjectPath(encoded string) string {
	// Replace leading dash with /
	if strings.HasPrefix(encoded, "-") {
		encoded = "/" + encoded[1:]
	}
	// Replace remaining dashes with /
	return strings.ReplaceAll(encoded, "-", "/")
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
