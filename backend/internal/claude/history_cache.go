package claude

import (
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/fsnotify/fsnotify"
	"go.uber.org/zap"
)

// SessionChangeCallback is called when a session file changes
type SessionChangeCallback func(encodedPath, sessionID string, newMessages []ClaudeMessage)

// PaginatedMessages contains messages with pagination info
type PaginatedMessages struct {
	Messages []ClaudeMessage `json:"messages"`
	Total    int             `json:"total"`
	Limit    int             `json:"limit"`
	Offset   int             `json:"offset"`
	HasMore  bool            `json:"has_more"`
}

// HistoryCache provides in-memory caching of Claude history with file watching
type HistoryCache struct {
	basePath string
	logger   *zap.Logger
	watcher  *fsnotify.Watcher

	mu       sync.RWMutex
	projects map[string]*ClaudeProject // keyed by encoded path
	ready    bool

	// Session change subscribers
	subscribersMu sync.RWMutex
	subscribers   map[string][]SessionChangeCallback // keyed by "encodedPath/sessionID"

	// Track last known message count per session for detecting new messages
	lastMessageCount   map[string]int // keyed by "encodedPath/sessionID"
	lastMessageCountMu sync.RWMutex
}

// NewHistoryCache creates a new cache with file watching
func NewHistoryCache(logger *zap.Logger) (*HistoryCache, error) {
	home, _ := os.UserHomeDir()
	basePath := filepath.Join(home, ".claude", "projects")

	watcher, err := fsnotify.NewWatcher()
	if err != nil {
		return nil, err
	}

	cache := &HistoryCache{
		basePath:         basePath,
		logger:           logger,
		watcher:          watcher,
		projects:         make(map[string]*ClaudeProject),
		subscribers:      make(map[string][]SessionChangeCallback),
		lastMessageCount: make(map[string]int),
	}

	// Initial load
	if err := cache.loadAll(); err != nil {
		logger.Warn("failed to load initial history", zap.Error(err))
	}
	cache.ready = true

	// Start watching
	go cache.watchLoop()

	// Add base path and all project directories to watcher
	if err := cache.setupWatchers(); err != nil {
		logger.Warn("failed to setup watchers", zap.Error(err))
	}

	return cache, nil
}

// GetProjects returns all cached projects sorted by last accessed
func (c *HistoryCache) GetProjects() []ClaudeProject {
	c.mu.RLock()
	defer c.mu.RUnlock()

	// Debug: check specific project
	if p, ok := c.projects["-Users-probe-git-devnogari-claude-code-native-frontend"]; ok {
		c.logger.Info("GetProjects: frontend project in cache",
			zap.Int("sessions", len(p.Sessions)))
	}

	projects := make([]ClaudeProject, 0, len(c.projects))
	for _, p := range c.projects {
		projects = append(projects, *p)
	}

	// Sort by last accessed (most recent first)
	sort.Slice(projects, func(i, j int) bool {
		return projects[i].LastAccessed.After(projects[j].LastAccessed)
	})

	// Debug: print project order after sorting
	c.logger.Info("GetProjects sorted order")
	for i, p := range projects {
		c.logger.Info("project order",
			zap.Int("index", i),
			zap.String("name", p.Name),
			zap.Time("lastAccessed", p.LastAccessed))
	}

	return projects
}

// GetProject returns a specific project by encoded path
func (c *HistoryCache) GetProject(encodedPath string) (*ClaudeProject, bool) {
	c.mu.RLock()
	defer c.mu.RUnlock()

	p, ok := c.projects[encodedPath]
	if !ok {
		return nil, false
	}
	// Return a copy
	copy := *p
	return &copy, true
}

// GetSessionMessages returns messages for a session (still reads from file)
func (c *HistoryCache) GetSessionMessages(encodedPath, sessionID string) ([]ClaudeMessage, error) {
	sessionFile := filepath.Join(c.basePath, encodedPath, sessionID+".jsonl")
	return parseJsonlFile(sessionFile)
}

