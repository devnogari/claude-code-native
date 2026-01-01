package com.claudecode.native.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Data class representing a slash command.
 *
 * @param name The command name (e.g., "clear", "help")
 * @param description Brief description of what the command does
 * @param icon Optional icon for the command
 * @param category Category for grouping commands
 */
data class SlashCommand(
    val name: String,
    val description: String,
    val icon: ImageVector? = null,
    val category: String = "General"
)

/**
 * Default slash commands available in the chat.
 */
val defaultSlashCommands = listOf(
    SlashCommand(
        name = "clear",
        description = "Clear the chat history display",
        icon = Icons.Default.Clear,
        category = "Chat"
    ),
    SlashCommand(
        name = "reset",
        description = "Delete session and start fresh",
        icon = Icons.Default.Refresh,
        category = "Session"
    ),
    SlashCommand(
        name = "help",
        description = "Show available commands",
        icon = Icons.Default.Settings,
        category = "General"
    ),
    SlashCommand(
        name = "compact",
        description = "Request Claude to summarize context",
        icon = Icons.Default.Build,
        category = "Claude"
    ),
    SlashCommand(
        name = "init",
        description = "Initialize Claude in project",
        icon = Icons.Default.PlayArrow,
        category = "Claude"
    )
)

/**
 * Displays a menu of available slash commands.
 * Shows when the user types "/" in the input field.
 *
 * @param visible Whether the menu should be visible
 * @param filter Filter text after the "/" (e.g., if user types "/cl", filter = "cl")
 * @param commands List of available commands
 * @param onCommandSelected Callback when a command is selected
 * @param modifier Optional modifier
 */
@Composable
fun SlashCommandMenu(
    visible: Boolean,
    filter: String = "",
    commands: List<SlashCommand> = defaultSlashCommands,
    onCommandSelected: (SlashCommand) -> Unit,
    modifier: Modifier = Modifier
) {
    // Filter commands based on input
    val filteredCommands = if (filter.isEmpty()) {
        commands
    } else {
        commands.filter {
            it.name.startsWith(filter, ignoreCase = true) ||
            it.description.contains(filter, ignoreCase = true)
        }
    }

    AnimatedVisibility(
        visible = visible && filteredCommands.isNotEmpty(),
        enter = expandVertically(),
        exit = shrinkVertically(),
        modifier = modifier
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            shape = RoundedCornerShape(12.dp),
            tonalElevation = 8.dp,
            shadowElevation = 4.dp
        ) {
            Column(
                modifier = Modifier.padding(8.dp)
            ) {
                // Header
                Text(
                    text = "Commands",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )

                // Command list
                LazyColumn(
                    modifier = Modifier.heightIn(max = 200.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(filteredCommands) { command ->
                        SlashCommandItem(
                            command = command,
                            onClick = { onCommandSelected(command) }
                        )
                    }
                }

                // Hint text
                if (filteredCommands.isEmpty()) {
                    Text(
                        text = "No matching commands",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }
        }
    }
}

/**
 * Individual slash command item in the menu.
 */
@Composable
private fun SlashCommandItem(
    command: SlashCommand,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = Color.Transparent
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Icon
            command.icon?.let { icon ->
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }

            // Command name and description
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "/${command.name}",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.Medium
                        ),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = command.category,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(4.dp)
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                Text(
                    text = command.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
    }
}
