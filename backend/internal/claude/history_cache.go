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

// HistoryCache provides in-memory caching of Claude history with file watching
type HistoryCache struct {
	basePath string
	logger   *zap.Logger
	watcher  *fsnotify.Watcher

	mu       sync.RWMutex
	projects map[string]*ClaudeProject // keyed by encoded path
	ready    bool
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
		basePath: basePath,
		logger:   logger,
		watcher:  watcher,
		projects: make(map[string]*ClaudeProject),
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

	projects := make([]ClaudeProject, 0, len(c.projects))
	for _, p := range c.projects {
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
		}
	}

	c.logger.Info("loaded claude history cache",
		zap.Int("projects", len(c.projects)))

	return nil
}

// loadProject loads a single project
func (c *HistoryCache) loadProject(encodedPath string) (*ClaudeProject, error) {
	projectDir := filepath.Join(c.basePath, encodedPath)
	projectPath := decodeProjectPath(encodedPath)

	sessions, lastAccessed, err := c.loadSessions(projectDir)
	if err != nil {
		return nil, err
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

// loadSessions loads sessions for a project directory
func (c *HistoryCache) loadSessions(projectDir string) ([]ClaudeSession, time.Time, error) {
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

	sort.Slice(sessions, func(i, j int) bool {
		return sessions[i].UpdatedAt.After(sessions[j].UpdatedAt)
	})

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

			// Add to pending reloads
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
