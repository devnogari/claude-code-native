# Contributing to Claude Code Native

Thank you for your interest in contributing to Claude Code Native! This document provides guidelines and instructions for contributing.

## Table of Contents

- [Code of Conduct](#code-of-conduct)
- [Getting Started](#getting-started)
- [Development Setup](#development-setup)
- [Making Changes](#making-changes)
- [Pull Request Process](#pull-request-process)
- [Code Style](#code-style)
- [Testing](#testing)
- [Commit Messages](#commit-messages)

## Code of Conduct

This project adheres to a [Code of Conduct](CODE_OF_CONDUCT.md). By participating, you are expected to uphold this code.

## Getting Started

1. Fork the repository
2. Clone your fork: `git clone https://github.com/YOUR_USERNAME/claude-code-native.git`
3. Add upstream remote: `git remote add upstream https://github.com/devnogari/claude-code-native.git`
4. Create a feature branch: `git checkout -b feature/your-feature-name`

## Development Setup

### Prerequisites

- **Go**: 1.24+
- **JDK**: 17+ (for Kotlin/Compose frontend)
- **Docker & Docker Compose**: For PostgreSQL
- **Node.js**: 18+ (for WASM builds)

### Backend Setup

```bash
cd backend

# Install dependencies
go mod download

# Set up environment
cp ../.env.example ../.env
# Edit .env with your values

# Start PostgreSQL
docker-compose up -d postgres

# Run the server
go run ./cmd/server
```

### Frontend Setup

```bash
cd frontend

# Run desktop app
./gradlew composeApp:desktopRun

# Run web development server
./gradlew composeApp:wasmJsBrowserRun
```

### Full Stack with Make

```bash
# Start everything (PostgreSQL + Backend)
make up

# Run backend
make run

# Run frontend desktop
make desktop

# Run frontend web
make web
```

## Making Changes

1. **Sync with upstream**: `git fetch upstream && git rebase upstream/develop`
2. **Create a branch**: `git checkout -b feature/description` or `fix/description`
3. **Make your changes**: Follow code style guidelines
4. **Test your changes**: Run all relevant tests
5. **Commit**: Use conventional commit messages

## Pull Request Process

1. **Update documentation**: If you changed APIs or behavior
2. **Add tests**: For new features or bug fixes
3. **Run checks**: Ensure all tests pass locally
4. **Create PR**: Against the `develop` branch
5. **Fill PR template**: Complete all sections
6. **Address reviews**: Respond to feedback promptly

### PR Requirements

- [ ] Tests pass locally (`go test ./...` and `./gradlew check`)
- [ ] Code follows style guidelines
- [ ] Documentation updated if needed
- [ ] Commit messages follow convention
- [ ] No unrelated changes included

## Code Style

### Go (Backend)

- Follow standard Go formatting (`gofmt`)
- Use meaningful variable and function names
- Keep functions focused and small
- Document exported functions and types
- Handle errors explicitly

```go
// Good
func (s *UserService) GetByID(ctx context.Context, id uuid.UUID) (*User, error) {
    user, err := s.repo.FindByID(ctx, id)
    if err != nil {
        return nil, fmt.Errorf("find user by id: %w", err)
    }
    return user, nil
}
```

### Kotlin (Frontend)

- Follow Kotlin coding conventions
- Use Detekt for static analysis
- Prefer immutable data (`val` over `var`)
- Use meaningful names for Composables

```kotlin
// Good
@Composable
fun MessageList(
    messages: List<Message>,
    onMessageClick: (Message) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(modifier = modifier) {
        items(messages) { message ->
            MessageItem(message, onClick = { onMessageClick(message) })
        }
    }
}
```

## Testing

### Backend Tests

```bash
cd backend

# Run all tests
go test ./...

# Run tests with coverage
go test -cover ./...

# Run specific package tests
go test -v ./internal/auth/...

# Run specific test
go test -v -run TestLogin ./internal/auth/...
```

### Frontend Tests

```bash
cd frontend

# Run all checks (tests + Detekt)
./gradlew check

# Run Detekt only
./gradlew detekt
```

## Commit Messages

We follow [Conventional Commits](https://www.conventionalcommits.org/):

```
<type>(<scope>): <description>

[optional body]

[optional footer]
```

### Types

- `feat`: New feature
- `fix`: Bug fix
- `docs`: Documentation changes
- `style`: Code style changes (formatting, etc.)
- `refactor`: Code changes that neither fix bugs nor add features
- `perf`: Performance improvements
- `test`: Adding or updating tests
- `chore`: Maintenance tasks

### Examples

```
feat(auth): add JWT refresh token support

fix(websocket): handle reconnection on network failure

docs(readme): update installation instructions

refactor(handler): extract validation logic to separate function
```

## Questions?

- Open an issue for bugs or feature requests
- Start a discussion for questions or ideas

Thank you for contributing!
