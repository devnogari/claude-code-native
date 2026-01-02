package claude

import (
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
)

func TestGetSessionState_Empty(t *testing.T) {
	messages := []ClaudeMessage{}
	state := GetSessionState(messages)
	assert.Equal(t, SessionStateIdle, state, "empty messages should return idle")
}

func TestGetSessionState_LastMessageIsUser(t *testing.T) {
	messages := []ClaudeMessage{
		{
			Type: "user",
			Message: &MessageContent{
				Role:    "user",
				Content: "Hello",
			},
		},
	}
	state := GetSessionState(messages)
	assert.Equal(t, SessionStateStreaming, state, "last user message should return streaming (waiting for assistant)")
}

func TestGetSessionState_AssistantWithStopReason(t *testing.T) {
	messages := []ClaudeMessage{
		{
			Type: "user",
			Message: &MessageContent{
				Role:    "user",
				Content: "Hello",
			},
		},
		{
			Type: "assistant",
			Message: &MessageContent{
				Role:       "assistant",
				Content:    "Hi there!",
				StopReason: "end_turn",
			},
		},
	}
	state := GetSessionState(messages)
	assert.Equal(t, SessionStateIdle, state, "assistant message with stop_reason should return idle")
}

func TestGetSessionState_AssistantWithoutStopReason(t *testing.T) {
	messages := []ClaudeMessage{
		{
			Type: "user",
			Message: &MessageContent{
				Role:    "user",
				Content: "Hello",
			},
		},
		{
			Type: "assistant",
			Message: &MessageContent{
				Role:    "assistant",
				Content: "Hi there!", // No StopReason - still streaming
			},
		},
	}
	state := GetSessionState(messages)
	assert.Equal(t, SessionStateStreaming, state, "assistant message without stop_reason should return streaming")
}

func TestGetSessionState_QueueEnqueue(t *testing.T) {
	messages := []ClaudeMessage{
		{
			Type: "user",
			Message: &MessageContent{
				Role:    "user",
				Content: "First message",
			},
		},
		{
			Type: "assistant",
			Message: &MessageContent{
				Role:       "assistant",
				Content:    "Response",
				StopReason: "end_turn",
			},
		},
		{
			Type:      "queue-operation",
			Operation: "enqueue",
			Content:   "Queued message",
		},
	}
	state := GetSessionState(messages)
	assert.Equal(t, SessionStateQueued, state, "enqueue operation should return queued")
}

func TestGetSessionState_QueueDequeue(t *testing.T) {
	messages := []ClaudeMessage{
		{
			Type: "user",
			Message: &MessageContent{
				Role:    "user",
				Content: "First message",
			},
		},
		{
			Type:      "queue-operation",
			Operation: "enqueue",
			Content:   "Queued message",
		},
		{
			Type:      "queue-operation",
			Operation: "dequeue",
		},
		{
			Type: "user",
			Message: &MessageContent{
				Role:    "user",
				Content: "Queued message now being processed",
			},
		},
	}
	state := GetSessionState(messages)
	assert.Equal(t, SessionStateStreaming, state, "after dequeue with last user message should return streaming")
}

func TestGetSessionState_QueueDequeueCompleted(t *testing.T) {
	messages := []ClaudeMessage{
		{
			Type: "user",
			Message: &MessageContent{
				Role:    "user",
				Content: "First message",
			},
		},
		{
			Type:      "queue-operation",
			Operation: "enqueue",
			Content:   "Queued message",
		},
		{
			Type:      "queue-operation",
			Operation: "dequeue",
		},
		{
			Type: "user",
			Message: &MessageContent{
				Role:    "user",
				Content: "Queued message now being processed",
			},
		},
		{
			Type: "assistant",
			Message: &MessageContent{
				Role:       "assistant",
				Content:    "Response to queued message",
				StopReason: "end_turn",
			},
		},
	}
	state := GetSessionState(messages)
	assert.Equal(t, SessionStateIdle, state, "after dequeue with completed assistant response should return idle")
}

