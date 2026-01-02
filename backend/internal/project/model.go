package project

import (
	"fmt"
	"time"

	"github.com/gofrs/uuid/v5"
	"github.com/uptrace/bun"
)

type Project struct {
	bun.BaseModel `bun:"table:projects,alias:p"`

	ID           uuid.UUID  `bun:"id,pk,type:uuid,default:uuidv7()"  json:"id"`
	UserID       uuid.UUID  `bun:"user_id,notnull,type:uuid"         json:"user_id"`
	Name         string     `bun:"name,notnull"                      json:"name"`
	Path         string     `bun:"path,notnull"                      json:"path"`
	ClaudeID     *string    `bun:"claude_id"                         json:"claude_id,omitempty"`
	LastAccessed *time.Time `bun:"last_accessed"                     json:"last_accessed,omitempty"`
	IsCompleted  bool       `bun:"is_completed,default:false"        json:"is_completed"`
	CreatedAt    time.Time  `bun:"created_at,default:now()"          json:"created_at"`
	UpdatedAt    time.Time  `bun:"updated_at,default:now()"          json:"updated_at"`
}

func (p *Project) Validate() error {
	if p.UserID == uuid.Nil {
		return fmt.Errorf("user_id is required")
	}
	if p.Name == "" {
		return fmt.Errorf("name is required")
	}
	if len(p.Name) > 255 {
		return fmt.Errorf("name must be 255 characters or less")
	}
	if p.Path == "" {
		return fmt.Errorf("path is required")
	}
	if len(p.Path) > 1024 {
		return fmt.Errorf("path must be 1024 characters or less")
	}
	return nil
}
