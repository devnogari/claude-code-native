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
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MonetizationOn
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay
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
import com.claudecode.native.data.model.Command

/**
 * Data class representing a slash command.
 *
 * @param name The command name (e.g., "clear", "help")
 * @param description Brief description of what the command does
 * @param icon Optional icon for the command
 * @param category Category for grouping commands (builtin, project, user)
 * @param path Optional path for custom commands
 */
data class SlashCommand(
    val name: String,
    val description: String,
    val icon: ImageVector? = null,
    val category: String = "builtin",
    val path: String? = null
)

/**
 * Converts a Command from the API to a SlashCommand for UI display.
 */
fun Command.toSlashCommand(): SlashCommand {
    val icon = when {
        namespace == "builtin" -> getBuiltinIcon(name)
        namespace == "project" -> Icons.Default.Folder
        namespace == "user" -> Icons.Default.Person
        else -> Icons.Default.Code
    }
    return SlashCommand(
        name = name.removePrefix("/"),
        description = description,
        icon = icon,
        category = namespace,
        path = path
    )
}

/**
 * Gets the appropriate icon for builtin commands.
 */
private fun getBuiltinIcon(name: String): ImageVector {
    return when (name.removePrefix("/").lowercase()) {
        "help" -> Icons.AutoMirrored.Filled.Help
        "clear" -> Icons.Default.Clear
        "model" -> Icons.Default.Build
        "cost" -> Icons.Default.MonetizationOn
        "memory" -> Icons.Default.Memory
        "config" -> Icons.Default.Settings
        "status" -> Icons.Default.Info
        "rewind" -> Icons.Default.Replay
        "reset" -> Icons.Default.Refresh
        "compact" -> Icons.Default.Description
        "init" -> Icons.Default.PlayArrow
        else -> Icons.Default.Code
    }
}

/**
 * Default slash commands available in the chat (fallback when API is unavailable).
 */
val defaultSlashCommands = listOf(
    SlashCommand(
        name = "clear",
        description = "Clear the chat history display",
        icon = Icons.Default.Clear,
        category = "builtin"
    ),
    SlashCommand(
        name = "help",
        description = "Show available commands",
        icon = Icons.AutoMirrored.Filled.Help,
        category = "builtin"
    )
)

/**
 * Displays a menu of available slash commands.
 * Shows when the user types "/" in the input field.
 *
 * @param visible Whether the menu should be visible
 * @param filter Filter text after the "/" (e.g., if user types "/cl", filter = "cl")
 * @param commands List of available commands
 * @param isLoading Whether commands are still loading
 * @param onCommandSelected Callback when a command is selected
 * @param modifier Optional modifier
 */
@Composable
fun SlashCommandMenu(
    visible: Boolean,
    filter: String = "",
    commands: List<SlashCommand> = defaultSlashCommands,
    isLoading: Boolean = false,
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

    // Group commands by category (namespace)
    val groupedCommands = filteredCommands.groupBy { it.category }
    val categoryOrder = listOf("builtin", "project", "user")
    val sortedCategories = groupedCommands.keys.sortedBy { categoryOrder.indexOf(it).takeIf { i -> i >= 0 } ?: 999 }

    AnimatedVisibility(
        visible = visible && (filteredCommands.isNotEmpty() || isLoading),
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
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Commands",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 1.5.dp
                        )
                    }
                }

                // Command list grouped by category
                LazyColumn(
                    modifier = Modifier.heightIn(max = 300.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    sortedCategories.forEach { category ->
                        val categoryCommands = groupedCommands[category] ?: emptyList()
                        if (categoryCommands.isNotEmpty()) {
                            // Category header
                            item(key = "header_$category") {
                                Text(
                                    text = getCategoryDisplayName(category),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(
                                        start = 8.dp,
                                        end = 8.dp,
                                        top = if (category != sortedCategories.first()) 8.dp else 4.dp,
                                        bottom = 4.dp
                                    )
                                )
                            }
                            // Commands in this category
                            items(categoryCommands, key = { "${it.category}_${it.name}" }) { command ->
                                SlashCommandItem(
                                    command = command,
                                    onClick = { onCommandSelected(command) }
                                )
                            }
                        }
                    }
                }

                // Hint text when no commands match
                if (filteredCommands.isEmpty() && !isLoading) {
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
 * Gets the display name for a command category.
 */
private fun getCategoryDisplayName(category: String): String {
    return when (category.lowercase()) {
        "builtin" -> "Built-in"
        "project" -> "Project"
        "user" -> "User"
        else -> category.replaceFirstChar { it.uppercase() }
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
