package com.claudecode.native.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.claudecode.native.data.model.TodoItem

/**
 * Progress status data for the StatusLine component.
 * Tracks various metrics during Claude's processing.
 */
data class ProgressStatus(
    /** Current status text (e.g., "Thinking", "Running tests") */
    val statusText: String = "",
    /** Elapsed time in seconds since processing started */
    val elapsedSeconds: Int = 0,
    /** Token count (if available from streaming) */
    val tokenCount: Int? = null,
    /** Thinking/reasoning time in seconds (if applicable) */
    val thinkingSeconds: Int? = null,
    /** Whether processing is currently active */
    val isActive: Boolean = false,
    /** Current todo items from TodoWrite tool */
    val todos: List<TodoItem> = emptyList()
) {
    /** Count of completed todos */
    val completedCount: Int get() = todos.count { it.isCompleted }
    /** Total number of todos */
    val totalCount: Int get() = todos.size
    /** Whether there are any todos to show */
    val hasTodos: Boolean get() = todos.isNotEmpty()
}

/**
 * Claude Code style status line displayed during processing.
 * Shows spinner, status text, elapsed time, tokens, and thinking time.
 * Clicking expands/collapses the todo list when todos are available.
 *
 * Example output:
 * ✶ Identifying root cause... (esc to interrupt · 2m 11s · ↑ 7.3k tokens · thought for 18s) 📋 2/5
 *
 * Note: The "esc to stop" hint refers to keyboard ESC handling in ChatInputBar,
 * not a click action on this component.
 *
 * @param status Current progress status data
 * @param modifier Optional modifier
 */
@Composable
fun StatusLine(
    status: ProgressStatus,
    modifier: Modifier = Modifier
) {
    var isTodoExpanded by remember { mutableStateOf(false) }

    AnimatedVisibility(
        visible = status.isActive,
        enter = fadeIn() + slideInVertically { it },
        exit = fadeOut() + slideOutVertically { it },
        modifier = modifier
    ) {
        Column {
            StatusLineContent(
                status = status,
                isTodoExpanded = isTodoExpanded,
                onToggleTodos = { isTodoExpanded = !isTodoExpanded }
            )

            // Expandable todo section
            AnimatedVisibility(
                visible = isTodoExpanded && status.hasTodos,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                TodoSection(todos = status.todos)
            }
        }
    }
}

// Claude Code CLI style status words - randomly selected when statusText is empty
private val STATUS_WORDS = listOf(
    "Accomplishing", "Actualizing", "Analyzing", "Baking", "Brewing",
    "Calculating", "Cerebrating", "Channeling", "Churning", "Clauding",
    "Coalescing", "Cogitating", "Computing", "Concocting", "Considering",
    "Contemplating", "Cooking", "Crafting", "Creating", "Crunching",
    "Deciphering", "Deliberating", "Determining", "Effecting", "Elucidating",
    "Enchanting", "Engineering", "Envisioning", "Evaluating", "Examining",
    "Executing", "Figuring", "Forging", "Formulating", "Generating",
    "Illuminating", "Implementing", "Inferring", "Investigating", "Iterating",
    "Manifesting", "Meditating", "Musing", "Orchestrating", "Parsing",
    "Percolating", "Philosophizing", "Pondering", "Processing", "Producing",
    "Prognosticating", "Radiating", "Reasoning", "Reflecting", "Ruminating",
    "Shaping", "Simulating", "Speculating", "Strategizing", "Synthesizing",
    "Thinking", "Transmuting", "Unraveling", "Weaving", "Working", "Wrangling"
)

