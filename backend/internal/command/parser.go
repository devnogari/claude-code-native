package command

import (
	"context"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strings"
	"time"

	"go.uber.org/zap"
)

const (
	// MaxIncludeDepth is the maximum depth for file includes to prevent infinite loops.
	MaxIncludeDepth = 3
	// BashTimeout is the maximum time allowed for bash command execution.
	BashTimeout = 30 * time.Second
	// MaxOutputSize is the maximum size of bash command output in bytes.
	MaxOutputSize = 1024 * 1024 // 1MB
)

// BashAllowlist contains the allowed bash commands that can be executed.
var BashAllowlist = []string{
	"echo", "ls", "pwd", "date", "whoami", "git",
	"npm", "node", "cat", "grep", "find", "head", "tail",
	"wc", "sort", "uniq", "which", "env", "printenv",
}

// dangerousCharsRegex matches dangerous shell characters.
var dangerousCharsRegex = regexp.MustCompile(`[;&|` + "`" + `$()<>{}[\]\\]`)

// Parser handles command content parsing and execution.
type Parser struct {
	logger *zap.Logger
}

// NewParser creates a new command parser.
func NewParser(logger *zap.Logger) *Parser {
	return &Parser{logger: logger}
}

// ReplaceArguments replaces $ARGUMENTS and $1-$9 placeholders with actual values.
func (p *Parser) ReplaceArguments(content string, args []string) string {
	result := content

	// Replace $ARGUMENTS with all args joined by space
	allArgs := strings.Join(args, " ")
	result = strings.ReplaceAll(result, "$ARGUMENTS", allArgs)

	// Replace $1-$9 with positional arguments
	for i := 1; i <= 9; i++ {
		placeholder := fmt.Sprintf("$%d", i)
		value := ""
		if i-1 < len(args) {
			value = args[i-1]
		}
		result = strings.ReplaceAll(result, placeholder, value)
	}

	return result
}

// ProcessFileIncludes processes @filename includes in content.
// It replaces @filename patterns with the actual file contents.
func (p *Parser) ProcessFileIncludes(ctx context.Context, content, basePath string, depth int) (string, bool, error) {
	if depth >= MaxIncludeDepth {
		return "", false, fmt.Errorf("maximum include depth (%d) exceeded", MaxIncludeDepth)
	}

	// Regex to match @filename patterns (at start of line or after whitespace)
	re := regexp.MustCompile(`(?m)(?:^|\s)@([^\s]+)`)
	matches := re.FindAllStringSubmatchIndex(content, -1)

	if len(matches) == 0 {
		return content, false, nil
	}

	hasIncludes := true
	result := content

	// Process matches in reverse order to preserve indices
	for i := len(matches) - 1; i >= 0; i-- {
		match := matches[i]
		fullMatchStart := match[0]
		fullMatchEnd := match[1]
		filenameStart := match[2]
		filenameEnd := match[3]

		filename := content[filenameStart:filenameEnd]

		// Security: validate path
		if !p.isPathSafe(filename, basePath) {
			return "", false, fmt.Errorf("invalid file path (directory traversal detected): %s", filename)
		}

		filePath := filepath.Join(basePath, filename)
		fileContent, err := os.ReadFile(filePath)
		if err != nil {
			return "", false, fmt.Errorf("file not found: %s", filename)
		}

		// Recursively process includes in the included file
		processed, _, err := p.ProcessFileIncludes(ctx, string(fileContent), filepath.Dir(filePath), depth+1)
		if err != nil {
			return "", false, err
		}

		// Preserve leading whitespace if match started with whitespace
		prefix := ""
		if fullMatchStart < filenameStart-1 {
			prefix = content[fullMatchStart : filenameStart-1]
		}

		result = result[:fullMatchStart] + prefix + processed + result[fullMatchEnd:]
	}

	return result, hasIncludes, nil
}

// isPathSafe validates that a file path is safe and doesn't traverse outside the base.
func (p *Parser) isPathSafe(filePath, basePath string) bool {
	// Reject paths with obvious traversal patterns
	if strings.Contains(filePath, "..") {
		return false
	}

	// Reject absolute paths
	if filepath.IsAbs(filePath) {
		return false
	}

	// Resolve and check the final path
	absPath := filepath.Join(basePath, filePath)
	resolved, err := filepath.Abs(absPath)
	if err != nil {
		return false
	}

	baseResolved, err := filepath.Abs(basePath)
	if err != nil {
		return false
	}

	rel, err := filepath.Rel(baseResolved, resolved)
	if err != nil {
		return false
	}

	return !strings.HasPrefix(rel, "..")
}

