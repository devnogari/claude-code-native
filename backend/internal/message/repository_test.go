package message

import (
	"strings"
	"testing"

	"github.com/gofrs/uuid/v5"
)

func TestMessage_Validate(t *testing.T) {
	validConversationID, _ := uuid.NewV7()

	tests := []struct {
		name    string
		message Message
		wantErr bool
		errMsg  string
	}{
		{
			name: "valid message with user role passes validation",
			message: Message{
				ConversationID: validConversationID,
				Role:           RoleUser,
				Content:        "Hello, how can you help me?",
			},
			wantErr: false,
		},
		{
			name: "valid message with assistant role passes validation",
			message: Message{
				ConversationID: validConversationID,
				Role:           RoleAssistant,
				Content:        "I can help you with many things!",
			},
			wantErr: false,
		},
		{
			name: "valid message with all fields passes validation",
			message: Message{
				ConversationID: validConversationID,
				Role:           RoleUser,
				Content:        "Test content",
				TokenCount:     ptrInt(150),
				SequenceNum:    1,
			},
			wantErr: false,
		},
		{
			name: "missing ConversationID fails validation",
			message: Message{
				ConversationID: uuid.Nil,
				Role:           RoleUser,
				Content:        "Hello",
			},
			wantErr: true,
			errMsg:  "conversation_id",
		},
		{
			name: "missing role fails validation",
			message: Message{
				ConversationID: validConversationID,
				Role:           "",
				Content:        "Hello",
			},
			wantErr: true,
			errMsg:  "role",
		},
		{
			name: "invalid role fails validation",
			message: Message{
				ConversationID: validConversationID,
				Role:           "admin",
				Content:        "Hello",
			},
			wantErr: true,
			errMsg:  "role must be",
		},
		{
			name: "missing content fails validation",
			message: Message{
				ConversationID: validConversationID,
				Role:           RoleUser,
				Content:        "",
			},
			wantErr: true,
			errMsg:  "content",
		},
		{
			name: "whitespace-only content fails validation",
			message: Message{
				ConversationID: validConversationID,
				Role:           RoleUser,
				Content:        "   \n\t  ",
			},
			wantErr: true,
			errMsg:  "content",
		},
		{
			name: "negative sequence_num fails validation",
			message: Message{
				ConversationID: validConversationID,
				Role:           RoleUser,
				Content:        "Hello",
				SequenceNum:    -1,
			},
			wantErr: true,
			errMsg:  "sequence_num",
		},
		{
			name: "negative token_count fails validation",
			message: Message{
				ConversationID: validConversationID,
				Role:           RoleUser,
				Content:        "Hello",
				TokenCount:     ptrInt(-5),
			},
			wantErr: true,
			errMsg:  "token_count",
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			err := tt.message.Validate()
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

func TestIsValidRole(t *testing.T) {
	tests := []struct {
		role  string
		valid bool
	}{
		{RoleUser, true},
		{RoleAssistant, true},
		{"user", true},
		{"assistant", true},
		{"admin", false},
		{"system", false},
		{"", false},
		{"USER", false}, // case-sensitive
		{"Assistant", false},
	}

	for _, tt := range tests {
		t.Run(tt.role, func(t *testing.T) {
			result := IsValidRole(tt.role)
			if result != tt.valid {
				t.Errorf("IsValidRole(%q) = %v, want %v", tt.role, result, tt.valid)
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

func TestRepository_CreateWithNilDB(t *testing.T) {
	repo := NewRepository(nil)
	conversationID, _ := uuid.NewV7()

	msg := &Message{
		ConversationID: conversationID,
		Role:           RoleUser,
		Content:        "Test",
		SequenceNum:    0,
	}

	err := repo.Create(nil, msg)
	if err == nil {
		t.Error("expected error for nil db")
	}
	if !strings.Contains(err.Error(), "database not initialized") {
		t.Errorf("expected 'database not initialized' error, got: %v", err)
	}
}

func TestRepository_CreateValidationError(t *testing.T) {
	repo := NewRepository(nil)

	// Message with invalid role should fail validation before DB check
	msg := &Message{
		ConversationID: uuid.Nil, // Invalid - will fail validation
		Role:           RoleUser,
		Content:        "Test",
	}

	err := repo.Create(nil, msg)
	if err == nil {
		t.Error("expected validation error")
	}
}

func TestRepository_FindByIDWithNilDB(t *testing.T) {
	repo := NewRepository(nil)
	id, _ := uuid.NewV7()

	_, err := repo.FindByID(nil, id)
	if err == nil {
		t.Error("expected error for nil db")
	}
	if !strings.Contains(err.Error(), "database not initialized") {
		t.Errorf("expected 'database not initialized' error, got: %v", err)
	}
}

func TestRepository_FindByConversationIDWithNilDB(t *testing.T) {
	repo := NewRepository(nil)
	conversationID, _ := uuid.NewV7()

	_, err := repo.FindByConversationID(nil, conversationID)
	if err == nil {
		t.Error("expected error for nil db")
	}
	if !strings.Contains(err.Error(), "database not initialized") {
		t.Errorf("expected 'database not initialized' error, got: %v", err)
	}
}

func TestRepository_DeleteWithNilDB(t *testing.T) {
	repo := NewRepository(nil)
	id, _ := uuid.NewV7()

	err := repo.Delete(nil, id)
	if err == nil {
		t.Error("expected error for nil db")
	}
	if !strings.Contains(err.Error(), "database not initialized") {
		t.Errorf("expected 'database not initialized' error, got: %v", err)
	}
}

func TestRepository_DeleteByConversationIDWithNilDB(t *testing.T) {
	repo := NewRepository(nil)
	conversationID, _ := uuid.NewV7()

	err := repo.DeleteByConversationID(nil, conversationID)
	if err == nil {
		t.Error("expected error for nil db")
	}
	if !strings.Contains(err.Error(), "database not initialized") {
		t.Errorf("expected 'database not initialized' error, got: %v", err)
	}
}

func TestRepository_GetMaxSequenceNumWithNilDB(t *testing.T) {
	repo := NewRepository(nil)
	conversationID, _ := uuid.NewV7()

	_, err := repo.GetMaxSequenceNum(nil, conversationID)
	if err == nil {
		t.Error("expected error for nil db")
	}
	if !strings.Contains(err.Error(), "database not initialized") {
		t.Errorf("expected 'database not initialized' error, got: %v", err)
	}
}

// Helper function to create int pointers
func ptrInt(i int) *int {
	return &i
}
