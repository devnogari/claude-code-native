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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material3.IconButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import com.claudecode.native.data.model.MessageRole
import com.claudecode.native.ui.viewmodel.ChatMessage
import com.claudecode.native.ui.viewmodel.ContentBlock
import com.claudecode.native.ui.viewmodel.ImageSource
import com.claudecode.native.ui.viewmodel.ToolUseInfo
import com.claudecode.native.util.ClipboardManager
import com.claudecode.native.util.Platform

/**
 * Displays a chat message bubble with appropriate styling based on the sender.
 *
 * User messages are right-aligned with primary color background (plain text).
 * Assistant messages are left-aligned with surface variant background (markdown rendered).
 * Content blocks (text and tools) are rendered in their original order for assistant messages.
 * Click the info icon to inspect message payload.
 *
 * @param message The chat message to display
 * @param modifier Optional modifier for the component
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun MessageBubble(
    message: ChatMessage,
    modifier: Modifier = Modifier
) {
    // Skip rendering if no blocks
    if (message.blocks.isEmpty()) return

    val isUser = message.role == MessageRole.USER
    var showInspectDialog by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var isHovered by remember { mutableStateOf(false) }
    val isDesktop = Platform.isDesktop

    // Inspect Payload Dialog
    if (showInspectDialog) {
        MessageInspectDialog(
            message = message,
            onDismiss = { showInspectDialog = false }
        )
    }

    // Use fixed widths to avoid BoxWithConstraints overhead
    // User messages: max 500dp, Assistant messages: fill available width
    val userMaxWidth = 500.dp

    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (isDesktop) {
                    Modifier.onPointerEvent(PointerEventType.Enter) { isHovered = true }
                        .onPointerEvent(PointerEventType.Exit) { isHovered = false }
                } else {
                    Modifier
                }
            ),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top
    ) {
        // Menu button on the left for assistant messages
        if (!isUser) {
            MessageMenuButton(
                showMenu = showMenu,
                onShowMenuChange = { showMenu = it },
                onInspectClick = { showInspectDialog = true },
                onCopyClick = if (isDesktop) {
                    { copyMessageContent(message) }
                } else null,
                showCopyButton = isDesktop && isHovered
            )
        }

        Column(
            modifier = Modifier.weight(1f, fill = false),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (isUser) {
                // User messages: render text blocks and image blocks
                // Cache text content to avoid repeated filtering
                val textContent = remember(message.blocks) {
                    message.blocks
                        .filterIsInstance<ContentBlock.Text>()
                        .joinToString("\n\n") { it.content }
                }
                val imageBlocks = remember(message.blocks) {
                    message.blocks.filterIsInstance<ContentBlock.Image>()
                }

                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Render text bubble if there's text content
                    if (textContent.isNotBlank()) {
                        val backgroundColor = if (message.isPending) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                        } else {
                            MaterialTheme.colorScheme.primary
                        }
                        Box(
                            modifier = Modifier
                                .widthIn(max = userMaxWidth)
                                .clip(RoundedCornerShape(16.dp))
                                .background(backgroundColor)
                                .padding(12.dp)
                        ) {
                            Text(
                                text = textContent,
                                color = MaterialTheme.colorScheme.onPrimary,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }

                    // Render attached images with stable keys
                    imageBlocks.forEachIndexed { index, imageBlock ->
                        androidx.compose.runtime.key(index) {
                            ImageBlock(
                                source = imageBlock.source,
                                modifier = Modifier.widthIn(max = userMaxWidth)
                            )
                        }
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
            } else {
                // Show metadata header for assistant messages (agentId, gitBranch, sidechain)
                val hasMetadata = message.agentId != null || message.gitBranch != null || message.isSidechain
                if (hasMetadata) {
                    MessageMetadataHeader(
                        agentId = message.agentId,
                        gitBranch = message.gitBranch,
                        isSidechain = message.isSidechain,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Assistant messages: render blocks in order (preserving interleaved structure)
                // Use key for stable recomposition
                message.blocks.forEachIndexed { index, block ->
                    androidx.compose.runtime.key(index) {
                        when (block) {
                            is ContentBlock.Text -> {
                                if (block.content.isNotBlank()) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
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
                                ToolUseItem(
                                    tool = block.info,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            is ContentBlock.Image -> {
                                ImageBlock(
                                    source = block.source,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }
            }
        }

        // Menu button on the right for user messages
        if (isUser) {
            MessageMenuButton(
                showMenu = showMenu,
                onShowMenuChange = { showMenu = it },
                onInspectClick = { showInspectDialog = true },
                onCopyClick = if (isDesktop) {
                    { copyMessageContent(message) }
                } else null,
                showCopyButton = isDesktop && isHovered
            )
        }
    }
}

/**
 * Helper function to extract text content from a message and copy to clipboard.
 */
