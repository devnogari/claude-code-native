package claude

import (
	"testing"

	"github.com/stretchr/testify/assert"
)

func TestValidateEncodedPath(t *testing.T) {
	tests := []struct {
		name     string
		input    string
		expected bool
	}{
		// Valid paths
		{"valid path with leading dash", "-Users-test-project", true},
		{"valid path simple", "myproject", true},
		{"valid path with dashes", "my-project-name", true},
		{"valid path with underscores", "my_project_name", true},
		{"valid path alphanumeric", "project123", true},
		{"valid path mixed", "-Users-home-project_v2", true},

		// Invalid paths - empty
		{"empty string", "", false},

		// Invalid paths - traversal attacks
		{"path traversal double dot", "..", false},
		{"path traversal with prefix", "test/../etc", false},
		{"path traversal encoded start", "-Users-..-etc-passwd", false},
		{"single dot", ".", false},

		// Invalid paths - null byte injection
		{"null byte injection", "test\x00.txt", false},
		{"null byte in middle", "test\x00/../etc", false},

		// Invalid paths - special characters
		{"contains slash", "path/to/file", false},
		{"contains backslash", "path\\to\\file", false},
		{"contains space", "path to file", false},
		{"contains colon", "C:Users", false},
		{"contains semicolon", "path;injection", false},
		{"contains pipe", "path|cmd", false},
		{"contains ampersand", "path&cmd", false},
		{"contains dollar", "path$var", false},
		{"contains backtick", "path`cmd`", false},
		{"contains quotes", "path\"test\"", false},
		{"contains single quote", "path'test'", false},
		{"contains angle brackets", "path<test>", false},
		{"contains percent", "path%20test", false},
		{"contains at sign", "user@host", false},
		{"contains hash", "path#anchor", false},
		{"contains asterisk", "path*glob", false},
		{"contains question mark", "path?query", false},
		{"contains exclamation", "path!cmd", false},
		{"contains parentheses", "path(test)", false},
		{"contains brackets", "path[0]", false},
		{"contains braces", "path{test}", false},

		// Unicode/special encoding attempts
		{"unicode slash attempt", "test\u002fpasswd", false},
		{"unicode backslash attempt", "test\u005cetc", false},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			result := validateEncodedPath(tt.input)
			assert.Equal(t, tt.expected, result, "validateEncodedPath(%q) = %v, want %v", tt.input, result, tt.expected)
		})
	}
}

func TestValidateSessionID(t *testing.T) {
	tests := []struct {
		name     string
		input    string
		expected bool
	}{
		// Valid UUIDs - must be lowercase (URL convention)
		{"valid UUID lowercase", "550e8400-e29b-41d4-a716-446655440000", true},
		{"valid UUIDv7", "01912e3e-3c4a-7b2f-8c1a-9b3f2e4d5a6c", true},

		// Invalid UUIDs - uppercase not accepted (normalize to lowercase before calling)
		{"uppercase UUID rejected", "550E8400-E29B-41D4-A716-446655440000", false},
		{"mixed case UUID rejected", "550e8400-E29B-41d4-A716-446655440000", false},

		// Invalid UUIDs
		{"empty string", "", false},
		{"too short", "550e8400-e29b-41d4-a716", false},
		{"too long", "550e8400-e29b-41d4-a716-446655440000-extra", false},
		{"missing dashes", "550e8400e29b41d4a716446655440000", false},
		{"wrong dash positions", "550e8400e-29b-41d4-a716-44665544000", false},
		{"invalid characters", "550e8400-e29b-41d4-a716-44665544xxxx", false},
		{"path traversal attempt", "../../../etc/passwd", false},
		{"sql injection attempt", "'; DROP TABLE users; --", false},
		{"command injection", "$(whoami)", false},
		{"null byte", "550e8400-e29b-\x00-a716-446655440000", false},
		{"spaces", "550e8400 e29b 41d4 a716 446655440000", false},
		{"newline injection", "550e8400-e29b-41d4-a716-446655440000\n", false},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			result := validateSessionID(tt.input)
			assert.Equal(t, tt.expected, result, "validateSessionID(%q) = %v, want %v", tt.input, result, tt.expected)
		})
	}
}
