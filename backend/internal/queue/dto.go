package queue

import (
	"time"

	"github.com/gofrs/uuid/v5"
)

// QueuedMessageResponse is the API response for a queued message.
type QueuedMessageResponse struct {
	ID             uuid.UUID              `json:"id"`
	ConversationID uuid.UUID              `json:"conversation_id"`
	Content        string                 `json:"content"`
	QueuedAt       int64                  `json:"queued_at"` // Unix milliseconds
	Images         []QueuedImageResponse  `json:"images,omitempty"`
}

// QueuedImageResponse is the API response for an image in a queued message.
type QueuedImageResponse struct {
	ID        uuid.UUID `json:"id"`
	URL       string    `json:"url"`
	MediaType string    `json:"media_type"`
	FileName  *string   `json:"file_name,omitempty"`
	Width     *int      `json:"width,omitempty"`
	Height    *int      `json:"height,omitempty"`
}

// QueueListResponse is the API response for listing queued messages.
type QueueListResponse struct {
	Messages []QueuedMessageResponse `json:"messages"`
}

// AddToQueueRequest is the request body for adding a message to the queue.
// Images are uploaded as multipart form data, not in JSON body.
type AddToQueueRequest struct {
	Content string `json:"content" validate:"required,min=1"`
}

// convertImagesToResponse converts a slice of QueuedMessageImage to API responses.
func convertImagesToResponse(images []QueuedMessageImage, getImageURL func(string) string) []QueuedImageResponse {
	result := make([]QueuedImageResponse, 0, len(images))
	for _, img := range images {
		result = append(result, QueuedImageResponse{
			ID:        img.ID,
			URL:       getImageURL(img.StoragePath),
			MediaType: img.MediaType,
			FileName:  img.FileName,
			Width:     img.Width,
			Height:    img.Height,
		})
	}
	return result
}

// ToResponse converts a QueuedMessage model to an API response.
func ToResponse(msg *QueuedMessage, getImageURL func(string) string) QueuedMessageResponse {
	return QueuedMessageResponse{
		ID:             msg.ID,
		ConversationID: msg.ConversationID,
		Content:        msg.Content,
		QueuedAt:       msg.QueuedAt.UnixMilli(),
		Images:         convertImagesToResponse(msg.Images, getImageURL),
	}
}

// ToResponseList converts a slice of QueuedMessage models to API responses.
func ToResponseList(messages []QueuedMessage, getImageURL func(string) string) []QueuedMessageResponse {
	responses := make([]QueuedMessageResponse, 0, len(messages))
	for i := range messages {
		responses = append(responses, ToResponse(&messages[i], getImageURL))
	}
	return responses
}

// WebSocket message payloads

// QueueAddPayload is the WebSocket payload when a message is added to the queue.
type QueueAddPayload struct {
	ID             uuid.UUID             `json:"id"`
	Content        string                `json:"content"`
	QueuedAt       int64                 `json:"queued_at"` // Unix milliseconds
	Images         []QueuedImageResponse `json:"images,omitempty"`
}

// QueueRemovePayload is the WebSocket payload when a message is removed from the queue.
type QueueRemovePayload struct {
	ID uuid.UUID `json:"id"`
}

// QueueSyncPayload is the WebSocket payload for syncing the full queue state.
type QueueSyncPayload struct {
	Messages []QueuedMessageResponse `json:"messages"`
}

// ToAddPayload converts a QueuedMessage to a WebSocket add payload.
func ToAddPayload(msg *QueuedMessage, getImageURL func(string) string) QueueAddPayload {
	return QueueAddPayload{
		ID:       msg.ID,
		Content:  msg.Content,
		QueuedAt: msg.QueuedAt.UnixMilli(),
		Images:   convertImagesToResponse(msg.Images, getImageURL),
	}
}

// NewQueuedMessage creates a new QueuedMessage from request data.
func NewQueuedMessage(conversationID, userID uuid.UUID, content string) *QueuedMessage {
	now := time.Now()
	return &QueuedMessage{
		ConversationID: conversationID,
		UserID:         userID,
		Content:        content,
		QueuedAt:       now,
		CreatedAt:      now,
	}
}