private fun copyMessageContent(message: ChatMessage) {
    val textContent = message.blocks
        .filterIsInstance<ContentBlock.Text>()
        .joinToString("\n\n") { it.content }

    if (textContent.isNotBlank()) {
        ClipboardManager.copyToClipboard(textContent)
    }
}

/**
 * Menu button with dropdown for message actions.
 */
/**
 * Reusable inspect menu button component.
 *
 * @param showMenu Whether the dropdown menu is currently shown
 * @param onShowMenuChange Callback when menu visibility changes
 * @param onInspectClick Callback when "Inspect Payload" is clicked
 * @param onCopyClick Optional callback when "Copy" is clicked
 * @param showCopyButton Whether to show the copy button (for desktop hover)
 * @param compact If true, uses smaller sizing suitable for tool items
 */
@Composable
private fun InspectMenuButton(
    showMenu: Boolean,
    onShowMenuChange: (Boolean) -> Unit,
    onInspectClick: () -> Unit,
    onCopyClick: (() -> Unit)? = null,
    showCopyButton: Boolean = false,
    compact: Boolean = false
) {
    val iconSize = if (compact) 18.dp else 20.dp
    val buttonSize = if (compact) 28.dp else 40.dp

    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Copy button (visible on hover for desktop, with fade animation)
        if (onCopyClick != null) {
            AnimatedVisibility(visible = showCopyButton) {
                IconButton(
                    onClick = onCopyClick,
                    modifier = Modifier.size(buttonSize)
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "Copy message",
                        modifier = Modifier.size(iconSize),
                        tint = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }

        // Menu button
        Box {
            IconButton(
                onClick = { onShowMenuChange(true) },
                modifier = Modifier.size(buttonSize)
            ) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "More options",
                    modifier = Modifier.size(iconSize),
                    tint = MaterialTheme.colorScheme.outline
                )
            }

            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { onShowMenuChange(false) }
            ) {
                // Copy option in dropdown (always available)
                if (onCopyClick != null) {
                    DropdownMenuItem(
                        text = { Text("Copy") },
                        onClick = {
                            onShowMenuChange(false)
                            onCopyClick()
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    )
                }
                DropdownMenuItem(
                    text = { Text("Inspect Payload") },
                    onClick = {
                        onShowMenuChange(false)
                        onInspectClick()
                    }
                )
            }
        }
    }
}

// Backwards compatibility alias
@Composable
private fun MessageMenuButton(
    showMenu: Boolean,
    onShowMenuChange: (Boolean) -> Unit,
    onInspectClick: () -> Unit,
    onCopyClick: (() -> Unit)? = null,
    showCopyButton: Boolean = false
) = InspectMenuButton(showMenu, onShowMenuChange, onInspectClick, onCopyClick, showCopyButton, compact = false)

/**
 * Dialog to inspect message payload details.
 */
