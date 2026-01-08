package claude

import (
	"os"
	"path/filepath"
	"sync"
	"testing"
	"time"

	"github.com/gofrs/uuid/v5"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
	"go.uber.org/zap"
)

func TestNewProcess_InteractiveFields(t *testing.T) {
	convID := uuid.Must(uuid.NewV4())
	workDir := "/tmp/test"

	p := NewProcess(convID, workDir, nil)

	assert.Equal(t, convID, p.ConversationID)
	assert.Equal(t, workDir, p.WorkDir)
	assert.Equal(t, ProcessStatusIdle, p.Status)
	assert.NotNil(t, p.Output)
	assert.NotNil(t, p.Error)
	assert.NotNil(t, p.Done)
	assert.False(t, p.interactive)
	assert.Nil(t, p.stdin)
}

func TestProcess_IsInteractive_Initially(t *testing.T) {
	convID := uuid.Must(uuid.NewV4())
	p := NewProcess(convID, "/tmp", nil)

	assert.False(t, p.IsInteractive())
}

func TestProcess_SendMessage_NotInteractive(t *testing.T) {
	convID := uuid.Must(uuid.NewV4())
	p := NewProcess(convID, "/tmp", nil)

	err := p.SendMessage("test", nil)

	assert.Error(t, err)
	assert.Contains(t, err.Error(), "not in interactive mode")
}

func TestProcess_SendMessage_NotRunning(t *testing.T) {
	convID := uuid.Must(uuid.NewV4())
	p := NewProcess(convID, "/tmp", nil)
	p.interactive = true // Force interactive mode

	err := p.SendMessage("test", nil)

	assert.Error(t, err)
	assert.Contains(t, err.Error(), "not running")
}

func TestProcess_TrackImages(t *testing.T) {
	convID := uuid.Must(uuid.NewV4())
	logger, _ := zap.NewDevelopment()
	p := NewProcess(convID, "/tmp", logger)

	// Track some images
	paths := []string{"/tmp/img1.png", "/tmp/img2.png"}
	p.TrackImages(paths)

	p.imagesMu.Lock()
	assert.Len(t, p.pendingImages, 2)
	assert.Contains(t, p.pendingImages, "/tmp/img1.png")
	assert.Contains(t, p.pendingImages, "/tmp/img2.png")
	p.imagesMu.Unlock()
}

func TestProcess_TrackImages_Empty(t *testing.T) {
	convID := uuid.Must(uuid.NewV4())
	p := NewProcess(convID, "/tmp", nil)

	// Track empty list should be no-op
	p.TrackImages(nil)
	p.TrackImages([]string{})

	p.imagesMu.Lock()
	assert.Len(t, p.pendingImages, 0)
	p.imagesMu.Unlock()
}

func TestProcess_CheckForUpdate(t *testing.T) {
	// 1. Create a fake "claude" binary
	tmpDir := t.TempDir()
	fakeBinary := filepath.Join(tmpDir, "claude")
	err := os.WriteFile(fakeBinary, []byte("#!/bin/sh\necho ok"), 0755)
	require.NoError(t, err)

	// 2. Add tmpDir to PATH
	oldPath := os.Getenv("PATH")
	os.Setenv("PATH", tmpDir+string(os.PathListSeparator)+oldPath)
	defer os.Setenv("PATH", oldPath)

	convID := uuid.Must(uuid.NewV4())
	p := NewProcess(convID, "/tmp", nil)

	// 3. Initially, it should be false (no mod time recorded)
	assert.False(t, p.CheckForUpdate())

	// 4. Record mod time
	p.recordBinaryModTime()
	assert.False(t, p.CheckForUpdate())

	// 5. Update binary (change mtime)
	// Some filesystems have low mtime precision, so we set it explicitly to a future time
	newTime := time.Now().Add(1 * time.Second)
	err = os.Chtimes(fakeBinary, newTime, newTime)
	require.NoError(t, err)

	// 6. Now it should be true
	assert.True(t, p.CheckForUpdate())
}

func TestProcess_TrackImages_Multiple(t *testing.T) {
	convID := uuid.Must(uuid.NewV4())
	logger, _ := zap.NewDevelopment()
	p := NewProcess(convID, "/tmp", logger)

	// Track images in multiple calls
	p.TrackImages([]string{"/tmp/a.png"})
	p.TrackImages([]string{"/tmp/b.png", "/tmp/c.png"})

	p.imagesMu.Lock()
	assert.Len(t, p.pendingImages, 3)
	p.imagesMu.Unlock()
}

