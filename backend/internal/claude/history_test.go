package claude

import (
	"testing"

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
