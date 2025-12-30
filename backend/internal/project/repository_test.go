package project

import (
	"strings"
	"testing"

	"github.com/gofrs/uuid/v5"
)

func TestProject_Validate(t *testing.T) {
	validUserID, _ := uuid.NewV7()

	tests := []struct {
		name    string
		project Project
		wantErr bool
		errMsg  string
	}{
		{
			name: "valid project passes validation",
			project: Project{
				UserID: validUserID,
				Name:   "My Project",
				Path:   "/path/to/project",
			},
			wantErr: false,
		},
		{
			name: "missing UserID fails validation",
			project: Project{
				UserID: uuid.Nil,
				Name:   "My Project",
				Path:   "/path/to/project",
			},
			wantErr: true,
			errMsg:  "user_id",
		},
		{
			name: "missing Name fails validation",
			project: Project{
				UserID: validUserID,
				Name:   "",
				Path:   "/path/to/project",
			},
			wantErr: true,
			errMsg:  "name",
		},
		{
			name: "missing Path fails validation",
			project: Project{
				UserID: validUserID,
				Name:   "My Project",
				Path:   "",
			},
			wantErr: true,
			errMsg:  "path",
		},
		{
			name: "name too long fails validation",
			project: Project{
				UserID: validUserID,
				Name:   strings.Repeat("a", 256),
				Path:   "/path/to/project",
			},
			wantErr: true,
			errMsg:  "255",
		},
		{
			name: "name exactly 255 chars passes validation",
			project: Project{
				UserID: validUserID,
				Name:   strings.Repeat("a", 255),
				Path:   "/path/to/project",
			},
			wantErr: false,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			err := tt.project.Validate()
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
