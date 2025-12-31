package claude

import (
	"context"
	"time"

	"github.com/devnogari/claude-code-native/backend/internal/conversation"
	"github.com/devnogari/claude-code-native/backend/internal/message"
	"github.com/devnogari/claude-code-native/backend/internal/project"
	"github.com/gofrs/uuid/v5"
	"go.uber.org/zap"
)

// SyncService syncs Claude CLI history to database
type SyncService struct {
	reader   *HistoryReader
	projRepo *project.Repository
	convRepo *conversation.Repository
	msgRepo  *message.Repository
	logger   *zap.Logger
}

// NewSyncService creates a new sync service
func NewSyncService(
	projRepo *project.Repository,
	convRepo *conversation.Repository,
	msgRepo *message.Repository,
	logger *zap.Logger,
) *SyncService {
	return &SyncService{
		reader:   NewHistoryReader(""),
		projRepo: projRepo,
		convRepo: convRepo,
		msgRepo:  msgRepo,
		logger:   logger,
	}
}

// SyncResult represents the result of a sync operation
type SyncResult struct {
	ProjectsCreated      int `json:"projects_created"`
	ProjectsUpdated      int `json:"projects_updated"`
	ConversationsCreated int `json:"conversations_created"`
	MessagesCreated      int `json:"messages_created"`
}

// SyncForUser syncs all Claude CLI history for a user
func (s *SyncService) SyncForUser(ctx context.Context, userID uuid.UUID) (*SyncResult, error) {
	result := &SyncResult{}

	// Get all Claude projects from filesystem
	claudeProjects, err := s.reader.GetProjects()
	if err != nil {
		s.logger.Error("failed to read Claude projects", zap.Error(err))
		return nil, err
	}

	for _, cp := range claudeProjects {
		// Check if project exists by path
		existingProj, err := s.projRepo.FindByPath(ctx, userID, cp.Path)

		var proj *project.Project
		if err != nil {
			// Create new project with original timestamps
			proj = &project.Project{
				UserID:       userID,
				Name:         cp.Name,
				Path:         cp.Path,
				ClaudeID:     &cp.ID,
				LastAccessed: &cp.LastAccessed,
				UpdatedAt:    cp.LastAccessed,
			}
			if err := s.projRepo.Create(ctx, proj); err != nil {
				s.logger.Error("failed to create project", zap.String("path", cp.Path), zap.Error(err))
				continue
			}
			result.ProjectsCreated++
		} else {
			// Update existing project with original timestamps
			proj = existingProj
			proj.Name = cp.Name
			proj.ClaudeID = &cp.ID
			proj.LastAccessed = &cp.LastAccessed
			proj.UpdatedAt = cp.LastAccessed // Use file modification time as UpdatedAt
			s.logger.Info("updating project timestamp",
				zap.String("project_path", cp.Path),
				zap.String("last_accessed", cp.LastAccessed.Format(time.RFC3339)),
				zap.String("updated_at", proj.UpdatedAt.Format(time.RFC3339)))
			if err := s.projRepo.UpdateWithTimestamp(ctx, proj); err != nil {
				s.logger.Error("failed to update project", zap.String("path", cp.Path), zap.Error(err))
			} else {
				result.ProjectsUpdated++
			}
		}

		// Sync sessions as conversations
		for _, session := range cp.Sessions {
			convCreated, msgsCreated := s.syncSession(ctx, proj.ID, cp.EncodedPath, session)
			result.ConversationsCreated += convCreated
			result.MessagesCreated += msgsCreated
		}
	}

	s.logger.Info("sync completed",
		zap.Int("projects_created", result.ProjectsCreated),
		zap.Int("projects_updated", result.ProjectsUpdated),
		zap.Int("conversations_created", result.ConversationsCreated),
		zap.Int("messages_created", result.MessagesCreated),
	)

	return result, nil
}