@Composable
private fun MessageInspectDialog(
    message: ChatMessage,
    onDismiss: () -> Unit
) {
    val scrollState = rememberScrollState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Message Payload",
                style = MaterialTheme.typography.titleMedium
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Message ID
                PayloadField("ID", message.id)

                // Role
                PayloadField("Role", message.role.name)

                // Agent ID
                message.agentId?.let { PayloadField("Agent ID", it) }

                // Git Branch
                message.gitBranch?.let { PayloadField("Git Branch", it) }

                // Sidechain
                PayloadField("Sidechain", message.isSidechain.toString())

                // Is Pending
                PayloadField("Pending", message.isPending.toString())

                // Blocks count
                PayloadField("Blocks Count", message.blocks.size.toString())

                // Blocks details
                if (message.blocks.isNotEmpty()) {
                    Text(
                        text = "Blocks:",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    message.blocks.forEachIndexed { index, block ->
                        when (block) {
                            is ContentBlock.Text -> {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                        .padding(8.dp)
                                ) {
                                    Column {
                                        Text(
                                            text = "[$index] Text Block",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Text(
                                            text = block.content.take(500) + if (block.content.length > 500) "..." else "",
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                fontFamily = FontFamily.Monospace
                                            ),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                            is ContentBlock.Tool -> {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                        .padding(8.dp)
                                ) {
                                    Column {
                                        Text(
                                            text = "[$index] Tool: ${block.info.name}",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Text(
                                            text = "ID: ${block.info.id}",
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                fontFamily = FontFamily.Monospace
                                            ),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = "Summary: ${block.info.summary}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        if (block.info.isError) {
                                            Text(
                                                text = "Error: true",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.error
                                            )
                                        }
                                    }
                                }
                            }
                            is ContentBlock.Image -> {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                        .padding(8.dp)
                                ) {
                                    Column {
                                        Text(
                                            text = "[$index] Image Block",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        when (val source = block.source) {
                                            is ImageSource.Base64 -> {
                                                Text(
                                                    text = "Type: Base64",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                                Text(
                                                    text = "Media Type: ${source.mediaType}",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                                Text(
                                                    text = "Data Size: ${source.data.length} chars",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

/**
 * Helper composable for displaying a labeled field in the inspect dialog.
 */
@Composable
private fun PayloadField(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "$label:",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.widthIn(min = 80.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace
            ),
            color = MaterialTheme.colorScheme.onSurface
        )
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
    var showInspectDialog by remember { mutableStateOf(false) }
    val hasResult = !tool.result.isNullOrBlank()
    val isSubagent = tool.name == "Task"
    val subagentColor = Color(0xFFBA68C8)  // Purple for subagents

    // Inspect Dialog for tool
    if (showInspectDialog) {
        ToolInspectDialog(
            tool = tool,
            onDismiss = { showInspectDialog = false }
        )
    }

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
                // Cache the annotated string to avoid rebuilding on each recomposition
                val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
                val summaryText = remember(tool.summary, tool.name, pathColor, commandColor, onSurfaceVariant) {
                    buildAnnotatedString {
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
                            else -> withStyle(SpanStyle(color = onSurfaceVariant)) {
                                append(summary)
                            }
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

                // More button for inspect (always at the end for consistent positioning)
                var showMenu by remember { mutableStateOf(false) }
                InspectMenuButton(
                    showMenu = showMenu,
                    onShowMenuChange = { showMenu = it },
                    onInspectClick = { showInspectDialog = true },
                    compact = true
                )
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
/**
 * Data class for cached diff line info
 */
private data class DiffLineInfo(
    val line: String,
    val colorType: DiffColorType,
    val hasBackground: Boolean
)

private enum class DiffColorType {
    ADDITION, DELETION, HUNK_HEADER, LINE_NUMBER, DEFAULT
}

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

    // Cache the diff content check and parsed lines
    val parsedLines = remember(text, toolName) {
        // Check if this looks like diff content
        val isDiffContent = toolName == "Edit" ||
                text.contains("\n+") ||
                text.contains("\n-") ||
                text.lines().any { it.startsWith("@@") }

        if (!isDiffContent) {
            null // Return null to indicate regular text
        } else {
            // Parse and cache line info
            text.lines().map { line ->
                val (colorType, hasBackground) = when {
                    line.startsWith("@@") -> DiffColorType.HUNK_HEADER to false
                    line.startsWith("+") && !line.startsWith("+++") -> DiffColorType.ADDITION to true
                    line.startsWith("-") && !line.startsWith("---") -> DiffColorType.DELETION to true
                    line.startsWith(">>>") || line.startsWith("<<<") -> DiffColorType.HUNK_HEADER to false
                    line.matches(Regex("^\\s*\\d+[→|].*")) -> DiffColorType.LINE_NUMBER to false
                    else -> DiffColorType.DEFAULT to false
                }
                DiffLineInfo(line, colorType, hasBackground)
            }
        }
    }

    if (parsedLines == null) {
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

    // Render diff content with cached line info
    Column(modifier = modifier) {
        parsedLines.forEachIndexed { index, lineInfo ->
            androidx.compose.runtime.key(index) {
                val color = when (lineInfo.colorType) {
                    DiffColorType.ADDITION -> additionColor
                    DiffColorType.DELETION -> deletionColor
                    DiffColorType.HUNK_HEADER -> hunkHeaderColor
                    DiffColorType.LINE_NUMBER -> lineNumberColor
                    DiffColorType.DEFAULT -> defaultColor
                }

                val backgroundColor = when {
                    lineInfo.colorType == DiffColorType.ADDITION -> additionColor.copy(alpha = 0.15f)
                    lineInfo.colorType == DiffColorType.DELETION -> deletionColor.copy(alpha = 0.15f)
                    else -> Color.Transparent
                }

                Text(
                    text = lineInfo.line,
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
 * Dialog to inspect tool payload details.
 */
@Composable
private fun ToolInspectDialog(
    tool: ToolUseInfo,
    onDismiss: () -> Unit
) {
    val scrollState = rememberScrollState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Tool Payload",
                style = MaterialTheme.typography.titleMedium
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PayloadField("Tool ID", tool.id)
                PayloadField("Name", tool.name)
                PayloadField("Is Error", tool.isError.toString())
                PayloadField("Has Result", (tool.result != null).toString())

                // Summary (tool input summary)
                if (tool.summary.isNotEmpty()) {
                    Text(
                        text = "Summary:",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(8.dp)
                    ) {
                        Text(
                            text = tool.summary.take(2000) + if (tool.summary.length > 2000) "..." else "",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Result
                tool.result?.let { result ->
                    Text(
                        text = "Result:",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(8.dp)
                    ) {
                        Text(
                            text = result.take(1000) + if (result.length > 1000) "..." else "",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace
                            ),
                            color = if (tool.isError) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}


/**
 * Displays a queued message bubble for messages waiting to be processed.
 * Shows the message content with a "Queued" indicator and cancel button.
 * Right-aligned like user messages but with a different visual style.
 *
 * @param content The queued message content
 * @param position Position in the queue (1-based, null if not applicable)
 * @param isFromCli Whether this message was queued from Claude CLI (terminal)
 * @param onCancel Callback when user cancels this queued message (null if not cancellable)
 * @param modifier Optional modifier for the component
 */
@Composable
fun QueuedMessageBubble(
    content: String,
    images: List<ImageSource> = emptyList(),
    position: Int? = null,
    isFromCli: Boolean = false,
    onCancel: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var showMenu by remember { mutableStateOf(false) }
    var showInspectDialog by remember { mutableStateOf(false) }

    // Inspect Dialog
    if (showInspectDialog) {
        QueuedInspectDialog(
            content = content,
            position = position,
            isFromCli = isFromCli,
            onDismiss = { showInspectDialog = false }
        )
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.End
        ) {
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .widthIn(max = 500.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Text content bubble
                if (content.isNotBlank()) {
                    Box(
                        modifier = Modifier
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
                }

                // Image thumbnails
                images.forEach { imageSource ->
                    ImageBlock(
                        source = imageSource,
                        modifier = Modifier
                            .widthIn(max = 300.dp)
                            .alpha(0.7f)  // Slightly dimmed to indicate pending state
                    )
                }
            }

            // Menu button with cancel option
            QueuedMessageMenuButton(
                showMenu = showMenu,
                onShowMenuChange = { showMenu = it },
                onInspectClick = { showInspectDialog = true },
                onCancelClick = onCancel,
                canCancel = onCancel != null && !isFromCli
            )
        }

        // Queued indicator with position
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
            val statusText = buildString {
                append("Queued")
                if (position != null) {
                    append(" #$position")
                }
                if (isFromCli) {
                    append(" (CLI)")
                }
            }
            Text(
                text = statusText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

/**
 * Menu button for queued messages with inspect and cancel options.
 */
@Composable
private fun QueuedMessageMenuButton(
    showMenu: Boolean,
    onShowMenuChange: (Boolean) -> Unit,
    onInspectClick: () -> Unit,
    onCancelClick: (() -> Unit)?,
    canCancel: Boolean,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier) {
        IconButton(
            onClick = { onShowMenuChange(true) },
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = "Menu",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.outline
            )
        }
        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { onShowMenuChange(false) }
        ) {
            DropdownMenuItem(
                text = { Text("Inspect") },
                onClick = {
                    onShowMenuChange(false)
                    onInspectClick()
                }
            )
            if (canCancel && onCancelClick != null) {
                DropdownMenuItem(
                    text = {
                        Text(
                            "Cancel",
                            color = MaterialTheme.colorScheme.error
                        )
                    },
                    onClick = {
                        onShowMenuChange(false)
                        onCancelClick()
                    }
                )
            }
        }
    }
}

/**
 * Inspect dialog for queued messages.
 */
@Composable
private fun QueuedInspectDialog(
    content: String,
    position: Int?,
    isFromCli: Boolean,
    onDismiss: () -> Unit
) {
    val scrollState = rememberScrollState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Queued Message Payload") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PayloadField("Status", "Queued" + if (position != null) " #$position" else "")
                PayloadField("Source", if (isFromCli) "Claude CLI (Terminal)" else "This App")
                PayloadField("Content Length", "${content.length} chars")

                // Content
                Text(
                    text = "Content:",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(8.dp)
                ) {
                    Text(
                        text = content.take(2000) + if (content.length > 2000) "..." else "",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

/**
 * Displays metadata header for messages (subagent, git branch, sidechain indicators).
 * Shows a compact row with relevant indicators before the message content.
 *
 * @param agentId Subagent ID if message is from a spawned agent
 * @param gitBranch Git branch where the message was sent
 * @param isSidechain True if message is part of a sidechain conversation
 * @param modifier Optional modifier for the component
 */
@Composable
fun MessageMetadataHeader(
    agentId: String?,
    gitBranch: String?,
    isSidechain: Boolean,
    modifier: Modifier = Modifier
) {
    val subagentColor = Color(0xFFBA68C8)  // Purple for subagents
    val branchColor = Color(0xFF64B5F6)    // Light blue for git branch
    val sidechainColor = Color(0xFFFFB74D) // Orange for sidechain

    Row(
        modifier = modifier
            .padding(bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Subagent indicator
        if (agentId != null) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = "Subagent",
                    modifier = Modifier.size(12.dp),
                    tint = subagentColor
                )
                Text(
                    text = "agent-${agentId.take(7)}",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace
                    ),
                    color = subagentColor
                )
            }
        }

        // Git branch indicator
        if (gitBranch != null) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.AccountTree,
                    contentDescription = "Git branch",
                    modifier = Modifier.size(12.dp),
                    tint = branchColor
                )
                Text(
                    text = gitBranch,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace
                    ),
                    color = branchColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // Sidechain indicator
        if (isSidechain) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.CallSplit,
                    contentDescription = "Sidechain",
                    modifier = Modifier.size(12.dp),
                    tint = sidechainColor
                )
                Text(
                    text = "sidechain",
                    style = MaterialTheme.typography.labelSmall,
                    color = sidechainColor
                )
            }
        }
    }
}
