package com.claudecode.native.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.BoxWithConstraints
import com.claudecode.native.data.model.MessageRole
import com.claudecode.native.ui.viewmodel.ChatMessage
import com.claudecode.native.ui.viewmodel.ContentBlock
import com.claudecode.native.ui.viewmodel.ToolUseInfo

/**
 * Displays a chat message bubble with appropriate styling based on the sender.
 *
 * User messages are right-aligned with primary color background (plain text).
 * Assistant messages are left-aligned with surface variant background (markdown rendered).
 * Content blocks (text and tools) are rendered in their original order for assistant messages.
 *
 * @param message The chat message to display
 * @param modifier Optional modifier for the component
 */
@Composable
fun MessageBubble(
    message: ChatMessage,
    modifier: Modifier = Modifier
) {
    // Skip rendering if no blocks
    if (message.blocks.isEmpty()) return

    val isUser = message.role == MessageRole.USER

    BoxWithConstraints(
        modifier = modifier.fillMaxWidth()
    ) {
        // Calculate max width based on available space
        // User messages: smaller (up to 70% or 500dp max)
        // Assistant messages: larger (up to 95% of available width for better code display)
        val userMaxWidth = minOf(maxWidth * 0.7f, 500.dp)
        val assistantMaxWidth = maxWidth * 0.95f

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (isUser) {
                // User messages: render all text blocks as a single bubble
                val textContent = message.blocks
                    .filterIsInstance<ContentBlock.Text>()
                    .joinToString("\n\n") { it.content }

                if (textContent.isNotBlank()) {
                    Column(
                        horizontalAlignment = Alignment.End
                    ) {
                        Box(
                            modifier = Modifier
                                .widthIn(max = userMaxWidth)
                                .clip(RoundedCornerShape(16.dp))
                                .background(
                                    if (message.isPending) {
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                                    } else {
                                        MaterialTheme.colorScheme.primary
                                    }
                                )
                                .padding(12.dp)
                        ) {
                            Text(
                                text = textContent,
                                color = MaterialTheme.colorScheme.onPrimary,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }

                        // Show pending indicator for user messages
                        if (message.isPending) {
                            Row(
                                modifier = Modifier.padding(top = 4.dp, end = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(10.dp),
                                    strokeWidth = 1.5.dp,
                                    color = MaterialTheme.colorScheme.outline
                                )
                                Text(
                                    text = "Sending...",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                        }
                    }
                }
            } else {
                // Assistant messages: render blocks in order (preserving interleaved structure)
                message.blocks.forEach { block ->
                    when (block) {
                        is ContentBlock.Text -> {
                            if (block.content.isNotBlank()) {
                                Box(
                                    modifier = Modifier
                                        .widthIn(max = assistantMaxWidth)
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                        .padding(12.dp)
                                ) {
                                    MarkdownText(
                                        text = block.content,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                        }
                        is ContentBlock.Tool -> {
                            Box(modifier = Modifier.widthIn(max = assistantMaxWidth)) {
                                ToolUseItem(tool = block.info)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Displays a collapsible tool usage item.
 * Shows tool name and summary by default, expands to show full result on click.
 *
 * @param tool The tool usage information to display
 * @param modifier Optional modifier for the component
 */
@Composable
fun ToolUseItem(
    tool: ToolUseInfo,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val hasResult = !tool.result.isNullOrBlank()
    val isSubagent = tool.name == "Task"
    val subagentColor = Color(0xFFBA68C8)  // Purple for subagents

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = when {
            tool.isError -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
            isSubagent -> subagentColor.copy(alpha = 0.1f)
            else -> MaterialTheme.colorScheme.surfaceContainerHigh
        },
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier
                .clickable(enabled = hasResult) { expanded = !expanded }
                .padding(10.dp)
        ) {
            // Header row: tool icon, name, summary, expand arrow
            // Colors for styling
            val pathColor = Color(0xFF64B5F6)  // Light blue for file paths
            val commandColor = Color(0xFFFFB74D)  // Orange for commands
            val toolColor = when {
                tool.isError -> MaterialTheme.colorScheme.error
                isSubagent -> subagentColor
                else -> MaterialTheme.colorScheme.primary
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Tool icon - Person for subagents, Build for others
                Icon(
                    imageVector = if (isSubagent) Icons.Default.Person else Icons.Default.Build,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = toolColor
                )

                // Tool name (bold)
                Text(
                    text = tool.name,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.Bold
                    ),
                    color = toolColor
                )

                // Summary with colored paths/commands (takes remaining space)
                val summaryText = buildAnnotatedString {
                    val summary = tool.summary
                    // Check if summary looks like a file path
                    val isPath = summary.startsWith("/") ||
                            summary.contains("/") ||
                            summary.endsWith(".kt") ||
                            summary.endsWith(".go") ||
                            summary.endsWith(".ts") ||
                            summary.endsWith(".js") ||
                            summary.endsWith(".py")
                    // Check if summary looks like a command
                    val isCommand = tool.name == "Bash" || tool.name == "Grep" || tool.name == "Glob"

                    when {
                        isPath -> withStyle(SpanStyle(color = pathColor, fontFamily = FontFamily.Monospace)) {
                            append(summary)
                        }
                        isCommand -> withStyle(SpanStyle(color = commandColor, fontFamily = FontFamily.Monospace)) {
                            append(summary)
                        }
                        else -> withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) {
                            append(summary)
                        }
                    }
                }

                Text(
                    text = summaryText,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                // Expand/collapse arrow (only if has result)
                if (hasResult) {
                    Icon(
                        imageVector = if (expanded) {
                            Icons.Default.KeyboardArrowUp
                        } else {
                            Icons.Default.KeyboardArrowDown
                        },
                        contentDescription = if (expanded) "Collapse" else "Expand",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Expandable result content
            AnimatedVisibility(
                visible = expanded && hasResult,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Box(
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                        .horizontalScroll(rememberScrollState())
                        .padding(8.dp)
                ) {
                    if (tool.isError) {
                        Text(
                            text = tool.result ?: "",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace
                            ),
                            color = MaterialTheme.colorScheme.error
                        )
                    } else {
                        DiffHighlightedText(
                            text = tool.result ?: "",
                            toolName = tool.name
                        )
                    }
                }
            }
        }
    }
}

/**
 * Displays text with diff-style syntax highlighting.
 * Highlights additions (green), deletions (red), and hunk headers (cyan).
 */
@Composable
fun DiffHighlightedText(
    text: String,
    toolName: String,
    modifier: Modifier = Modifier
) {
    val additionColor = Color(0xFF4CAF50)  // Green for additions
    val deletionColor = Color(0xFFE57373)  // Red for deletions
    val hunkHeaderColor = Color(0xFF64B5F6) // Blue for @@ headers
    val lineNumberColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    val defaultColor = MaterialTheme.colorScheme.onSurface

    // Check if this looks like diff content (for Edit tool or content with diff markers)
    val isDiffContent = toolName == "Edit" ||
            text.contains("\n+") ||
            text.contains("\n-") ||
            text.lines().any { it.startsWith("@@") }

    if (!isDiffContent) {
        // Regular text display
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace
            ),
            color = defaultColor,
            modifier = modifier
        )
        return
    }

    // Parse and highlight diff content
    Column(modifier = modifier) {
        text.lines().forEach { line ->
            val (color, displayLine) = when {
                line.startsWith("@@") -> hunkHeaderColor to line
                line.startsWith("+") && !line.startsWith("+++") -> additionColor to line
                line.startsWith("-") && !line.startsWith("---") -> deletionColor to line
                line.startsWith(">>>") || line.startsWith("<<<") -> hunkHeaderColor to line
                line.matches(Regex("^\\s*\\d+[→|].*")) -> {
                    // Line number format like "  123→content" or "  123|content"
                    lineNumberColor to line
                }
                else -> defaultColor to line
            }

            Row {
                // Highlight background for additions/deletions
                val backgroundColor = when {
                    line.startsWith("+") && !line.startsWith("+++") ->
                        additionColor.copy(alpha = 0.15f)
                    line.startsWith("-") && !line.startsWith("---") ->
                        deletionColor.copy(alpha = 0.15f)
                    else -> Color.Transparent
                }

                Text(
                    text = displayLine,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace
                    ),
                    color = color,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(backgroundColor)
                )
            }
        }
    }
}

/**
 * Displays a streaming message bubble for assistant responses in progress.
 *
 * Shows the current streaming content with markdown rendering or a loading indicator if empty.
 * Also displays streaming tools with progress indicators for in-progress operations.
 * When blocks are provided, renders them in their original interleaved order (text and tools mixed).
 * Always left-aligned like regular assistant messages.
 *
 * @param content Current streaming content (may be empty during initial load)
 * @param tools List of tools being used during streaming
 * @param blocks Ordered list of content blocks (text and tools interleaved). If provided, renders in order.
 * @param modifier Optional modifier for the component
 */
@Composable
fun StreamingBubble(
    content: String,
    tools: List<ToolUseInfo> = emptyList(),
    blocks: List<ContentBlock> = emptyList(),
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // If ordered blocks are provided, render them in order
        if (blocks.isNotEmpty()) {
            blocks.forEach { block ->
                when (block) {
                    is ContentBlock.Text -> {
                        if (block.content.isNotBlank()) {
                            Box(
                                modifier = Modifier
                                    .widthIn(max = 600.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .padding(12.dp)
                            ) {
                                MarkdownText(
                                    text = block.content,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                    is ContentBlock.Tool -> {
                        Box(modifier = Modifier.widthIn(max = 600.dp)) {
                            StreamingToolItem(tool = block.info)
                        }
                    }
                }
            }
        } else {
            // Fallback: Legacy behavior - content first, then all tools
            // Main content bubble
            if (content.isNotEmpty() || tools.isEmpty()) {
                Box(
                    modifier = Modifier
                        .widthIn(max = 600.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(12.dp)
                ) {
                    if (content.isEmpty()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "...",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    } else {
                        // Render streaming content with markdown
                        MarkdownText(
                            text = content,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            // Streaming tools
            if (tools.isNotEmpty()) {
                Column(
                    modifier = Modifier.widthIn(max = 600.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    tools.forEach { tool ->
                        StreamingToolItem(tool = tool)
                    }
                }
            }
        }
    }
}

/**
 * Displays a tool item during streaming with a progress indicator if no result yet.
 */
@Composable
private fun StreamingToolItem(
    tool: ToolUseInfo,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val hasResult = !tool.result.isNullOrBlank()
    val isInProgress = !hasResult

    val isSubagent = tool.name == "Task"
    val subagentColor = Color(0xFFBA68C8)  // Purple for subagents

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = when {
            tool.isError -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
            isSubagent && isInProgress -> subagentColor.copy(alpha = 0.15f)
            isSubagent -> subagentColor.copy(alpha = 0.1f)
            isInProgress -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            else -> MaterialTheme.colorScheme.surfaceContainerHigh
        },
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier
                .clickable(enabled = hasResult) { expanded = !expanded }
                .padding(10.dp)
        ) {
            // Header row: spinner/icon, tool name, summary, expand arrow
            val pathColor = Color(0xFF64B5F6)  // Light blue for file paths
            val commandColor = Color(0xFFFFB74D)  // Orange for commands
            val toolColor = when {
                tool.isError -> MaterialTheme.colorScheme.error
                isSubagent -> subagentColor
                else -> MaterialTheme.colorScheme.primary
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Progress indicator or tool icon (Person for subagents)
                if (isInProgress) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = toolColor
                    )
                } else {
                    Icon(
                        imageVector = if (isSubagent) Icons.Default.Person else Icons.Default.Build,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = toolColor
                    )
                }

                // Tool name (bold)
                Text(
                    text = tool.name,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.Bold
                    ),
                    color = toolColor
                )

                // Summary with colored paths/commands
                val summaryText = buildAnnotatedString {
                    val summary = tool.summary
                    val isPath = summary.startsWith("/") ||
                            summary.contains("/") ||
                            summary.endsWith(".kt") ||
                            summary.endsWith(".go") ||
                            summary.endsWith(".ts") ||
                            summary.endsWith(".js") ||
                            summary.endsWith(".py")
                    val isCommand = tool.name == "Bash" || tool.name == "Grep" || tool.name == "Glob"

                    when {
                        isPath -> withStyle(SpanStyle(color = pathColor, fontFamily = FontFamily.Monospace)) {
                            append(summary)
                        }
                        isCommand -> withStyle(SpanStyle(color = commandColor, fontFamily = FontFamily.Monospace)) {
                            append(summary)
                        }
                        else -> withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) {
                            append(summary)
                        }
                    }
                }

                Text(
                    text = summaryText,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                // Expand/collapse arrow (only if has result)
                if (hasResult) {
                    Icon(
                        imageVector = if (expanded) {
                            Icons.Default.KeyboardArrowUp
                        } else {
                            Icons.Default.KeyboardArrowDown
                        },
                        contentDescription = if (expanded) "Collapse" else "Expand",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Expandable result content
            AnimatedVisibility(
                visible = expanded && hasResult,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Box(
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                        .horizontalScroll(rememberScrollState())
                        .padding(8.dp)
                ) {
                    if (tool.isError) {
                        Text(
                            text = tool.result ?: "",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace
                            ),
                            color = MaterialTheme.colorScheme.error
                        )
                    } else {
                        DiffHighlightedText(
                            text = tool.result ?: "",
                            toolName = tool.name
                        )
                    }
                }
            }
        }
    }
}

/**
 * Displays a queued message bubble for messages waiting to be processed.
 * Shows the message content with a "Queued" indicator.
 * Right-aligned like user messages but with a different visual style.
 *
 * @param content The queued message content
 * @param modifier Optional modifier for the component
 */
@Composable
fun QueuedMessageBubble(
    content: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.End
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 500.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
                .padding(12.dp)
        ) {
            Text(
                text = content,
                color = MaterialTheme.colorScheme.onPrimary,
                style = MaterialTheme.typography.bodyMedium
            )
        }

        // Queued indicator
        Row(
            modifier = Modifier.padding(top = 4.dp, end = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(10.dp),
                strokeWidth = 1.5.dp,
                color = MaterialTheme.colorScheme.outline
            )
            Text(
                text = "Queued",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}
