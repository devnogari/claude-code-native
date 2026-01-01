package command

import (
	"bufio"
	"context"
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"strings"

	"go.uber.org/zap"
	"gopkg.in/yaml.v3"
)

// Service handles command business logic.
type Service struct {
	logger *zap.Logger
}

// NewService creates a new command service.
func NewService(logger *zap.Logger) *Service {
	return &Service{logger: logger}
}

// BuiltinCommands returns all built-in commands.
func (s *Service) BuiltinCommands() []Command {
	return []Command{
		{Name: "/help", Description: "Show help documentation for Claude Code", Namespace: NamespaceBuiltin},
		{Name: "/clear", Description: "Clear the conversation history", Namespace: NamespaceBuiltin},
		{Name: "/model", Description: "Switch or view the current AI model", Namespace: NamespaceBuiltin},
		{Name: "/cost", Description: "Display token usage and cost information", Namespace: NamespaceBuiltin},
		{Name: "/memory", Description: "Open CLAUDE.md memory file for editing", Namespace: NamespaceBuiltin},
		{Name: "/config", Description: "Open settings and configuration", Namespace: NamespaceBuiltin},
		{Name: "/status", Description: "Show system status and version information", Namespace: NamespaceBuiltin},
		{Name: "/rewind", Description: "Rewind the conversation to a previous state", Namespace: NamespaceBuiltin},
	}
}

// IsBuiltinCommand checks if a command name is a built-in command.
func (s *Service) IsBuiltinCommand(name string) bool {
	for _, cmd := range s.BuiltinCommands() {
		if cmd.Name == name {
			return true
		}
	}
	return false
}

// ScanCommands scans for custom commands in project and user directories.
func (s *Service) ScanCommands(ctx context.Context, projectPath string) ([]Command, error) {
	var commands []Command

	// Scan project-level commands
	if projectPath != "" {
		projectCommandsDir := filepath.Join(projectPath, ".claude", "commands")
		projectCommands, err := s.scanDirectory(projectCommandsDir, NamespaceProject)
		if err != nil {
			s.logger.Debug("No project commands found", zap.String("path", projectCommandsDir), zap.Error(err))
		} else {
			commands = append(commands, projectCommands...)
		}
	}

	// Scan user-level commands
	homeDir, err := os.UserHomeDir()
	if err == nil {
		userCommandsDir := filepath.Join(homeDir, ".claude", "commands")
		userCommands, err := s.scanDirectory(userCommandsDir, NamespaceUser)
		if err != nil {
			s.logger.Debug("No user commands found", zap.String("path", userCommandsDir), zap.Error(err))
		} else {
			commands = append(commands, userCommands...)
		}
	}

	return commands, nil
}

// scanDirectory recursively scans a directory for .md command files.
func (s *Service) scanDirectory(dir, namespace string) ([]Command, error) {
	var commands []Command

	// Check if directory exists
	if _, err := os.Stat(dir); os.IsNotExist(err) {
		return nil, fmt.Errorf("directory does not exist: %s", dir)
	}

	err := filepath.Walk(dir, func(path string, info os.FileInfo, err error) error {
		if err != nil {
			return err
		}

		// Skip directories and non-markdown files
		if info.IsDir() || !strings.HasSuffix(info.Name(), ".md") {
			return nil
		}

		// Get relative path for command name
		relPath, err := filepath.Rel(dir, path)
		if err != nil {
			return err
		}

		// Parse the command file
		cmd, err := s.parseCommandFile(path, relPath, namespace)
		if err != nil {
			s.logger.Warn("Failed to parse command file", zap.String("path", path), zap.Error(err))
			return nil // Continue scanning other files
		}

		commands = append(commands, cmd)
		return nil
	})

	if err != nil {
		return nil, err
	}

	return commands, nil
}

