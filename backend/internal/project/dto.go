package project

import "errors"

// CreateProjectRequest is the request body for creating a project
type CreateProjectRequest struct {
	Name string `json:"name"`
	Path string `json:"path"`
}

// Validate validates the CreateProjectRequest
func (r *CreateProjectRequest) Validate() error {
	if r.Name == "" {
		return errors.New("name is required")
	}
	if len(r.Name) > 255 {
		return errors.New("name must be 255 characters or less")
	}
	if r.Path == "" {
		return errors.New("path is required")
	}
	if len(r.Path) > 1024 {
		return errors.New("path must be 1024 characters or less")
	}
	return nil
}

// UpdateProjectRequest is the request body for updating a project
type UpdateProjectRequest struct {
	Name string `json:"name"`
}

// Validate validates the UpdateProjectRequest
func (r *UpdateProjectRequest) Validate() error {
	if r.Name == "" {
		return errors.New("name is required")
	}
	if len(r.Name) > 255 {
		return errors.New("name must be 255 characters or less")
	}
	return nil
}

// ProjectResponse is the response for project operations
type ProjectResponse struct {
	ID           string  `json:"id"`
	Name         string  `json:"name"`
	Path         string  `json:"path"`
	ClaudeID     *string `json:"claude_id,omitempty"`
	LastAccessed *string `json:"last_accessed,omitempty"`
	CreatedAt    string  `json:"created_at"`
	UpdatedAt    string  `json:"updated_at"`
}

// ErrorResponse is the standard error response
type ErrorResponse struct {
	Error   string `json:"error"`
	Details string `json:"details,omitempty"`
}

// ToResponse converts a Project model to a ProjectResponse
func ToResponse(p *Project) ProjectResponse {
	resp := ProjectResponse{
		ID:        p.ID.String(),
		Name:      p.Name,
		Path:      p.Path,
		CreatedAt: p.CreatedAt.Format("2006-01-02T15:04:05Z07:00"),
		UpdatedAt: p.UpdatedAt.Format("2006-01-02T15:04:05Z07:00"),
	}

	if p.ClaudeID != nil {
		resp.ClaudeID = p.ClaudeID
	}

	if p.LastAccessed != nil {
		la := p.LastAccessed.Format("2006-01-02T15:04:05Z07:00")
		resp.LastAccessed = &la
	}

	return resp
}

// ToResponseList converts a slice of Project models to ProjectResponses
func ToResponseList(projects []*Project) []ProjectResponse {
	responses := make([]ProjectResponse, len(projects))
	for i, p := range projects {
		responses[i] = ToResponse(p)
	}
	return responses
}
