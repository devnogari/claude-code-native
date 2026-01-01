package command

// Command represents a slash command available in the chat interface.
type Command struct {
	Name         string         `json:"name"`
	Path         string         `json:"path,omitempty"`
	RelativePath string         `json:"relativePath,omitempty"`
	Description  string         `json:"description"`
	Namespace    string         `json:"namespace"` // "builtin", "project", "user"
	Metadata     map[string]any `json:"metadata,omitempty"`
}

// BuiltinAction represents the action type for built-in commands.
type BuiltinAction string

const (
	ActionHelp   BuiltinAction = "help"
	ActionClear  BuiltinAction = "clear"
	ActionModel  BuiltinAction = "model"
	ActionStatus BuiltinAction = "status"
	ActionMemory BuiltinAction = "memory"
	ActionConfig BuiltinAction = "config"
	ActionRewind BuiltinAction = "rewind"
	ActionCost   BuiltinAction = "cost"
)

// Namespace constants for command categorization.
const (
	NamespaceBuiltin = "builtin"
	NamespaceProject = "project"
	NamespaceUser    = "user"
)