// Close stops the watcher
func (c *HistoryCache) Close() error {
	return c.watcher.Close()
}

// loadAll loads all projects into cache
func (c *HistoryCache) loadAll() error {
	entries, err := os.ReadDir(c.basePath)
	if err != nil {
		if os.IsNotExist(err) {
			return nil
		}
		return err
	}

	c.mu.Lock()
	defer c.mu.Unlock()

	for _, entry := range entries {
		if !entry.IsDir() {
			continue
		}
		encodedPath := entry.Name()
		if project, err := c.loadProject(encodedPath); err == nil {
			c.projects[encodedPath] = project
			// Debug: log projects with inherited sessions
			if len(project.Sessions) > 0 && strings.Contains(encodedPath, "frontend") {
				c.logger.Info("loaded project with sessions",
					zap.String("project", encodedPath),
					zap.Int("sessions", len(project.Sessions)))
			}
		}
	}

	c.logger.Info("loaded claude history cache",
		zap.Int("projects", len(c.projects)))

	// Debug: verify specific project
	if p, ok := c.projects["-Users-probe-git-devnogari-claude-code-native-frontend"]; ok {
		c.logger.Info("verification: frontend project in cache",
			zap.Int("sessions", len(p.Sessions)))
	}

	return nil
}

// loadProject loads a single project
func (c *HistoryCache) loadProject(encodedPath string) (*ClaudeProject, error) {
	projectDir := filepath.Join(c.basePath, encodedPath)
	projectPath := DecodeProjectPath(encodedPath)

	sessions, lastAccessed, err := c.loadSessions(projectDir, encodedPath)
	if err != nil {
		return nil, err
	}

	// If no sessions found, try to inherit from parent project
	// (Claude CLI stores sessions in git root, not subdirectories)
	if len(sessions) == 0 {
		c.logger.Debug("no sessions found, trying parent lookup",
			zap.String("project", encodedPath))
		if parentSessions, parentLastAccessed := c.findParentProjectSessions(encodedPath); len(parentSessions) > 0 {
			sessions = parentSessions
			if parentLastAccessed.After(lastAccessed) {
				lastAccessed = parentLastAccessed
			}
			c.logger.Info("inherited sessions from parent project",
				zap.String("project", encodedPath),
				zap.Int("sessions", len(sessions)))
		} else {
			c.logger.Debug("no parent sessions found",
				zap.String("project", encodedPath))
		}
	}

	name := filepath.Base(projectPath)
	if name == "" || name == "." {
		name = encodedPath
	}

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

// findParentProjectSessions looks for sessions in parent project directories
// This handles the case where Claude CLI stores sessions in git root, not subdirectories
func (c *HistoryCache) findParentProjectSessions(encodedPath string) ([]ClaudeSession, time.Time) {
	// Try removing path segments from the end to find parent project
	parts := strings.Split(encodedPath, "-")

	c.logger.Debug("looking for parent sessions",
		zap.String("encodedPath", encodedPath),
		zap.Int("parts", len(parts)))

	for i := len(parts) - 1; i > 1; i-- {
		parentEncodedPath := strings.Join(parts[:i], "-")

		// Skip home directory - it contains unrelated sessions from ad-hoc CLI usage
		// Home directories look like: -Users-username, -home-username, -root
		if isHomeDirectory(parentEncodedPath) {
			c.logger.Debug("skipping home directory",
				zap.String("parentEncodedPath", parentEncodedPath))
			continue
		}

		parentDir := filepath.Join(c.basePath, parentEncodedPath)

		c.logger.Debug("checking parent dir",
			zap.String("parentEncodedPath", parentEncodedPath),
			zap.String("parentDir", parentDir))

		// Check if parent project directory exists
		if info, err := os.Stat(parentDir); err == nil && info.IsDir() {
			// Pass parentEncodedPath so sessions know their actual source location
			if sessions, lastAccessed, err := c.loadSessions(parentDir, parentEncodedPath); err == nil && len(sessions) > 0 {
				c.logger.Info("found parent sessions",
					zap.String("parentDir", parentDir),
					zap.Int("sessions", len(sessions)))
				return sessions, lastAccessed
			}
		}
	}

	return nil, time.Time{}
}

// isHomeDirectory checks if the encoded path represents a home directory
// Home directories should not be used for session inheritance as they contain
// unrelated sessions from ad-hoc CLI usage
func isHomeDirectory(encodedPath string) bool {
	// Common home directory patterns:
	// macOS: -Users-username
	// Linux: -home-username
	// Root: -root
	parts := strings.Split(encodedPath, "-")

	// Filter out empty parts (from leading dash)
	var filtered []string
	for _, p := range parts {
		if p != "" {
			filtered = append(filtered, p)
		}
	}

	// Home directory patterns have exactly 2 parts: [Users|home, username] or 1 part: [root]
	if len(filtered) == 2 {
		prefix := strings.ToLower(filtered[0])
		return prefix == "users" || prefix == "home"
	}
	if len(filtered) == 1 {
		return strings.ToLower(filtered[0]) == "root"
	}

	return false
}

// loadSessions loads sessions for a project directory
// encodedPath is the encoded path of the directory where session files are located (used for deletion)
func (c *HistoryCache) loadSessions(projectDir string, encodedPath string) ([]ClaudeSession, time.Time, error) {
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
		if strings.HasPrefix(entry.Name(), "agent-") {
			continue
		}

		filePath := filepath.Join(projectDir, entry.Name())
		info, err := entry.Info()
		if err != nil {
			continue
		}

		messages, err := parseJsonlFile(filePath)
		if err != nil {
			continue
		}

		sessionID := strings.TrimSuffix(entry.Name(), ".jsonl")
		firstMsg := extractFirstUserMessage(messages)
		createdAt := extractCreatedAt(messages)
		modTime := info.ModTime()
		messageCount := countUserAssistantMessages(messages)

		// Skip sessions with 0 messages
		if messageCount == 0 {
			continue
		}

		if modTime.After(lastAccessed) {
			lastAccessed = modTime
		}

		sessions = append(sessions, ClaudeSession{
			ID:                sessionID,
			Filename:          entry.Name(),
			MessageCount:      messageCount,
			FirstMessage:      truncateString(firstMsg, 100),
			CreatedAt:         createdAt,
			UpdatedAt:         modTime,
			SourceEncodedPath: encodedPath, // Track where the session file actually resides
		})
	}

	sort.Slice(sessions, func(i, j int) bool {
		return sessions[i].UpdatedAt.After(sessions[j].UpdatedAt)
	})

	// Debug: print session order for this project
	c.logger.Info("loadSessions sorted order",
		zap.String("projectDir", projectDir))
	for i, s := range sessions {
		c.logger.Info("session order",
			zap.Int("index", i),
			zap.String("sessionID", s.ID[:8]),
			zap.Time("updatedAt", s.UpdatedAt),
			zap.String("firstMsg", truncateString(s.FirstMessage, 30)))
	}

	return sessions, lastAccessed, nil
}