func TestGetSessionState_QueueClear(t *testing.T) {
	messages := []ClaudeMessage{
		{
			Type: "user",
			Message: &MessageContent{
				Role:    "user",
				Content: "First message",
			},
		},
		{
			Type: "assistant",
			Message: &MessageContent{
				Role:       "assistant",
				Content:    "Response",
				StopReason: "end_turn",
			},
		},
		{
			Type:      "queue-operation",
			Operation: "enqueue",
			Content:   "Queued message 1",
		},
		{
			Type:      "queue-operation",
			Operation: "enqueue",
			Content:   "Queued message 2",
		},
		{
			Type:      "queue-operation",
			Operation: "clear",
		},
	}
	state := GetSessionState(messages)
	// After clear, falls through to check last message role
	// Last relevant message is the completed assistant response
	assert.Equal(t, SessionStateIdle, state, "after queue clear with completed assistant should return idle")
}

func TestGetSessionState_DifferentStopReasons(t *testing.T) {
	stopReasons := []string{"end_turn", "max_tokens", "stop_sequence", "tool_use"}

	for _, stopReason := range stopReasons {
		t.Run(stopReason, func(t *testing.T) {
			messages := []ClaudeMessage{
				{
					Type: "user",
					Message: &MessageContent{
						Role:    "user",
						Content: "Hello",
					},
				},
				{
					Type: "assistant",
					Message: &MessageContent{
						Role:       "assistant",
						Content:    "Response",
						StopReason: stopReason,
					},
				},
			}
			state := GetSessionState(messages)
			assert.Equal(t, SessionStateIdle, state, "stop_reason=%s should return idle", stopReason)
		})
	}
}

func TestGetSessionState_MixedMessagesWithNilMessage(t *testing.T) {
	// Test that messages without Message field are ignored
	messages := []ClaudeMessage{
		{
			Type: "summary", // No Message field
		},
		{
			Type: "user",
			Message: &MessageContent{
				Role:    "user",
				Content: "Hello",
			},
		},
		{
			Type: "tool_result", // No Message field
		},
		{
			Type: "assistant",
			Message: &MessageContent{
				Role:       "assistant",
				Content:    "Response",
				StopReason: "end_turn",
			},
		},
	}
	state := GetSessionState(messages)
	assert.Equal(t, SessionStateIdle, state, "should handle messages without Message field correctly")
}

func TestGetSessionState_OnlyNonRelevantMessages(t *testing.T) {
	messages := []ClaudeMessage{
		{Type: "summary"},
		{Type: "tool_result"},
		{Type: "system"},
	}
	state := GetSessionState(messages)
	assert.Equal(t, SessionStateIdle, state, "messages without user/assistant should return idle")
}

// TestGetSessionState_StreamingChunksWithSameMessageID tests the behavior when
// Claude CLI appends multiple JSONL lines for the same message during streaming.
// Each chunk has the same message.ID but different UUIDs. Only the final chunk
// has stop_reason set.
func TestGetSessionState_StreamingChunksWithSameMessageID(t *testing.T) {
	msgID := "msg_01ABC123"

	// Simulate streaming chunks: first chunk without stop_reason, then chunk with stop_reason
	messages := []ClaudeMessage{
		{
			Type: "user",
			UUID: "user-uuid-1",
			Message: &MessageContent{
				Role:    "user",
				Content: "Hello",
			},
		},
		// First streaming chunk (no stop_reason)
		{
			Type: "assistant",
			UUID: "assistant-uuid-1",
			Message: &MessageContent{
				ID:      msgID,
				Role:    "assistant",
				Content: "Hi the",
			},
		},
		// Second streaming chunk (still no stop_reason)
		{
			Type: "assistant",
			UUID: "assistant-uuid-2",
			Message: &MessageContent{
				ID:      msgID,
				Role:    "assistant",
				Content: "Hi there!",
			},
		},
		// Final chunk with stop_reason
		{
			Type: "assistant",
			UUID: "assistant-uuid-3",
			Message: &MessageContent{
				ID:         msgID,
				Role:       "assistant",
				Content:    "Hi there! How can I help?",
				StopReason: "end_turn",
			},
		},
	}
	state := GetSessionState(messages)
	assert.Equal(t, SessionStateIdle, state, "streaming chunks with same message ID should return idle when any chunk has stop_reason")
}