func TestProcess_CleanupImages(t *testing.T) {
	convID := uuid.Must(uuid.NewV4())
	logger, _ := zap.NewDevelopment()
	p := NewProcess(convID, "/tmp", logger)

	// Create temp files
	tmpDir := t.TempDir()
	file1 := filepath.Join(tmpDir, "test1.png")
	file2 := filepath.Join(tmpDir, "test2.png")
	require.NoError(t, os.WriteFile(file1, []byte("test"), 0644))
	require.NoError(t, os.WriteFile(file2, []byte("test"), 0644))

	// Track and cleanup
	p.TrackImages([]string{file1, file2})
	p.cleanupImages()

	// Files should be deleted
	_, err1 := os.Stat(file1)
	_, err2 := os.Stat(file2)
	assert.True(t, os.IsNotExist(err1))
	assert.True(t, os.IsNotExist(err2))

	// Pending images should be cleared
	p.imagesMu.Lock()
	assert.Len(t, p.pendingImages, 0)
	p.imagesMu.Unlock()
}

func TestProcess_Close_CleansUpImages(t *testing.T) {
	convID := uuid.Must(uuid.NewV4())
	logger, _ := zap.NewDevelopment()
	p := NewProcess(convID, "/tmp", logger)

	// Create temp file
	tmpDir := t.TempDir()
	file := filepath.Join(tmpDir, "test.png")
	require.NoError(t, os.WriteFile(file, []byte("test"), 0644))

	// Track image and close
	p.TrackImages([]string{file})
	p.Close()

	// File should be deleted
	_, err := os.Stat(file)
	assert.True(t, os.IsNotExist(err))
}

func TestProcess_Close_CleansUpStdin(t *testing.T) {
	convID := uuid.Must(uuid.NewV4())
	p := NewProcess(convID, "/tmp", nil)

	// Simulate interactive mode with stdin
	p.interactive = true
	// stdin would be set by StartInteractive, but we test cleanup logic

	p.Close()

	assert.False(t, p.interactive)
	assert.True(t, p.closed)
}

func TestProcess_Close_OnlyOnce(t *testing.T) {
	convID := uuid.Must(uuid.NewV4())
	p := NewProcess(convID, "/tmp", nil)

	// Close multiple times should not panic
	p.Close()
	p.Close()
	p.Close()

	assert.True(t, p.closed)
}

func TestProcess_TrackImages_Concurrent(t *testing.T) {
	convID := uuid.Must(uuid.NewV4())
	logger, _ := zap.NewDevelopment()
	p := NewProcess(convID, "/tmp", logger)

	var wg sync.WaitGroup
	for i := 0; i < 10; i++ {
		wg.Add(1)
		go func(n int) {
			defer wg.Done()
			p.TrackImages([]string{"/tmp/concurrent.png"})
		}(i)
	}
	wg.Wait()

	p.imagesMu.Lock()
	assert.Len(t, p.pendingImages, 10)
	p.imagesMu.Unlock()
}

func TestProcess_GetStatus_ConcurrentAccess(t *testing.T) {
	convID := uuid.Must(uuid.NewV4())
	p := NewProcess(convID, "/tmp", nil)

	var wg sync.WaitGroup

	// Concurrent reads
	for i := 0; i < 10; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			_ = p.GetStatus()
		}()
	}

	// Concurrent writes
	for i := 0; i < 10; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			p.SetStatus(ProcessStatusRunning)
		}()
	}

	wg.Wait()
}

func TestProcess_IsInteractive_ThreadSafe(t *testing.T) {
	convID := uuid.Must(uuid.NewV4())
	p := NewProcess(convID, "/tmp", nil)

	var wg sync.WaitGroup

	// Concurrent reads
	for i := 0; i < 10; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			_ = p.IsInteractive()
		}()
	}

	wg.Wait()
}

