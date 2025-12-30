package conversation

import (
	"strings"
	"testing"

	"github.com/gofrs/uuid/v5"
)

func TestConversation_Validate(t *testing.T) {
	validProjectID, _ := uuid.NewV7()

	tests := []struct {
		name         string
		conversation Conversation
		wantErr      bool
		errMsg       string
	}{
		{
			name: "valid conversation passes validation",
			conversation: Conversation{
				ProjectID: validProjectID,
			},
			wantErr: false,
		},
		{
			name: "valid conversation with all fields passes validation",
			conversation: Conversation{
				ProjectID:     validProjectID,
				ClaudeSession: ptrString("session-123"),
				Title:         ptrString("My Conversation"),
				MessageCount:  5,
				JsonlPath:     ptrString("/path/to/conversation.jsonl"),
			},
			wantErr: false,
		},
		{
			name: "missing ProjectID fails validation",
			conversation: Conversation{
				ProjectID: uuid.Nil,
			},
			wantErr: true,
			errMsg:  "project_id",
		},
		{
			name: "claude_session too long fails validation",
			conversation: Conversation{
				ProjectID:     validProjectID,
				ClaudeSession: ptrString(strings.Repeat("a", 256)),
			},
			wantErr: true,
			errMsg:  "255",
		},
		{
			name: "claude_session exactly 255 chars passes validation",
			conversation: Conversation{
				ProjectID:     validProjectID,
				ClaudeSession: ptrString(strings.Repeat("a", 255)),
			},
			wantErr: false,
		},
		{
			name: "title too long fails validation",
			conversation: Conversation{
				ProjectID: validProjectID,
				Title:     ptrString(strings.Repeat("a", 256)),
			},
			wantErr: true,
			errMsg:  "255",
		},
		{
			name: "title exactly 255 chars passes validation",
			conversation: Conversation{
				ProjectID: validProjectID,
				Title:     ptrString(strings.Repeat("a", 255)),
			},
			wantErr: false,
		},
		{
			name: "jsonl_path too long fails validation",
			conversation: Conversation{
				ProjectID: validProjectID,
				JsonlPath: ptrString(strings.Repeat("a", 1025)),
			},
			wantErr: true,
			errMsg:  "1024",
		},
		{
			name: "jsonl_path exactly 1024 chars passes validation",
			conversation: Conversation{
				ProjectID: validProjectID,
				JsonlPath: ptrString(strings.Repeat("a", 1024)),
			},
			wantErr: false,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			err := tt.conversation.Validate()
			if tt.wantErr {
				if err == nil {
					t.Errorf("expected error but got nil")
					return
				}
				if tt.errMsg != "" && !strings.Contains(err.Error(), tt.errMsg) {
					t.Errorf("expected error to contain %q, got %q", tt.errMsg, err.Error())
				}
			} else {
				if err != nil {
					t.Errorf("expected no error but got: %v", err)
				}
			}
		})
	}
}

func TestNewRepository(t *testing.T) {
	repo := NewRepository(nil)
	if repo == nil {
		t.Error("expected non-nil repository")
	}
	if repo.db != nil {
		t.Error("expected nil db for nil input")
	}
}

// Helper function to create string pointers
func ptrString(s string) *string {
	return &s
}
