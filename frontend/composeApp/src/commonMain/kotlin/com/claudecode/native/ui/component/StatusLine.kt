package com.claudecode.native.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
    val isActive: Boolean = false
)

/**
 * Claude Code style status line displayed during processing.
 * Shows spinner, status text, elapsed time, tokens, and thinking time.
 *
 * Example output:
 * ✶ Identifying root cause... (esc to interrupt · 2m 11s · ↑ 7.3k tokens · thought for 18s)
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
    AnimatedVisibility(
        visible = status.isActive,
        enter = fadeIn() + slideInVertically { it },
        exit = fadeOut() + slideOutVertically { it },
        modifier = modifier
    ) {
        StatusLineContent(status = status)
    }
}

@Composable
private fun StatusLineContent(
    status: ProgressStatus
) {
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
            .padding(horizontal = 16.dp, vertical = 4.dp),
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
                    text = status.statusText.ifEmpty { "Processing" } + "…",
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

            // Right side: Stop hint (subtle)
            Text(
                text = "esc to stop",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.padding(start = 8.dp)
            )
        }
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
            if (k >= 10) "${k.toInt()}k" else "%.1fk".format(k)
        }
        else -> {
            val m = tokens / 1_000_000.0
            "%.1fM".format(m)
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
