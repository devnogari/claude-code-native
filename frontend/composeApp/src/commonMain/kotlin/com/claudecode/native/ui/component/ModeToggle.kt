package com.claudecode.native.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.claudecode.native.data.model.OperationMode

/**
 * Mode toggle chip showing the current operation mode.
 * Clicking cycles through: DEFAULT -> PLAN -> BYPASS -> DEFAULT
 *
 * Colors:
 * - DEFAULT: Gray (neutral)
 * - PLAN: Blue (review mode)
 * - BYPASS: Orange (caution, skip permissions)
 *
 * @param currentMode The current operation mode
 * @param onModeClick Called when the user clicks to cycle the mode
 * @param enabled Whether the toggle is clickable
 * @param modifier Optional modifier
 */
@Composable
fun ModeToggle(
    currentMode: OperationMode,
    onModeClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    val backgroundColor by animateColorAsState(
        targetValue = when (currentMode) {
            OperationMode.DEFAULT -> MaterialTheme.colorScheme.surfaceContainerHighest
            OperationMode.PLAN -> Color(0xFF1976D2).copy(alpha = 0.15f) // Blue
            OperationMode.BYPASS -> Color(0xFFE65100).copy(alpha = 0.15f) // Orange
        },
        animationSpec = tween(200),
        label = "mode_bg_color"
    )

    val textColor by animateColorAsState(
        targetValue = when (currentMode) {
            OperationMode.DEFAULT -> MaterialTheme.colorScheme.onSurfaceVariant
            OperationMode.PLAN -> Color(0xFF1976D2) // Blue
            OperationMode.BYPASS -> Color(0xFFE65100) // Orange
        },
        animationSpec = tween(200),
        label = "mode_text_color"
    )

    val borderColor by animateColorAsState(
        targetValue = when (currentMode) {
            OperationMode.DEFAULT -> MaterialTheme.colorScheme.outlineVariant
            OperationMode.PLAN -> Color(0xFF1976D2).copy(alpha = 0.5f)
            OperationMode.BYPASS -> Color(0xFFE65100).copy(alpha = 0.5f)
        },
        animationSpec = tween(200),
        label = "mode_border_color"
    )

    val scale by animateFloatAsState(
        targetValue = if (enabled) 1f else 0.95f,
        animationSpec = tween(100),
        label = "mode_scale"
    )

    Surface(
        modifier = modifier
            .scale(scale)
            .clip(RoundedCornerShape(16.dp))
            .then(
                if (enabled) {
                    Modifier
                        .clickable(onClick = onModeClick)
                        .pointerHoverIcon(PointerIcon.Hand)
                } else {
                    Modifier
                }
            ),
        shape = RoundedCornerShape(16.dp),
        color = backgroundColor,
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Mode icon
            Text(
                text = getModeIcon(currentMode),
                fontSize = 12.sp
            )

            // Mode name
            Text(
                text = currentMode.displayName,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = textColor
            )

            // Keyboard hint (subtle)
            Text(
                text = "⇧⇥",
                fontSize = 10.sp,
                color = textColor.copy(alpha = 0.5f)
            )
        }
    }
}

/**
 * Compact mode indicator for tight spaces.
 * Shows only icon and abbreviated mode name.
 */
@Composable
fun ModeIndicatorCompact(
    currentMode: OperationMode,
    onModeClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    val backgroundColor by animateColorAsState(
        targetValue = when (currentMode) {
            OperationMode.DEFAULT -> MaterialTheme.colorScheme.surfaceContainerHighest
            OperationMode.PLAN -> Color(0xFF1976D2).copy(alpha = 0.2f)
            OperationMode.BYPASS -> Color(0xFFE65100).copy(alpha = 0.2f)
        },
        animationSpec = tween(200),
        label = "mode_bg_color_compact"
    )

    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .then(
                if (enabled) {
                    Modifier
                        .clickable(onClick = onModeClick)
                        .pointerHoverIcon(PointerIcon.Hand)
                } else {
                    Modifier
                }
            ),
        shape = RoundedCornerShape(12.dp),
        color = backgroundColor
    ) {
        Text(
            text = getModeIcon(currentMode),
            fontSize = 14.sp,
            modifier = Modifier.padding(6.dp)
        )
    }
}

/**
 * Mode selector dropdown for explicit mode selection.
 * Use when you need direct access to all modes.
 */
@Composable
fun ModeSelector(
    currentMode: OperationMode,
    onModeSelected: (OperationMode) -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        ModeToggle(
            currentMode = currentMode,
            onModeClick = { expanded = true },
            enabled = enabled
        )

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            OperationMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(getModeIcon(mode))
                            Column {
                                Text(
                                    text = mode.displayName,
                                    fontWeight = if (mode == currentMode) FontWeight.Bold else FontWeight.Normal
                                )
                                Text(
                                    text = mode.description,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    onClick = {
                        onModeSelected(mode)
                        expanded = false
                    },
                    enabled = mode != currentMode
                )
            }
        }
    }
}

/**
 * Get the icon for a given operation mode.
 */
private fun getModeIcon(mode: OperationMode): String = when (mode) {
    OperationMode.DEFAULT -> "⚡"  // Lightning for default/fast mode
    OperationMode.PLAN -> "📋"     // Clipboard for plan review
    OperationMode.BYPASS -> "🔓"   // Unlocked for bypass
}
