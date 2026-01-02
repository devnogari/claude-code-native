#!/bin/bash
# Claude Code Stop Hook - Session Completion Notifier
#
# This hook is called by Claude Code when a session completes (Stop event).
# It sends a notification to the claude-code-native backend to mark the project as completed.
#
# Installation:
# 1. Make this script executable: chmod +x session-complete-hook.sh
# 2. Configure in Claude Code settings (~/.claude/settings.json):
#    {
#      "hooks": {
#        "Stop": [
#          {
#            "matcher": "",
#            "hooks": [
#              {
#                "type": "command",
#                "command": "/path/to/session-complete-hook.sh"
#              }
#            ]
#          }
#        ]
#      }
#    }
#
# Environment:
# - The hook receives JSON input via stdin with: session_id, transcript_path, cwd, permission_mode, hook_event_name
# - Set CLAUDE_CODE_NATIVE_URL to override the backend URL (default: http://localhost:8080)
# - Set HOOK_API_KEY if authentication is enabled on the backend

set -e

# Configuration
API_URL="${CLAUDE_CODE_NATIVE_URL:-http://localhost:8080}"
ENDPOINT="${API_URL}/api/v1/hooks/session-complete"

# Read JSON input from stdin
INPUT=$(cat)

# Extract fields from JSON using jq (install if not present)
if ! command -v jq &> /dev/null; then
    echo "Warning: jq not installed, attempting to install..." >&2
    if command -v brew &> /dev/null; then
        brew install jq
    elif command -v apt-get &> /dev/null; then
        sudo apt-get install -y jq
    else
        echo "Error: jq is required but not installed. Please install jq." >&2
        exit 1
    fi
fi

# Parse the input JSON
SESSION_ID=$(echo "$INPUT" | jq -r '.session_id // empty')
TRANSCRIPT_PATH=$(echo "$INPUT" | jq -r '.transcript_path // empty')
CWD=$(echo "$INPUT" | jq -r '.cwd // empty')
PERMISSION_MODE=$(echo "$INPUT" | jq -r '.permission_mode // empty')
HOOK_EVENT_NAME=$(echo "$INPUT" | jq -r '.hook_event_name // empty')

# Validate required fields
if [ -z "$CWD" ]; then
    echo "Error: cwd is required in hook input" >&2
    exit 1
fi

# Build the request payload
PAYLOAD=$(jq -n \
    --arg session_id "$SESSION_ID" \
    --arg transcript_path "$TRANSCRIPT_PATH" \
    --arg cwd "$CWD" \
    --arg permission_mode "$PERMISSION_MODE" \
    --arg hook_event_name "$HOOK_EVENT_NAME" \
    '{
        session_id: $session_id,
        transcript_path: $transcript_path,
        cwd: $cwd,
        permission_mode: $permission_mode,
        hook_event_name: $hook_event_name
    }')

# Build headers
HEADERS=(-H "Content-Type: application/json")
if [ -n "$HOOK_API_KEY" ]; then
    HEADERS+=(-H "X-API-Key: $HOOK_API_KEY")
fi

# Send request to backend
RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "$ENDPOINT" "${HEADERS[@]}" -d "$PAYLOAD")
HTTP_CODE=$(echo "$RESPONSE" | tail -n1)
BODY=$(echo "$RESPONSE" | sed '$d')

# Check response
if [ "$HTTP_CODE" -eq 200 ] || [ "$HTTP_CODE" -eq 204 ]; then
    echo "Session marked as completed for project: $CWD"
else
    echo "Warning: Failed to mark session as completed (HTTP $HTTP_CODE): $BODY" >&2
    # Don't exit with error to avoid blocking Claude Code
fi
