package conversation

// CreateConversationRequest is the request body for creating a conversation
type CreateConversationRequest struct {
	ProjectID string  `json:"project_id"`
	Title     *string `json:"title,omitempty"`
}

// UpdateConversationRequest is the request body for updating a conversation
type UpdateConversationRequest struct {
	Title *string `json:"title"`
}

// ConversationResponse is the response for conversation operations
type ConversationResponse struct {
	ID            string  `json:"id"`
	ProjectID     string  `json:"project_id"`
	ClaudeSession *string `json:"claude_session,omitempty"`
	Title         *string `json:"title,omitempty"`
	MessageCount  int     `json:"message_count"`
	JsonlPath     *string `json:"jsonl_path,omitempty"`
	IsFavorite    bool    `json:"is_favorite"`
	CreatedAt     string  `json:"created_at"`
	UpdatedAt     string  `json:"updated_at"`
}

// ErrorResponse is the standard error response
type ErrorResponse struct {
	Error   string `json:"error"`
	Details string `json:"details,omitempty"`
}

// ToResponse converts a Conversation model to a ConversationResponse
func ToResponse(c *Conversation) ConversationResponse {
	resp := ConversationResponse{
		ID:           c.ID.String(),
		ProjectID:    c.ProjectID.String(),
		MessageCount: c.MessageCount,
		IsFavorite:   c.IsFavorite,
		CreatedAt:    c.CreatedAt.Format("2006-01-02T15:04:05Z07:00"),
		UpdatedAt:    c.UpdatedAt.Format("2006-01-02T15:04:05Z07:00"),
	}

	if c.ClaudeSession != nil {
		resp.ClaudeSession = c.ClaudeSession
	}

	if c.Title != nil {
		resp.Title = c.Title
	}

	if c.JsonlPath != nil {
		resp.JsonlPath = c.JsonlPath
	}

	return resp
}

// ToResponseList converts a slice of Conversation models to ConversationResponses
func ToResponseList(conversations []*Conversation) []ConversationResponse {
	responses := make([]ConversationResponse, len(conversations))
	for i, c := range conversations {
		responses[i] = ToResponse(c)
	}
	return responses
}
