package user

import (
	"testing"
)

func TestNewRepository(t *testing.T) {
	repo := NewRepository(nil)
	if repo == nil {
		t.Error("expected non-nil repository")
	}
	if repo.db != nil {
		t.Error("expected nil db for nil input")
	}
}
