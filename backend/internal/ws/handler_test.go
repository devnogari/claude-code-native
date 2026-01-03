package ws

import (
	"context"
	"encoding/json"
	"testing"

	"github.com/devnogari/claude-code-native/backend/internal/claude"
	"github.com/devnogari/claude-code-native/backend/internal/config"
	"github.com/devnogari/claude-code-native/backend/internal/conversation"
	"github.com/devnogari/claude-code-native/backend/internal/message"
	"github.com/devnogari/claude-code-native/backend/internal/project"
	"github.com/devnogari/claude-code-native/backend/internal/queue"
	"github.com/gofrs/uuid/v5"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
	"go.uber.org/zap"
)

// --- DTO Tests ---

func TestMessageTypeConstants(t *testing.T) {
	t.Run("message types have expected values", func(t *testing.T) {
		assert.Equal(t, "chat", MessageTypeChat)
		assert.Equal(t, "stream", MessageTypeStream)
		assert.Equal(t, "status", MessageTypeStatus)
		assert.Equal(t, "error", MessageTypeError)
		assert.Equal(t, "stop", MessageTypeStop)
		assert.Equal(t, "complete", MessageTypeComplete)
		assert.Equal(t, "ping", MessageTypePing)
		assert.Equal(t, "pong", MessageTypePong)
	})
}

func TestIncomingMessage_JSONParsing(t *testing.T) {
	t.Run("parses valid chat message", func(t *testing.T) {
		jsonData := `{"type":"chat","content":"Hello, Claude!"}`
		var msg IncomingMessage
		err := json.Unmarshal([]byte(jsonData), &msg)

		require.NoError(t, err)
		assert.Equal(t, MessageTypeChat, msg.Type)
		assert.Equal(t, "Hello, Claude!", msg.Content)
	})

	t.Run("parses stop message without content", func(t *testing.T) {
		jsonData := `{"type":"stop"}`
		var msg IncomingMessage
		err := json.Unmarshal([]byte(jsonData), &msg)

		require.NoError(t, err)
		assert.Equal(t, MessageTypeStop, msg.Type)
		assert.Empty(t, msg.Content)
	})

	t.Run("parses ping message", func(t *testing.T) {
		jsonData := `{"type":"ping"}`
		var msg IncomingMessage
		err := json.Unmarshal([]byte(jsonData), &msg)

		require.NoError(t, err)
		assert.Equal(t, MessageTypePing, msg.Type)
	})

	t.Run("fails on invalid JSON", func(t *testing.T) {
		jsonData := `{"type":"chat",invalid}`
		var msg IncomingMessage
		err := json.Unmarshal([]byte(jsonData), &msg)

		assert.Error(t, err)
	})

	t.Run("parses empty content as empty string", func(t *testing.T) {
		jsonData := `{"type":"chat","content":""}`
		var msg IncomingMessage
		err := json.Unmarshal([]byte(jsonData), &msg)

		require.NoError(t, err)
		assert.Equal(t, MessageTypeChat, msg.Type)
		assert.Equal(t, "", msg.Content)
	})

	t.Run("parses message with unicode content", func(t *testing.T) {
		jsonData := `{"type":"chat","content":"Hello 你好 🎉"}`
		var msg IncomingMessage
		err := json.Unmarshal([]byte(jsonData), &msg)

		require.NoError(t, err)
		assert.Equal(t, "Hello 你好 🎉", msg.Content)
	})
}

