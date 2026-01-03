# Ralph Wiggum + Superpowers Integration Guide

## Overview

This document explores how to integrate Ralph Wiggum (autonomous loop plugin) with Superpowers (workflow and quality skills) for maximum development efficiency.

## What is Ralph Wiggum?

[Ralph Wiggum](https://github.com/anthropics/claude-code/tree/main/plugins/ralph-wiggum) is an official Anthropic plugin for Claude Code that implements autonomous iterative development loops.

### Core Mechanism

```bash
# Run once:
/ralph-loop "Your task" --completion-promise "DONE" --max-iterations 50

# Claude automatically:
# 1. Works on task
# 2. Attempts exit
# 3. Stop hook blocks exit
# 4. Same prompt re-fed
# 5. Repeats until completion
```

The **Stop hook** intercepts Claude's exit attempts and re-feeds the original prompt, creating a self-referential feedback loop within the current session.

### Key Features

| Feature | Description |
|---------|-------------|
| `--max-iterations` | Safety limit to prevent infinite loops |
| `--completion-promise` | Exact string match that signals completion |
| `/cancel-ralph` | Cancel active loop at any time |

### Real-World Results

- 6 repositories shipped overnight at YC hackathon ($297 API cost)
- Complete programming language ("Cursed") built over 3 months
- $50K contract completed for $297 in API costs

## What is Superpowers?

Superpowers is a Claude Code plugin providing structured workflow skills:

| Skill | Purpose |
|-------|---------|
| `brainstorming` | Requirements discovery |
| `writing-plans` | Implementation planning |
| `executing-plans` | Batch execution with checkpoints |
| `subagent-driven-development` | Fresh subagent per task + review |
| `test-driven-development` | TDD workflow |
| `requesting-code-review` | Quality verification |
| `systematic-debugging` | Root cause analysis |
| `finishing-a-development-branch` | Merge/PR completion |

## Integration Patterns

### Pattern 1: Ralph + TDD

Combine autonomous loops with test-driven development:

```bash
/ralph-loop "Implement user authentication following TDD:

For each component:
1. Write failing tests first (use superpowers:test-driven-development)
2. Implement minimal code to pass
3. Run tests
4. If any fail, debug and fix
5. Refactor if needed

Requirements:
- JWT token generation and validation
- Password hashing with bcrypt
- Login/register endpoints
- Tests with >80% coverage

Output <promise>COMPLETE</promise> when all tests pass." --max-iterations 30
```

### Pattern 2: Ralph + Subagent-Driven Development

Execute plans with fresh subagents per task:

```bash
/ralph-loop "Execute the plan in docs/plans/feature-plan.md

Use superpowers:subagent-driven-development:
- Dispatch implementer subagent per task
- Dispatch spec reviewer after implementation
- Dispatch code quality reviewer after spec passes
- Fix any issues found in reviews

Only output <promise>COMPLETE</promise> when:
- All tasks implemented
- All spec reviews pass
- All code quality reviews pass" --max-iterations 50
```

### Pattern 3: Ralph + Code Review Loop

Continuous implementation with quality gates:

```bash
/ralph-loop "Build REST API for todos:

For each endpoint:
1. Implement endpoint
2. Write tests
3. Run tests until passing
4. Use superpowers:requesting-code-review
5. Fix any issues from review
6. Re-review until approved

Endpoints needed:
- GET /todos (list all)
- POST /todos (create)
- GET /todos/:id (get one)
- PUT /todos/:id (update)
- DELETE /todos/:id (delete)

Output <promise>COMPLETE</promise> when all endpoints pass code review." --max-iterations 40
```

### Pattern 4: Ralph + Systematic Debugging

Fix bugs with structured debugging:

```bash
/ralph-loop "Fix the flaky test in auth_test.go:

Use superpowers:systematic-debugging:
1. Reproduce the failure
2. Isolate the root cause
3. Form hypothesis
4. Test hypothesis
5. Implement fix
6. Verify fix across multiple runs

Success criteria:
- Test passes 10 consecutive times
- No timing dependencies
- Root cause documented

Output <promise>COMPLETE</promise> when stable." --max-iterations 20
```

## When to Use Each Approach

### Use Ralph + Superpowers When:

- Well-defined tasks with clear completion criteria
- Automatic verification possible (tests, linters)
- Quality gates needed (code review, spec compliance)
- Greenfield development you can walk away from
- Large refactoring with consistent patterns

### Use Superpowers Only When:

- Human judgment needed between steps
- Design decisions require discussion
- Single-pass operations
- Exploration and discovery phase

### Use Ralph Only When:

- Simple repetitive tasks
- Quality gates not needed
- Fast iteration more important than review

## Best Practices

### 1. Always Set Iteration Limits

```bash
# Good: Conservative limit
/ralph-loop "..." --max-iterations 20

# Bad: No limit (infinite loop risk)
/ralph-loop "..."
```

### 2. Clear Completion Criteria

```markdown
# Good: Specific and verifiable
When complete:
- All CRUD endpoints working
- Tests passing (coverage > 80%)
- Code review approved
- Output: <promise>COMPLETE</promise>

# Bad: Vague
Make it good and output DONE when finished.
```

### 3. Include Escape Hatches

```markdown
After 15 iterations, if not complete:
- Document what's blocking progress
- List what was attempted
- Suggest alternative approaches
- Output: <promise>BLOCKED</promise>
```

### 4. Use Appropriate Skills

| Task Type | Recommended Skill |
|-----------|-------------------|
| New feature | `test-driven-development` |
| Bug fix | `systematic-debugging` |
| Code quality | `requesting-code-review` |
| Complex plan | `subagent-driven-development` |

## Cost Considerations

| Scenario | Estimated Cost |
|----------|----------------|
| 20-iteration simple task | $10-30 |
| 50-iteration large codebase | $50-100+ |
| Multi-day autonomous run | $200-500+ |

**Tips:**
- Start with low `--max-iterations` and increase if needed
- Monitor API usage during extended runs
- Use `--completion-promise` to exit early when done

## Installation

```bash
# Install Ralph Wiggum
/plugin install ralph-wiggum@claude-plugins-official

# Install Superpowers (if not already installed)
/plugin install superpowers@superpowers-marketplace
```

## References

- [Ralph Wiggum Plugin](https://github.com/anthropics/claude-code/blob/main/plugins/ralph-wiggum/README.md)
- [Ralph Wiggum Blog Post](https://paddo.dev/blog/ralph-wiggum-autonomous-loops/)
- [Original Ralph Technique](https://ghuntley.com/ralph/)
- [Superpowers Marketplace](https://github.com/anthropics/claude-plugins-official)
