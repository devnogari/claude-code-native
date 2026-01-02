package hook

import "errors"

// SessionCompleteRequest represents the Stop hook payload from Claude CLI
type SessionCompleteRequest struct {
	SessionID      string `json:"session_id"`
	TranscriptPath string `json:"transcript_path"`
	Cwd            string `json:"cwd"`
	PermissionMode string `json:"permission_mode"`
	HookEventName  string `json:"hook_event_name"`
}

// Validate validates the SessionCompleteRequest
func (r *SessionCompleteRequest) Validate() error {
	if r.SessionID == "" {
		return errors.New("session_id is required")
	}
	if r.Cwd == "" {
		return errors.New("cwd is required")
	}
	return nil
}

// SessionCompleteResponse is the response for session complete
type SessionCompleteResponse struct {
	Success     bool   `json:"success"`
	ProjectID   string `json:"project_id,omitempty"`
	ProjectName string `json:"project_name,omitempty"`
	Message     string `json:"message,omitempty"`
}

// ErrorResponse is the standard error response
type ErrorResponse struct {
	Error   string `json:"error"`
	Details string `json:"details,omitempty"`
}