// ValidateCommand checks if a bash command is in the allowlist.
// Returns the command name, arguments, and whether it's allowed.
func (p *Parser) ValidateCommand(commandString string) (allowed bool, cmdName string, cmdArgs []string, err error) {
	parts := strings.Fields(strings.TrimSpace(commandString))
	if len(parts) == 0 {
		return false, "", nil, fmt.Errorf("empty command")
	}

	cmdName = filepath.Base(parts[0])
	cmdArgs = parts[1:]

	// Check allowlist
	allowed = false
	for _, allowedCmd := range BashAllowlist {
		if cmdName == allowedCmd {
			allowed = true
			break
		}
	}

	if !allowed {
		return false, cmdName, cmdArgs, fmt.Errorf("command '%s' not in allowlist", cmdName)
	}

	// Check for dangerous operators in arguments
	for _, arg := range cmdArgs {
		if dangerousCharsRegex.MatchString(arg) {
			return false, cmdName, cmdArgs, fmt.Errorf("argument contains dangerous characters: %s", arg)
		}
	}

	return true, cmdName, cmdArgs, nil
}

// ProcessBashCommands executes !command lines in content and replaces them with output.
func (p *Parser) ProcessBashCommands(ctx context.Context, content, cwd string) (string, bool, error) {
	// Regex to match !command at start of line
	re := regexp.MustCompile(`(?m)^!(.+?)$`)
	matches := re.FindAllStringSubmatchIndex(content, -1)

	if len(matches) == 0 {
		return content, false, nil
	}

	hasBashCommands := true
	result := content

	// Process matches in reverse order to preserve indices
	for i := len(matches) - 1; i >= 0; i-- {
		match := matches[i]
		fullMatchStart := match[0]
		fullMatchEnd := match[1]
		cmdStart := match[2]
		cmdEnd := match[3]

		cmdString := strings.TrimSpace(content[cmdStart:cmdEnd])

		// Validate command
		allowed, cmdName, cmdArgs, err := p.ValidateCommand(cmdString)
		if !allowed {
			return "", false, fmt.Errorf("command not allowed: %s - %v", cmdString, err)
		}

		// Execute with timeout
		execCtx, cancel := context.WithTimeout(ctx, BashTimeout)
		defer cancel()

		cmd := exec.CommandContext(execCtx, cmdName, cmdArgs...)
		cmd.Dir = cwd

		output, err := cmd.Output()
		if err != nil {
			// Include stderr in error message if available
			if exitErr, ok := err.(*exec.ExitError); ok {
				return "", false, fmt.Errorf("command failed: %s - %s", cmdString, string(exitErr.Stderr))
			}
			return "", false, fmt.Errorf("command failed: %s - %v", cmdString, err)
		}

		// Limit output size
		outputStr := string(output)
		if len(outputStr) > MaxOutputSize {
			outputStr = outputStr[:MaxOutputSize] + "\n... (output truncated)"
		}

		// Sanitize output - remove control characters except newline, tab, carriage return
		outputStr = p.sanitizeOutput(outputStr)

		result = result[:fullMatchStart] + outputStr + result[fullMatchEnd:]
	}

	return result, hasBashCommands, nil
}

// sanitizeOutput removes dangerous control characters from command output.
func (p *Parser) sanitizeOutput(output string) string {
	var result strings.Builder
	for _, r := range output {
		// Allow printable characters, newline, tab, carriage return
		if r >= 32 || r == '\n' || r == '\t' || r == '\r' {
			result.WriteRune(r)
		}
	}
	return result.String()
}

// ProcessContent processes a command file content with all substitutions.
// It performs argument replacement, file includes, and bash command execution.
func (p *Parser) ProcessContent(ctx context.Context, content string, args []string, basePath, cwd string) (string, bool, bool, error) {
	// Step 1: Replace arguments
	result := p.ReplaceArguments(content, args)

	// Step 2: Process file includes
	var hasFileIncludes bool
	var err error
	result, hasFileIncludes, err = p.ProcessFileIncludes(ctx, result, basePath, 0)
	if err != nil {
		return "", false, false, fmt.Errorf("file include error: %w", err)
	}

	// Step 3: Process bash commands
	var hasBashCommands bool
	result, hasBashCommands, err = p.ProcessBashCommands(ctx, result, cwd)
	if err != nil {
		return "", false, false, fmt.Errorf("bash command error: %w", err)
	}

	return result, hasFileIncludes, hasBashCommands, nil
}

// HasFileIncludes checks if content contains @filename patterns.
func (p *Parser) HasFileIncludes(content string) bool {
	re := regexp.MustCompile(`(?m)(?:^|\s)@([^\s]+)`)
	return re.MatchString(content)
}

// HasBashCommands checks if content contains !command patterns.
func (p *Parser) HasBashCommands(content string) bool {
	re := regexp.MustCompile(`(?m)^!(.+?)$`)
	return re.MatchString(content)
}