// parseCommandFile parses a markdown command file and extracts metadata.
func (s *Service) parseCommandFile(path, relPath, namespace string) (Command, error) {
	file, err := os.Open(path)
	if err != nil {
		return Command{}, err
	}
	defer file.Close()

	// Command name from relative path (remove .md extension, add / prefix)
	// Use ":" as separator for nested commands (e.g., sc/cleanup.md -> /sc:cleanup)
	// First convert path separators to colons, then add / prefix
	cmdName := strings.TrimSuffix(relPath, ".md")
	// Replace OS-specific separator (backslash on Windows, forward slash on Unix)
	cmdName = strings.ReplaceAll(cmdName, string(filepath.Separator), ":")
	// Also replace forward slash for cross-platform compatibility
	cmdName = strings.ReplaceAll(cmdName, "/", ":")
	cmdName = "/" + cmdName

	cmd := Command{
		Name:         cmdName,
		Path:         path,
		RelativePath: relPath,
		Namespace:    namespace,
		Metadata:     make(map[string]any),
	}

	// Read file content
	scanner := bufio.NewScanner(file)
	var content strings.Builder
	inFrontmatter := false
	frontmatterDone := false
	var frontmatterLines []string

	for scanner.Scan() {
		line := scanner.Text()

		// Check for frontmatter delimiters
		if line == "---" {
			if !inFrontmatter && !frontmatterDone {
				inFrontmatter = true
				continue
			} else if inFrontmatter {
				inFrontmatter = false
				frontmatterDone = true
				continue
			}
		}

		if inFrontmatter {
			frontmatterLines = append(frontmatterLines, line)
		} else {
			content.WriteString(line)
			content.WriteString("\n")
		}
	}

	// Parse frontmatter as YAML
	if len(frontmatterLines) > 0 {
		frontmatterStr := strings.Join(frontmatterLines, "\n")
		var metadata map[string]any
		if err := yaml.Unmarshal([]byte(frontmatterStr), &metadata); err == nil {
			cmd.Metadata = metadata

			// Extract description from frontmatter
			if desc, ok := metadata["description"].(string); ok {
				cmd.Description = desc
			}
		}
	}

	// If no description in frontmatter, try to extract from first heading or line
	if cmd.Description == "" {
		contentStr := strings.TrimSpace(content.String())
		lines := strings.Split(contentStr, "\n")
		for _, line := range lines {
			line = strings.TrimSpace(line)
			if line == "" {
				continue
			}
			// Remove markdown heading prefix
			if strings.HasPrefix(line, "#") {
				line = strings.TrimSpace(strings.TrimLeft(line, "#"))
			}
			cmd.Description = line
			break
		}
	}

	return cmd, nil
}

// ExecuteBuiltin executes a built-in command and returns the result.
func (s *Service) ExecuteBuiltin(ctx context.Context, name string, args []string, execCtx ExecuteContext) (*ExecuteCommandResponse, error) {
	switch name {
	case "/help":
		return s.executeHelp(ctx, args, execCtx)
	case "/clear":
		return s.executeClear(ctx, args, execCtx)
	case "/model":
		return s.executeModel(ctx, args, execCtx)
	case "/status":
		return s.executeStatus(ctx, args, execCtx)
	case "/memory":
		return s.executeMemory(ctx, args, execCtx)
	case "/config":
		return s.executeConfig(ctx, args, execCtx)
	case "/rewind":
		return s.executeRewind(ctx, args, execCtx)
	case "/cost":
		return s.executeCost(ctx, args, execCtx)
	default:
		return nil, fmt.Errorf("unknown builtin command: %s", name)
	}
}

func (s *Service) executeHelp(ctx context.Context, args []string, execCtx ExecuteContext) (*ExecuteCommandResponse, error) {
	helpContent := `# Claude Code Commands

## Built-in Commands

| Command | Description |
|---------|-------------|
| /help | Show this help documentation |
| /clear | Clear the conversation history |
| /model | Switch or view the current AI model |
| /cost | Display token usage and cost information |
| /memory | Open CLAUDE.md memory file for editing |
| /config | Open settings and configuration |
| /status | Show system status and version information |
| /rewind [n] | Rewind the conversation by n steps |

## Custom Commands

Custom commands can be created in:
- Project: .claude/commands/*.md
- User: ~/.claude/commands/*.md

### Command File Format

` + "```markdown" + `
---
description: "Brief description"
---

Your command content here.
Use $ARGUMENTS for all args or $1, $2 for positional.
Use @filename to include file contents.
Use !command to execute bash (allowlist only).
` + "```"

	return &ExecuteCommandResponse{
		Type:    "builtin",
		Command: "/help",
		Action:  ActionHelp,
		Data: map[string]any{
			"content": helpContent,
		},
	}, nil
}

