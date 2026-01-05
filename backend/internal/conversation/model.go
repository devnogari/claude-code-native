package conversation

import (
	"fmt"
	"time"

	"github.com/gofrs/uuid/v5"
	"github.com/uptrace/bun"
)

type Conversation struct {
	bun.BaseModel `bun:"table:conversations,alias:c"`

	ID            uuid.UUID  `bun:"id,pk,type:uuid,default:uuidv7()"  json:"id"`
	ProjectID     uuid.UUID  `bun:"project_id,notnull,type:uuid"      json:"project_id"`
	ClaudeSession *string    `bun:"claude_session"                    json:"claude_session,omitempty"`
	Title         *string    `bun:"title"                             json:"title,omitempty"`
	MessageCount  int        `bun:"message_count,default:0"           json:"message_count"`
	JsonlPath     *string    `bun:"jsonl_path"                        json:"jsonl_path,omitempty"`
	IsFavorite     bool       `bun:"is_favorite,default:false"         json:"is_favorite"`
	PermissionMode string     `bun:"permission_mode,default:'default'" json:"permission_mode"`
	CreatedAt      time.Time  `bun:"created_at,default:now()"          json:"created_at"`
	UpdatedAt      time.Time  `bun:"updated_at,default:now()"          json:"updated_at"`
}

func (c *Conversation) Validate() error {
	if c.ProjectID == uuid.Nil {
		return fmt.Errorf("project_id is required")
	}
	if c.ClaudeSession != nil && len(*c.ClaudeSession) > 255 {
		return fmt.Errorf("claude_session must be 255 characters or less")
	}
	if c.Title != nil && len(*c.Title) > 255 {
		return fmt.Errorf("title must be 255 characters or less")
	}
	if c.JsonlPath != nil && len(*c.JsonlPath) > 1024 {
		return fmt.Errorf("jsonl_path must be 1024 characters or less")
	}
	// Validate permission mode
	if c.PermissionMode != "" {
		validModes := map[string]bool{
			"default":           true,
			"plan":              true,
			"bypassPermissions": true,
		}
		if !validModes[c.PermissionMode] {
			return fmt.Errorf("permission_mode must be one of: default, plan, bypassPermissions")
		}
	}
	return nil
}