// syncSession syncs a Claude session to a conversation
func (s *SyncService) syncSession(ctx context.Context, projectID uuid.UUID, encodedPath string, session ClaudeSession) (convCreated int, msgsCreated int) {
	// Check if conversation exists by claude_session
	existingConv, err := s.convRepo.FindByClaudeSession(ctx, projectID, session.ID)

	var conv *conversation.Conversation
	if err != nil {
		// Create new conversation
		title := session.FirstMessage
		if title == "" {
			title = "Session " + session.ID[:8]
		}
		if len(title) > 100 {
			title = title[:97] + "..."
		}

		conv = &conversation.Conversation{
			ProjectID:     projectID,
			ClaudeSession: &session.ID,
			Title:         &title,
			MessageCount:  session.MessageCount,
			CreatedAt:     session.CreatedAt,
			UpdatedAt:     session.UpdatedAt,
		}
		if err := s.convRepo.Create(ctx, conv); err != nil {
			s.logger.Error("failed to create conversation",
				zap.String("session_id", session.ID),
				zap.Error(err))
			return 0, 0
		}
		convCreated = 1

		// Import messages for new conversation
		msgsCreated = s.importMessages(ctx, conv.ID, encodedPath, session.ID, 0)
	} else {
		// Update existing conversation
		conv = existingConv

		// Get current max sequence number in DB
		maxSeqNum, err := s.msgRepo.GetMaxSequenceNum(ctx, conv.ID)
		if err != nil {
			s.logger.Error("failed to get max sequence num",
				zap.String("session_id", session.ID),
				zap.Error(err))
			maxSeqNum = 0
		}

		// Always import new messages (incremental sync)
		msgsCreated = s.importMessages(ctx, conv.ID, encodedPath, session.ID, maxSeqNum)

		// Update message count and timestamp from file
		conv.MessageCount = session.MessageCount
		conv.UpdatedAt = session.UpdatedAt
		s.logger.Info("updating conversation timestamp",
			zap.String("session_id", session.ID[:8]),
			zap.Time("session_updated_at", session.UpdatedAt),
			zap.Time("conv_updated_at", conv.UpdatedAt),
			zap.String("conv_id", conv.ID.String()))
		if err := s.convRepo.UpdateWithTimestamp(ctx, conv); err != nil {
			s.logger.Error("failed to update conversation timestamp",
				zap.String("session_id", session.ID[:8]),
				zap.Error(err))
		}
	}

	return convCreated, msgsCreated
}

// importMessages imports messages from a Claude session file
// startSeqNum: skip messages with sequence number <= startSeqNum (for incremental sync)
func (s *SyncService) importMessages(ctx context.Context, convID uuid.UUID, encodedPath, sessionID string, startSeqNum int) int {
	messages, err := s.reader.GetSessionMessages(encodedPath, sessionID)
	if err != nil {
		s.logger.Error("failed to read session messages",
			zap.String("session_id", sessionID),
			zap.Error(err))
		return 0
	}

	count := 0
	seqNum := 0

	for _, msg := range messages {
		if msg.Message == nil {
			continue
		}

		role := msg.Message.Role
		if role != "user" && role != "assistant" {
			continue
		}

		// Extract content as string
		content := extractContentAsString(msg.Message.Content)
		if content == "" {
			continue
		}

		seqNum++

		// Skip already imported messages (incremental sync)
		if seqNum <= startSeqNum {
			continue
		}

		dbMsg := &message.Message{
			ConversationID: convID,
			Role:           role,
			Content:        content,
			SequenceNum:    seqNum,
		}

		if err := s.msgRepo.Create(ctx, dbMsg); err != nil {
			s.logger.Error("failed to create message", zap.Error(err))
			continue
		}

		count++
	}

	return count
}

// extractContentAsString converts message content to string
func extractContentAsString(content any) string {
	switch c := content.(type) {
	case string:
		return c
	case []interface{}:
		// Handle array content (like images + text)
		for _, item := range c {
			if m, ok := item.(map[string]interface{}); ok {
				if text, ok := m["text"].(string); ok {
					return text
				}
			}
		}
	}
	return ""
}