// setupWatchers adds the base path and project directories to the watcher
func (c *HistoryCache) setupWatchers() error {
	// Watch base path for new project directories
	if err := c.watcher.Add(c.basePath); err != nil {
		if !os.IsNotExist(err) {
			return err
		}
	}

	// Watch each project directory for session file changes
	entries, err := os.ReadDir(c.basePath)
	if err != nil {
		if os.IsNotExist(err) {
			return nil
		}
		return err
	}

	for _, entry := range entries {
		if entry.IsDir() {
			projectDir := filepath.Join(c.basePath, entry.Name())
			if err := c.watcher.Add(projectDir); err != nil {
				c.logger.Debug("failed to watch project dir",
					zap.String("dir", projectDir),
					zap.Error(err))
			}
		}
	}

	return nil
}

// watchLoop handles file system events
func (c *HistoryCache) watchLoop() {
	// Debounce timer to batch rapid changes
	var debounceTimer *time.Timer
	pendingReloads := make(map[string]bool)
	var pendingMu sync.Mutex

	for {
		select {
		case event, ok := <-c.watcher.Events:
			if !ok {
				return
			}

			// Determine which project was affected
			encodedPath := c.getEncodedPathFromEvent(event.Name)
			if encodedPath == "" {
				// Might be a new project directory
				if event.Has(fsnotify.Create) {
					c.handleNewProject(event.Name)
				}
				continue
			}

			// Notify subscribers immediately for real-time updates
			go c.notifySessionSubscribers(encodedPath, event.Name)

			// Add to pending reloads for cache update
			pendingMu.Lock()
			pendingReloads[encodedPath] = true

			// Reset debounce timer
			if debounceTimer != nil {
				debounceTimer.Stop()
			}
			debounceTimer = time.AfterFunc(100*time.Millisecond, func() {
				pendingMu.Lock()
				toReload := make([]string, 0, len(pendingReloads))
				for ep := range pendingReloads {
					toReload = append(toReload, ep)
				}
				pendingReloads = make(map[string]bool)
				pendingMu.Unlock()

				for _, ep := range toReload {
					c.reloadProject(ep)
				}
			})
			pendingMu.Unlock()

		case err, ok := <-c.watcher.Errors:
			if !ok {
				return
			}
			c.logger.Error("watcher error", zap.Error(err))
		}
	}
}

