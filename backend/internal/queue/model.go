package queue

import (
	"fmt"
	"strings"
	"time"

	"github.com/gofrs/uuid/v5"
	"github.com/uptrace/bun"
)

// QueuedMessage represents a message waiting to be processed in a conversation.
type QueuedMessage struct {
	bun.BaseModel `bun:"table:queued_messages,alias:qm"`

	ID             uuid.UUID `bun:"id,pk,type:uuid,default:uuidv7()"  json:"id"`
	ConversationID uuid.UUID `bun:"conversation_id,notnull,type:uuid" json:"conversation_id"`
	UserID         uuid.UUID `bun:"user_id,notnull,type:uuid"         json:"user_id"`
	Content        string    `bun:"content,notnull"                   json:"content"`
	QueuedAt       time.Time `bun:"queued_at,notnull,default:now()"   json:"queued_at"`
	CreatedAt      time.Time `bun:"created_at,default:now()"          json:"created_at"`

	// Relations
	Images []QueuedMessageImage `bun:"rel:has-many,join:id=queued_message_id" json:"images,omitempty"`
}

// QueuedMessageImage represents an image attached to a queued message.
type QueuedMessageImage struct {
	bun.BaseModel `bun:"table:queued_message_images,alias:qmi"`

	ID              uuid.UUID `bun:"id,pk,type:uuid,default:uuidv7()"        json:"id"`
	QueuedMessageID uuid.UUID `bun:"queued_message_id,notnull,type:uuid"     json:"queued_message_id"`
	StoragePath     string    `bun:"storage_path,notnull"                    json:"storage_path"`
	MediaType       string    `bun:"media_type,notnull"                      json:"media_type"`
	FileName        *string   `bun:"file_name"                               json:"file_name,omitempty"`
	Width           *int      `bun:"width"                                   json:"width,omitempty"`
	Height          *int      `bun:"height"                                  json:"height,omitempty"`
	CreatedAt       time.Time `bun:"created_at,default:now()"                json:"created_at"`
}

// Validate checks if the queued message has all required fields.
func (m *QueuedMessage) Validate() error {
	if m.ConversationID == uuid.Nil {
		return fmt.Errorf("conversation_id is required")
	}

	if m.UserID == uuid.Nil {
		return fmt.Errorf("user_id is required")
	}

	if strings.TrimSpace(m.Content) == "" {
		return fmt.Errorf("content is required and cannot be empty")
	}

	return nil
}

// Validate checks if the queued message image has all required fields.
func (i *QueuedMessageImage) Validate() error {
	if i.QueuedMessageID == uuid.Nil {
		return fmt.Errorf("queued_message_id is required")
	}

	if strings.TrimSpace(i.StoragePath) == "" {
		return fmt.Errorf("storage_path is required")
	}

	if strings.TrimSpace(i.MediaType) == "" {
		return fmt.Errorf("media_type is required")
	}

	return nil
}
