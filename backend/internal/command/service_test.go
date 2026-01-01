package command

import (
	"os"
	"path/filepath"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
	"go.uber.org/zap"
)

func TestParseCommandFile_NameGeneration(t *testing.T) {
	logger := zap.NewNop()
	service := NewService(logger)

	tests := []struct {
		name        string
		relPath     string
		expectedCmd string
	}{
		{
			name:        "single level command",
			relPath:     "cleanup.md",
			expectedCmd: "/cleanup",
		},
		{
			name:        "two level nested command",
			relPath:     "sc/cleanup.md",
			expectedCmd: "/sc:cleanup",
		},
		{
			name:        "deeply nested command",
			relPath:     "my/nested/deep/cmd.md",
			expectedCmd: "/my:nested:deep:cmd",
		},
		{
			name:        "namespace with single command",
			relPath:     "devnogari/slack-notify.md",
			expectedCmd: "/devnogari:slack-notify",
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			// Create a temporary directory and command file
			tmpDir := t.TempDir()

			// Create nested directories if needed
			cmdPath := filepath.Join(tmpDir, tt.relPath)
			err := os.MkdirAll(filepath.Dir(cmdPath), 0755)
			require.NoError(t, err)

			// Write a minimal command file
			content := `---
description: "Test command"
---

Test command content.
`
			err = os.WriteFile(cmdPath, []byte(content), 0644)
			require.NoError(t, err)

			// Parse the command file
			cmd, err := service.parseCommandFile(cmdPath, tt.relPath, NamespaceUser)
			require.NoError(t, err)

			// Verify command name
			assert.Equal(t, tt.expectedCmd, cmd.Name, "command name should match expected format")
			assert.Equal(t, "Test command", cmd.Description)
			assert.Equal(t, NamespaceUser, cmd.Namespace)
			assert.Equal(t, cmdPath, cmd.Path)
			assert.Equal(t, tt.relPath, cmd.RelativePath)
		})
	}
}

func TestParseCommandFile_FrontmatterParsing(t *testing.T) {
	logger := zap.NewNop()
	service := NewService(logger)

	tests := []struct {
		name            string
		content         string
		expectedDesc    string
		expectedMeta    map[string]any
	}{
		{
			name: "with frontmatter",
			content: `---
description: "My custom command"
category: "utility"
---

Command body here.
`,
			expectedDesc: "My custom command",
			expectedMeta: map[string]any{
				"description": "My custom command",
				"category":    "utility",
			},
		},
		{
			name: "without frontmatter uses first line",
			content: `# Command Title

Some content here.
`,
			expectedDesc: "Command Title",
			expectedMeta: map[string]any{},
		},
		{
			name: "empty frontmatter",
			content: `---
---

First line as description.
`,
			expectedDesc: "First line as description.",
			expectedMeta: map[string]any{},
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			tmpDir := t.TempDir()
			cmdPath := filepath.Join(tmpDir, "test.md")

			err := os.WriteFile(cmdPath, []byte(tt.content), 0644)
			require.NoError(t, err)

			cmd, err := service.parseCommandFile(cmdPath, "test.md", NamespaceProject)
			require.NoError(t, err)

			assert.Equal(t, tt.expectedDesc, cmd.Description)
			for key, val := range tt.expectedMeta {
				assert.Equal(t, val, cmd.Metadata[key])
			}
		})
	}
}

func TestScanCommands_Integration(t *testing.T) {
	logger := zap.NewNop()
	service := NewService(logger)

	// Create a temporary commands directory
	tmpDir := t.TempDir()
	commandsDir := filepath.Join(tmpDir, ".claude", "commands")

	// Create test command structure
	// sc/cleanup.md -> /sc:cleanup
	// sc/test.md -> /sc:test
	// simple.md -> /simple
	dirs := []string{
		filepath.Join(commandsDir, "sc"),
	}
	for _, dir := range dirs {
		err := os.MkdirAll(dir, 0755)
		require.NoError(t, err)
	}

	files := map[string]string{
		filepath.Join(commandsDir, "sc", "cleanup.md"): "---\ndescription: Clean up code\n---\n\nCleanup content",
		filepath.Join(commandsDir, "sc", "test.md"):    "---\ndescription: Run tests\n---\n\nTest content",
		filepath.Join(commandsDir, "simple.md"):        "---\ndescription: Simple command\n---\n\nSimple content",
	}

	for path, content := range files {
		err := os.WriteFile(path, []byte(content), 0644)
		require.NoError(t, err)
	}

	// Scan the directory
	commands, err := service.scanDirectory(commandsDir, NamespaceUser)
	require.NoError(t, err)

	// Verify results
	assert.Len(t, commands, 3)

	// Create a map for easier lookup
	cmdMap := make(map[string]Command)
	for _, cmd := range commands {
		cmdMap[cmd.Name] = cmd
	}

	// Verify each command
	assert.Contains(t, cmdMap, "/sc:cleanup")
	assert.Contains(t, cmdMap, "/sc:test")
	assert.Contains(t, cmdMap, "/simple")

	assert.Equal(t, "Clean up code", cmdMap["/sc:cleanup"].Description)
	assert.Equal(t, "Run tests", cmdMap["/sc:test"].Description)
	assert.Equal(t, "Simple command", cmdMap["/simple"].Description)
}

func TestBuiltinCommands(t *testing.T) {
	logger := zap.NewNop()
	service := NewService(logger)

	builtins := service.BuiltinCommands()

	// Verify builtin commands exist
	expectedBuiltins := []string{"/help", "/clear", "/model", "/cost", "/memory", "/config", "/status", "/rewind"}

	assert.Len(t, builtins, len(expectedBuiltins))

	for _, expected := range expectedBuiltins {
		found := false
		for _, cmd := range builtins {
			if cmd.Name == expected {
				found = true
				assert.Equal(t, NamespaceBuiltin, cmd.Namespace)
				assert.NotEmpty(t, cmd.Description)
				break
			}
		}
		assert.True(t, found, "expected builtin command %s not found", expected)
	}
}

func TestIsBuiltinCommand(t *testing.T) {
	logger := zap.NewNop()
	service := NewService(logger)

	tests := []struct {
		name     string
		cmdName  string
		expected bool
	}{
		{"help is builtin", "/help", true},
		{"clear is builtin", "/clear", true},
		{"custom is not builtin", "/sc:cleanup", false},
		{"unknown is not builtin", "/unknown", false},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			result := service.IsBuiltinCommand(tt.cmdName)
			assert.Equal(t, tt.expected, result)
		})
	}
}