func TestOutgoingMessage_JSONMarshaling(t *testing.T) {
	t.Run("marshals stream message with content", func(t *testing.T) {
		convID := uuid.Must(uuid.NewV7())
		msg := OutgoingMessage{
			Type:           MessageTypeStream,
			ConversationID: convID.String(),
			Content:        "Here is some output...",
		}

		data, err := json.Marshal(msg)
		require.NoError(t, err)

		var parsed map[string]interface{}
		err = json.Unmarshal(data, &parsed)
		require.NoError(t, err)

		assert.Equal(t, "stream", parsed["type"])
		assert.Equal(t, convID.String(), parsed["conversation_id"])
		assert.Equal(t, "Here is some output...", parsed["content"])
	})

	t.Run("marshals error message", func(t *testing.T) {
		msg := OutgoingMessage{
			Type:  MessageTypeError,
			Error: "Something went wrong",
		}

		data, err := json.Marshal(msg)
		require.NoError(t, err)

		var parsed map[string]interface{}
		err = json.Unmarshal(data, &parsed)
		require.NoError(t, err)

		assert.Equal(t, "error", parsed["type"])
		assert.Equal(t, "Something went wrong", parsed["error"])
	})

	t.Run("marshals status message", func(t *testing.T) {
		msg := OutgoingMessage{
			Type:   MessageTypeStatus,
			Status: "processing",
		}

		data, err := json.Marshal(msg)
		require.NoError(t, err)

		var parsed map[string]interface{}
		err = json.Unmarshal(data, &parsed)
		require.NoError(t, err)

		assert.Equal(t, "status", parsed["type"])
		assert.Equal(t, "processing", parsed["status"])
	})

	t.Run("marshals pong message", func(t *testing.T) {
		msg := OutgoingMessage{
			Type: MessageTypePong,
		}

		data, err := json.Marshal(msg)
		require.NoError(t, err)

		var parsed map[string]interface{}
		err = json.Unmarshal(data, &parsed)
		require.NoError(t, err)

		assert.Equal(t, "pong", parsed["type"])
	})

	t.Run("omits empty optional fields", func(t *testing.T) {
		msg := OutgoingMessage{
			Type: MessageTypeComplete,
		}

		data, err := json.Marshal(msg)
		require.NoError(t, err)

		// Verify that empty fields are omitted (they have omitempty)
		jsonStr := string(data)
		assert.Contains(t, jsonStr, `"type":"complete"`)
		// These should be omitted when empty
		assert.NotContains(t, jsonStr, `"conversation_id":""`)
		assert.NotContains(t, jsonStr, `"content":""`)
		assert.NotContains(t, jsonStr, `"error":""`)
		assert.NotContains(t, jsonStr, `"status":""`)
	})
}

// --- Mock Repositories ---

type mockConversationRepository struct {
	findByIDFunc func(ctx context.Context, id uuid.UUID) (*conversation.Conversation, error)
	updateFunc   func(ctx context.Context, c *conversation.Conversation) error
}

func (m *mockConversationRepository) FindByID(ctx context.Context, id uuid.UUID) (*conversation.Conversation, error) {
	if m.findByIDFunc != nil {
		return m.findByIDFunc(ctx, id)
	}
	return nil, nil
}

func (m *mockConversationRepository) Update(ctx context.Context, c *conversation.Conversation) error {
	if m.updateFunc != nil {
		return m.updateFunc(ctx, c)
	}
	return nil
}

type mockProjectRepository struct {
	findByIDFunc func(ctx context.Context, id uuid.UUID) (*project.Project, error)
}

func (m *mockProjectRepository) FindByID(ctx context.Context, id uuid.UUID) (*project.Project, error) {
	if m.findByIDFunc != nil {
		return m.findByIDFunc(ctx, id)
	}
	return nil, nil
}

type mockMessageRepository struct {
	createFunc               func(ctx context.Context, m *message.Message) error
	findByConversationIDFunc func(ctx context.Context, conversationID uuid.UUID) ([]*message.Message, error)
	getMaxSequenceNumFunc    func(ctx context.Context, conversationID uuid.UUID) (int, error)
}

func (m *mockMessageRepository) Create(ctx context.Context, msg *message.Message) error {
	if m.createFunc != nil {
		return m.createFunc(ctx, msg)
	}
	return nil
}

func (m *mockMessageRepository) FindByConversationID(ctx context.Context, conversationID uuid.UUID) ([]*message.Message, error) {
	if m.findByConversationIDFunc != nil {
		return m.findByConversationIDFunc(ctx, conversationID)
	}
	return nil, nil
}

func (m *mockMessageRepository) GetMaxSequenceNum(ctx context.Context, conversationID uuid.UUID) (int, error) {
	if m.getMaxSequenceNumFunc != nil {
		return m.getMaxSequenceNumFunc(ctx, conversationID)
	}
	return 0, nil
}

type mockQueueService struct {
	getQueueFunc func(ctx context.Context, conversationID uuid.UUID) ([]queue.QueuedMessage, error)
}

func (m *mockQueueService) AddToQueue(ctx context.Context, conversationID, userID uuid.UUID, content string, images []queue.ImageData) (*queue.QueuedMessage, error) {
	return nil, nil
}

func (m *mockQueueService) GetQueue(ctx context.Context, conversationID uuid.UUID) ([]queue.QueuedMessage, error) {
	if m.getQueueFunc != nil {
		return m.getQueueFunc(ctx, conversationID)
	}
	return nil, nil
}

func (m *mockQueueService) GetNextMessage(ctx context.Context, conversationID uuid.UUID) (*queue.QueuedMessage, error) {
	return nil, nil
}

func (m *mockQueueService) RemoveFromQueue(ctx context.Context, messageID uuid.UUID) error {
	return nil
}

func (m *mockQueueService) ClearQueue(ctx context.Context, conversationID uuid.UUID) error {
	return nil
}

func (m *mockQueueService) GetImageURL(storagePath string) string {
	return ""
}

