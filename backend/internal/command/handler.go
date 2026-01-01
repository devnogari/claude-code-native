package command

import (
	"os"
	"path/filepath"
	"strings"

	"github.com/gofiber/fiber/v2"
	"go.uber.org/zap"
)

// Handler handles HTTP requests for command operations.
type Handler struct {
	service *Service
	parser  *Parser
	logger  *zap.Logger
}

// NewHandler creates a new command handler.
func NewHandler(service *Service, parser *Parser, logger *zap.Logger) *Handler {
	return &Handler{
		service: service,
		parser:  parser,
		logger:  logger,
	}
}

// List handles POST /api/v1/commands/list
// Returns all available commands (built-in and custom).
func (h *Handler) List(c *fiber.Ctx) error {
	var req ListCommandsRequest
	if err := c.BodyParser(&req); err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(ErrorResponse{
			Error: "invalid request body",
		})
	}

	if err := req.Validate(); err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(ErrorResponse{
			Error: err.Error(),
		})
	}

	// Get built-in commands
	builtIn := h.service.BuiltinCommands()

	// Scan for custom commands
	custom, err := h.service.ScanCommands(c.Context(), req.ProjectPath)
	if err != nil {
		h.logger.Warn("Failed to scan commands", zap.Error(err))
	}

	if custom == nil {
		custom = []Command{}
	}

	return c.Status(fiber.StatusOK).JSON(ListCommandsResponse{
		BuiltIn: builtIn,
		Custom:  custom,
		Count:   len(builtIn) + len(custom),
	})
}

// Execute handles POST /api/v1/commands/execute
// Executes a command and returns the result.
func (h *Handler) Execute(c *fiber.Ctx) error {
	var req ExecuteCommandRequest
	if err := c.BodyParser(&req); err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(ErrorResponse{
			Error: "invalid request body",
		})
	}

	if err := req.Validate(); err != nil {
		return c.Status(fiber.StatusBadRequest).JSON(ErrorResponse{
			Error: err.Error(),
		})
	}

	// Ensure args is not nil
	if req.Args == nil {
		req.Args = []string{}
	}

	// Check if it's a built-in command
	if h.service.IsBuiltinCommand(req.CommandName) {
		result, err := h.service.ExecuteBuiltin(c.Context(), req.CommandName, req.Args, req.Context)
		if err != nil {
			return c.Status(fiber.StatusInternalServerError).JSON(ErrorResponse{
				Error:   "builtin command failed",
				Message: err.Error(),
			})
		}
		return c.Status(fiber.StatusOK).JSON(result)
	}

	// Custom command - requires path
	if req.CommandPath == "" {
		return c.Status(fiber.StatusBadRequest).JSON(ErrorResponse{
			Error:   "commandPath is required for custom commands",
			Message: "Custom commands must specify the path to the command file",
		})
	}

	// Security: validate command path
	if !h.isValidCommandPath(req.CommandPath, req.Context.ProjectPath) {
		return c.Status(fiber.StatusForbidden).JSON(ErrorResponse{
			Error:   "access denied",
			Message: "command must be in .claude/commands directory",
		})
	}

	// Read command file
	content, err := os.ReadFile(req.CommandPath)
	if err != nil {
		return c.Status(fiber.StatusNotFound).JSON(ErrorResponse{
			Error:   "command not found",
			Message: req.CommandPath,
		})
	}

	// Parse frontmatter and get command content
	commandContent := h.extractContent(string(content))

	// Check for file includes and bash commands before processing
	hasFileIncludes := h.parser.HasFileIncludes(commandContent)
	hasBashCommands := h.parser.HasBashCommands(commandContent)

	// Determine base path and working directory
	basePath := filepath.Dir(req.CommandPath)
	cwd := req.Context.ProjectPath
	if cwd == "" {
		cwd = basePath
	}

	// Process content with all substitutions
	processed, _, _, err := h.parser.ProcessContent(c.Context(), commandContent, req.Args, basePath, cwd)
	if err != nil {
		return c.Status(fiber.StatusInternalServerError).JSON(ErrorResponse{
			Error:   "command processing failed",
			Message: err.Error(),
		})
	}

	return c.Status(fiber.StatusOK).JSON(ExecuteCommandResponse{
		Type:            "custom",
		Command:         req.CommandName,
		Content:         processed,
		HasFileIncludes: hasFileIncludes,
		HasBashCommands: hasBashCommands,
	})
}

// isValidCommandPath validates that the command path is within allowed directories.
func (h *Handler) isValidCommandPath(cmdPath, projectPath string) bool {
	resolved, err := filepath.Abs(cmdPath)
	if err != nil {
		return false
	}

	// Check if under user commands directory
	homeDir, err := os.UserHomeDir()
	if err == nil {
		userBase := filepath.Join(homeDir, ".claude", "commands")
		if rel, err := filepath.Rel(userBase, resolved); err == nil && !strings.HasPrefix(rel, "..") && rel != "" {
			return true
		}
	}

	// Check if under project commands directory
	if projectPath != "" {
		projectBase := filepath.Join(projectPath, ".claude", "commands")
		if rel, err := filepath.Rel(projectBase, resolved); err == nil && !strings.HasPrefix(rel, "..") && rel != "" {
			return true
		}
	}

	return false
}

// extractContent extracts the content part from a command file, skipping frontmatter.
func (h *Handler) extractContent(fileContent string) string {
	lines := strings.Split(fileContent, "\n")
	inFrontmatter := false
	var contentLines []string

	for i, line := range lines {
		if line == "---" {
			if i == 0 {
				inFrontmatter = true
				continue
			} else if inFrontmatter {
				inFrontmatter = false
				continue
			}
		}

		if !inFrontmatter {
			contentLines = append(contentLines, line)
		}
	}

	return strings.TrimSpace(strings.Join(contentLines, "\n"))
}