// TestGetSessionState_StreamingChunksInProgress tests the case when streaming is
// still in progress - multiple chunks exist but none have stop_reason yet.
func TestGetSessionState_StreamingChunksInProgress(t *testing.T) {
	msgID := "msg_01XYZ789"

	messages := []ClaudeMessage{
		{
			Type: "user",
			UUID: "user-uuid-1",
			Message: &MessageContent{
				Role:    "user",
				Content: "Hello",
			},
		},
		// First streaming chunk (no stop_reason)
		{
			Type: "assistant",
			UUID: "assistant-uuid-1",
			Message: &MessageContent{
				ID:      msgID,
				Role:    "assistant",
				Content: "Hi the",
			},
		},
		// Second streaming chunk (still no stop_reason) - streaming in progress
		{
			Type: "assistant",
			UUID: "assistant-uuid-2",
			Message: &MessageContent{
				ID:      msgID,
				Role:    "assistant",
				Content: "Hi there!",
			},
		},
	}
	state := GetSessionState(messages)
	assert.Equal(t, SessionStateStreaming, state, "streaming chunks without any stop_reason should return streaming")
}

// TestGetSessionState_StopReasonInEarlierChunk tests that stop_reason is detected
// even if it appears in an earlier chunk (not the last line in the file).
func TestGetSessionState_StopReasonInEarlierChunk(t *testing.T) {
	msgID := "msg_01DEF456"

	messages := []ClaudeMessage{
		{
			Type: "user",
			UUID: "user-uuid-1",
			Message: &MessageContent{
				Role:    "user",
				Content: "Hello",
			},
		},
		// Chunk with stop_reason
		{
			Type: "assistant",
			UUID: "assistant-uuid-1",
			Message: &MessageContent{
				ID:         msgID,
				Role:       "assistant",
				Content:    "Response",
				StopReason: "end_turn",
			},
		},
		// Later chunk without stop_reason (could happen due to file write order)
		{
			Type: "assistant",
			UUID: "assistant-uuid-2",
			Message: &MessageContent{
				ID:      msgID,
				Role:    "assistant",
				Content: "Response with more content",
			},
		},
	}
	state := GetSessionState(messages)
	assert.Equal(t, SessionStateIdle, state, "should detect stop_reason from any chunk with the same message ID")
}

// TestGetSessionState_OldMessageWithoutStopReason tests that old messages without
// stop_reason are considered IDLE (handles interrupted/crashed sessions)
func TestGetSessionState_OldMessageWithoutStopReason(t *testing.T) {
	// Message from 1 minute ago (older than StreamingTimeout)
	oldTimestamp := time.Now().Add(-1 * time.Minute)

	messages := []ClaudeMessage{
		{
			Type: "user",
			Message: &MessageContent{
				Role:    "user",
				Content: "Hello",
			},
		},
		{
			Type:      "assistant",
			UUID:      "assistant-uuid-1",
			Timestamp: oldTimestamp,
			Message: &MessageContent{
				ID:      "msg_old123",
				Role:    "assistant",
				Content: "Partial response...",
				// No StopReason - but message is old
			},
		},
	}
	state := GetSessionState(messages)
	assert.Equal(t, SessionStateIdle, state, "old message without stop_reason should return idle (interrupted session)")
}

// TestGetSessionState_RecentMessageWithoutStopReason tests that recent messages
// without stop_reason are considered STREAMING (active streaming)
func TestGetSessionState_RecentMessageWithoutStopReason(t *testing.T) {
	// Message from 5 seconds ago (within StreamingTimeout)
	recentTimestamp := time.Now().Add(-5 * time.Second)

	messages := []ClaudeMessage{
		{
			Type: "user",
			Message: &MessageContent{
				Role:    "user",
				Content: "Hello",
			},
		},
		{
			Type:      "assistant",
			UUID:      "assistant-uuid-1",
			Timestamp: recentTimestamp,
			Message: &MessageContent{
				ID:      "msg_recent123",
				Role:    "assistant",
				Content: "Streaming response...",
				// No StopReason - and message is recent
			},
		},
	}
	state := GetSessionState(messages)
	assert.Equal(t, SessionStateStreaming, state, "recent message without stop_reason should return streaming")
}