// getEncodedPathFromEvent extracts the project's encoded path from a file path
func (c *HistoryCache) getEncodedPathFromEvent(path string) string {
	rel, err := filepath.Rel(c.basePath, path)
	if err != nil {
		return ""
	}

	parts := strings.Split(rel, string(filepath.Separator))
	if len(parts) == 0 {
		return ""
	}

	return parts[0]
}

// handleNewProject handles creation of a new project directory
func (c *HistoryCache) handleNewProject(path string) {
	info, err := os.Stat(path)
	if err != nil || !info.IsDir() {
		return
	}

	encodedPath := filepath.Base(path)

	// Add watcher for new project
	if err := c.watcher.Add(path); err != nil {
		c.logger.Debug("failed to watch new project",
			zap.String("path", path),
			zap.Error(err))
	}

	// Load the project
	c.reloadProject(encodedPath)
}

// reloadProject reloads a single project into cache
func (c *HistoryCache) reloadProject(encodedPath string) {
	projectDir := filepath.Join(c.basePath, encodedPath)

	// Check if directory still exists
	if _, err := os.Stat(projectDir); os.IsNotExist(err) {
		// Project was deleted
		c.mu.Lock()
		delete(c.projects, encodedPath)
		c.mu.Unlock()
		c.logger.Debug("removed project from cache", zap.String("project", encodedPath))
		return
	}

	project, err := c.loadProject(encodedPath)
	if err != nil {
		c.logger.Debug("failed to reload project",
			zap.String("project", encodedPath),
			zap.Error(err))
		return
	}

	c.mu.Lock()
	c.projects[encodedPath] = project
	c.mu.Unlock()

	c.logger.Debug("reloaded project",
		zap.String("project", encodedPath),
		zap.Int("sessions", len(project.Sessions)))
}

// Refresh forces a full reload of all projects
func (c *HistoryCache) Refresh() {
	if err := c.loadAll(); err != nil {
		c.logger.Error("failed to refresh cache", zap.Error(err))
	}
}

// GetSessionMessagesPaginated returns messages with pagination (most recent first)
func (c *HistoryCache) GetSessionMessagesPaginated(encodedPath, sessionID string, limit, offset int) (*PaginatedMessages, error) {
	sessionFile := filepath.Join(c.basePath, encodedPath, sessionID+".jsonl")
	allMessages, err := parseJsonlFile(sessionFile)
	if err != nil {
		return nil, err
	}

	// Filter only user/assistant messages
	var filtered []ClaudeMessage
	for _, msg := range allMessages {
		if msg.Message != nil && (msg.Message.Role == "user" || msg.Message.Role == "assistant") {
			filtered = append(filtered, msg)
		}
	}

	total := len(filtered)

	// Calculate pagination (offset from end for most recent first)
	// offset=0 means most recent messages
	startIdx := total - offset - limit
	if startIdx < 0 {
		startIdx = 0
	}
	endIdx := total - offset
	if endIdx < 0 {
		endIdx = 0
	}
	if endIdx > total {
		endIdx = total
	}

	var result []ClaudeMessage
	if startIdx < endIdx {
		result = filtered[startIdx:endIdx]
	}

	return &PaginatedMessages{
		Messages: result,
		Total:    total,
		Limit:    limit,
		Offset:   offset,
		HasMore:  startIdx > 0,
	}, nil
}

