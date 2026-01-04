package claude

import (
	"bufio"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/fsnotify/fsnotify"
	"go.uber.org/zap"
)

const (
	excludedProjectsFile        = ".excluded_projects"
	maxConcurrentProjectLoads   = 10 // Limit concurrent file I/O operations
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

	// Track excluded projects (deleted from UI)
	excludedMu sync.RWMutex
	excluded   map[string]bool // keyed by encoded path

	// Session change subscribers
	subscribersMu sync.RWMutex
	subscribers   map[string][]SessionChangeCallback // keyed by "encodedPath/sessionID"

	// Track last known message UUID per session for detecting new messages
	lastMessageUUID   map[string]string // keyed by "encodedPath/sessionID"
	lastMessageUUIDMu sync.RWMutex

	// Track last known session state for detecting state changes
	// This ensures sessionState changes (e.g., STREAMING -> IDLE) are broadcast
	// even when no new messages are detected (same UUID but updated stop_reason)
	lastSessionState   map[string]SessionState // keyed by "encodedPath/sessionID"
	lastSessionStateMu sync.RWMutex
}

// NewHistoryCache creates a new cache with file watching
// basePath should be the path to Claude base directory (e.g., ~/.claude or /claude-projects)
func NewHistoryCache(logger *zap.Logger, basePath string) (*HistoryCache, error) {
	// If basePath starts with ~, expand it
	if strings.HasPrefix(basePath, "~") {
		home, _ := os.UserHomeDir()
		basePath = strings.Replace(basePath, "~", home, 1)
	}
	// Always append "projects" subdirectory - Claude stores project data in {basePath}/projects/
	basePath = filepath.Join(basePath, "projects")
	logger.Info("HistoryCache initialized", zap.String("basePath", basePath))

	watcher, err := fsnotify.NewWatcher()
	if err != nil {
		return nil, err
	}

	cache := &HistoryCache{
		basePath:         basePath,
		logger:           logger,
		watcher:          watcher,
		projects:         make(map[string]*ClaudeProject),
		excluded:         make(map[string]bool),
		subscribers:      make(map[string][]SessionChangeCallback),
		lastMessageUUID:  make(map[string]string),
		lastSessionState: make(map[string]SessionState),
	}

	// Load excluded projects from persistent storage
	cache.loadExcludedProjects()

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
// Projects with the same path (cwd) are merged into a single entry
func (c *HistoryCache) GetProjects() []ClaudeProject {
	c.mu.RLock()
	defer c.mu.RUnlock()

	// Group projects by their actual path (cwd) to deduplicate
	// Multiple encoded paths can resolve to the same project path
	byPath := make(map[string]*ClaudeProject)
	for _, p := range c.projects {
		existing, ok := byPath[p.Path]
		if !ok {
			// First occurrence - create a deep copy to prevent data races
			// (shallow copy would share the sessions slice with original)
			copy := *p
			copy.Sessions = make([]ClaudeSession, len(p.Sessions))
			for i := range p.Sessions {
				copy.Sessions[i] = p.Sessions[i]
			}
			byPath[p.Path] = &copy
		} else {
			// Merge sessions from duplicate project
			existing.Sessions = append(existing.Sessions, p.Sessions...)
			// Update last accessed if newer
			if p.LastAccessed.After(existing.LastAccessed) {
				existing.LastAccessed = p.LastAccessed
			}
		}
	}

	// Deduplicate sessions within each merged project (by session ID)
	for _, p := range byPath {
		seen := make(map[string]bool)
		unique := make([]ClaudeSession, 0, len(p.Sessions))
		for _, s := range p.Sessions {
			if !seen[s.ID] {
				seen[s.ID] = true
				unique = append(unique, s)
			}
		}
		p.Sessions = unique
		// Re-sort sessions by updated time
		sort.Slice(p.Sessions, func(i, j int) bool {
			return p.Sessions[i].UpdatedAt.After(p.Sessions[j].UpdatedAt)
		})
	}

	projects := make([]ClaudeProject, 0, len(byPath))
	for _, p := range byPath {
		projects = append(projects, *p)
	}

	// Sort by last accessed (most recent first)
	sort.Slice(projects, func(i, j int) bool {
		return projects[i].LastAccessed.After(projects[j].LastAccessed)
	})

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
// Handles inherited sessions by checking SourceEncodedPath if file not found
func (c *HistoryCache) GetSessionMessages(encodedPath, sessionID string) ([]ClaudeMessage, error) {
	sessionFile := filepath.Join(c.basePath, encodedPath, sessionID+".jsonl")
	messages, err := parseJsonlFile(sessionFile)
	if err == nil {
		return messages, nil
	}

	// File not found - check if this is an inherited session
	if os.IsNotExist(err) {
		if sourceEncodedPath := c.findSessionSourcePath(encodedPath, sessionID); sourceEncodedPath != "" {
			sessionFile = filepath.Join(c.basePath, sourceEncodedPath, sessionID+".jsonl")
			return parseJsonlFile(sessionFile)
		}
	}
	return nil, err
}

// findSessionSourcePath looks up the SourceEncodedPath for a session in the cache
func (c *HistoryCache) findSessionSourcePath(encodedPath, sessionID string) string {
	c.mu.RLock()
	defer c.mu.RUnlock()

	project, ok := c.projects[encodedPath]
	if !ok {
		return ""
	}

	for _, session := range project.Sessions {
		if session.ID == sessionID && session.SourceEncodedPath != "" && session.SourceEncodedPath != encodedPath {
			c.logger.Debug("found inherited session source",
				zap.String("encodedPath", encodedPath),
				zap.String("sessionID", sessionID),
				zap.String("sourceEncodedPath", session.SourceEncodedPath))
			return session.SourceEncodedPath
		}
	}
	return ""
}

// Close stops the watcher
func (c *HistoryCache) Close() error {
	return c.watcher.Close()
}

// loadAll loads all projects into cache using parallel workers
func (c *HistoryCache) loadAll() error {
	entries, err := os.ReadDir(c.basePath)
	if err != nil {
		if os.IsNotExist(err) {
			return nil
		}
		return err
	}

	// Get excluded projects
	c.excludedMu.RLock()
	excludedProjects := make(map[string]bool, len(c.excluded))
	for k, v := range c.excluded {
		excludedProjects[k] = v
	}
	c.excludedMu.RUnlock()

	// Collect valid project paths
	var projectPaths []string
	for _, entry := range entries {
		if !entry.IsDir() {
			continue
		}
		encodedPath := entry.Name()
		if excludedProjects[encodedPath] {
			continue
		}
		projectPaths = append(projectPaths, encodedPath)
	}

	// Load projects in parallel with worker pool
	type result struct {
		path    string
		project *ClaudeProject
	}
	results := make(chan result, len(projectPaths))
	sem := make(chan struct{}, maxConcurrentProjectLoads)

	var wg sync.WaitGroup
	for _, path := range projectPaths {
		wg.Add(1)
		go func(encodedPath string) {
			defer wg.Done()
			sem <- struct{}{}        // Acquire
			defer func() { <-sem }() // Release

			if project, err := c.loadProject(encodedPath); err == nil {
				results <- result{path: encodedPath, project: project}
			}
		}(path)
	}

	// Close results when all workers done
	go func() {
		wg.Wait()
		close(results)
	}()

	// Collect results
	c.mu.Lock()
	defer c.mu.Unlock()
	for r := range results {
		c.projects[r.path] = r.project
	}

	c.logger.Info("loaded claude history cache",
		zap.Int("projects", len(c.projects)))

	return nil
}

// loadProject loads a single project
func (c *HistoryCache) loadProject(encodedPath string) (*ClaudeProject, error) {
	projectDir := filepath.Join(c.basePath, encodedPath)

	sessions, lastAccessed, projectPath, err := c.loadSessions(projectDir, encodedPath)
	if err != nil {
		return nil, err
	}

	// If no sessions found, try to inherit from parent project
	// (Claude CLI stores sessions in git root, not subdirectories)
	if len(sessions) == 0 {
		c.logger.Debug("no sessions found, trying parent lookup",
			zap.String("project", encodedPath))
		if parentSessions, parentLastAccessed, parentPath := c.findParentProjectSessions(encodedPath); len(parentSessions) > 0 {
			sessions = parentSessions
			if parentLastAccessed.After(lastAccessed) {
				lastAccessed = parentLastAccessed
			}
			// Use parent path if we don't have one yet
			if projectPath == "" && parentPath != "" {
				projectPath = parentPath
			}
			c.logger.Info("inherited sessions from parent project",
				zap.String("project", encodedPath),
				zap.Int("sessions", len(sessions)))
		} else {
			c.logger.Debug("no parent sessions found",
				zap.String("project", encodedPath))
		}
	}

	// Fallback to DecodeProjectPath if cwd not found in sessions
	if projectPath == "" {
		projectPath = DecodeProjectPath(encodedPath)
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
// Returns sessions, lastAccessed time, and projectPath extracted from session cwd
func (c *HistoryCache) findParentProjectSessions(encodedPath string) ([]ClaudeSession, time.Time, string) {
	// Try using DecodeProjectPath for accurate path resolution (handles dashes in directory names)
	// Falls back to encoded path segment removal for test environments
	decodedPath := DecodeProjectPath(encodedPath)

	c.logger.Debug("looking for parent sessions",
		zap.String("encodedPath", encodedPath),
		zap.String("decodedPath", decodedPath))

	// Check if decoded path exists (won't exist in test environments with synthetic paths)
	if _, err := os.Stat(decodedPath); err == nil {
		// Use filepath.Dir approach for real paths - more reliable for paths with dashes
		currentPath := decodedPath
		for {
			parentPath := filepath.Dir(currentPath)
			if parentPath == currentPath || parentPath == "/" || parentPath == "." {
				break // Reached root
			}
			currentPath = parentPath
			parentEncodedPath := EncodeProjectPath(parentPath)

			if isHomeDirectory(parentEncodedPath) {
				c.logger.Debug("skipping home directory",
					zap.String("parentEncodedPath", parentEncodedPath))
				continue
			}

			parentDir := filepath.Join(c.basePath, parentEncodedPath)
			c.logger.Debug("checking parent dir (decoded)",
				zap.String("parentEncodedPath", parentEncodedPath),
				zap.String("parentDir", parentDir))

			if info, err := os.Stat(parentDir); err == nil && info.IsDir() {
				if sessions, lastAccessed, projectPath, err := c.loadSessions(parentDir, parentEncodedPath); err == nil && len(sessions) > 0 {
					c.logger.Info("found parent sessions",
						zap.String("parentDir", parentDir),
						zap.Int("sessions", len(sessions)),
						zap.String("projectPath", projectPath))
					return sessions, lastAccessed, projectPath
				}
			}
		}
	}

	// Fallback: Split by "-" for synthetic/test paths where decoded path doesn't exist
	parts := strings.Split(encodedPath, "-")
	c.logger.Debug("using fallback segment-based parent search",
		zap.Int("parts", len(parts)))

	for i := len(parts) - 1; i > 1; i-- {
		parentEncodedPath := strings.Join(parts[:i], "-")

		if isHomeDirectory(parentEncodedPath) {
			c.logger.Debug("skipping home directory",
				zap.String("parentEncodedPath", parentEncodedPath))
			continue
		}

		parentDir := filepath.Join(c.basePath, parentEncodedPath)
		c.logger.Debug("checking parent dir (fallback)",
			zap.String("parentEncodedPath", parentEncodedPath),
			zap.String("parentDir", parentDir))

		if info, err := os.Stat(parentDir); err == nil && info.IsDir() {
			if sessions, lastAccessed, projectPath, err := c.loadSessions(parentDir, parentEncodedPath); err == nil && len(sessions) > 0 {
				c.logger.Info("found parent sessions",
					zap.String("parentDir", parentDir),
					zap.Int("sessions", len(sessions)),
					zap.String("projectPath", projectPath))
				return sessions, lastAccessed, projectPath
			}
		}
	}

	return nil, time.Time{}, ""
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
// Returns sessions, lastAccessed time, projectPath extracted from session cwd, and error
func (c *HistoryCache) loadSessions(projectDir string, encodedPath string) ([]ClaudeSession, time.Time, string, error) {
	entries, err := os.ReadDir(projectDir)
	if err != nil {
		return nil, time.Time{}, "", err
	}

	var sessions []ClaudeSession
	var lastAccessed time.Time
	var projectPath string // Extracted from session cwd

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

		modTime := info.ModTime()
		// Update lastAccessed BEFORE filtering - directory timestamp matters for project visibility
		if modTime.After(lastAccessed) {
			lastAccessed = modTime
		}

		// Use fast metadata parsing instead of full file parse
		meta, err := parseSessionMetadataFast(filePath)
		if err != nil {
			continue
		}

		// Extract cwd from session metadata
		if projectPath == "" && meta.Cwd != "" {
			projectPath = meta.Cwd
		}

		// Skip sessions with 0 messages (but lastAccessed is already updated above)
		if meta.MessageCount == 0 {
			continue
		}

		sessionID := strings.TrimSuffix(entry.Name(), ".jsonl")
		sessions = append(sessions, ClaudeSession{
			ID:                sessionID,
			Filename:          entry.Name(),
			MessageCount:      meta.MessageCount,
			FirstMessage:      truncateString(meta.FirstMessage, 100),
			CreatedAt:         meta.CreatedAt,
			UpdatedAt:         modTime,
			SourceEncodedPath: encodedPath, // Track where the session file actually resides
		})
	}

	sort.Slice(sessions, func(i, j int) bool {
		return sessions[i].UpdatedAt.After(sessions[j].UpdatedAt)
	})

	return sessions, lastAccessed, projectPath, nil
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

// DeleteProject removes a project from the cache and adds it to the excluded list
// This does NOT delete files on disk - only hides from the project list
// The exclusion is persisted to disk so it survives server restarts
func (c *HistoryCache) DeleteProject(encodedPath string) {
	// Add to excluded list first
	c.excludedMu.Lock()
	c.excluded[encodedPath] = true
	c.excludedMu.Unlock()

	// Remove from projects cache
	c.mu.Lock()
	delete(c.projects, encodedPath)
	c.mu.Unlock()

	// Persist to file
	c.saveExcludedProjects()

	c.logger.Info("deleted project from cache and added to excluded list",
		zap.String("project", encodedPath))
}

// loadExcludedProjects loads the excluded projects list from persistent storage
func (c *HistoryCache) loadExcludedProjects() {
	filePath := filepath.Join(c.basePath, excludedProjectsFile)
	file, err := os.Open(filePath)
	if err != nil {
		if !os.IsNotExist(err) {
			c.logger.Warn("failed to open excluded projects file", zap.Error(err))
		}
		return
	}
	defer file.Close()

	c.excludedMu.Lock()
	defer c.excludedMu.Unlock()

	scanner := bufio.NewScanner(file)
	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if line != "" && !strings.HasPrefix(line, "#") {
			c.excluded[line] = true
		}
	}

	if err := scanner.Err(); err != nil {
		c.logger.Warn("error reading excluded projects file", zap.Error(err))
	}

	c.logger.Info("loaded excluded projects", zap.Int("count", len(c.excluded)))
}

// saveExcludedProjects saves the excluded projects list to persistent storage
func (c *HistoryCache) saveExcludedProjects() {
	filePath := filepath.Join(c.basePath, excludedProjectsFile)

	c.excludedMu.RLock()
	projects := make([]string, 0, len(c.excluded))
	for encodedPath := range c.excluded {
		projects = append(projects, encodedPath)
	}
	c.excludedMu.RUnlock()

	// Sort for consistent file output
	sort.Strings(projects)

	file, err := os.Create(filePath)
	if err != nil {
		c.logger.Error("failed to create excluded projects file", zap.Error(err))
		return
	}
	defer file.Close()

	writer := bufio.NewWriter(file)
	writer.WriteString("# Excluded projects - these won't appear in the project list\n")
	writer.WriteString("# Delete lines to restore projects\n")
	for _, p := range projects {
		writer.WriteString(p + "\n")
	}
	writer.Flush()

	c.logger.Debug("saved excluded projects", zap.Int("count", len(projects)))
}

// GetSessionMessagesPaginated returns messages with pagination (most recent first)
// Handles inherited sessions by checking SourceEncodedPath if file not found
func (c *HistoryCache) GetSessionMessagesPaginated(encodedPath, sessionID string, limit, offset int) (*PaginatedMessages, error) {
	sessionFile := filepath.Join(c.basePath, encodedPath, sessionID+".jsonl")
	allMessages, err := parseJsonlFile(sessionFile)
	if err != nil {
		// File not found - check if this is an inherited session
		if os.IsNotExist(err) {
			if sourceEncodedPath := c.findSessionSourcePath(encodedPath, sessionID); sourceEncodedPath != "" {
				sessionFile = filepath.Join(c.basePath, sourceEncodedPath, sessionID+".jsonl")
				allMessages, err = parseJsonlFile(sessionFile)
			}
		}
		if err != nil {
			return nil, err
		}
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

	// Initialize last message UUID and session state
	c.lastMessageUUIDMu.Lock()
	if _, exists := c.lastMessageUUID[key]; !exists {
		messages, _ := c.GetSessionMessages(encodedPath, sessionID)
		// Find last message with UUID (user/assistant or queue-operation)
		lastUUID := ""
		for _, msg := range messages {
			if msg.UUID != "" {
				if msg.Message != nil && (msg.Message.Role == "user" || msg.Message.Role == "assistant") {
					lastUUID = msg.UUID
				} else if msg.Type == "queue-operation" {
					lastUUID = msg.UUID
				}
			}
		}
		c.lastMessageUUID[key] = lastUUID

		// Also initialize session state to prevent spurious notification on first file change
		c.lastSessionStateMu.Lock()
		c.lastSessionState[key] = GetSessionState(messages)
		c.lastSessionStateMu.Unlock()
	}
	c.lastMessageUUIDMu.Unlock()

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

	// Clean up UUID tracking to prevent memory leak
	c.lastMessageUUIDMu.Lock()
	delete(c.lastMessageUUID, key)
	c.lastMessageUUIDMu.Unlock()

	// Clean up session state tracking to prevent memory leak
	c.lastSessionStateMu.Lock()
	delete(c.lastSessionState, key)
	c.lastSessionStateMu.Unlock()

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

	// Calculate current session state to detect state changes
	// This ensures STREAMING -> IDLE transitions are broadcast even when
	// no new messages are detected (same UUID but updated stop_reason)
	currentSessionState := GetSessionState(messages)

	// Get last known UUID
	c.lastMessageUUIDMu.Lock()
	lastUUID := c.lastMessageUUID[key]

	// Find new messages after lastUUID
	var newMessages []ClaudeMessage
	foundLast := lastUUID == "" // If no last UUID, all messages are new
	newLastUUID := lastUUID

	for _, msg := range messages {
		// Only include user/assistant messages and queue-operation events
		isRelevant := false
		if msg.Message != nil && (msg.Message.Role == "user" || msg.Message.Role == "assistant") {
			isRelevant = true
		} else if msg.Type == "queue-operation" {
			isRelevant = true
		}

		if !isRelevant {
			continue
		}

		if foundLast {
			// Collect messages after the last known UUID
			// Note: Messages without UUIDs (e.g., queue-operation) are still collected
			// and sent to clients, but they don't update newLastUUID. This means if
			// only UUID-less messages are appended, they may be re-sent on next change.
			// This is acceptable for queue-operation events which are transient.
			newMessages = append(newMessages, msg)
			if msg.UUID != "" {
				newLastUUID = msg.UUID
			}
		} else if msg.UUID == lastUUID {
			// Found the last known message, start collecting from next
			foundLast = true
		}
	}

	// Update last UUID
	c.lastMessageUUID[key] = newLastUUID
	c.lastMessageUUIDMu.Unlock()

	// Check if session state changed
	c.lastSessionStateMu.Lock()
	lastSessionState := c.lastSessionState[key]
	sessionStateChanged := currentSessionState != lastSessionState
	if sessionStateChanged {
		c.lastSessionState[key] = currentSessionState
	}
	c.lastSessionStateMu.Unlock()

	// Skip callback only if BOTH: no new messages AND session state unchanged
	// This ensures state changes (e.g., stop_reason added to existing message)
	// are always broadcast to clients, even without new messages
	if len(newMessages) == 0 && !sessionStateChanged {
		return
	}

	c.logger.Debug("notifying session subscribers",
		zap.String("sessionID", sessionID),
		zap.Int("newMessages", len(newMessages)),
		zap.String("lastUUID", lastUUID),
		zap.String("newLastUUID", newLastUUID),
		zap.String("sessionState", string(currentSessionState)),
		zap.Bool("stateChanged", sessionStateChanged))

	// Notify all subscribers
	for _, callback := range callbacks {
		go callback(encodedPath, sessionID, newMessages)
	}
}