func (s *Service) executeClear(ctx context.Context, args []string, execCtx ExecuteContext) (*ExecuteCommandResponse, error) {
	return &ExecuteCommandResponse{
		Type:    "builtin",
		Command: "/clear",
		Action:  ActionClear,
		Data:    map[string]any{},
	}, nil
}

func (s *Service) executeModel(ctx context.Context, args []string, execCtx ExecuteContext) (*ExecuteCommandResponse, error) {
	currentModel := execCtx.Model
	if currentModel == "" {
		currentModel = "unknown"
	}

	return &ExecuteCommandResponse{
		Type:    "builtin",
		Command: "/model",
		Action:  ActionModel,
		Data: map[string]any{
			"current": currentModel,
			"available": []string{
				"claude-sonnet-4-20250514",
				"claude-opus-4-20250514",
				"claude-3-5-haiku-20241022",
			},
		},
	}, nil
}

func (s *Service) executeStatus(ctx context.Context, args []string, execCtx ExecuteContext) (*ExecuteCommandResponse, error) {
	hostname, _ := os.Hostname()

	return &ExecuteCommandResponse{
		Type:    "builtin",
		Command: "/status",
		Action:  ActionStatus,
		Data: map[string]any{
			"version":  "1.0.0",
			"platform": runtime.GOOS,
			"arch":     runtime.GOARCH,
			"hostname": hostname,
			"goVersion": runtime.Version(),
		},
	}, nil
}

func (s *Service) executeMemory(ctx context.Context, args []string, execCtx ExecuteContext) (*ExecuteCommandResponse, error) {
	var memoryPath string

	// Check project-level CLAUDE.md first
	if execCtx.ProjectPath != "" {
		projectMemory := filepath.Join(execCtx.ProjectPath, "CLAUDE.md")
		if _, err := os.Stat(projectMemory); err == nil {
			memoryPath = projectMemory
		}
	}

	// Fall back to user-level
	if memoryPath == "" {
		homeDir, err := os.UserHomeDir()
		if err == nil {
			userMemory := filepath.Join(homeDir, ".claude", "CLAUDE.md")
			if _, err := os.Stat(userMemory); err == nil {
				memoryPath = userMemory
			}
		}
	}

	exists := memoryPath != ""

	return &ExecuteCommandResponse{
		Type:    "builtin",
		Command: "/memory",
		Action:  ActionMemory,
		Data: map[string]any{
			"path":   memoryPath,
			"exists": exists,
		},
	}, nil
}

func (s *Service) executeConfig(ctx context.Context, args []string, execCtx ExecuteContext) (*ExecuteCommandResponse, error) {
	return &ExecuteCommandResponse{
		Type:    "builtin",
		Command: "/config",
		Action:  ActionConfig,
		Data:    map[string]any{},
	}, nil
}

func (s *Service) executeRewind(ctx context.Context, args []string, execCtx ExecuteContext) (*ExecuteCommandResponse, error) {
	steps := 1
	if len(args) > 0 {
		if n, err := fmt.Sscanf(args[0], "%d", &steps); n == 1 && err == nil && steps > 0 {
			// Valid step count
		} else {
			steps = 1
		}
	}

	return &ExecuteCommandResponse{
		Type:    "builtin",
		Command: "/rewind",
		Action:  ActionRewind,
		Data: map[string]any{
			"steps": steps,
		},
	}, nil
}

func (s *Service) executeCost(ctx context.Context, args []string, execCtx ExecuteContext) (*ExecuteCommandResponse, error) {
	// Cost information would typically come from session tracking
	// For now, return placeholder data
	return &ExecuteCommandResponse{
		Type:    "builtin",
		Command: "/cost",
		Action:  ActionCost,
		Data: map[string]any{
			"inputTokens":  0,
			"outputTokens": 0,
			"totalCost":    0.0,
			"currency":     "USD",
		},
	}, nil
}