@Composable
private fun StatusLineContent(
    status: ProgressStatus,
    isTodoExpanded: Boolean = false,
    onToggleTodos: () -> Unit = {}
) {
    // Random status word - remembered once per composition to avoid flickering
    val randomStatusWord = remember { STATUS_WORDS.random() }

    // Claude Code CLI priority: statusText > in_progress todo's activeForm > random word
    val inProgressTodo = status.todos.find { it.isInProgress }
    val displayStatusText = status.statusText.ifEmpty {
        inProgressTodo?.activeForm ?: randomStatusWord
    }

    // Spinner animation - cycles through spinner characters
    val infiniteTransition = rememberInfiniteTransition(label = "statusline_spinner")
    val animationPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 4f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

    // Subtle pulse animation for the spinner
    val pulse by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    // Spinner characters matching Claude Code CLI style
    val spinners = listOf("✶", "✸", "✹", "✺")
    val currentSpinner = spinners[animationPhase.toInt() % spinners.size]

    // Format elapsed time (2m 11s format)
    val elapsedText = formatElapsedTime(status.elapsedSeconds)

    // Format token count (7.3k format)
    val tokenText = status.tokenCount?.let { formatTokenCount(it) }

    // Format thinking time
    val thinkingText = status.thinkingSeconds?.let { "thought for ${it}s" }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .then(
                if (status.hasTodos) {
                    Modifier.clickable(onClick = onToggleTodos)
                } else {
                    Modifier
                }
            ),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left side: spinner + status text + metadata
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                // Animated spinner
                Text(
                    text = currentSpinner,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.scale(pulse)
                )

                // Status text (main description)
                Text(
                    text = displayStatusText + "…",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )

                // Metadata in parentheses
                Text(
                    text = buildMetadataString(elapsedText, tokenText, thinkingText),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }

            // Right side: Todo counter (if has todos) + Stop hint
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Todo counter badge
                if (status.hasTodos) {
                    TodoCounterBadge(
                        completed = status.completedCount,
                        total = status.totalCount,
                        isExpanded = isTodoExpanded
                    )
                }

                // Stop hint (subtle)
                Text(
                    text = "esc to stop",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }
    }
}

/**
 * Todo counter badge showing completion progress (e.g., "📋 2/5")
 */
@Composable
private fun TodoCounterBadge(
    completed: Int,
    total: Int,
    isExpanded: Boolean
) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (isExpanded) "▼" else "▶",
                fontSize = 8.sp,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Text(
                text = "📋 $completed/$total",
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

/**
 * Expandable todo section showing all todo items
 */
@Composable
private fun TodoSection(
    todos: List<TodoItem>
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 4.dp),
        shape = RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 0.5.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            todos.forEach { todo ->
                TodoItemRow(todo = todo)
            }
        }
    }
}

/**
 * Single todo item row with status icon
 */
@Composable
private fun TodoItemRow(
    todo: TodoItem
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Status icon
        Text(
            text = when {
                todo.isCompleted -> "✅"
                todo.isInProgress -> "🔄"
                else -> "⬜"
            },
            fontSize = 12.sp
        )

        // Todo content
        Text(
            text = if (todo.isInProgress && todo.activeForm != null) {
                todo.activeForm
            } else {
                todo.content
            },
            fontSize = 12.sp,
            color = when {
                todo.isCompleted -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                todo.isInProgress -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurface
            },
            textDecoration = if (todo.isCompleted) TextDecoration.LineThrough else TextDecoration.None,
            fontWeight = if (todo.isInProgress) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Format elapsed time in human-readable format (e.g., "2m 11s", "45s")
 */
private fun formatElapsedTime(seconds: Int): String {
    return when {
        seconds < 60 -> "${seconds}s"
        seconds < 3600 -> {
            val minutes = seconds / 60
            val secs = seconds % 60
            if (secs > 0) "${minutes}m ${secs}s" else "${minutes}m"
        }
        else -> {
            val hours = seconds / 3600
            val minutes = (seconds % 3600) / 60
            if (minutes > 0) "${hours}h ${minutes}m" else "${hours}h"
        }
    }
}

/**
 * Format token count in compact format (e.g., "7.3k", "1.2M")
 */
private fun formatTokenCount(tokens: Int): String {
    return when {
        tokens < 1000 -> "$tokens"
        tokens < 1_000_000 -> {
            val k = tokens / 1000.0
            if (k >= 10) "${k.toInt()}k" else "${(k * 10).toInt() / 10.0}k"
        }
        else -> {
            val m = tokens / 1_000_000.0
            "${(m * 10).toInt() / 10.0}M"
        }
    }
}

/**
 * Build the metadata string with separators
 */
private fun buildMetadataString(
    elapsed: String,
    tokens: String?,
    thinking: String?
): String {
    val parts = mutableListOf<String>()
    parts.add(elapsed)
    tokens?.let { parts.add("↑ $it tokens") }
    thinking?.let { parts.add(it) }
    return "(${parts.joinToString(" · ")})"
}
