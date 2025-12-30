package message

import (
	"fmt"
	"strings"
	"time"

	"github.com/gofrs/uuid/v5"
	"github.com/uptrace/bun"
)

// Role constants for message roles
const (
	RoleUser      = "user"
	RoleAssistant = "assistant"
)

// Message represents a chat message in a conversation
type Message struct {
	bun.BaseModel `bun:"table:messages,alias:m"`

	ID             uuid.UUID `bun:"id,pk,type:uuid,default:uuidv7()"  json:"id"`
	ConversationID uuid.UUID `bun:"conversation_id,notnull,type:uuid" json:"conversation_id"`
	Role           string    `bun:"role,notnull"                      json:"role"`
	Content        string    `bun:"content,notnull"                   json:"content"`
	TokenCount     *int      `bun:"token_count"                       json:"token_count,omitempty"`
	SequenceNum    int       `bun:"sequence_num,notnull"              json:"sequence_num"`
	CreatedAt      time.Time `bun:"created_at,default:now()"          json:"created_at"`
}

// IsValidRole checks if the given role is a valid message role
func IsValidRole(role string) bool {
	return role == RoleUser || role == RoleAssistant
}

// Validate checks if the message has all required fields with valid values
func (m *Message) Validate() error {
	if m.ConversationID == uuid.Nil {
		return fmt.Errorf("conversation_id is required")
	}

	if m.Role == "" {
		return fmt.Errorf("role is required")
	}

	if !IsValidRole(m.Role) {
		return fmt.Errorf("role must be one of: %s, %s", RoleUser, RoleAssistant)
	}

	if strings.TrimSpace(m.Content) == "" {
		return fmt.Errorf("content is required and cannot be empty")
	}

	if m.SequenceNum < 0 {
		return fmt.Errorf("sequence_num cannot be negative")
	}

	if m.TokenCount != nil && *m.TokenCount < 0 {
		return fmt.Errorf("token_count cannot be negative")
	}

	return nil
}
