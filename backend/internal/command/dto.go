package command

import "errors"

// ListCommandsRequest represents the request body for listing commands.
type ListCommandsRequest struct {
	ProjectPath string `json:"projectPath"`
}

// Validate validates the list commands request.
func (r *ListCommandsRequest) Validate() error {
	// ProjectPath can be empty (lists only builtin and user commands)
	return nil
}

// ListCommandsResponse represents the response for listing commands.
type ListCommandsResponse struct {
	BuiltIn []Command `json:"builtIn"`
	Custom  []Command `json:"custom"`
	Count   int       `json:"count"`
}

// ExecuteCommandRequest represents the request body for executing a command.
type ExecuteCommandRequest struct {
	CommandName string         `json:"commandName"`
	CommandPath string         `json:"commandPath,omitempty"`
	Args        []string       `json:"args"`
	Context     ExecuteContext `json:"context"`
}

// ExecuteContext contains context information for command execution.
type ExecuteContext struct {
	ProjectPath string `json:"projectPath,omitempty"`
	Model       string `json:"model,omitempty"`
	Provider    string `json:"provider,omitempty"`
}

// Validate validates the execute command request.
func (r *ExecuteCommandRequest) Validate() error {
	if r.CommandName == "" {
		return errors.New("commandName is required")
	}
	return nil
}

// ExecuteCommandResponse represents the response for command execution.
type ExecuteCommandResponse struct {
	Type    string         `json:"type"` // "builtin" or "custom"
	Command string         `json:"command"`
	Action  BuiltinAction  `json:"action,omitempty"`
	Content string         `json:"content,omitempty"`
	Data    map[string]any `json:"data,omitempty"`

	// Custom command specific fields
	HasFileIncludes bool           `json:"hasFileIncludes,omitempty"`
	HasBashCommands bool           `json:"hasBashCommands,omitempty"`
	Metadata        map[string]any `json:"metadata,omitempty"`
}

// ErrorResponse represents an error response.
type ErrorResponse struct {
	Error   string `json:"error"`
	Message string `json:"message,omitempty"`
}