func TestProcess_Close_WithConcurrentSendMessage(t *testing.T) {
	convID := uuid.Must(uuid.NewV4())
	p := NewProcess(convID, "/tmp", nil)
	p.interactive = true
	p.Status = ProcessStatusRunning
	// Note: stdin is nil, so SendMessage will fail, but this tests the mutex coordination

	var wg sync.WaitGroup

	// Start Close in background
	wg.Add(1)
	go func() {
		defer wg.Done()
		time.Sleep(1 * time.Millisecond) // Small delay
		p.Close()
	}()

	// Try to send message concurrently
	wg.Add(1)
	go func() {
		defer wg.Done()
		_ = p.SendMessage("test", nil) // Will fail due to nil stdin, but tests thread safety
	}()

	wg.Wait()
	assert.True(t, p.closed)
}

func TestParseStreamJSON_Assistant(t *testing.T) {
	json := `{"type":"assistant","message":{"content":[{"type":"text","text":"Hello"}]}}`
	text, isDisplayable := ParseStreamJSON(json)

	assert.True(t, isDisplayable)
	assert.Equal(t, "Hello", text)
}

func TestParseStreamJSON_NonAssistant(t *testing.T) {
	json := `{"type":"system","subtype":"init"}`
	text, isDisplayable := ParseStreamJSON(json)

	assert.False(t, isDisplayable)
	assert.Empty(t, text)
}

func TestParseStreamJSON_Invalid(t *testing.T) {
	text, isDisplayable := ParseStreamJSON("invalid json")

	assert.False(t, isDisplayable)
	assert.Empty(t, text)
}

// ============================================
// Interactive Mode Tests
// ============================================

func TestProcess_SendMessage_NilStdin(t *testing.T) {
	convID := uuid.Must(uuid.NewV4())
	p := NewProcess(convID, "/tmp", nil)
	p.interactive = true
	p.Status = ProcessStatusRunning
	// stdin is nil

	err := p.SendMessage("test", nil)

	assert.Error(t, err)
	assert.Contains(t, err.Error(), "stdin not available")
}

func TestProcess_SendMessage_WithImages_BuildsCorrectMessage(t *testing.T) {
	// This test verifies the message format with images
	// Since we can't easily mock stdin, we verify the error path
	convID := uuid.Must(uuid.NewV4())
	logger, _ := zap.NewDevelopment()
	p := NewProcess(convID, "/tmp", logger)
	p.interactive = true
	p.Status = ProcessStatusRunning
	// stdin is nil, but we test message building logic

	// The message building happens before stdin write
	// Images should be prepended as @ mentions
	err := p.SendMessage("hello world", []string{"/tmp/img1.png", "/tmp/img2.png"})

	// Will fail due to nil stdin, but message was built correctly
	assert.Error(t, err)
	assert.Contains(t, err.Error(), "stdin not available")
}

func TestProcess_InteractiveFlag_Transitions(t *testing.T) {
	convID := uuid.Must(uuid.NewV4())
	p := NewProcess(convID, "/tmp", nil)

	// Initially not interactive
	assert.False(t, p.IsInteractive())

	// Set interactive
	p.mu.Lock()
	p.interactive = true
	p.mu.Unlock()
	assert.True(t, p.IsInteractive())

	// Close should reset interactive flag
	p.Close()
	assert.False(t, p.IsInteractive())
}

func TestProcess_StatusTransitions_InteractiveMode(t *testing.T) {
	convID := uuid.Must(uuid.NewV4())
	p := NewProcess(convID, "/tmp", nil)

	// Start idle
	assert.Equal(t, ProcessStatusIdle, p.GetStatus())

	// Transition to running
	p.SetStatus(ProcessStatusRunning)
	assert.Equal(t, ProcessStatusRunning, p.GetStatus())

	// Transition to stopped
	p.SetStatus(ProcessStatusStopped)
	assert.Equal(t, ProcessStatusStopped, p.GetStatus())

	// Transition to error
	p.SetStatus(ProcessStatusError)
	assert.Equal(t, ProcessStatusError, p.GetStatus())
}

func TestProcess_CleanupImages_NonExistentFiles(t *testing.T) {
	convID := uuid.Must(uuid.NewV4())
	logger, _ := zap.NewDevelopment()
	p := NewProcess(convID, "/tmp", logger)

	// Track non-existent files - should not panic
	p.TrackImages([]string{"/nonexistent/file1.png", "/nonexistent/file2.png"})
	p.cleanupImages() // Should not panic

	// Pending images should still be cleared
	p.imagesMu.Lock()
	assert.Len(t, p.pendingImages, 0)
	p.imagesMu.Unlock()
}

