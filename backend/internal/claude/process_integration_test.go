package claude

import (
	"testing"
	"time"

	"github.com/gofrs/uuid/v5"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
	"go.uber.org/zap"
)

func TestProcessStartWithPrompt_Integration(t *testing.T) {
	if testing.Short() {
		t.Skip("Skipping integration test")
	}

	logger, _ := zap.NewDevelopment()
	convID := uuid.Must(uuid.NewV7())

	process := NewProcess(
		convID,
		"/Users/probe/git/devnogari/claude-code-native",
		logger,
	)

	// Start the process
	err := process.StartWithPrompt("hello")
	require.NoError(t, err, "StartWithPrompt should not return error")

	assert.Equal(t, ProcessStatusRunning, process.GetStatus())

	// Collect output
	var outputs []OutputMessage
	var processErr error
	done := false

	timeout := time.After(30 * time.Second)

	for !done {
		select {
		case output, ok := <-process.Output:
			if !ok {
				done = true
				break
			}
			t.Logf("Output [%s]: %s", output.Type, output.Content[:min(len(output.Content), 200)])
			outputs = append(outputs, output)
			if output.Type == "status" && output.Content == "completed" {
				done = true
			}
		case err, ok := <-process.Error:
			if ok {
				t.Logf("Error: %v", err)
				processErr = err
			}
		case <-timeout:
			t.Fatal("Test timed out after 30 seconds")
		}
	}

	// Check results
	t.Logf("Total outputs received: %d", len(outputs))
	t.Logf("Final status: %s", process.GetStatus())

	if processErr != nil {
		t.Errorf("Process error: %v", processErr)
	}

	// We should have received some output
	assert.Greater(t, len(outputs), 0, "Should receive at least some output")
}