// Subscribe registers a callback for session file changes
func (c *HistoryCache) Subscribe(encodedPath, sessionID string, callback SessionChangeCallback) {
	key := encodedPath + "/" + sessionID
	c.subscribersMu.Lock()
	defer c.subscribersMu.Unlock()
	c.subscribers[key] = append(c.subscribers[key], callback)

	// Initialize last message count
	c.lastMessageCountMu.Lock()
	if _, exists := c.lastMessageCount[key]; !exists {
		messages, _ := c.GetSessionMessages(encodedPath, sessionID)
		count := 0
		for _, msg := range messages {
			if msg.Message != nil && (msg.Message.Role == "user" || msg.Message.Role == "assistant") {
				count++
			}
		}
		c.lastMessageCount[key] = count
	}
	c.lastMessageCountMu.Unlock()

	c.logger.Debug("subscribed to session changes",
		zap.String("encodedPath", encodedPath),
		zap.String("sessionID", sessionID))
}

// Unsubscribe removes all callbacks for a session
func (c *HistoryCache) Unsubscribe(encodedPath, sessionID string) {
	key := encodedPath + "/" + sessionID
	c.subscribersMu.Lock()
	defer c.subscribersMu.Unlock()
	delete(c.subscribers, key)

	c.logger.Debug("unsubscribed from session changes",
		zap.String("encodedPath", encodedPath),
		zap.String("sessionID", sessionID))
}

// notifySessionSubscribers notifies subscribers when a session file changes
func (c *HistoryCache) notifySessionSubscribers(encodedPath string, filePath string) {
	// Extract session ID from file path
	filename := filepath.Base(filePath)
	if !strings.HasSuffix(filename, ".jsonl") || strings.HasPrefix(filename, "agent-") {
		return
	}
	sessionID := strings.TrimSuffix(filename, ".jsonl")
	key := encodedPath + "/" + sessionID

	// Check if there are subscribers
	c.subscribersMu.RLock()
	callbacks := c.subscribers[key]
	c.subscribersMu.RUnlock()

	if len(callbacks) == 0 {
		return
	}

	// Read current messages (all messages including queue-operation)
	messages, err := c.GetSessionMessages(encodedPath, sessionID)
	if err != nil {
		c.logger.Error("failed to read session for notification",
			zap.String("sessionID", sessionID),
			zap.Error(err))
		return
	}

	// Filter user/assistant messages AND queue-operation events
	var filtered []ClaudeMessage
	for _, msg := range messages {
		// Include user/assistant messages
		if msg.Message != nil && (msg.Message.Role == "user" || msg.Message.Role == "assistant") {
			filtered = append(filtered, msg)
		}
		// Also include queue-operation events (for queued message display)
		if msg.Type == "queue-operation" {
			filtered = append(filtered, msg)
		}
	}

	// Check for new messages
	c.lastMessageCountMu.Lock()
	lastCount := c.lastMessageCount[key]
	currentCount := len(filtered)
	c.lastMessageCount[key] = currentCount
	c.lastMessageCountMu.Unlock()

	if currentCount <= lastCount {
		return // No new messages
	}

	// Get only new messages
	newMessages := filtered[lastCount:]

	c.logger.Debug("notifying session subscribers",
		zap.String("sessionID", sessionID),
		zap.Int("newMessages", len(newMessages)))

	// Notify all subscribers
	for _, callback := range callbacks {
		go callback(encodedPath, sessionID, newMessages)
	}
}