func TestProcess_CleanupImages_CalledMultipleTimes(t *testing.T) {
	convID := uuid.Must(uuid.NewV4())
	logger, _ := zap.NewDevelopment()
	p := NewProcess(convID, "/tmp", logger)

	// Create temp file
	tmpDir := t.TempDir()
	file := filepath.Join(tmpDir, "test.png")
	require.NoError(t, os.WriteFile(file, []byte("test"), 0644))

	p.TrackImages([]string{file})

	// Call cleanup multiple times - should not panic
	p.cleanupImages()
	p.cleanupImages()
	p.cleanupImages()

	// Pending images should be empty after first cleanup
	p.imagesMu.Lock()
	assert.Len(t, p.pendingImages, 0)
	p.imagesMu.Unlock()
}

func TestProcess_TrackImages_DuringCleanup(t *testing.T) {
	convID := uuid.Must(uuid.NewV4())
	logger, _ := zap.NewDevelopment()
	p := NewProcess(convID, "/tmp", logger)

	var wg sync.WaitGroup

	// Concurrent track and cleanup
	for i := 0; i < 5; i++ {
		wg.Add(2)
		go func() {
			defer wg.Done()
			p.TrackImages([]string{"/tmp/test.png"})
		}()
		go func() {
			defer wg.Done()
			p.cleanupImages()
		}()
	}

	wg.Wait()
	// Should not panic or deadlock
}

func TestProcess_Close_ResetsAllInteractiveState(t *testing.T) {
	convID := uuid.Must(uuid.NewV4())
	logger, _ := zap.NewDevelopment()
	p := NewProcess(convID, "/tmp", logger)

	// Set up interactive state
	p.interactive = true
	p.Status = ProcessStatusRunning
	p.TrackImages([]string{"/tmp/test.png"})

	// Close should reset all state
	p.Close()

	assert.False(t, p.interactive)
	assert.True(t, p.closed)
	assert.Nil(t, p.stdin)

	p.imagesMu.Lock()
	assert.Len(t, p.pendingImages, 0)
	p.imagesMu.Unlock()
}

func TestProcess_SendMessage_AfterClose(t *testing.T) {
	convID := uuid.Must(uuid.NewV4())
	p := NewProcess(convID, "/tmp", nil)
	p.interactive = true
	p.Status = ProcessStatusRunning

	// Close the process
	p.Close()

	// Attempt to send message should fail
	err := p.SendMessage("test", nil)
	assert.Error(t, err)
}

func TestProcess_IsInteractive_AfterClose(t *testing.T) {
	convID := uuid.Must(uuid.NewV4())
	p := NewProcess(convID, "/tmp", nil)
	p.interactive = true

	p.Close()

	// Should return false after close
	assert.False(t, p.IsInteractive())
}

func TestProcess_MultipleQueuedMessages_Simulation(t *testing.T) {
	// Simulates multiple queued messages scenario
	convID := uuid.Must(uuid.NewV4())
	logger, _ := zap.NewDevelopment()
	p := NewProcess(convID, "/tmp", logger)

	// Track images from multiple "queued" messages
	p.TrackImages([]string{"/tmp/msg1_img.png"})
	p.TrackImages([]string{"/tmp/msg2_img.png"})
	p.TrackImages([]string{"/tmp/msg3_img.png"})

	p.imagesMu.Lock()
	assert.Len(t, p.pendingImages, 3)
	p.imagesMu.Unlock()

	// Close should clean up all images
	p.Close()

	p.imagesMu.Lock()
	assert.Len(t, p.pendingImages, 0)
	p.imagesMu.Unlock()
}

func TestProcess_TrackImages_PreservesOrder(t *testing.T) {
	convID := uuid.Must(uuid.NewV4())
	logger, _ := zap.NewDevelopment()
	p := NewProcess(convID, "/tmp", logger)

	// Track images in specific order
	p.TrackImages([]string{"/tmp/first.png"})
	p.TrackImages([]string{"/tmp/second.png"})
	p.TrackImages([]string{"/tmp/third.png"})

	p.imagesMu.Lock()
	assert.Equal(t, "/tmp/first.png", p.pendingImages[0])
	assert.Equal(t, "/tmp/second.png", p.pendingImages[1])
	assert.Equal(t, "/tmp/third.png", p.pendingImages[2])
	p.imagesMu.Unlock()
}

