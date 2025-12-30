package claude

import (
	"sync"
	"testing"
	"time"

	"github.com/gofrs/uuid/v5"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestNewManager(t *testing.T) {
	manager := NewManager()

	require.NotNil(t, manager, "expected non-nil manager")
	assert.NotNil(t, manager.processes, "expected processes map to be initialized")
}

func TestManager_CreateProcess(t *testing.T) {
	manager := NewManager()
	convID, _ := uuid.NewV7()
	workDir := "/tmp/test"
	env := []string{"TEST_VAR=1"}

	process, err := manager.CreateProcess(convID, workDir, env)

	require.NoError(t, err)
	require.NotNil(t, process)
	assert.Equal(t, convID, process.ConversationID)
	assert.Equal(t, workDir, process.WorkDir)
	assert.Equal(t, ProcessStatusIdle, process.GetStatus())
}

func TestManager_CreateProcess_ReturnsExisting(t *testing.T) {
	manager := NewManager()
	convID, _ := uuid.NewV7()
	workDir := "/tmp/test"

	// Create first process
	process1, err := manager.CreateProcess(convID, workDir, nil)
	require.NoError(t, err)

	// Create again with same conversation ID
	process2, err := manager.CreateProcess(convID, "/different/dir", nil)
	require.NoError(t, err)

	// Should return same process
	assert.Same(t, process1, process2, "expected same process instance")
	assert.Equal(t, workDir, process2.WorkDir, "workDir should not change for existing process")
}

func TestManager_GetProcess_NonExistent(t *testing.T) {
	manager := NewManager()
	convID, _ := uuid.NewV7()

	process := manager.GetProcess(convID)

	assert.Nil(t, process, "expected nil for non-existent process")
}

func TestManager_GetProcess_AfterCreation(t *testing.T) {
	manager := NewManager()
	convID, _ := uuid.NewV7()
	workDir := "/tmp/test"

	created, err := manager.CreateProcess(convID, workDir, nil)
	require.NoError(t, err)

	retrieved := manager.GetProcess(convID)

	require.NotNil(t, retrieved)
	assert.Same(t, created, retrieved, "expected same process instance")
}

func TestManager_StopProcess(t *testing.T) {
	manager := NewManager()
	convID, _ := uuid.NewV7()

	// Create a process
	process, err := manager.CreateProcess(convID, "/tmp/test", nil)
	require.NoError(t, err)

	// Stop the process
	err = manager.StopProcess(convID)
	require.NoError(t, err)

	// Process should be removed
	assert.Nil(t, manager.GetProcess(convID), "expected process to be removed")

	// Done channel should be closed
	select {
	case <-process.Done:
		// Expected - Done channel is closed
	default:
		t.Error("expected Done channel to be closed")
	}
}

func TestManager_StopProcess_NonExistent(t *testing.T) {
	manager := NewManager()
	convID, _ := uuid.NewV7()

	err := manager.StopProcess(convID)

	assert.Error(t, err, "expected error when stopping non-existent process")
}

func TestManager_StopAll(t *testing.T) {
	manager := NewManager()

	// Create multiple processes
	convIDs := make([]uuid.UUID, 3)
	processes := make([]*Process, 3)
	for i := 0; i < 3; i++ {
		convIDs[i], _ = uuid.NewV7()
		var err error
		processes[i], err = manager.CreateProcess(convIDs[i], "/tmp/test", nil)
		require.NoError(t, err)
	}

	// Stop all
	manager.StopAll()

	// All processes should be removed
	for _, convID := range convIDs {
		assert.Nil(t, manager.GetProcess(convID), "expected process to be removed")
	}

	// All Done channels should be closed
	for i, process := range processes {
		select {
		case <-process.Done:
			// Expected - Done channel is closed
		default:
			t.Errorf("expected Done channel to be closed for process %d", i)
		}
	}
}

func TestManager_ListProcesses(t *testing.T) {
	manager := NewManager()

	// Create multiple processes
	convIDs := make([]uuid.UUID, 3)
	for i := 0; i < 3; i++ {
		convIDs[i], _ = uuid.NewV7()
		_, err := manager.CreateProcess(convIDs[i], "/tmp/test", nil)
		require.NoError(t, err)
	}

	// List processes
	processes := manager.ListProcesses()

	assert.Len(t, processes, 3, "expected 3 processes")

	// Verify all conversation IDs are present
	foundIDs := make(map[uuid.UUID]bool)
	for _, p := range processes {
		foundIDs[p.ConversationID] = true
	}
	for _, convID := range convIDs {
		assert.True(t, foundIDs[convID], "expected conversation ID %s to be found", convID)
	}
}

func TestManager_ConcurrentAccess(t *testing.T) {
	manager := NewManager()
	numOps := 50
	var wg sync.WaitGroup

	convIDs := make([]uuid.UUID, numOps)
	for i := 0; i < numOps; i++ {
		convIDs[i], _ = uuid.NewV7()
	}

	// Concurrent creates
	for i := 0; i < numOps; i++ {
		wg.Add(1)
		go func(idx int) {
			defer wg.Done()
			_, err := manager.CreateProcess(convIDs[idx], "/tmp/test", nil)
			assert.NoError(t, err)
		}(i)
	}
	wg.Wait()

	// Verify all created
	processes := manager.ListProcesses()
	assert.Len(t, processes, numOps, "expected %d processes", numOps)

	// Concurrent reads
	for i := 0; i < numOps; i++ {
		wg.Add(1)
		go func(idx int) {
			defer wg.Done()
			p := manager.GetProcess(convIDs[idx])
			assert.NotNil(t, p)
		}(i)
	}
	wg.Wait()

	// Concurrent stops
	for i := 0; i < numOps; i++ {
		wg.Add(1)
		go func(idx int) {
			defer wg.Done()
			err := manager.StopProcess(convIDs[idx])
			assert.NoError(t, err)
		}(i)
	}
	wg.Wait()

	// Verify all stopped
	processes = manager.ListProcesses()
	assert.Len(t, processes, 0, "expected 0 processes after stopping all")
}

// Process tests

func TestNewProcess(t *testing.T) {
	convID, _ := uuid.NewV7()
	workDir := "/tmp/test"

	process := NewProcess(convID, workDir)

	require.NotNil(t, process)
	assert.Equal(t, convID, process.ConversationID)
	assert.Equal(t, workDir, process.WorkDir)
	assert.Equal(t, ProcessStatusIdle, process.Status)
	assert.NotNil(t, process.Input, "expected Input channel to be initialized")
	assert.NotNil(t, process.Output, "expected Output channel to be initialized")
	assert.NotNil(t, process.Error, "expected Error channel to be initialized")
	assert.NotNil(t, process.Done, "expected Done channel to be initialized")
	assert.Nil(t, process.StartedAt, "expected StartedAt to be nil initially")
	assert.Nil(t, process.Cmd, "expected Cmd to be nil initially")
}

func TestProcess_SetStatus(t *testing.T) {
	convID, _ := uuid.NewV7()
	process := NewProcess(convID, "/tmp/test")

	process.SetStatus(ProcessStatusRunning)

	assert.Equal(t, ProcessStatusRunning, process.GetStatus())
}

func TestProcess_GetStatus_ThreadSafe(t *testing.T) {
	convID, _ := uuid.NewV7()
	process := NewProcess(convID, "/tmp/test")

	var wg sync.WaitGroup
	statuses := []string{ProcessStatusIdle, ProcessStatusRunning, ProcessStatusStopped, ProcessStatusError}

	// Concurrent status updates and reads
	for i := 0; i < 100; i++ {
		wg.Add(2)
		go func(idx int) {
			defer wg.Done()
			process.SetStatus(statuses[idx%len(statuses)])
		}(i)
		go func() {
			defer wg.Done()
			_ = process.GetStatus()
		}()
	}
	wg.Wait()

	// Just verify it doesn't panic - final status can be any of the values
	status := process.GetStatus()
	validStatuses := map[string]bool{
		ProcessStatusIdle:    true,
		ProcessStatusRunning: true,
		ProcessStatusStopped: true,
		ProcessStatusError:   true,
	}
	assert.True(t, validStatuses[status], "expected valid status, got %s", status)
}

func TestProcess_SendInput(t *testing.T) {
	convID, _ := uuid.NewV7()
	process := NewProcess(convID, "/tmp/test")

	testMsg := "test message"
	process.SendInput(testMsg)

	select {
	case msg := <-process.Input:
		assert.Equal(t, testMsg, msg)
	case <-time.After(100 * time.Millisecond):
		t.Error("expected message on Input channel")
	}
}

func TestProcess_SendInput_Buffered(t *testing.T) {
	convID, _ := uuid.NewV7()
	process := NewProcess(convID, "/tmp/test")

	// Should not block for buffered sends (buffer size 10)
	for i := 0; i < 10; i++ {
		done := make(chan bool)
		go func() {
			process.SendInput("test")
			done <- true
		}()

		select {
		case <-done:
			// Non-blocking - good
		case <-time.After(50 * time.Millisecond):
			t.Fatalf("SendInput blocked on message %d", i)
		}
	}
}

func TestProcess_Close(t *testing.T) {
	convID, _ := uuid.NewV7()
	process := NewProcess(convID, "/tmp/test")

	process.Close()

	// Done channel should be closed
	select {
	case <-process.Done:
		// Expected - Done channel is closed
	default:
		t.Error("expected Done channel to be closed")
	}

	// Input channel should be closed
	select {
	case _, ok := <-process.Input:
		assert.False(t, ok, "expected Input channel to be closed")
	default:
		t.Error("expected Input channel to be closed")
	}
}

func TestProcess_ChannelBufferSizes(t *testing.T) {
	convID, _ := uuid.NewV7()
	process := NewProcess(convID, "/tmp/test")

	// Input channel buffer: 10
	for i := 0; i < 10; i++ {
		select {
		case process.Input <- "test":
		default:
			t.Fatalf("Input channel buffer should accept at least 10 messages, blocked at %d", i)
		}
	}

	// Output channel buffer: 100
	for i := 0; i < 100; i++ {
		select {
		case process.Output <- OutputMessage{Type: "test", Content: "test"}:
		default:
			t.Fatalf("Output channel buffer should accept at least 100 messages, blocked at %d", i)
		}
	}

	// Error channel buffer: 10
	for i := 0; i < 10; i++ {
		select {
		case process.Error <- nil:
		default:
			t.Fatalf("Error channel buffer should accept at least 10 messages, blocked at %d", i)
		}
	}
}

func TestOutputMessage(t *testing.T) {
	msg := OutputMessage{
		Type:    "stdout",
		Content: "hello world",
	}

	assert.Equal(t, "stdout", msg.Type)
	assert.Equal(t, "hello world", msg.Content)
}