func (m *mockQueueService) GetImageData(ctx context.Context, storagePath string) ([]byte, error) {
	return nil, nil
}

// --- Handler Tests ---

func TestNewHandler(t *testing.T) {
	t.Run("creates handler with all dependencies", func(t *testing.T) {
		hub := NewHub()
		cfg := &config.Config{}
		logger := zap.NewNop()
		claudeMgr := claude.NewManager(logger)
		convRepo := &mockConversationRepository{}
		projRepo := &mockProjectRepository{}
		msgRepo := &mockMessageRepository{}
		queueSvc := &mockQueueService{}

		handler := NewHandler(hub, cfg, logger, claudeMgr, convRepo, projRepo, msgRepo, queueSvc)

		require.NotNil(t, handler)
		assert.Equal(t, hub, handler.hub)
		assert.Equal(t, cfg, handler.config)
		assert.Equal(t, logger, handler.logger)
		assert.Equal(t, claudeMgr, handler.claudeMgr)
		assert.Equal(t, convRepo, handler.convRepo)
		assert.Equal(t, projRepo, handler.projRepo)
		assert.Equal(t, msgRepo, handler.msgRepo)
		assert.Equal(t, queueSvc, handler.queueService)
	})

	t.Run("handler has correct type", func(t *testing.T) {
		hub := NewHub()
		cfg := &config.Config{}
		logger := zap.NewNop()
		claudeMgr := claude.NewManager(logger)
		convRepo := &mockConversationRepository{}
		projRepo := &mockProjectRepository{}
		msgRepo := &mockMessageRepository{}
		queueSvc := &mockQueueService{}

		handler := NewHandler(hub, cfg, logger, claudeMgr, convRepo, projRepo, msgRepo, queueSvc)

		// Verify the handler is of correct type
		var _ *Handler = handler
	})
}

func TestHandler_HelperMethods(t *testing.T) {
	t.Run("createOutgoingError creates error message", func(t *testing.T) {
		msg := createOutgoingError("test error")

		require.NotNil(t, msg)
		assert.Equal(t, MessageTypeError, msg.Type)
		assert.Equal(t, "test error", msg.Error)
	})

	t.Run("createOutgoingStatus creates status message", func(t *testing.T) {
		msg := createOutgoingStatus("processing")

		require.NotNil(t, msg)
		assert.Equal(t, MessageTypeStatus, msg.Type)
		assert.Equal(t, "processing", msg.Status)
	})

	t.Run("createOutgoingPong creates pong message", func(t *testing.T) {
		msg := createOutgoingPong()

		require.NotNil(t, msg)
		assert.Equal(t, MessageTypePong, msg.Type)
	})

	t.Run("createOutgoingStream creates stream message", func(t *testing.T) {
		convID := uuid.Must(uuid.NewV7())
		msg := createOutgoingStream(convID, "streaming content")

		require.NotNil(t, msg)
		assert.Equal(t, MessageTypeStream, msg.Type)
		assert.Equal(t, convID.String(), msg.ConversationID)
		assert.Equal(t, "streaming content", msg.Content)
	})

	t.Run("createOutgoingComplete creates complete message", func(t *testing.T) {
		convID := uuid.Must(uuid.NewV7())
		msg := createOutgoingComplete(convID)

		require.NotNil(t, msg)
		assert.Equal(t, MessageTypeComplete, msg.Type)
		assert.Equal(t, convID.String(), msg.ConversationID)
	})

	t.Run("createOutgoingStderr creates stderr message", func(t *testing.T) {
		convID := uuid.Must(uuid.NewV7())
		msg := createOutgoingStderr(convID, "Error: something went wrong")

		require.NotNil(t, msg)
		assert.Equal(t, MessageTypeStderr, msg.Type)
		assert.Equal(t, convID.String(), msg.ConversationID)
		assert.Equal(t, "Error: something went wrong", msg.Content)
	})
}

func TestCompactionErrorPattern(t *testing.T) {
	t.Run("CompactionErrorPattern matches expected error", func(t *testing.T) {
		// This is the error pattern from Claude CLI when conversation is too long
		errorMsg := "Error: Error during compaction: Error: Conversation too long. Press esc twice to go up a few messages and try again."
		assert.Contains(t, errorMsg, CompactionErrorPattern)
	})

	t.Run("CompactionErrorPattern does not match unrelated errors", func(t *testing.T) {
		unrelatedError := "Error: Network timeout"
		assert.NotContains(t, unrelatedError, CompactionErrorPattern)
	})
}

func TestStderrMessageType(t *testing.T) {
	t.Run("stderr message type constant is correct", func(t *testing.T) {
		assert.Equal(t, "stderr", MessageTypeStderr)
	})
}