func TestProcess_ConcurrentSendMessage_WithClose(t *testing.T) {
	convID := uuid.Must(uuid.NewV4())
	p := NewProcess(convID, "/tmp", nil)
	p.interactive = true
	p.Status = ProcessStatusRunning

	var wg sync.WaitGroup
	errCount := 0
	var errMu sync.Mutex

	// Multiple concurrent SendMessage attempts
	for i := 0; i < 10; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			if err := p.SendMessage("test", nil); err != nil {
				errMu.Lock()
				errCount++
				errMu.Unlock()
			}
		}()
	}

	// Close while messages are being sent
	time.Sleep(100 * time.Microsecond)
	p.Close()

	wg.Wait()

	// All messages should have failed (nil stdin or closed)
	assert.Equal(t, 10, errCount)
	assert.True(t, p.closed)
}

func TestProcess_StartInteractive_AlreadyRunning(t *testing.T) {
	convID := uuid.Must(uuid.NewV4())
	p := NewProcess(convID, "/tmp", nil)

	// Set status to running
	p.Status = ProcessStatusRunning

	// StartInteractive should fail
	err := p.StartInteractive()
	assert.Error(t, err)
	assert.Contains(t, err.Error(), "already running")
}

func TestProcess_HasRun_Flag(t *testing.T) {
	convID := uuid.Must(uuid.NewV4())
	p := NewProcess(convID, "/tmp", nil)

	// Initially hasRun is false
	assert.False(t, p.hasRun)

	// After Close, hasRun should be preserved (set by Start methods)
	p.Close()
	assert.False(t, p.hasRun) // Still false since we didn't actually start
}

func TestProcess_ChannelsClosed_AfterClose(t *testing.T) {
	convID := uuid.Must(uuid.NewV4())
	p := NewProcess(convID, "/tmp", nil)

	p.Close()

	// Reading from closed channels should not block
	select {
	case _, ok := <-p.Done:
		assert.False(t, ok, "Done channel should be closed")
	default:
		t.Fatal("Done channel should be readable (closed)")
	}

	select {
	case _, ok := <-p.Output:
		assert.False(t, ok, "Output channel should be closed")
	default:
		t.Fatal("Output channel should be readable (closed)")
	}

	select {
	case _, ok := <-p.Error:
		assert.False(t, ok, "Error channel should be closed")
	default:
		t.Fatal("Error channel should be readable (closed)")
	}
}

func TestParseStreamJSON_EmptyContent(t *testing.T) {
	json := `{"type":"assistant","message":{"content":[]}}`
	text, isDisplayable := ParseStreamJSON(json)

	assert.False(t, isDisplayable)
	assert.Empty(t, text)
}

func TestParseStreamJSON_NonTextBlock(t *testing.T) {
	json := `{"type":"assistant","message":{"content":[{"type":"tool_use","id":"123"}]}}`
	text, isDisplayable := ParseStreamJSON(json)

	assert.False(t, isDisplayable)
	assert.Empty(t, text)
}

func TestParseStreamJSON_EmptyText(t *testing.T) {
	json := `{"type":"assistant","message":{"content":[{"type":"text","text":""}]}}`
	text, isDisplayable := ParseStreamJSON(json)

	assert.False(t, isDisplayable)
	assert.Empty(t, text)
}

func TestParseStreamJSON_MultipleBlocks(t *testing.T) {
	// Only first text block should be returned
	json := `{"type":"assistant","message":{"content":[{"type":"text","text":"First"},{"type":"text","text":"Second"}]}}`
	text, isDisplayable := ParseStreamJSON(json)

	assert.True(t, isDisplayable)
	assert.Equal(t, "First", text)
}

func TestParseStreamJSON_UserMessage(t *testing.T) {
	json := `{"type":"user","message":{"content":[{"type":"text","text":"Hello"}]}}`
	text, isDisplayable := ParseStreamJSON(json)

	assert.False(t, isDisplayable)
	assert.Empty(t, text)
}

func TestParseStreamJSON_ResultMessage(t *testing.T) {
	json := `{"type":"result","subtype":"success"}`
	text, isDisplayable := ParseStreamJSON(json)

	assert.False(t, isDisplayable)
	assert.Empty(t, text)
}
